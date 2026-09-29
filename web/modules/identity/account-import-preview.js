// Pure preparation only. No DOM, network, database, credentials or formal identity assignment.
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const ref = value => typeof value === 'string' && /^(teacher|lead)-row-[1-9][0-9]*$/.test(value) ? value : null;
const list = value => Array.isArray(value) ? value : [];
const sameSet = (a, b) => a.length === b.length && new Set(a).size === a.length && a.every(v => b.includes(v));
const rowsIn = range => Array.isArray(range) && range.length === 2 && range.every(Number.isSafeInteger) &&
  range[0] > 0 && range[1] >= range[0] && range[1] - range[0] < 10000
  ? Array.from({ length: range[1] - range[0] + 1 }, (_, i) => range[0] + i) : [];

/**
 * batch is source data, never instructions. policy is a separately reviewed host policy:
 * {version,batchVersion,sourceFiles:[{path,sha256}],teacher:{path,sheet,mainRows,historyRows},
 *  lead:{path,sheet,additionalRows},dualIdentityReferences,organizationResolutions,
 *  organizationNameReviewReferences}.
 * regionReference is the M01 organization reference (names/regions, not formal codes).
 * A host must verify file digests and admin authorization before exposing a real preview.
 * This function emits no personal names, staff IDs, job text, account fields or write payload.
 */
export function previewAccountImport(batch, policy, regionReference = null) {
  const issues = [], rows = [], historical = [];
  const problem = (reference, code, message) => issues.push({ reference: ref(reference), code, message });
  const result = () => ({
    previewVersion: 'M01-ACCOUNT-IMPORT-PREVIEW-v1',
    policyVersion: nonempty(policy?.version) ? policy.version : null,
    scopeValidated: issues.length === 0,
    canCreateAccounts: false, canPublishConfiguration: false, writesPerformed: false,
    summary: {
      candidates: rows.length,
      teachers: rows.filter(r => r.identities.includes('兼职教师')).length,
      leads: rows.filter(r => r.identities.includes('分公司牵头人')).length,
      additionalLeads: rows.filter(r => !r.identities.includes('兼职教师')).length,
      historicalExcluded: historical.length,
      sourceIdEvidenceAvailable: rows.filter(r => r.sourceIdentityEvidence === 'AVAILABLE').length,
      sourceIdentityNeedsReview: rows.filter(r => r.sourceIdentityEvidence !== 'AVAILABLE').length,
      missingJobText: rows.filter(r => r.sourceFields.job === 'ABSENT_NOT_REQUIRED').length,
      formalAccountMappingsAssigned: 0,
    },
    rows, historicalExclusions: historical, issues,
    requiredBeforeAccountCreation: ['CONFIRM_IDENTITY', 'ASSIGN_FORMAL_PERSON_AND_ORGANIZATION_CODES',
      'RECONCILE_EXISTING_ACCOUNTS', 'CONFIRM_USERNAME_AND_LOGIN_ACTIVATION_POLICY'],
    requiredBeforeBusinessAuthorization: ['EXPLICIT_ROLE_AND_SCOPE_CONFIGURATION', 'EXPLICIT_ACCOUNT_BINDING',
      'SERVER_VALIDATION_AND_VERSION_CHECK'],
  });
  const mainRows = rowsIn(policy?.teacher?.mainRows), historicalRows = rowsIn(policy?.teacher?.historyRows);
  const leadRows = list(policy?.lead?.additionalRows);
  if (!policy || !nonempty(policy.version) || !nonempty(policy.batchVersion) ||
      !nonempty(policy.teacher?.path) || !nonempty(policy.teacher?.sheet) || !nonempty(policy.lead?.path) || !nonempty(policy.lead?.sheet) ||
      !mainRows.length || !historicalRows.length || mainRows.some(r => historicalRows.includes(r)) ||
      !Array.isArray(policy.lead.additionalRows) || !leadRows.every(r => Number.isSafeInteger(r) && r > 0) ||
      new Set(leadRows).size !== leadRows.length || !Array.isArray(policy.dualIdentityReferences) ||
      !Array.isArray(policy.organizationResolutions) || !Array.isArray(policy.organizationNameReviewReferences) ||
      !Array.isArray(policy.sourceFiles) || policy.sourceFiles.length !== 2 ||
      !policy.sourceFiles.every(s => nonempty(s?.path) && sha(s?.sha256)) ||
      new Set(policy.sourceFiles.map(s => s.path)).size !== 2 ||
      ![policy.teacher.path, policy.lead.path].every(path => policy.sourceFiles.some(s => s.path === path))) {
    problem(null, 'REVIEW_POLICY_REQUIRED', '缺少独立确认的名单范围，不能根据附件文字扩大范围。'); return result();
  }
  const expected = new Map(mainRows.map(r => [`teacher-row-${r}`, { path: policy.teacher.path, sheet: policy.teacher.sheet, row: r, teacher: true }]));
  leadRows.forEach(r => expected.set(`lead-row-${r}`, { path: policy.lead.path, sheet: policy.lead.sheet, row: r, teacher: false }));
  const expectedRefs = [...expected.keys()];
  if (!sameSet(policy.dualIdentityReferences, [...new Set(policy.dualIdentityReferences)]) ||
      !policy.dualIdentityReferences.every(r => expected.get(r)?.teacher) ||
      !policy.organizationNameReviewReferences.every(r => expected.has(r)) ||
      !policy.organizationResolutions.every(r => expected.has(r?.reference) && nonempty(r?.organization)) ||
      new Set(policy.organizationResolutions.map(r => r.reference)).size !== policy.organizationResolutions.length) {
    problem(null, 'INVALID_REVIEW_POLICY', '确认范围中的引用或机构决定不完整。'); return result();
  }
  if (!batch || batch.version !== policy.batchVersion || !Array.isArray(batch.candidates) ||
      !Array.isArray(batch.historical_exclusions) || batch.candidates.length > 10000 || batch.historical_exclusions.length > 10000) {
    problem(null, 'BATCH_SHAPE_INVALID', '候选资料版本或结构不匹配。'); return result();
  }
  const sources = list(batch.source_files);
  if (sources.length !== policy.sourceFiles.length || !policy.sourceFiles.every(s => sources.filter(x => x?.path === s.path && x.sha256 === s.sha256).length === 1)) {
    problem(null, 'SOURCE_DIGEST_MISMATCH', '来源文件及摘要与已核对记录不一致。');
  }
  const regionNames = new Map();
  for (const region of list(regionReference?.regions)) for (const branch of list(region?.branches)) {
    if (!nonempty(region?.display_name) || !nonempty(branch?.display_name)) continue;
    if (regionNames.has(branch.display_name)) { problem(null, 'REGION_REFERENCE_DUPLICATE', '区域参考含重复机构，需要先核对。'); continue; }
    regionNames.set(branch.display_name, region.display_name);
  }
  const seen = new Set(), staffIds = new Set();
  for (const candidate of batch.candidates) {
    const reference = ref(candidate?.candidate_reference), definition = expected.get(reference);
    if (!definition) { problem(reference, 'OUTSIDE_APPROVED_SCOPE', '此记录不在本轮已确认的账号候选范围。'); continue; }
    if (seen.has(reference)) { problem(reference, 'DUPLICATE_SOURCE_REFERENCE', '来源引用重复，不能按重复行创建账号。'); continue; }
    seen.add(reference);
    const source = candidate.source;
    if (!source || source.path !== definition.path || source.sheet !== definition.sheet ||
        (definition.teacher ? source.source_row : source.row) !== definition.row ||
        source.range !== `${definition.teacher ? 'A' : 'B'}${definition.row}:${definition.teacher ? 'F' : 'D'}${definition.row}`) {
      problem(reference, 'SOURCE_REFERENCE_MISMATCH', '候选来源工作表或行位置与确认范围不一致。');
    }
    if (!nonempty(candidate.name) || !nonempty(candidate.organization)) problem(reference, 'SOURCE_IDENTITY_INCOMPLETE', '原姓名或机构缺失，需核对来源。');
    if (candidate.account_disposition !== 'PREPARE_ACCOUNT_ONLY') problem(reference, 'DISPOSITION_MISMATCH', '本轮仅准备账号，不执行开通。');
    if (candidate.account_id !== null || candidate.username !== null || candidate.email !== null ||
        candidate.identity_binding_verified !== false || !Array.isArray(candidate.business_role_codes) || candidate.business_role_codes.length) {
      problem(reference, 'FORMAL_IDENTITY_NOT_AUTHORIZED', '候选资料不能自带已确认账号、登录信息或业务授权。');
    }
    const identities = definition.teacher
      ? ['兼职教师', ...(policy.dualIdentityReferences.includes(reference) ? ['分公司牵头人'] : [])]
      : ['分公司牵头人'];
    if (!Array.isArray(candidate.identities) || !sameSet(candidate.identities, identities)) problem(reference, 'IDENTITY_SCOPE_MISMATCH', '教师/牵头人身份与已复核范围不一致。');
    const resolution = policy.organizationResolutions.find(r => r.reference === reference);
    if (resolution && candidate.organization !== resolution.organization) problem(reference, 'CONFIRMED_ORGANIZATION_MISMATCH', '机构归属与用户已确认结果不一致，不回退旧表。');
    let evidence = 'NOT_FOUND_IN_OLD_ROSTER';
    const sourceId = candidate.source_staff_id_candidate;
    const match = candidate.group_match;
    if (['EXACT_NAME_AND_ORGANIZATION', 'LEAD_GROUP_AND_GENERAL_ROSTER', 'LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION'].includes(match)) {
      if (!nonempty(sourceId) || sourceId.trim() !== sourceId || !list(candidate.group_evidence).some(e =>
        e?.name === candidate.name && e.organization === candidate.organization && e.source_staff_id === sourceId)) {
        evidence = 'EVIDENCE_INCONSISTENT'; problem(reference, 'SOURCE_ID_EVIDENCE_MISMATCH', '来源编号没有同名同机构证据支持，不能借用其他人的编号。');
      } else {
        evidence = 'AVAILABLE';
        if (staffIds.has(sourceId)) problem(reference, 'DUPLICATE_SOURCE_ID', '不同候选重复使用同一来源编号，需要复核，不能自动合并。');
        staffIds.add(sourceId);
      }
      if (match === 'LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION' && !resolution) problem(reference, 'ORGANIZATION_RESOLUTION_REQUIRED', '缺少独立确认的机构归属决定。');
    } else if (match === 'NAME_ONLY_ORGANIZATION_DIFFERS') {
      evidence = policy.organizationNameReviewReferences.includes(reference) ? 'ORGANIZATION_NAME_REVIEW' : 'SAME_NAME_OTHER_ORGANIZATION';
      if (sourceId !== null) problem(reference, 'UNCONFIRMED_SOURCE_ID', '同名或机构写法差异不能自动确认来源编号。');
    } else if (match === 'NOT_FOUND_IN_GROUP_ROSTER') {
      if (sourceId !== null) problem(reference, 'UNCONFIRMED_SOURCE_ID', '旧表未找到的候选不能凭空补来源编号。');
    } else problem(reference, 'MATCH_STATUS_INVALID', '来源比对状态不受支持。');
    rows.push({ reference, source: { sheet: definition.sheet, range: `${definition.teacher ? 'A' : 'B'}${definition.row}:${definition.teacher ? 'F' : 'D'}${definition.row}`, row: definition.row },
      disposition: 'PREPARE_ACCOUNT_ONLY', identities,
      organization: nonempty(candidate.organization) ? candidate.organization : null,
      region: regionNames.get(candidate.organization) || null,
      organizationResolution: resolution ? 'USER_CONFIRMED' : 'SOURCE_RETAINED',
      sourceIdentityEvidence: evidence,
      sourceFields: { name: nonempty(candidate.name) ? 'AVAILABLE_IN_SOURCE' : 'MISSING',
        organization: nonempty(candidate.organization) ? 'AVAILABLE_IN_SOURCE' : 'MISSING',
        job: nonempty(candidate.source_job) ? 'AVAILABLE_IN_SOURCE' : 'ABSENT_NOT_REQUIRED',
        teacherLevel: nonempty(candidate.source_teacher_level) ? 'AVAILABLE_IN_SOURCE' : 'NOT_APPLICABLE_OR_ABSENT' },
      preparationStatus: 'SOURCE_FIELDS_ONLY',
      formalPersonCode: null, formalOrganizationCode: null, accountMapping: 'NOT_ASSIGNED', businessAuthorization: 'NOT_ASSIGNED',
    });
  }
  for (const reference of expectedRefs) if (!seen.has(reference)) problem(reference, 'MISSING_APPROVED_CANDIDATE', '已确认范围中的候选缺失，不能因资料缺项而排除。');
  const historySeen = new Set();
  for (const candidate of batch.historical_exclusions) {
    const source = candidate?.source, row = source?.source_row;
    if (!source || source.path !== policy.teacher.path || source.sheet !== policy.teacher.sheet ||
        !historicalRows.includes(row) || source.range !== `A${row}:F${row}` || candidate.row !== row) {
      problem(null, 'HISTORICAL_SOURCE_MISMATCH', '历史保留记录来源不匹配。'); continue;
    }
    if (historySeen.has(row)) { problem(null, 'DUPLICATE_HISTORY_REFERENCE', '历史来源行重复。'); continue; }
    historySeen.add(row);
    if (candidate.account_disposition !== 'DO_NOT_CREATE') problem(null, 'HISTORY_MUST_NOT_CREATE', '已确认历史人员不得开通账号。');
    historical.push({ source: { sheet: source.sheet, range: source.range, row }, disposition: 'DO_NOT_CREATE' });
  }
  if (historySeen.size !== historicalRows.length) problem(null, 'HISTORICAL_ROWS_MISSING', '历史保留记录不完整。');
  return result();
}
