// Synthetic-only pure-function checks. No actual identities, spreadsheets, databases, or APIs.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createApprovalRoleFixture, createConfirmedApprovalRoleFixture } from './M01-approval-role-fixture.mjs';

const [accountSource, approvalSource] = await Promise.all([
  readFile(new URL('../web/modules/identity/account-import-preview.js', import.meta.url), 'utf8'),
  readFile(new URL('../web/modules/identity/approval-role-preview.js', import.meta.url), 'utf8'),
]);
const moduleURL = text => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const accountURL = moduleURL(`${accountSource}\n//# sourceURL=M01-account-import-preview-under-test.js`);
const adapted = approvalSource.replace(/(['"])\.\/account-import-preview\.js\1/, JSON.stringify(accountURL));
assert.notEqual(adapted, approvalSource, 'Relative account module import must be replaced explicitly');
const { previewApprovalRoles } = await import(moduleURL(`${adapted}\n//# sourceURL=M01-approval-role-preview-under-test.js`));
let assertions = 0, scenarios = 0; const failures = [];
function check(label, actual, expected = true) { assert.deepEqual(actual, expected, label); assertions++; }
function scenario(label, action) { scenarios++; try { action(); console.log(`PASS ${label}`); } catch (error) { failures.push(label); console.error(`FAIL ${label}: ${error.stack || error}`); } }
const run = data => previewApprovalRoles(data.batch, data.candidatePolicy, data.roleSource, data.authorization, data.regionReference, data.scopeDecision);
const freeze = item => { if (item && typeof item === 'object') { Object.values(item).forEach(freeze); Object.freeze(item); } return item; };

function safety(result) {
  const switches = [];
  const walk = value => { if (!value || typeof value !== 'object') return; for (const [key, item] of Object.entries(value)) { if (/^can[A-Z]/.test(key)) switches.push(item); walk(item); } };
  walk(result);
  check('所有执行和审批开关始终为false', switches.length >= 2 && switches.every(item => item === false));
  check('无写入操作', result.writesPerformed, false); check('实际审批授权数为零', result.summary.actualApprovalPermissionsAssigned, 0);
  check('人员机构编码与账号绑定保持空值', result.rows.every(row => row.formalPersonCode === null && row.formalOrganizationCode === null && row.accountBinding === null));
}
function privateValuesAbsent(result, sentinels) { check('输出不包含姓名/来源ID/原岗位值', sentinels.every(value => !JSON.stringify(result).includes(value))); }
function invalid(change, issueCode, accountIssueCode, factory = createApprovalRoleFixture) {
  const data = factory(); change(data); const result = run(data);
  check('错误材料不得通过范围核对', result.scopeValidated, false);
  check(`问题包含${issueCode}`, result.issues.some(issue => issue.code === issueCode));
  if (accountIssueCode) check(`保留账号准备问题${accountIssueCode}`, result.accountIssues.some(issue => issue.code === accountIssueCode));
  check('错误时全部候选审批角色被清空', result.rows.every(row => row.approvalEligibility === 'REVIEW_BLOCKED' && row.approvalRoles.length === 0));
  check('错误时无覆盖表或有效授权决定', [result.branchCoverage, result.decision], [[], null]);
  safety(result); privateValuesAbsent(result, data.privateSentinels); return { data, result };
}

scenario('valid preparation preserves every candidate and excludes history without granting permissions', () => {
  const data = createApprovalRoleFixture(), result = run(data);
  check('合成账号范围与岗位证据均有效', [result.accountScopeValidated, result.scopeValidated], [true, true]); check('无问题', result.issues, []);
  check('候选与岗位人数准确', { candidates: result.summary.candidates, branches: result.summary.branchRolePeople, leads: result.summary.branchLeads, bps: result.summary.bpRolePeople, ordinary: result.summary.ordinaryCandidatesWithoutApproval, history: result.summary.historicalExcluded }, { candidates: 7, branches: 4, leads: 2, bps: 2, ordinary: 1, history: 2 });
  check('历史仅保留DO_NOT_CREATE记录', result.historicalExclusions.every(item => item.disposition === 'DO_NOT_CREATE'));
  check('历史引用不进入候选或审批组', !result.rows.some(row => ['teacher-row-12', 'teacher-row-13'].includes(row.reference))); safety(result); privateValuesAbsent(result, data.privateSentinels);
});

scenario('ordinary teacher never inherits approval from same-name personnel', () => {
  const data = createApprovalRoleFixture(), result = run(data);
  check('合成输入确有同名不同机构', data.batch.candidates[0].name === data.batch.candidates[1].name && data.batch.candidates[0].organization !== data.batch.candidates[1].organization);
  const ordinary = result.rows.find(row => row.reference === 'teacher-row-3'), responsible = result.rows.find(row => row.reference === 'teacher-row-4');
  check('普通教师不继承同名负责人身份', [ordinary.approvalEligibility, ordinary.approvalRoles], ['NO_APPROVAL_ROLE', []]);
  check('指定引用的负责人保留其角色', responsible.approvalRoles[0].kind, 'BRANCH_RESPONSIBLE');
  check('同名两条记录没有合并', result.rows.filter(row => ['teacher-row-3', 'teacher-row-4'].includes(row.reference)).length, 2); safety(result);
});

scenario('responsible people cover only their branch, never all branches in the same region', () => {
  const result = run(createApprovalRoleFixture()); const role = result.rows.find(row => row.reference === 'teacher-row-4').approvalRoles[0];
  check('明确范围仅为对应分公司', [role.scopeStatus, role.proposedBranches, role.proposedRegions], ['CORRESPONDING_BRANCH_ONLY', ['合成分公司甲'], []]);
  check('同区域另一分公司不借用该负责人', !result.branchCoverage.find(item => item.organization === '合成分公司乙').candidateReferences.includes('teacher-row-4'));
  check('来源列界从授权行重建', role.source.range, 'B20:D20'); safety(result);
});

scenario('all BP scopes remain pending with empty branches and regions', () => {
  const result = run(createApprovalRoleFixture()); const bps = result.rows.filter(row => row.approvalEligibility === 'BP_SCOPE_PENDING');
  check('两位BP都保留', bps.length, 2);
  check('全部BP不推定负责区或分公司', bps.every(row => row.approvalRoles.length === 1 && row.approvalRoles[0].kind === 'BP' && row.approvalRoles[0].scopeStatus === 'PENDING_BP_REGION_EVIDENCE' && row.approvalRoles[0].proposedBranches.length === 0 && row.approvalRoles[0].proposedRegions.length === 0));
  check('BP不成为分公司默认负责人', result.branchCoverage.every(branch => branch.defaultHandlerReference === null && !branch.candidateReferences.some(reference => ['teacher-row-6', 'teacher-row-7'].includes(reference)))); safety(result);
});

scenario('teachers retain concurrent roles and missing staff IDs do not exclude candidates', () => {
  const result = run(createApprovalRoleFixture()); const concurrent = result.rows.find(row => row.reference === 'teacher-row-8');
  check('教师兼牵头身份保留', concurrent.identities, ['兼职教师', '分公司牵头人']); check('兼岗来源明确', concurrent.approvalRoles[0].kind, 'BRANCH_LEAD');
  const pending = result.rows.find(row => row.reference === 'teacher-row-5'); check('无ID负责人仍保留候选与角色证据', [pending.approvalEligibility, pending.approvalRoles[0].sourceIdentityCheck], ['BRANCH_SCOPE_PREPARED', 'SOURCE_ID_PENDING_CANDIDATE_RETAINED']);
  check('BP兼教师身份也不丢失', result.rows.find(row => row.reference === 'teacher-row-6').identities, ['兼职教师']); safety(result);
});

scenario('BP source without an organization field retains independently confirmed group membership', () => {
  const data = createApprovalRoleFixture(); data.roleSource.bp_approvers.forEach(person => { delete person.organization; });
  const result = run(data); check('原BP来源可省略机构字段', result.scopeValidated); check('独立岗位成员仍保留两位BP', result.summary.bpRolePeople, 2); safety(result);
});

scenario('multiple branch identities require routing confirmation and missing branches remain explicit', () => {
  const result = run(createApprovalRoleFixture()); const multiple = result.branchCoverage.find(item => item.organization === '合成分公司甲');
  check('多负责人和牵头人全部保留', multiple.candidateReferences, ['teacher-row-4', 'teacher-row-5', 'teacher-row-8']);
  check('多人不默认选第一人', [multiple.status, multiple.defaultHandlerReference], ['MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING', null]);
  const missing = result.branchCoverage.find(item => item.organization === '合成缺负责人分公司'); check('缺负责人公司明确待补', [missing.status, missing.candidateReferences, missing.defaultHandlerReference], ['CURRENT_ASSIGNEE_MISSING', [], null]);
  check('覆盖统计不掩盖缺口', [result.summary.branchesTotal, result.summary.branchesCovered, result.summary.branchesMissingAssignee, result.summary.branchesWithMultipleIdentities], [3, 2, 1, 1]); safety(result);
});

scenario('user-confirmed organization supersedes the old group organization without creating identity mappings', () => {
  const result = run(createApprovalRoleFixture()); const row = result.rows.find(item => item.reference === 'lead-row-9'), role = row.approvalRoles[0];
  check('用户确认的新机构保持', row.organization, '合成分公司乙');
  check('旧机构仅作为来源并保留用户覆写标识', [role.sourceOrganization, role.currentOrganization, role.organizationResolution, role.proposedBranches], ['合成旧归属机构', '合成分公司乙', 'USER_CONFIRMED', ['合成分公司乙']]); safety(result);
});

const cases = [
  ['missing role source row', d => d.roleSource.branch_approvers.pop(), 'ROLE_SOURCE_SCOPE_MISMATCH'],
  ['duplicate role cell hides another required cell', d => { d.roleSource.branch_approvers[1] = structuredClone(d.roleSource.branch_approvers[0]); }, 'ROLE_CANDIDATE_REFERENCE_MISMATCH'],
  ['wrong role source worksheet', d => { d.roleSource.branch_approvers[0].source_sheet = '合成错误表'; }, 'ROLE_CANDIDATE_REFERENCE_MISMATCH'],
  ['wrong role source cell', d => { d.roleSource.branch_approvers[0].source_range = 'B20:D21'; }, 'ROLE_CANDIDATE_REFERENCE_MISMATCH'],
  ['missing original candidate cell', d => { delete d.roleSource.branch_approvers[0].teacher_source; }, 'CANDIDATE_CELL_MISMATCH'],
  ['wrong original candidate reference cell', d => { d.roleSource.branch_approvers[0].teacher_source.range = 'A3:F3'; }, 'CANDIDATE_CELL_MISMATCH'],
  ['role source name differs from explicit reference', d => { d.roleSource.branch_approvers[0].name = 'SYNTHETIC-OTHER-PRIVATE-NAME'; }, 'PERSON_OR_ORGANIZATION_MISMATCH'],
  ['role label mismatch', d => { d.roleSource.branch_approvers[0].approval_identity = 'HR-BP'; }, 'ROLE_EVIDENCE_MISMATCH'],
  ['incorrect branch region', d => { d.roleSource.branch_approvers[0].region = '合成错误区域'; }, 'BRANCH_SCOPE_MISMATCH'],
  ['unknown branch does not become region-wide scope', d => { d.authorization.assignments[0].organization = '合成无来源分公司'; }, 'BRANCH_SCOPE_MISMATCH'],
  ['confirmed organization cannot revert to old organization', d => { d.batch.candidates.at(-1).organization = '合成旧归属机构'; }, 'ACCOUNT_PREPARATION_INVALID', 'CONFIRMED_ORGANIZATION_MISMATCH'],
  ['organization override requires independent decision', d => { d.candidatePolicy.organizationResolutions = []; }, 'ORGANIZATION_DECISION_REQUIRED'],
  ['BP cannot claim a responsible region', d => { d.roleSource.bp_approvers[0].responsible_region = '合成区域一'; }, 'BP_SCOPE_WITHOUT_EVIDENCE'],
  ['BP missing null scope also remains invalid', d => { delete d.roleSource.bp_approvers[0].responsible_region; }, 'BP_SCOPE_WITHOUT_EVIDENCE'],
  ['BP membership cannot switch source institution', d => { d.roleSource.bp_approvers[0].organization = '合成其他来源机构'; }, 'BP_MEMBERSHIP_MISMATCH'],
  ['BP assignment cannot switch institution', d => { d.authorization.assignments[4].sourceOrganization = '合成其他来源机构'; }, 'BP_MEMBERSHIP_MISMATCH'],
  ['conflicting source ID cannot be borrowed', d => { d.roleSource.branch_approvers[0].staff_id = '00089999'; }, 'SOURCE_ID_CONFLICT'],
  ['source ID numeric coercion is rejected', d => { d.roleSource.branch_approvers[0].staff_id = 80001; }, 'SOURCE_ID_TYPE_INVALID'],
  ['source ID whitespace is rejected', d => { d.roleSource.branch_approvers[0].staff_id = ' 00080001'; }, 'SOURCE_ID_TYPE_INVALID'],
  ['unknown authorization strategy rejected', d => { d.authorization.decision.scope = 'ALL_REGION_ADMIN'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['ordinary teacher approval strategy rejected', d => { d.authorization.decision.ordinaryTeacherApproval = true; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['BP blanket scope strategy rejected', d => { d.authorization.decision.bpScope = 'ALL_REGIONS'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['unknown role enum rejected', d => { d.authorization.assignments[0].role = 'SUPER_APPROVER'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['duplicate assignment reference rejected', d => { d.authorization.assignments[1].reference = d.authorization.assignments[0].reference; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['invalid source reference rejected', d => { d.authorization.assignments[0].reference = 'account-999'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['well-formed unapproved candidate reference rejected', d => { d.authorization.assignments[0].reference = 'teacher-row-999'; }, 'ROLE_CANDIDATE_REFERENCE_MISMATCH'],
  ['historical candidate cannot receive approval assignment', d => { d.authorization.assignments[0].reference = 'teacher-row-12'; }, 'ROLE_CANDIDATE_REFERENCE_MISMATCH'],
  ['missing authorized source row rejected', d => d.authorization.assignments.pop(), 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['duplicate authorization source row rejected', d => { d.authorization.assignments[1].sourceRow = 20; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['incorrect authorization source workbook rejected', d => { d.authorization.branchSource.path = '/synthetic-only/other.xlsx'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['missing authorization rejected', d => { d.authorization = null; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['invalid manifest digest shape rejected', d => { d.authorization.roleSourceSha256 = 'not-a-digest'; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['missing source audit digest rejected', d => { delete d.authorization.sourceAuditSha256; }, 'REVIEWED_AUTHORIZATION_REQUIRED'],
  ['candidate original digest mismatch blocks roles', d => { d.batch.source_files[0].sha256 = '0'.repeat(64); }, 'ACCOUNT_PREPARATION_INVALID', 'SOURCE_DIGEST_MISMATCH'],
  ['missing approved account candidate blocks roles', d => d.batch.candidates.splice(0, 1), 'ACCOUNT_PREPARATION_INVALID', 'MISSING_APPROVED_CANDIDATE'],
  ['historical exclusion cannot become create request', d => { d.batch.historical_exclusions[0].account_disposition = 'PREPARE_ACCOUNT_ONLY'; }, 'ACCOUNT_PREPARATION_INVALID', 'HISTORY_MUST_NOT_CREATE'],
  ['missing region reference prevents branch preparation', d => { d.regionReference = null; }, 'REGION_REFERENCE_INVALID'],
  ['missing region source metadata rejected', d => { delete d.regionReference.regions[0].branches[0].region_reference; }, 'REGION_SOURCE_CELL_INVALID'],
  ['incorrect region source range rejected', d => { d.regionReference.regions[0].branches[0].region_reference.range = 'A3:B4'; }, 'REGION_SOURCE_CELL_INVALID'],
  ['duplicate branch names rejected', d => { d.regionReference.regions[1].branches.push(structuredClone(d.regionReference.regions[0].branches[0])); }, 'REGION_REFERENCE_INVALID'],
];
for (const [label, mutate, code, accountCode] of cases) scenario(label, () => invalid(mutate, code, accountCode));

scenario('region source extra private fields are stripped by metadata whitelist', () => {
  const data = createApprovalRoleFixture(); const region = data.regionReference.regions[0].branches[0].region_reference;
  region.name = 'SYNTHETIC-REGION-PRIVATE-NAME'; region.staff_id = 'SYNTHETIC-REGION-PRIVATE-ID'; region.extra = { nested: 'SYNTHETIC-NESTED-PRIVATE-VALUE' };
  const result = run(data); check('多余属性不变成业务指令', result.scopeValidated);
  check('区域覆盖只输出白名单来源字段', result.branchCoverage[0].source, { sheet: '合成分区表', range: 'A3:B3' });
  privateValuesAbsent(result, [...data.privateSentinels, region.name, region.staff_id, region.extra.nested]); safety(result);
});

scenario('untrusted role-source flags cannot turn preparation into permission or routing', () => {
  const data = createApprovalRoleFixture(); Object.assign(data.roleSource, { canApprove: true, canCreateAccounts: true, canPublishConfiguration: true, defaultHandlerReference: 'teacher-row-4' });
  data.roleSource.branch_approvers[0].proposedRegions = ['合成区域一']; data.roleSource.branch_approvers[0].canApprove = true;
  const result = run(data); check('只按独立manifest解释范围', result.scopeValidated); check('来源文字不建立默认路由', result.branchCoverage.every(item => item.defaultHandlerReference === null)); safety(result);
});

scenario('frozen inputs remain unchanged and output is deterministic', () => {
  const data = createApprovalRoleFixture(), before = structuredClone(data); freeze(data); const first = run(data), second = run(data);
  check('接受深冻结输入', first.scopeValidated); check('不修改任一来源或策略对象', data, before); check('重复执行结果一致', first, second);
  first.rows[0].identities.push('SYNTHETIC-OUTPUT-CHANGE'); check('输出身份数组不影响原输入', data.batch.candidates[0].identities, before.batch.candidates[0].identities); privateValuesAbsent(second, data.privateSentinels); safety(second);
});

scenario('pure preparation never accesses browser or persistent services', () => {
  const data = createApprovalRoleFixture(), names = ['fetch', 'document', 'localStorage', 'sessionStorage']; const descriptors = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)])); let result;
  try {
    for (const name of names) Object.defineProperty(globalThis, name, { configurable: true, get() { throw new Error(`Forbidden access: ${name}`); } });
    result = run(data);
  } finally { for (const name of names) { const old = descriptors.get(name); if (old) Object.defineProperty(globalThis, name, old); else delete globalThis[name]; } }
  check('无DOM网络存储访问', result.scopeValidated); safety(result);
});

scenario('confirmed BP scopes and additional management preserve person counts and require workflow review', () => {
  const data = createConfirmedApprovalRoleFixture(), result = run(data);
  check('独立新决定模式核对通过', result.scopeValidated); check('新模式无问题', result.issues, []);
  check('兼任增加岗位数但不重复计人', { candidates: result.summary.candidates, sourceBranch: result.summary.sourceBranchRolePeople, branch: result.summary.branchRolePeople, bp: result.summary.bpRolePeople, preparedBp: result.summary.bpScopesPrepared, unique: result.summary.uniqueApprovalPeople, roles: result.summary.preparedRoleAssignments, combined: result.summary.peopleWithCombinedDuties, ordinary: result.summary.ordinaryCandidatesWithoutApproval }, { candidates: 7, sourceBranch: 4, branch: 5, bp: 2, preparedBp: 2, unique: 6, roles: 7, combined: 1, ordinary: 1 });
  const manager = result.rows.find(row => row.reference === 'teacher-row-6');
  check('同一候选保留BP和负责人两个角色', manager.approvalRoles.map(role => role.kind), ['BP', 'BRANCH_RESPONSIBLE']);
  check('兼管不改变原所属机构', manager.organization, '合成总部人力机构'); check('兼任需流程复核', manager.combinedDutiesRequiresWorkflowReview);
  check('待办包含同一人跨阶段处理复核', result.requiredBeforePublication.includes('HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW'));
  check('已确认BP不再提示范围缺失', !result.requiredBeforePublication.includes('CONFIRM_BP_SCOPE_RELATIONS'));
  check('明确兼管补齐缺口', result.summary.branchesMissingAssignee, 0); check('没有新增候选记录', result.rows.map(row => row.reference), data.batch.candidates.map(row => row.candidate_reference));
  safety(result); privateValuesAbsent(result, data.privateSentinels);
});

scenario('confirmed BP regions are individually scoped and coverage counts only explicit branch management', () => {
  const result = run(createConfirmedApprovalRoleFixture()), first = result.rows.find(row => row.reference === 'teacher-row-6'), second = result.rows.find(row => row.reference === 'teacher-row-7');
  check('第一BP仅确认区域二', [first.approvalEligibility, first.approvalRoles[0].scopeStatus, first.approvalRoles[0].proposedRegions, first.approvalRoles[0].proposedBranches], ['BP_SCOPE_PREPARED', 'USER_CONFIRMED_REGION', ['合成区域二'], ['合成缺负责人分公司']]);
  check('第二BP仅确认区域一及其分公司', [second.approvalRoles[0].proposedRegions, second.approvalRoles[0].proposedBranches], [['合成区域一'], ['合成分公司甲', '合成分公司乙']]);
  check('BP区域范围不自动成为各公司的负责人', result.branchCoverage.find(branch => branch.organization === '合成分公司甲').candidateReferences, ['teacher-row-4', 'teacher-row-5', 'teacher-row-8']);
  check('只有明确兼管的公司增加该负责人', result.branchCoverage.find(branch => branch.organization === '合成缺负责人分公司').candidateReferences, ['teacher-row-6']);
  check('已确认仍不推定默认办理人', result.branchCoverage.every(branch => branch.defaultHandlerReference === null));
  check('兼任负责人范围只是一家公司', first.approvalRoles[1].proposedBranches, ['合成缺负责人分公司']); safety(result);
});

scenario('confirmed decision does not overwrite roster evidence or fill a missing candidate ID', () => {
  const data = createConfirmedApprovalRoleFixture(), before = structuredClone(data); freeze(data); const result = run(data);
  check('新模式接受冻结证据', result.scopeValidated); check('新决定不写回旧材料', data, before);
  check('旧BP来源的负责区仍为空', data.roleSource.bp_approvers.every(person => person.responsible_region === null));
  check('旧候选缺失ID仍保持缺失', data.batch.candidates.find(row => row.candidate_reference === 'teacher-row-7').source_staff_id_candidate, null);
  check('有新范围但身份绑定仍待核验', result.rows.find(row => row.reference === 'teacher-row-7').approvalRoles[0].sourceIdentityCheck, 'SOURCE_ID_PENDING_CANDIDATE_RETAINED');
  check('确认模式计算可重复', result, run(data)); privateValuesAbsent(result, data.privateSentinels); safety(result);
});

const confirmedCases = [
  ['confirmed mode requires separate raw decision', d => { delete d.scopeDecision; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['confirmed decision digest shape required', d => { d.authorization.scopeDecision.sha256 = 'bad'; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['confirmed decision path required', d => { delete d.authorization.scopeDecision.path; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['every BP needs exactly one region assignment', d => d.authorization.bpRegionAssignments.pop(), 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['duplicate confirmed BP reference rejected', d => { d.authorization.bpRegionAssignments[1].reference = 'teacher-row-6'; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['duplicate decision index rejected', d => { d.authorization.bpRegionAssignments[1].decisionIndex = 0; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['unapproved BP reference rejected', d => { d.authorization.bpRegionAssignments[1].reference = 'teacher-row-3'; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['extra raw BP decision cannot broaden the approved set', d => d.scopeDecision.bp_region_assignments.push(structuredClone(d.scopeDecision.bp_region_assignments[0])), 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['confirmed BP decision name must match reference', d => { d.scopeDecision.bp_region_assignments[0].name = 'SYNTHETIC-OTHER-PRIVATE-NAME'; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['confirmed BP decision ID must preserve exact value', d => { d.scopeDecision.bp_region_assignments[0].source_staff_id = '00089999'; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['confirmed BP decision ID cannot become numeric', d => { d.scopeDecision.bp_region_assignments[0].source_staff_id = 80003; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['confirmed BP decision ID is required', d => { delete d.scopeDecision.bp_region_assignments[0].source_staff_id; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['missing candidate ID does not permit borrowed original BP ID', d => { d.scopeDecision.bp_region_assignments[1].source_staff_id = '00089998'; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['confirmed BP raw region must match policy', d => { d.scopeDecision.bp_region_assignments[0].region = '合成区域一'; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['wildcard BP regions rejected', d => { d.authorization.bpRegionAssignments[0].region = '*'; d.scopeDecision.bp_region_assignments[0].region = '*'; }, 'CONFIRMED_BP_SCOPE_MISMATCH'],
  ['missing raw branch-management decision rejected', d => { d.scopeDecision.branch_management_overrides = []; }, 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['duplicate management assignment rejected', d => d.authorization.branchManagementAssignments.push(structuredClone(d.authorization.branchManagementAssignments[0])), 'CONFIRMED_SCOPE_POLICY_INVALID'],
  ['ordinary teacher cannot be declared BP branch manager', d => { d.authorization.branchManagementAssignments[0].reference = 'teacher-row-3'; }, 'CONFIRMED_BRANCH_MANAGER_MISMATCH'],
  ['branch manager name must match explicit candidate', d => { d.scopeDecision.branch_management_overrides[0].manager_name = 'SYNTHETIC-OTHER-PRIVATE-NAME'; }, 'CONFIRMED_BRANCH_MANAGER_MISMATCH'],
  ['branch manager ID must match BP decision identity', d => { d.scopeDecision.branch_management_overrides[0].source_staff_id = '00089997'; }, 'CONFIRMED_BRANCH_MANAGER_MISMATCH'],
  ['branch manager outside own confirmed region rejected', d => { d.authorization.branchManagementAssignments[0].organization = '合成分公司甲'; d.scopeDecision.branch_management_overrides[0].organization = '合成分公司甲'; }, 'CONFIRMED_BRANCH_MANAGER_MISMATCH'],
  ['branch manager raw and policy company must agree', d => { d.scopeDecision.branch_management_overrides[0].organization = '合成分公司乙'; }, 'CONFIRMED_BRANCH_MANAGER_MISMATCH'],
];
for (const [label, mutate, code] of confirmedCases) scenario(label, () => invalid(mutate, code, undefined, createConfirmedApprovalRoleFixture));

scenario('unconfirmed policy cannot consume a scope-decision attachment', () => {
  invalid(data => { data.scopeDecision = createConfirmedApprovalRoleFixture().scopeDecision; }, 'SCOPE_DECISION_NOT_AUTHORIZED');
});

scenario('confirmed raw decision extra personal fields are never reflected', () => {
  const data = createConfirmedApprovalRoleFixture(); data.scopeDecision.bp_region_assignments[0].private_note = 'SYNTHETIC-PRIVATE-NOTE'; data.scopeDecision.branch_management_overrides[0].account_id = 'SYNTHETIC-FAKE-ACCOUNT-ID';
  const result = run(data); check('附加文字不被解释为身份授权', result.scopeValidated); privateValuesAbsent(result, [...data.privateSentinels, 'SYNTHETIC-PRIVATE-NOTE', 'SYNTHETIC-FAKE-ACCOUNT-ID']); safety(result);
});

console.log(`M01 approval role preview: ${scenarios} scenarios; ${assertions} assertions passed; ${failures.length} scenarios failed.`);
if (failures.length) process.exitCode = 1;
