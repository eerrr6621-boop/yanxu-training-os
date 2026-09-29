// Pure synthetic tests: no real person names, staff IDs, source documents, or services.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const moduleURL = new URL('../web/modules/identity/account-import-preview.js', import.meta.url);
const source = await readFile(moduleURL, 'utf8');
const { previewAccountImport } = await import(`data:text/javascript;base64,${Buffer.from(`${source}\n//# sourceURL=M01-account-import-preview-under-test.js`).toString('base64')}`);
const clone = value => structuredClone(value);
const freeze = value => { if (value && typeof value === 'object') { for (const nested of Object.values(value)) freeze(nested); Object.freeze(value); } return value; };
let assertions = 0, scenarios = 0;
const failures = [];
function check(label, actual, expected = true) { assert.deepEqual(actual, expected, label); assertions++; }
function scenario(label, action) { scenarios++; try { action(); console.log(`PASS ${label}`); } catch (error) { failures.push(label); console.error(`FAIL ${label}: ${error.stack || error}`); } }

function fixture() {
  const teacherPath = '/synthetic-only/teacher-source.xlsx', leadPath = '/synthetic-only/lead-source.xlsx';
  const sourceFiles = [{ path: teacherPath, sha256: 'a'.repeat(64) }, { path: leadPath, sha256: 'b'.repeat(64) }];
  const policy = {
    version: 'SYNTHETIC-REVIEW-1', batchVersion: 'SYNTHETIC-CANDIDATES-1', sourceFiles,
    teacher: { path: teacherPath, sheet: '合成教师表', mainRows: [4, 6], historyRows: [10, 11] },
    lead: { path: leadPath, sheet: '合成牵头表', additionalRows: [7] },
    dualIdentityReferences: ['teacher-row-4'],
    organizationResolutions: [{ reference: 'lead-row-7', organization: '合成确认机构' }],
    organizationNameReviewReferences: [],
  };
  const candidate = (row, teacher, name, organization) => ({
    candidate_reference: `${teacher ? 'teacher' : 'lead'}-row-${row}`,
    name, organization, source_job: 'SYNTHETIC-JOB-TEXT-DO-NOT-EMIT', source_teacher_level: teacher ? 'SYNTHETIC-LEVEL-DO-NOT-EMIT' : null,
    identities: teacher ? ['兼职教师', ...(row === 4 ? ['分公司牵头人'] : [])] : ['分公司牵头人'],
    source: { path: teacher ? teacherPath : leadPath, sheet: teacher ? '合成教师表' : '合成牵头表', range: `${teacher ? 'A' : 'B'}${row}:${teacher ? 'F' : 'D'}${row}`, ...(teacher ? { source_row: row } : { row }) },
    group_match: 'NOT_FOUND_IN_GROUP_ROSTER', group_evidence: [], source_staff_id_candidate: null,
    account_id: null, username: null, email: null, business_role_codes: [],
    account_disposition: 'PREPARE_ACCOUNT_ONLY', identity_binding_verified: false,
  });
  const candidates = [
    candidate(4, true, 'SYNTHETIC-NAME-A-DO-NOT-EMIT', '合成总部'),
    candidate(5, true, 'SYNTHETIC-SAME-NAME-DO-NOT-EMIT', '合成机构甲'),
    candidate(6, true, 'SYNTHETIC-SAME-NAME-DO-NOT-EMIT', '合成机构乙'),
    candidate(7, false, 'SYNTHETIC-NAME-D-DO-NOT-EMIT', '合成确认机构'),
  ];
  candidates[0].group_match = 'EXACT_NAME_AND_ORGANIZATION'; candidates[0].source_staff_id_candidate = '00070001';
  candidates[0].group_evidence = [{ name: candidates[0].name, organization: candidates[0].organization, source_staff_id: '00070001' }];
  candidates[1].source_job = null;
  candidates[2].group_match = 'NAME_ONLY_ORGANIZATION_DIFFERS';
  candidates[2].group_evidence = [{ name: candidates[2].name, organization: '合成不同旧机构', source_staff_id: '00079999' }];
  candidates[3].group_match = 'LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION'; candidates[3].source_staff_id_candidate = '00070004';
  candidates[3].group_evidence = [{ name: candidates[3].name, organization: candidates[3].organization, source_staff_id: '00070004' }];
  const batch = {
    version: policy.batchVersion, source_files: clone(sourceFiles), candidates,
    historical_exclusions: [10, 11].map(row => ({ row, name: `SYNTHETIC-HISTORY-NAME-${row}-DO-NOT-EMIT`, account_disposition: 'DO_NOT_CREATE', source: { path: teacherPath, sheet: '合成教师表', range: `A${row}:F${row}`, source_row: row } })),
  };
  const regions = { regions: [{ display_name: '合成区域', branches: [{ display_name: '合成机构甲' }, { display_name: '合成机构乙' }] }] };
  return { batch, policy, regions };
}

function gates(result) {
  check('不允许创建账号', result.canCreateAccounts, false); check('不允许发布配置', result.canPublishConfiguration, false);
  check('声明未执行写入', result.writesPerformed, false); check('不分配正式账号映射', result.summary.formalAccountMappingsAssigned, 0);
}
function noPrivateValues(result, batch, extra = []) {
  const privateValues = [...batch.candidates.flatMap(row => [row.name, row.source_job, row.source_teacher_level, row.source_staff_id_candidate, row.account_id, row.username, row.email, ...(row.business_role_codes || []), ...(row.group_evidence || []).flatMap(item => [item.name, item.source_staff_id])]), ...batch.historical_exclusions.map(row => row.name), ...extra].filter(value => value != null && String(value).length > 0);
  const encoded = JSON.stringify(result);
  check('输出不包含姓名/来源ID/岗位/账号等原始值', privateValues.every(value => !encoded.includes(String(value))));
  check('所有候选无正式身份分配', result.rows.every(row => row.formalPersonCode === null && row.formalOrganizationCode === null && row.accountMapping === 'NOT_ASSIGNED' && row.businessAuthorization === 'NOT_ASSIGNED'));
}
function rejected(change, code) {
  const data = fixture(); change(data); const result = previewAccountImport(data.batch, data.policy, data.regions);
  check('范围验证不通过', result.scopeValidated, false); check(`报告${code}`, result.issues.some(issue => issue.code === code)); gates(result); return { ...data, result };
}

scenario('approved synthetic scope remains preparation only, including headquarters without a region', () => {
  const { batch, policy, regions } = fixture(); const result = previewAccountImport(batch, policy, regions);
  check('完整合成范围通过', result.scopeValidated); check('通过时无问题', result.issues, []); gates(result);
  check('人数和双身份统计不膨胀', { candidates: result.summary.candidates, teachers: result.summary.teachers, leads: result.summary.leads, additionalLeads: result.summary.additionalLeads, history: result.summary.historicalExcluded }, { candidates: 4, teachers: 3, leads: 2, additionalLeads: 1, history: 2 });
  const headquarters = result.rows.find(row => row.reference === 'teacher-row-4'); check('总部无区域仍保留', headquarters.organization, '合成总部'); check('缺分区仅留空参考', headquarters.region, null);
  check('教师使用A至F来源范围', headquarters.source, { sheet: '合成教师表', range: 'A4:F4', row: 4 });
  check('牵头来源row规范化后保留B至D范围', result.rows.find(row => row.reference === 'lead-row-7').source, { sheet: '合成牵头表', range: 'B7:D7', row: 7 });
  check('匹配来源ID只是证据状态', headquarters.sourceIdentityEvidence, 'AVAILABLE'); noPrivateValues(result, batch);
});

scenario('missing job text or old-roster ID never removes approved candidates', () => {
  const { batch, policy } = fixture(); for (const candidate of batch.candidates) { candidate.source_job = null; candidate.source_teacher_level = null; }
  const result = previewAccountImport(batch, policy); check('职务职级缺失不破坏范围', result.scopeValidated); check('全部候选保留', result.rows.length, 4);
  check('缺职务计数完整', result.summary.missingJobText, 4); const row = result.rows.find(row => row.reference === 'teacher-row-5');
  check('旧表缺ID仍保留来源候选', row.sourceIdentityEvidence, 'NOT_FOUND_IN_OLD_ROSTER'); check('缺岗位明确非必填', row.sourceFields.job, 'ABSENT_NOT_REQUIRED'); gates(result);
});

scenario('same names at different organizations stay separate with no borrowed source ID', () => {
  const { batch, policy } = fixture(); const result = previewAccountImport(batch, policy); const rows = result.rows.filter(row => ['teacher-row-5', 'teacher-row-6'].includes(row.reference));
  check('同名两机构保留两条来源', rows.map(row => [row.reference, row.organization]), [['teacher-row-5', '合成机构甲'], ['teacher-row-6', '合成机构乙']]);
  check('同名不同机构不能当已匹配', rows[1].sourceIdentityEvidence, 'SAME_NAME_OTHER_ORGANIZATION'); check('原始人员身份没有自动核实', batch.candidates.every(row => row.identity_binding_verified === false)); noPrivateValues(result, batch);
});

scenario('independent institution name-review and resolution decisions are recognized only as preparation evidence', () => {
  const { batch, policy } = fixture(); policy.organizationNameReviewReferences = ['teacher-row-6']; const result = previewAccountImport(batch, policy);
  check('已确认机构写法复核标识', result.rows.find(row => row.reference === 'teacher-row-6').sourceIdentityEvidence, 'ORGANIZATION_NAME_REVIEW');
  check('用户明确机构决定保留', result.rows.find(row => row.reference === 'lead-row-7').organizationResolution, 'USER_CONFIRMED'); gates(result);
});

for (const mutation of [
  ['missing confirmed candidate', data => data.batch.candidates.splice(1, 1), 'MISSING_APPROVED_CANDIDATE'],
  ['duplicate confirmed reference', data => data.batch.candidates.push(clone(data.batch.candidates[0])), 'DUPLICATE_SOURCE_REFERENCE'],
  ['candidate outside trusted scope', data => { const row = clone(data.batch.candidates[0]); row.candidate_reference = 'teacher-row-9'; row.source.source_row = 9; row.source.range = 'A9:F9'; data.batch.candidates.push(row); }, 'OUTSIDE_APPROVED_SCOPE'],
  ['historical row smuggled into candidates', data => { const row = clone(data.batch.candidates[0]); row.candidate_reference = 'teacher-row-10'; row.source.source_row = 10; row.source.range = 'A10:F10'; data.batch.candidates.push(row); }, 'OUTSIDE_APPROVED_SCOPE'],
  ['source file digest differs', data => { data.batch.source_files[0].sha256 = 'c'.repeat(64); }, 'SOURCE_DIGEST_MISMATCH'],
  ['source file path differs', data => { data.batch.source_files[0].path = '/synthetic-only/substituted.xlsx'; }, 'SOURCE_DIGEST_MISMATCH'],
  ['source row differs', data => { data.batch.candidates[0].source.source_row = 40; }, 'SOURCE_REFERENCE_MISMATCH'],
  ['source sheet differs', data => { data.batch.candidates[0].source.sheet = '合成错误表'; }, 'SOURCE_REFERENCE_MISMATCH'],
  ['source column boundary differs', data => { data.batch.candidates[0].source.range = 'A4:Z4'; }, 'SOURCE_REFERENCE_MISMATCH'],
  ['lead source must use row rather than teacher source_row', data => { const source = data.batch.candidates[3].source; source.source_row = source.row; delete source.row; }, 'SOURCE_REFERENCE_MISMATCH'],
  ['lead source must begin in column B', data => { data.batch.candidates[3].source.range = 'A7:D7'; }, 'SOURCE_REFERENCE_MISMATCH'],
  ['history creation request rejected', data => { data.batch.historical_exclusions[0].account_disposition = 'PREPARE_ACCOUNT_ONLY'; }, 'HISTORY_MUST_NOT_CREATE'],
  ['history source row mismatch', data => { data.batch.historical_exclusions[0].row = 12; }, 'HISTORICAL_SOURCE_MISMATCH'],
  ['history missing row', data => data.batch.historical_exclusions.pop(), 'HISTORICAL_ROWS_MISSING'],
  ['history duplicate row', data => data.batch.historical_exclusions.push(clone(data.batch.historical_exclusions[0])), 'DUPLICATE_HISTORY_REFERENCE'],
  ['identity scope escalation rejected', data => { data.batch.candidates[1].identities.push('分公司牵头人'); }, 'IDENTITY_SCOPE_MISMATCH'],
  ['actual creation disposition rejected', data => { data.batch.candidates[0].account_disposition = 'CREATE_ACCOUNT'; }, 'DISPOSITION_MISMATCH'],
  ['confirmed organization cannot roll back to old roster', data => { data.batch.candidates[3].organization = '合成旧表机构'; }, 'CONFIRMED_ORGANIZATION_MISMATCH'],
  ['candidate cannot self-assert an organization resolution', data => { data.policy.organizationResolutions = []; data.batch.organizationResolutions = [{ reference: 'lead-row-7', organization: '合成确认机构' }]; }, 'ORGANIZATION_RESOLUTION_REQUIRED'],
  ['another name cannot lend its source ID', data => { data.batch.candidates[0].group_evidence[0].name = 'SYNTHETIC-OTHER-NAME-DO-NOT-EMIT'; }, 'SOURCE_ID_EVIDENCE_MISMATCH'],
  ['another organization cannot lend its source ID', data => { data.batch.candidates[0].group_evidence[0].organization = '合成其他机构'; }, 'SOURCE_ID_EVIDENCE_MISMATCH'],
  ['source ID cannot lose leading zeros during evidence matching', data => { data.batch.candidates[0].group_evidence[0].source_staff_id = '70001'; }, 'SOURCE_ID_EVIDENCE_MISMATCH'],
  ['same name with different organization cannot assert staff ID', data => { data.batch.candidates[2].source_staff_id_candidate = '00079999'; }, 'UNCONFIRMED_SOURCE_ID'],
  ['not-found roster cannot invent staff ID', data => { data.batch.candidates[1].source_staff_id_candidate = '00079998'; }, 'UNCONFIRMED_SOURCE_ID'],
  ['source ID reuse does not merge candidates', data => { data.batch.candidates[3].source_staff_id_candidate = '00070001'; data.batch.candidates[3].group_evidence[0].source_staff_id = '00070001'; }, 'DUPLICATE_SOURCE_ID'],
]) scenario(mutation[0], () => { const { result, batch } = rejected(mutation[1], mutation[2]); noPrivateValues(result, batch); });

scenario('forged formal account, username, email, verified identity, and roles are never returned or authorized', () => {
  const { batch, result } = rejected(data => { Object.assign(data.batch.candidates[0], { account_id: 990070001, username: 'SYNTHETIC-LOGIN-DO-NOT-EMIT', email: 'synthetic-only@example.invalid', identity_binding_verified: true, business_role_codes: ['ROLE-SYNTHETIC-ESCALATION'] }); data.batch.canCreateAccounts = true; data.batch.canPublishConfiguration = true; }, 'FORMAL_IDENTITY_NOT_AUTHORIZED');
  noPrivateValues(result, batch); check('候选仍不会输出创建载荷', !Object.hasOwn(result, 'configuration') && !Object.hasOwn(result, 'accounts'));
});

scenario('embedded batch policy cannot expand independent approved scope', () => {
  const { result } = rejected(data => { const row = clone(data.batch.candidates[0]); row.candidate_reference = 'teacher-row-7'; row.source.source_row = 7; row.source.range = 'A7:F7'; data.batch.candidates.push(row); data.batch.policy = { ...clone(data.policy), teacher: { ...data.policy.teacher, mainRows: [4, 7] } }; }, 'OUTSIDE_APPROVED_SCOPE');
  check('伪造范围候选不进入预览行', !result.rows.some(row => row.reference === 'teacher-row-7'));
});

for (const mutation of [
  ['missing independent policy', data => { data.policy = null; }, 'REVIEW_POLICY_REQUIRED'],
  ['overlapping approved and historical ranges', data => { data.policy.teacher.historyRows = [6, 10]; }, 'REVIEW_POLICY_REQUIRED'],
  ['duplicate lead source rows in policy', data => { data.policy.lead.additionalRows = [7, 7]; }, 'REVIEW_POLICY_REQUIRED'],
  ['unapproved dual identity reference', data => { data.policy.dualIdentityReferences = ['teacher-row-999']; }, 'INVALID_REVIEW_POLICY'],
  ['unapproved organization decision reference', data => { data.policy.organizationResolutions[0].reference = 'lead-row-999'; }, 'INVALID_REVIEW_POLICY'],
  ['batch version mismatch', data => { data.batch.version = 'SYNTHETIC-OTHER-VERSION'; }, 'BATCH_SHAPE_INVALID'],
]) scenario(mutation[0], () => { rejected(mutation[1], mutation[2]); });

scenario('regional map supplies optional reference information without assigning formal institution codes', () => {
  const { batch, policy, regions } = fixture(); const withRegion = previewAccountImport(batch, policy, regions); const without = previewAccountImport(batch, policy);
  check('缺区域参考仍可核对来源范围', without.scopeValidated); check('参考分区只为命中机构显示', withRegion.rows.find(row => row.reference === 'teacher-row-5').region, '合成区域');
  check('有无分区均保留相同候选', withRegion.rows.map(row => row.reference), without.rows.map(row => row.reference)); check('分区不生成正式机构编码', withRegion.rows.every(row => row.formalOrganizationCode === null));
  regions.regions.push({ display_name: '合成重复区域', branches: [{ display_name: '合成机构甲' }] });
  const duplicate = previewAccountImport(batch, policy, regions); check('区域参考重复需复核', duplicate.issues.some(issue => issue.code === 'REGION_REFERENCE_DUPLICATE')); gates(duplicate);
});

scenario('untrusted source range never reflects private strings or nested objects into output', () => {
  for (const badRange of ['SYNTHETIC-RANGE-PRIVATE-NAME', { name: 'SYNTHETIC-RANGE-PRIVATE-NAME', id: 'SYNTHETIC-RANGE-PRIVATE-ID' }]) {
    const { batch, policy } = fixture(); batch.candidates[0].source.range = badRange; const result = previewAccountImport(batch, policy);
    check('恶意range报来源位置错误', result.issues.some(issue => issue.code === 'SOURCE_REFERENCE_MISMATCH'));
    check('输出来源列界从可信policy重建', result.rows.find(row => row.reference === 'teacher-row-4').source.range, 'A4:F4');
    noPrivateValues(result, batch, ['SYNTHETIC-RANGE-PRIVATE-NAME', 'SYNTHETIC-RANGE-PRIVATE-ID']); gates(result);
  }
});

scenario('frozen inputs remain unchanged and repeated previews are deterministic', () => {
  const data = fixture(); const before = clone(data); freeze(data);
  const first = previewAccountImport(data.batch, data.policy, data.regions), second = previewAccountImport(data.batch, data.policy, data.regions);
  check('纯函数接受深冻结输入', first.scopeValidated); check('输入完全不变', data, before); check('重复计算得到同样输出', second, first);
  first.rows[0].identities.push('SYNTHETIC-OUTPUT-MUTATION'); check('返回数组不与输入共用', data.batch.candidates[0].identities, before.batch.candidates[0].identities); noPrivateValues(second, data.batch);
});

scenario('preview requires no DOM, network, or persistent storage', () => {
  const names = ['fetch', 'document', 'localStorage', 'sessionStorage']; const descriptors = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const data = fixture(); let result;
  try {
    for (const name of names) Object.defineProperty(globalThis, name, { configurable: true, get() { throw new Error(`Forbidden global access: ${name}`); } });
    result = previewAccountImport(data.batch, data.policy, data.regions);
  } finally { for (const name of names) { const old = descriptors.get(name); if (old) Object.defineProperty(globalThis, name, old); else delete globalThis[name]; } }
  check('没有访问浏览器或外部服务也能预览', result.scopeValidated); gates(result);
});

console.log(`M01 account import preview: ${scenarios} scenarios; ${assertions} assertions passed; ${failures.length} scenarios failed.`);
if (failures.length) process.exitCode = 1;
