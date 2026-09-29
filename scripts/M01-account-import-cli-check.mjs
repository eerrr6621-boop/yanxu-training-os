// Fully synthetic, offline CLI checks. Only this process's mkdtemp tree is removed.
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, access, stat, symlink, link, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runPreview } from './M01-account-import-preview.mjs';

const cli = fileURLToPath(new URL('./M01-account-import-preview.mjs', import.meta.url));
const digest = value => createHash('sha256').update(value).digest('hex');
const encode = value => JSON.stringify(value, null, 2) + '\n';
const marker = 'LEAKMARK';
let checks = 0;
const equal = (actual, expected, label) => { assert.deepEqual(actual, expected, label); checks++; };
const ok = (value, label) => { assert.ok(value, label); checks++; };
const rejects = async (operation, expected, label) => { await assert.rejects(operation, expected, label); checks++; };
const scratch = await mkdtemp(join(tmpdir(), 'm01-import-cli-check-'));

async function fixture(label) {
  const dir = join(scratch, label);
  await mkdir(dir);
  const files = {
    candidatesPath: join(dir, 'candidates.json'), policyPath: join(dir, 'policy.json'),
    regionsPath: join(dir, 'regions.json'), outputPath: join(dir, 'report.json'),
    teacherPath: join(dir, 'teacher-source.txt'), leadPath: join(dir, 'lead-source.txt'),
  };
  const sourceBytes = ['Synthetic teacher original\n', 'Synthetic lead original\n'];
  const sourceFiles = [files.teacherPath, files.leadPath].map((path, i) => ({ path, sha256: digest(sourceBytes[i]) }));
  const common = { organization: 'Synthetic Branch', account_disposition: 'PREPARE_ACCOUNT_ONLY',
    account_id: null, username: null, email: null, identity_binding_verified: false, business_role_codes: [] };
  const batch = { version: 'SYNTHETIC-BATCH-v1', source_files: sourceFiles, candidates: [
    { ...common, candidate_reference: 'teacher-row-2', name: `${marker}_NAME_A`,
      source: { path: files.teacherPath, sheet: 'Teachers', source_row: 2, range: 'A2:F2' },
      identities: ['兼职教师'], source_job: null, source_teacher_level: `${marker}_LEVEL`,
      group_match: 'EXACT_NAME_AND_ORGANIZATION', source_staff_id_candidate: `${marker}_STAFF_ID`,
      group_evidence: [{ name: `${marker}_NAME_A`, organization: common.organization, source_staff_id: `${marker}_STAFF_ID` }] },
    { ...common, candidate_reference: 'lead-row-2', name: `${marker}_NAME_B`,
      source: { path: files.leadPath, sheet: 'Leads', row: 2, range: 'B2:D2' },
      identities: ['分公司牵头人'], source_job: `${marker}_JOB`, source_teacher_level: null,
      group_match: 'NOT_FOUND_IN_GROUP_ROSTER', source_staff_id_candidate: null, group_evidence: [] },
  ], historical_exclusions: Array.from({ length: 9 }, (_, i) => {
    const row = i + 3;
    return { row, name: `${marker}_HISTORY_${row}`, account_disposition: 'DO_NOT_CREATE',
      source: { path: files.teacherPath, sheet: 'Teachers', source_row: row, range: `A${row}:F${row}` } };
  }) };
  const regions = { regions: [{ display_name: 'Synthetic Region', branches: [{ display_name: common.organization }] }] };
  const policy = { version: 'SYNTHETIC-POLICY-v1', batchVersion: batch.version, sourceFiles,
    teacher: { path: files.teacherPath, sheet: 'Teachers', mainRows: [2, 2], historyRows: [3, 11] },
    lead: { path: files.leadPath, sheet: 'Leads', additionalRows: [2] },
    dualIdentityReferences: [], organizationResolutions: [], organizationNameReviewReferences: [],
    candidateSha256: digest(encode(batch)), regionReferenceSha256: digest(encode(regions)) };
  await Promise.all([
    writeFile(files.teacherPath, sourceBytes[0]), writeFile(files.leadPath, sourceBytes[1]),
    writeFile(files.candidatesPath, encode(batch)), writeFile(files.regionsPath, encode(regions)),
    writeFile(files.policyPath, encode(policy)),
  ]);
  return { ...files, policy };
}

const inputs = f => [f.candidatesPath, f.policyPath, f.regionsPath, f.teacherPath, f.leadPath];
async function capture(f) { return Promise.all(inputs(f).map(async path => digest(await readFile(path)))); }
async function unchanged(f, before) { equal(await capture(f), before, 'all five original inputs remain byte-identical'); }
async function absent(path) { await rejects(() => access(path), { code: 'ENOENT' }, 'rejected preparation creates no report'); }
async function savePolicy(f) { await writeFile(f.policyPath, encode(f.policy)); }
async function rejectedFixture(f, expected) {
  const before = await capture(f);
  await rejects(() => runPreview(f), expected, 'invalid input is rejected');
  await absent(f.outputPath);
  await unchanged(f, before);
}
function child(f) {
  const result = spawnSync(process.execPath, [cli, f.candidatesPath, f.policyPath, f.regionsPath, f.outputPath],
    { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
  if (result.error) throw result.error;
  equal(result.signal, null, 'CLI terminates normally');
  ok(!`${result.stdout}${result.stderr}`.includes(marker), 'CLI output contains no synthetic personal sentinel');
  return result;
}

try {
  const f = await fixture('valid');
  const before = await capture(f);
  const report = await runPreview(f);
  equal(report.scopeValidated, true, 'valid scope is accepted');
  equal([report.canCreateAccounts, report.canPublishConfiguration, report.writesPerformed], [false, false, false], 'preview never authorizes writes');
  equal(report.summary, { candidates: 2, teachers: 1, leads: 1, additionalLeads: 1, historicalExcluded: 9,
    sourceIdEvidenceAvailable: 1, sourceIdentityNeedsReview: 1, missingJobText: 1, formalAccountMappingsAssigned: 0 }, 'metadata matches the synthetic fixture');
  equal(report.evidence, { candidateSha256: f.policy.candidateSha256,
    regionReferenceSha256: f.policy.regionReferenceSha256, sourceDigestsVerified: true }, 'verified provenance is reported');
  const written = await readFile(f.outputPath, 'utf8');
  equal(JSON.parse(written), report, 'saved report equals return value');
  ok(!written.includes(marker), 'report does not expose names, staff IDs, job text or history names');
  equal((await stat(f.outputPath)).mode & 0o777, 0o600, 'new report is private');
  await unchanged(f, before);

  const cliFixture = await fixture('valid-cli');
  const cliBefore = await capture(cliFixture);
  const cliResult = child(cliFixture);
  equal(cliResult.status, 0, 'successful CLI exit');
  equal(cliResult.stderr, '', 'successful CLI has no stderr');
  const summary = JSON.parse(cliResult.stdout);
  equal([summary.scopeValidated, summary.candidates, summary.historicalExcluded, summary.issueCount], [true, 2, 9, 0], 'CLI emits summary metadata only');
  equal([summary.canCreateAccounts, summary.canPublishConfiguration], [false, false], 'CLI does not advertise authority');
  await unchanged(cliFixture, cliBefore);

  for (const kind of ['missing', 'empty', 'bad-sha', 'same-path', 'same-symlink', 'same-hardlink']) {
    const invalid = await fixture(`sources-${kind}`);
    if (kind === 'missing') delete invalid.policy.sourceFiles;
    else if (kind === 'empty') invalid.policy.sourceFiles = [];
    else if (kind === 'bad-sha') invalid.policy.sourceFiles[1].sha256 = 'not-a-digest';
    else {
      let path = invalid.teacherPath;
      if (kind !== 'same-path') {
        path = join(scratch, `source-alias-${kind}`);
        await (kind === 'same-symlink' ? symlink : link)(invalid.teacherPath, path);
      }
      invalid.policy.sourceFiles[1] = { path, sha256: invalid.policy.sourceFiles[0].sha256 };
    }
    await savePolicy(invalid);
    await rejectedFixture(invalid, kind.startsWith('same-') ? /同一文件/ : /两份来源原件及其摘要/);
  }

  for (const kind of ['policy', 'candidates', 'regions']) {
    const invalid = await fixture(`invalid-json-${kind}`);
    const malformed = `${marker}_INVALID_JSON`;
    await writeFile(invalid[`${kind}Path`], malformed);
    if (kind !== 'policy') {
      invalid.policy[kind === 'candidates' ? 'candidateSha256' : 'regionReferenceSha256'] = digest(malformed);
      await savePolicy(invalid);
    }
    const originalBytes = await capture(invalid);
    const response = child(invalid);
    equal(response.status, 1, 'malformed JSON fails the CLI');
    equal(response.stdout, '', 'malformed JSON emits no report summary');
    ok(response.stderr.includes('不是有效JSON，未输出原文。'), 'JSON error uses a fixed safe message');
    await absent(invalid.outputPath);
    await unchanged(invalid, originalBytes);
  }

  for (const kind of ['existing', 'symlink', 'hardlink']) {
    const protectedFiles = await fixture(`output-${kind}`);
    if (kind === 'existing') await writeFile(protectedFiles.outputPath, 'Existing synthetic report must survive.\n');
    else await (kind === 'symlink' ? symlink : link)(kind === 'symlink' ? protectedFiles.candidatesPath : protectedFiles.teacherPath, protectedFiles.outputPath);
    const originalBytes = await capture(protectedFiles);
    const outputBefore = await readFile(protectedFiles.outputPath);
    await rejects(() => runPreview(protectedFiles), { code: 'EEXIST' }, 'exclusive creation rejects existing output and aliases');
    equal(await readFile(protectedFiles.outputPath), outputBefore, 'existing output or linked input is not overwritten');
    await unchanged(protectedFiles, originalBytes);
  }

  for (const kind of ['candidates', 'regions', 'teacher', 'lead']) {
    const changed = await fixture(`digest-${kind}`);
    const path = changed[`${kind}Path`];
    await writeFile(path, Buffer.concat([await readFile(path), Buffer.from('\n')]));
    await rejectedFixture(changed, kind === 'candidates' || kind === 'regions' ? /候选或区域参考已变化/ : /来源原表已变化/);
  }
} finally {
  await rm(scratch, { recursive: true, force: true });
}
await absent(scratch);
console.log(`M01 account-import CLI: ${checks} checks passed; synthetic temporary files cleaned.`);
