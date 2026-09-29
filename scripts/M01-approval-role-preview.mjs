// Offline review only: verified sources in, new non-personal preview out. No account/config API.
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
class PreviewError extends Error {}
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const parse = bytes => { try { return JSON.parse(bytes); } catch { throw new PreviewError('输入不是有效JSON，未输出原文。'); } };
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

export async function runApprovalPreview({ candidatesPath, candidatePolicyPath, rolesPath, authorizationPath, auditPath, regionsPath, scopeDecisionPath, outputPath }) {
  const paths = [candidatesPath, candidatePolicyPath, rolesPath, authorizationPath, auditPath, regionsPath, ...(scopeDecisionPath !== undefined ? [scopeDecisionPath] : []), outputPath];
  if (paths.some(p => typeof p !== 'string' || !p) || new Set(paths.map(p => resolve(p))).size !== paths.length) {
    throw new PreviewError('需要独立的资料、确认文件与一个新输出路径，不能覆盖输入。');
  }
  const bytes = await Promise.all(paths.slice(0, -1).map(p => readFile(p)));
  const [batch, candidatePolicy, roleSource, authorization, audit, regions, scopeDecision = null] = bytes.map(parse);
  const pairs = [[bytes[0], candidatePolicy?.candidateSha256], [bytes[1], authorization?.candidatePolicySha256],
    [bytes[2], authorization?.roleSourceSha256], [bytes[4], authorization?.sourceAuditSha256], [bytes[5], candidatePolicy?.regionReferenceSha256]];
  if (!pairs.every(([b, h]) => hash(h) && digest(b) === h)) throw new PreviewError('输入摘要与确认记录不一致，须先重新核对。');
  if (authorization?.decision?.bpScope === 'USER_CONFIRMED' && (!scopeDecisionPath || !bytes[6] ||
      typeof authorization.scopeDecision?.path !== 'string' || resolve(authorization.scopeDecision.path) !== resolve(scopeDecisionPath) ||
      !hash(authorization.scopeDecision?.sha256) || digest(bytes[6]) !== authorization.scopeDecision.sha256)) {
    throw new PreviewError('缺少已确认的BP及管理范围决定，或其来源摘要已变化。');
  }
  if (!Array.isArray(candidatePolicy?.sourceFiles) || candidatePolicy.sourceFiles.length !== 2 ||
      !authorization?.regionSource || !Array.isArray(audit?.audits)) throw new PreviewError('缺少来源原件核对信息。');
  const originals = [...candidatePolicy.sourceFiles, authorization.regionSource];
  if (!originals.every(s => typeof s?.path === 'string' && s.path && hash(s.sha256))) throw new PreviewError('原件路径或摘要不完整。');
  const inputIdentities = new Set();
  for (const path of paths.slice(0, -1)) {
    const info = await stat(path);
    if (!info.isFile()) throw new PreviewError('输入必须为普通文件。');
    const identity = `${info.dev}:${info.ino}`;
    if (inputIdentities.has(identity)) throw new PreviewError('输入不能通过链接重复使用同一文件。');
    inputIdentities.add(identity);
  }
  for (const source of originals) {
    if (resolve(source.path) === resolve(outputPath)) throw new PreviewError('输出不能覆盖原件。');
    const info = await stat(source.path);
    if (!info.isFile()) throw new PreviewError('原件必须为普通文件。');
    const identity = `${info.dev}:${info.ino}`;
    if (inputIdentities.has(identity)) throw new PreviewError('来源文件不能重复或替代核对输入。');
    inputIdentities.add(identity);
    if (digest(await readFile(source.path)) !== source.sha256) throw new PreviewError('来源原件已变化，须先重新核对。');
  }
  // The audit independently pins the roster and current region workbook; it contains private values.
  for (const source of [candidatePolicy.sourceFiles.find(s => s.path === authorization.branchSource?.path), authorization.regionSource]) {
    if (!source || audit.audits.filter(a => a?.path === source.path && a.sha256 === source.sha256).length !== 1) {
      throw new PreviewError('独立审计与当前原件摘要不一致。');
    }
  }
  const accountModule = await readFile(new URL('../web/modules/identity/account-import-preview.js', import.meta.url), 'utf8');
  const accountUrl = `data:text/javascript;base64,${Buffer.from(accountModule).toString('base64')}`;
  const sourceModule = await readFile(new URL('../web/modules/identity/approval-role-preview.js', import.meta.url), 'utf8');
  const module = sourceModule.replace("'./account-import-preview.js'", JSON.stringify(accountUrl));
  const { previewApprovalRoles } = await import(`data:text/javascript;base64,${Buffer.from(module).toString('base64')}`);
  const preview = previewApprovalRoles(batch, candidatePolicy, roleSource, authorization, regions, scopeDecision);
  const output = { ...preview, evidence: { candidateSha256: candidatePolicy.candidateSha256,
    candidatePolicySha256: authorization.candidatePolicySha256, roleSourceSha256: authorization.roleSourceSha256,
    sourceAuditSha256: authorization.sourceAuditSha256, regionReferenceSha256: candidatePolicy.regionReferenceSha256,
    authorizationSha256: digest(bytes[3]), sourceFiles: originals.map(s => ({ path: s.path, sha256: s.sha256 })),
    scopeDecision: authorization.decision?.bpScope === 'USER_CONFIRMED'
      ? { path: authorization.scopeDecision.path, sha256: authorization.scopeDecision.sha256 } : null,
    sourceDigestsVerified: true } };
  await mkdir(dirname(resolve(outputPath)), { recursive: true });
  await writeFile(outputPath, JSON.stringify(output, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  return output;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 7 && args.length !== 8) throw new PreviewError('需要七个参数；有新增范围确认文件时为八个参数。');
    const [candidatesPath, candidatePolicyPath, rolesPath, authorizationPath, auditPath, regionsPath] = args;
    const scopeDecisionPath = args.length === 8 ? args[6] : undefined, outputPath = args.at(-1);
    const result = await runApprovalPreview({ candidatesPath, candidatePolicyPath, rolesPath, authorizationPath, auditPath, regionsPath, scopeDecisionPath, outputPath });
    console.log(JSON.stringify({ scopeValidated: result.scopeValidated, ...result.summary, issueCount: result.issues.length,
      canCreateAccounts: false, canPublishConfiguration: false }));
    if (!result.scopeValidated) process.exitCode = 2;
  } catch (error) {
    console.error(error instanceof PreviewError ? error.message : error?.code === 'EEXIST'
      ? '输出文件已存在，请换新文件名；现有文件未覆盖。' : '准备未完成，请检查文件及输入结构；未输出个人原文。');
    process.exitCode = 1;
  }
}
