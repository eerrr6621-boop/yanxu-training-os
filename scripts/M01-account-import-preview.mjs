// Offline preparation only. Never writes users/configuration or outputs raw personnel values.
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

class PreviewError extends Error {}
const parse = (bytes, label) => {
  try { return JSON.parse(bytes); }
  catch { throw new PreviewError(`${label}不是有效JSON，未输出原文。`); }
};

export async function runPreview({ candidatesPath, policyPath, regionsPath, outputPath }) {
  const names = [candidatesPath, policyPath, regionsPath, outputPath];
  if (names.some(p => typeof p !== 'string' || !p)) throw new PreviewError('需要候选、确认范围、区域参考及输出四个文件路径。');
  if (new Set(names.map(p => resolve(p))).size !== names.length) throw new PreviewError('输出不能覆盖输入文件。');
  const [candidateBytes, policyBytes, regionBytes, moduleSource] = await Promise.all([
    readFile(candidatesPath), readFile(policyPath), readFile(regionsPath),
    readFile(new URL('../web/modules/identity/account-import-preview.js', import.meta.url), 'utf8'),
  ]);
  const digest = data => createHash('sha256').update(data).digest('hex');
  const policy = parse(policyBytes, '确认范围');
  if (!policy || !Array.isArray(policy.sourceFiles) || policy.sourceFiles.length !== 2 ||
      !policy.sourceFiles.every(s => typeof s?.path === 'string' && s.path && /^[a-f0-9]{64}$/.test(s.sha256))) {
    throw new PreviewError('必须明确两份来源原件及其摘要，未生成新预览。');
  }
  if (digest(candidateBytes) !== policy.candidateSha256 || digest(regionBytes) !== policy.regionReferenceSha256) {
    throw new PreviewError('候选或区域参考已变化，请先重新复核；未生成新预览。');
  }
  const sourceIdentities = new Set();
  for (const source of policy.sourceFiles) {
    if (resolve(outputPath) === resolve(source.path)) throw new PreviewError('输出不能覆盖来源原表。');
    const metadata = await stat(source.path);
    if (!metadata.isFile()) throw new PreviewError('来源必须是普通文件。');
    const identity = `${metadata.dev}:${metadata.ino}`;
    if (sourceIdentities.has(identity)) throw new PreviewError('两份来源指向同一文件，未生成新预览。');
    sourceIdentities.add(identity);
    if (digest(await readFile(source.path)) !== source.sha256) throw new PreviewError('来源原表已变化，请先重新复核；未生成新预览。');
  }
  const { previewAccountImport } = await import(`data:text/javascript;base64,${Buffer.from(moduleSource).toString('base64')}`);
  const preview = previewAccountImport(parse(candidateBytes, '候选资料'), policy, parse(regionBytes, '区域参考'));
  const output = { ...preview, evidence: { candidateSha256: policy.candidateSha256,
    regionReferenceSha256: policy.regionReferenceSha256, sourceDigestsVerified: true } };
  await mkdir(dirname(resolve(outputPath)), { recursive: true });
  // Exclusive creation also rejects existing hard links and symlinks to protected inputs.
  await writeFile(outputPath, JSON.stringify(output, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  return output;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [candidatesPath, policyPath, regionsPath, outputPath, ...extra] = process.argv.slice(2);
    if (extra.length) throw new PreviewError('参数过多。');
    const result = await runPreview({ candidatesPath, policyPath, regionsPath, outputPath });
    console.log(JSON.stringify({ scopeValidated: result.scopeValidated, ...result.summary, issueCount: result.issues.length,
      canCreateAccounts: result.canCreateAccounts, canPublishConfiguration: result.canPublishConfiguration }));
    if (!result.scopeValidated) process.exitCode = 2;
  } catch (error) {
    console.error(error instanceof PreviewError ? error.message : error?.code === 'EEXIST'
      ? '输出文件已存在，请使用新的输出文件名；现有文件未覆盖。'
      : '准备过程未完成，请检查文件与输入结构；未输出个人原文。');
    process.exitCode = 1;
  }
}
