// Synthetic, offline checks only. Cleanup is restricted to this run's mkdtemp tree.
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, stat, access, symlink, link, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runApprovalPreview } from './M01-approval-role-preview.mjs';
import { createApprovalRoleFixture, createConfirmedApprovalRoleFixture } from './M01-approval-role-fixture.mjs';

const cli = fileURLToPath(new URL('./M01-approval-role-preview.mjs', import.meta.url));
const inputKeys = ['candidatesPath', 'candidatePolicyPath', 'rolesPath', 'authorizationPath', 'auditPath', 'regionsPath'];
const inputObjects = ['batch', 'candidatePolicy', 'roleSource', 'authorization', 'audit', 'regionReference'];
const encode = value => JSON.stringify(value, null, 2) + '\n';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const marker = 'CLI_PRIVATE_SENTINEL';
const scratch = await mkdtemp(join(tmpdir(), 'm01-approval-cli-check-'));
let checks = 0;
const equal = (actual, expected, label) => { assert.deepEqual(actual, expected, label); checks++; };
const ok = (value, label) => { assert.ok(value, label); checks++; };
const rejects = async (operation, expected, label) => { await assert.rejects(operation, expected, label); checks++; };

async function saveInputs(f) {
  const d = f.data;
  if (d.scopeDecision !== undefined && f.scopeDecisionPath) {
    d.authorization.scopeDecision.sha256 = hash(encode(d.scopeDecision));
    await writeFile(f.scopeDecisionPath, encode(d.scopeDecision));
  }
  d.candidatePolicy.candidateSha256 = hash(encode(d.batch));
  d.candidatePolicy.regionReferenceSha256 = hash(encode(d.regionReference));
  d.authorization.candidatePolicySha256 = hash(encode(d.candidatePolicy));
  d.authorization.roleSourceSha256 = hash(encode(d.roleSource));
  d.authorization.sourceAuditSha256 = hash(encode(d.audit));
  await Promise.all(inputKeys.map((key, i) => writeFile(f[key], encode(d[inputObjects[i]]))));
}

async function fixture(label, confirmed = false) {
  const directory = join(scratch, label);
  await mkdir(directory);
  const initial = confirmed ? createConfirmedApprovalRoleFixture() : createApprovalRoleFixture();
  const originalPaths = ['teacher-original.txt', 'group-original.txt', 'region-original.txt'].map(name => join(directory, name));
  const oldPaths = [...initial.candidatePolicy.sourceFiles.map(source => source.path), initial.authorization.regionSource.path];
  const mapping = new Map(oldPaths.map((path, i) => [path, originalPaths[i]]));
  const data = JSON.parse(JSON.stringify(initial, (_, value) => typeof value === 'string' && mapping.has(value) ? mapping.get(value) : value));
  const sourceHashes = new Map();
  for (const [i, path] of originalPaths.entries()) {
    const bytes = `SYNTHETIC ORIGINAL ${i}\n`;
    await writeFile(path, bytes);
    sourceHashes.set(path, hash(bytes));
  }
  const updateMetadata = value => {
    if (!value || typeof value !== 'object') return;
    if (sourceHashes.has(value.path) && 'sha256' in value) value.sha256 = sourceHashes.get(value.path);
    Object.values(value).forEach(updateMetadata);
  };
  updateMetadata(data);
  data.audit = { audits: [originalPaths[1], originalPaths[2]].map(path => ({ path, sha256: sourceHashes.get(path),
    privateValue: marker })) };
  const f = { data, originalPaths, privateSentinels: [...data.privateSentinels, marker], outputPath: join(directory, 'report.json') };
  inputKeys.forEach((key, i) => { f[key] = join(directory, `${inputObjects[i]}.json`); });
  if (confirmed) {
    f.scopeDecisionPath = join(directory, 'scope-decision.json');
    data.authorization.scopeDecision.path = f.scopeDecisionPath;
  }
  await saveInputs(f);
  return f;
}

const protectedPaths = f => [...inputKeys.map(key => f[key]), ...(f.scopeDecisionPath ? [f.scopeDecisionPath] : []), ...f.originalPaths];
async function snapshot(f) { return Promise.all(protectedPaths(f).map(async path => hash(await readFile(path)))); }
async function unchanged(f, previous) { equal(await snapshot(f), previous, 'all inputs, optional decision and three originals remain byte-identical'); }
async function absent(path) { await rejects(() => access(path), { code: 'ENOENT' }, 'no rejected report is created'); }
function noPrivateText(value, f, label) {
  const serialized = typeof value === 'string' ? value : JSON.stringify(value);
  ok(f.privateSentinels.every(sentinel => !serialized.includes(sentinel)), label);
}
async function rejectWithoutOutput(f, expected) {
  const previous = await snapshot(f);
  await rejects(() => runApprovalPreview(f), expected, 'unsafe preparation is rejected');
  await absent(f.outputPath);
  await unchanged(f, previous);
}
function child(f) {
  const result = spawnSync(process.execPath, [cli, ...inputKeys.map(key => f[key]), ...(f.scopeDecisionPath ? [f.scopeDecisionPath] : []), f.outputPath],
    { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
  if (result.error) throw result.error;
  equal(result.signal, null, 'CLI exits without a signal');
  noPrivateText(`${result.stdout}${result.stderr}`, f, 'CLI never prints personal sentinels');
  ok(!`${result.stdout}${result.stderr}`.includes('CLI_PRIV'), 'CLI does not print truncated JSON input');
  return result;
}
async function blocked(f, code) {
  await saveInputs(f);
  const previous = await snapshot(f);
  const report = await runApprovalPreview(f);
  equal(report.scopeValidated, false, 'bad role evidence fails closed');
  ok(report.issues.some(issue => issue.code === code), `reports ${code}`);
  ok(report.rows.every(row => row.canApprove === false && row.approvalRoles.length === 0), 'no prepared role survives failed validation');
  equal([report.canCreateAccounts, report.canPublishConfiguration], [false, false], 'blocked report cannot authorize writes');
  noPrivateText(report, f, 'blocked report excludes private values');
  await unchanged(f, previous);
}

try {
  const f = await fixture('valid');
  const previous = await snapshot(f);
  const report = await runApprovalPreview(f);
  equal(report.scopeValidated, true, 'valid preparation accepted');
  equal([report.canCreateAccounts, report.canPublishConfiguration, report.writesPerformed], [false, false, false], 'preparation confers no write authority');
  equal(report.summary, { candidates: 7, sourceBranchRolePeople: 4, branchRolePeople: 4, branchLeads: 2, bpRolePeople: 2,
    bpScopesPrepared: 0, uniqueApprovalPeople: 6, preparedRoleAssignments: 6, peopleWithCombinedDuties: 0,
    ordinaryCandidatesWithoutApproval: 1, branchesTotal: 3, branchesCovered: 2,
    branchesMissingAssignee: 1, branchesWithMultipleIdentities: 1, historicalExcluded: 2,
    actualApprovalPermissionsAssigned: 0 }, 'metadata preserves role, history and coverage counts');
  ok(report.rows.every(row => row.canApprove === false && row.accountBinding === null && row.formalPersonCode === null && row.formalOrganizationCode === null), 'no formal mappings or permissions assigned');
  const preparedRoles = report.rows.flatMap(row => row.approvalRoles);
  ok(preparedRoles.filter(role => role.kind === 'BP').every(role => role.proposedBranches.length === 0 && role.proposedRegions.length === 0 && role.scopeStatus === 'PENDING_BP_REGION_EVIDENCE'), 'BP missing scope never becomes global scope');
  ok(preparedRoles.filter(role => role.kind !== 'BP').every(role => role.proposedBranches.length === 1 && role.proposedBranches[0] === role.currentOrganization && role.proposedRegions.length === 0 && role.canApprove === false), 'branch evidence remains local to its explicit branch');
  equal(report.rows.find(row => row.reference === 'teacher-row-3').approvalRoles, [], 'ordinary teacher does not inherit a role from a same-name colleague');
  equal(report.rows.find(row => row.reference === 'teacher-row-5').approvalEligibility, 'BRANCH_SCOPE_PREPARED', 'missing job text retains an explicitly evidenced candidate');
  equal(report.evidence.authorizationSha256, hash(await readFile(f.authorizationPath)), 'independently reviewed manifest digest is recorded');
  equal(report.evidence.sourceDigestsVerified, true, 'all originals verified');
  equal(report.evidence.sourceFiles.length, 3, 'exactly three original metadata entries');
  equal(JSON.parse(await readFile(f.outputPath, 'utf8')), report, 'saved report equals returned report');
  equal((await stat(f.outputPath)).mode & 0o777, 0o600, 'new report is private');
  noPrivateText(report, f, 'successful report excludes personal and audit values');
  await unchanged(f, previous);

  const normalCli = await fixture('valid-cli');
  const cliPrevious = await snapshot(normalCli);
  const success = child(normalCli);
  equal(success.status, 0, 'valid CLI succeeds');
  equal(success.stderr, '', 'valid CLI emits no errors');
  equal(JSON.parse(success.stdout).issueCount, 0, 'CLI emits summary without issues');
  await unchanged(normalCli, cliPrevious);

  const metadata = await fixture('private-metadata');
  metadata.data.candidatePolicy.sourceFiles[0].staff_id = marker;
  metadata.data.authorization.regionSource.name = marker;
  metadata.data.regionReference.regions[0].branches[0].region_reference.raw_cells = { name: marker, staff_id: marker };
  await saveInputs(metadata);
  const metadataReport = await runApprovalPreview(metadata);
  equal(metadataReport.scopeValidated, true, 'harmless extra source metadata cannot change scope');
  noPrivateText(metadataReport, metadata, 'metadata allowlists exclude private extensions');
  ok(metadataReport.evidence.sourceFiles.every(source => Object.keys(source).sort().join(',') === 'path,sha256'), 'original metadata is explicitly allowlisted');
  ok(metadataReport.branchCoverage.every(row => Object.keys(row.source).sort().join(',') === 'range,sheet'), 'region cell metadata is explicitly allowlisted');

  for (const key of ['candidatesPath', 'candidatePolicyPath', 'rolesPath', 'auditPath', 'regionsPath']) {
    const changed = await fixture(`changed-${key}`);
    await writeFile(changed[key], Buffer.concat([await readFile(changed[key]), Buffer.from('\n')]));
    await rejectWithoutOutput(changed, /输入摘要与确认记录不一致/);
  }
  for (let i = 0; i < 3; i++) {
    const changed = await fixture(`changed-original-${i}`);
    const path = changed.originalPaths[i];
    await writeFile(path, Buffer.concat([await readFile(path), Buffer.from('\n')]));
    await rejectWithoutOutput(changed, /来源原件已变化/);
  }
  for (const key of inputKeys) {
    const invalid = await fixture(`bad-json-${key}`);
    await writeFile(invalid[key], `${marker}_MALFORMED`);
    const prior = await snapshot(invalid);
    const response = child(invalid);
    equal(response.status, 1, 'each malformed JSON input fails');
    equal(response.stdout, '', 'malformed input emits no summary');
    ok(response.stderr.includes('输入不是有效JSON，未输出原文。'), 'parse error uses fixed safe text');
    await absent(invalid.outputPath);
    await unchanged(invalid, prior);
  }

  for (const kind of ['missing-originals', 'empty-originals', 'same-original', 'audit-mismatch']) {
    const invalid = await fixture(kind);
    if (kind === 'missing-originals') delete invalid.data.candidatePolicy.sourceFiles;
    else if (kind === 'empty-originals') invalid.data.candidatePolicy.sourceFiles = [];
    else if (kind === 'same-original') invalid.data.candidatePolicy.sourceFiles[1] = { ...invalid.data.candidatePolicy.sourceFiles[0] };
    else invalid.data.audit.audits[0].sha256 = '0'.repeat(64);
    await saveInputs(invalid);
    await rejectWithoutOutput(invalid, kind === 'same-original' ? /来源文件不能重复/ : kind === 'audit-mismatch' ? /独立审计/ : /缺少来源原件核对信息/);
  }
  for (const kind of ['symlink', 'hardlink']) {
    const repeated = await fixture(`same-input-${kind}`);
    const combined = { ...repeated.data.roleSource, ...repeated.data.audit };
    repeated.data.roleSource = combined; repeated.data.audit = combined;
    await saveInputs(repeated);
    await rm(repeated.auditPath);
    await (kind === 'symlink' ? symlink : link)(repeated.rolesPath, repeated.auditPath);
    await rejectWithoutOutput(repeated, /输入不能通过链接重复/);

    const protectedOutput = await fixture(`output-${kind}`);
    await (kind === 'symlink' ? symlink : link)(kind === 'symlink' ? protectedOutput.candidatesPath : protectedOutput.originalPaths[0], protectedOutput.outputPath);
    const prior = await snapshot(protectedOutput);
    const originalOutput = await readFile(protectedOutput.outputPath);
    await rejects(() => runApprovalPreview(protectedOutput), { code: 'EEXIST' }, 'output aliases cannot overwrite existing input');
    equal(await readFile(protectedOutput.outputPath), originalOutput, 'linked output remains unchanged');
    await unchanged(protectedOutput, prior);
  }
  const existing = await fixture('existing-output');
  await writeFile(existing.outputPath, 'Existing synthetic report.\n');
  const existingBefore = await snapshot(existing);
  await rejects(() => runApprovalPreview(existing), { code: 'EEXIST' }, 'existing output is rejected');
  equal(await readFile(existing.outputPath, 'utf8'), 'Existing synthetic report.\n', 'existing output survives');
  await unchanged(existing, existingBefore);

  const bpScope = await fixture('bp-scope');
  bpScope.data.roleSource.bp_approvers[0].responsible_region = 'ALL';
  await blocked(bpScope, 'BP_SCOPE_WITHOUT_EVIDENCE');
  const unknownRole = await fixture('unknown-role');
  unknownRole.data.authorization.assignments[0].role = 'GLOBAL_ADMIN';
  await blocked(unknownRole, 'REVIEWED_AUTHORIZATION_REQUIRED');
  const invalidRegion = await fixture('bad-region-cell');
  invalidRegion.data.regionReference.regions[0].branches[0].region_reference.range = marker;
  await blocked(invalidRegion, 'REGION_SOURCE_CELL_INVALID');
  const nullRoles = await fixture('null-role-source');
  nullRoles.data.roleSource = null;
  await blocked(nullRoles, 'ROLE_SOURCE_SCOPE_MISMATCH');
  const wrongBp = await fixture('bp-organization-mismatch');
  wrongBp.data.roleSource.bp_approvers[0].organization = '合成错误机构';
  await blocked(wrongBp, 'BP_MEMBERSHIP_MISMATCH');

  const confirmed = await fixture('confirmed-valid', true);
  const confirmedBefore = await snapshot(confirmed);
  const confirmedReport = await runApprovalPreview(confirmed);
  equal(confirmedReport.scopeValidated, true, 'independently confirmed BP scope is accepted');
  equal(confirmedReport.summary, { candidates: 7, sourceBranchRolePeople: 4, branchRolePeople: 5, branchLeads: 2,
    bpRolePeople: 2, bpScopesPrepared: 2, uniqueApprovalPeople: 6, preparedRoleAssignments: 7, peopleWithCombinedDuties: 1,
    ordinaryCandidatesWithoutApproval: 1, branchesTotal: 3, branchesCovered: 3, branchesMissingAssignee: 0,
    branchesWithMultipleIdentities: 1, historicalExcluded: 2, actualApprovalPermissionsAssigned: 0 }, 'confirmed scopes add a duty without duplicating the person');
  const combined = confirmedReport.rows.find(row => row.reference === 'teacher-row-6');
  equal(combined.approvalRoles.map(role => role.kind), ['BP', 'BRANCH_RESPONSIBLE'], 'one candidate holds the two explicitly confirmed duties');
  equal(combined.approvalRoles[0].proposedRegions, ['合成区域二'], 'BP covers only the confirmed region');
  equal(combined.approvalRoles[0].proposedBranches, ['合成缺负责人分公司'], 'BP branches derive from the confirmed region');
  equal(combined.approvalRoles[1].proposedBranches, ['合成缺负责人分公司'], 'management duty covers only the explicit branch');
  equal(combined.organization, '合成总部人力机构', 'management duty does not rewrite home organization');
  equal(combined.combinedDutiesRequiresWorkflowReview, true, 'same-person workflow handling remains pending');
  ok(confirmedReport.requiredBeforePublication.includes('HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW'), 'combined duties do not bypass workflow review');
  ok(confirmedReport.rows.every(row => !row.canApprove && row.accountBinding === null && row.approvalRoles.every(role => !role.canApprove)), 'confirmed scopes remain non-executable preparation');
  equal(confirmedReport.evidence.scopeDecision, { path: confirmed.scopeDecisionPath, sha256: hash(await readFile(confirmed.scopeDecisionPath)) }, 'decision metadata is pinned and allowlisted');
  noPrivateText(confirmedReport, confirmed, 'confirmed decision names and IDs never appear in output');
  await unchanged(confirmed, confirmedBefore);

  const confirmedCli = await fixture('confirmed-cli', true);
  const confirmedCliBefore = await snapshot(confirmedCli);
  const confirmedResponse = child(confirmedCli);
  equal(confirmedResponse.status, 0, 'eight-argument CLI accepts a valid decision');
  equal(JSON.parse(confirmedResponse.stdout).peopleWithCombinedDuties, 1, 'eight-argument CLI returns only confirmed metadata');
  await unchanged(confirmedCli, confirmedCliBefore);

  const confirmedPrivate = await fixture('confirmed-private-extras', true);
  confirmedPrivate.data.authorization.scopeDecision.privateName = marker;
  confirmedPrivate.data.scopeDecision.privateNotes = marker;
  confirmedPrivate.data.scopeDecision.bp_region_assignments[0].privateNotes = marker;
  await saveInputs(confirmedPrivate);
  const privateReport = await runApprovalPreview(confirmedPrivate);
  equal(privateReport.scopeValidated, true, 'unused private notes do not alter confirmed scope');
  noPrivateText(privateReport, confirmedPrivate, 'decision metadata and raw notes are never echoed');

  const alteredDecision = await fixture('decision-digest-changed', true);
  await writeFile(alteredDecision.scopeDecisionPath, Buffer.concat([await readFile(alteredDecision.scopeDecisionPath), Buffer.from('\n')]));
  await rejectWithoutOutput(alteredDecision, /来源摘要已变化/);
  const wrongDecisionPath = await fixture('decision-path-mismatch', true);
  const alternate = join(scratch, 'decision-alternate.json');
  await writeFile(alternate, await readFile(wrongDecisionPath.scopeDecisionPath));
  wrongDecisionPath.scopeDecisionPath = alternate;
  await rejectWithoutOutput(wrongDecisionPath, /缺少已确认的BP/);
  const missingDecision = await fixture('decision-argument-missing', true);
  delete missingDecision.scopeDecisionPath;
  await rejectWithoutOutput(missingDecision, /缺少已确认的BP/);
  const wrongPinnedHash = await fixture('decision-wrong-pinned-hash', true);
  wrongPinnedHash.data.authorization.scopeDecision.sha256 = '0'.repeat(64);
  await writeFile(wrongPinnedHash.authorizationPath, encode(wrongPinnedHash.data.authorization));
  await rejectWithoutOutput(wrongPinnedHash, /来源摘要已变化/);

  const malformedDecision = await fixture('decision-bad-json', true);
  await writeFile(malformedDecision.scopeDecisionPath, `${marker}_MALFORMED`);
  const malformedBefore = await snapshot(malformedDecision);
  const malformedResponse = child(malformedDecision);
  equal(malformedResponse.status, 1, 'malformed decision fails the CLI');
  equal(malformedResponse.stdout, '', 'malformed decision emits no summary');
  ok(malformedResponse.stderr.includes('输入不是有效JSON，未输出原文。'), 'decision parse error is sanitized');
  await absent(malformedDecision.outputPath);
  await unchanged(malformedDecision, malformedBefore);

  for (const kind of ['symlink', 'hardlink']) {
    const decisionAlias = await fixture(`decision-input-${kind}`, true);
    const shared = { ...decisionAlias.data.audit, ...decisionAlias.data.scopeDecision };
    decisionAlias.data.audit = shared; decisionAlias.data.scopeDecision = shared;
    await saveInputs(decisionAlias);
    await rm(decisionAlias.scopeDecisionPath);
    await (kind === 'symlink' ? symlink : link)(decisionAlias.auditPath, decisionAlias.scopeDecisionPath);
    await rejectWithoutOutput(decisionAlias, /输入不能通过链接重复/);
    const protectedDecision = await fixture(`decision-output-${kind}`, true);
    await (kind === 'symlink' ? symlink : link)(protectedDecision.scopeDecisionPath, protectedDecision.outputPath);
    const prior = await snapshot(protectedDecision);
    await rejects(() => runApprovalPreview(protectedDecision), { code: 'EEXIST' }, 'output alias cannot overwrite private decision');
    await unchanged(protectedDecision, prior);
  }

  const differentDecisionRegion = await fixture('confirmed-region-mismatch', true);
  differentDecisionRegion.data.scopeDecision.bp_region_assignments[0].region = '合成区域一';
  await blocked(differentDecisionRegion, 'CONFIRMED_BP_SCOPE_MISMATCH');
  const differentSourceId = await fixture('confirmed-source-id-mismatch', true);
  differentSourceId.data.scopeDecision.bp_region_assignments[1].source_staff_id = 'SYNTHETIC-CONFLICT-ID';
  differentSourceId.privateSentinels.push('SYNTHETIC-CONFLICT-ID');
  await blocked(differentSourceId, 'CONFIRMED_BP_SCOPE_MISMATCH');
  const borrowedOrdinaryTeacher = await fixture('confirmed-ordinary-teacher', true);
  borrowedOrdinaryTeacher.data.authorization.bpRegionAssignments[0].reference = 'teacher-row-3';
  await blocked(borrowedOrdinaryTeacher, 'CONFIRMED_SCOPE_POLICY_INVALID');
  const wrongManagementBranch = await fixture('confirmed-management-outside-region', true);
  wrongManagementBranch.data.authorization.branchManagementAssignments[0].organization = '合成分公司甲';
  wrongManagementBranch.data.scopeDecision.branch_management_overrides[0].organization = '合成分公司甲';
  await blocked(wrongManagementBranch, 'CONFIRMED_BRANCH_MANAGER_MISMATCH');
} finally {
  await rm(scratch, { recursive: true, force: true });
}
await absent(scratch);
console.log(`M01 approval-role CLI: ${checks} checks passed; synthetic temporary files cleaned.`);
