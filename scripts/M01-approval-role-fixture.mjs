// Shared synthetic-only fixture. No real identities, IDs, organizations, or source files.
export function createApprovalRoleFixture() {
  const teacherPath = '/synthetic-only/teachers.xlsx', leadPath = '/synthetic-only/groups.xlsx', regionPath = '/synthetic-only/regions.xlsx';
  const branchA = '合成分公司甲', branchB = '合成分公司乙', branchC = '合成缺负责人分公司', bpOrganization = '合成总部人力机构';
  const candidatePolicy = {
    version: 'SYNTHETIC-CANDIDATE-POLICY-1', batchVersion: 'SYNTHETIC-CANDIDATES-1',
    sourceFiles: [{ path: teacherPath, sha256: 'a'.repeat(64) }, { path: leadPath, sha256: 'b'.repeat(64) }],
    teacher: { path: teacherPath, sheet: '合成教师名单', mainRows: [3, 8], historyRows: [12, 13] },
    lead: { path: leadPath, sheet: '合成牵头候选', additionalRows: [9] },
    dualIdentityReferences: ['teacher-row-8'],
    organizationResolutions: [{ reference: 'lead-row-9', organization: branchB }], organizationNameReviewReferences: [],
  };
  const candidate = (row, organization, teacher = true) => ({
    candidate_reference: `${teacher ? 'teacher' : 'lead'}-row-${row}`,
    name: `SYNTHETIC-PRIVATE-NAME-${row}`, organization, source_job: row === 5 ? null : 'SYNTHETIC-PRIVATE-JOB-TEXT', source_teacher_level: teacher ? 'SYNTHETIC-PRIVATE-LEVEL' : null,
    identities: teacher ? ['兼职教师', ...(row === 8 ? ['分公司牵头人'] : [])] : ['分公司牵头人'],
    source: { path: teacher ? teacherPath : leadPath, sheet: teacher ? '合成教师名单' : '合成牵头候选', range: `${teacher ? 'A' : 'B'}${row}:${teacher ? 'F' : 'D'}${row}`, ...(teacher ? { source_row: row } : { row }) },
    group_match: 'NOT_FOUND_IN_GROUP_ROSTER', group_evidence: [], source_staff_id_candidate: null,
    account_id: null, username: null, email: null, business_role_codes: [], account_disposition: 'PREPARE_ACCOUNT_ONLY', identity_binding_verified: false,
  });
  const candidates = [candidate(3, branchB), candidate(4, branchA), candidate(5, branchA), candidate(6, bpOrganization), candidate(7, bpOrganization), candidate(8, branchA), candidate(9, branchB, false)];
  candidates[0].name = candidates[1].name; // Same synthetic name at different branches must never merge identities.
  for (const index of [1, 3]) {
    const item = candidates[index]; item.source_staff_id_candidate = `0008000${index}`; item.group_match = 'EXACT_NAME_AND_ORGANIZATION';
    item.group_evidence = [{ name: item.name, organization: item.organization, source_staff_id: item.source_staff_id_candidate }];
  }
  const batch = { version: candidatePolicy.batchVersion, source_files: structuredClone(candidatePolicy.sourceFiles), candidates,
    historical_exclusions: [12, 13].map(row => ({ row, name: `SYNTHETIC-PRIVATE-HISTORY-${row}`, account_disposition: 'DO_NOT_CREATE', source: { path: teacherPath, sheet: '合成教师名单', source_row: row, range: `A${row}:F${row}` } })) };
  const assignments = [
    { reference: 'teacher-row-4', role: 'BRANCH_RESPONSIBLE', sourceRow: 20, organization: branchA, sourceOrganization: branchA },
    { reference: 'teacher-row-5', role: 'BRANCH_RESPONSIBLE', sourceRow: 21, organization: branchA, sourceOrganization: branchA },
    { reference: 'teacher-row-8', role: 'BRANCH_LEAD', sourceRow: 22, organization: branchA, sourceOrganization: branchA },
    { reference: 'lead-row-9', role: 'BRANCH_LEAD', sourceRow: 23, organization: branchB, sourceOrganization: '合成旧归属机构' },
    { reference: 'teacher-row-6', role: 'BP', sourceRow: 30, organization: bpOrganization, sourceOrganization: bpOrganization },
    { reference: 'teacher-row-7', role: 'BP', sourceRow: 31, organization: bpOrganization, sourceOrganization: bpOrganization },
  ];
  const authorization = { version: 'SYNTHETIC-APPROVAL-POLICY-1',
    decision: { reference: 'SYNTHETIC-USER-DECISION-1', scope: 'BRANCH_AND_BP_ONLY', ordinaryTeacherApproval: false, bpScope: 'PENDING_EVIDENCE' },
    candidatePolicySha256: 'c'.repeat(64), roleSourceSha256: 'd'.repeat(64), sourceAuditSha256: 'e'.repeat(64),
    regionSource: { path: regionPath, sha256: 'f'.repeat(64) },
    branchSource: { path: leadPath, sheet: '合成负责人组', rows: [20, 23], leadRows: [22, 23] },
    bpSource: { path: leadPath, sheet: '合成BP组', rows: [30, 31], organization: bpOrganization }, assignments,
  };
  const roleSource = { branch_approvers: [], bp_approvers: [] };
  for (const assignment of assignments) {
    const person = candidates.find(item => item.candidate_reference === assignment.reference), bp = assignment.role === 'BP';
    const record = { name: person.name, staff_id: person.source_staff_id_candidate, organization: assignment.organization,
      source_sheet: bp ? authorization.bpSource.sheet : authorization.branchSource.sheet, source_range: `B${assignment.sourceRow}:D${assignment.sourceRow}`,
      approval_identity: bp ? 'HR-BP' : assignment.role === 'BRANCH_LEAD' ? '分公司牵头人' : '分公司负责人组成员' };
    if (bp) { record.responsible_region = null; roleSource.bp_approvers.push(record); }
    else { Object.assign(record, { region: '合成区域一', source_organization: assignment.sourceOrganization, teacher_source: structuredClone(person.source) }); roleSource.branch_approvers.push(record); }
  }
  const regionReference = { regions: [
    { display_name: '合成区域一', branches: [branchA, branchB].map((display_name, index) => ({ display_name, region_reference: { path: regionPath, sha256: 'f'.repeat(64), sheet: '合成分区表', range: `A${index + 3}:B${index + 3}`, row: index + 3 } })) },
    { display_name: '合成区域二', branches: [{ display_name: branchC, region_reference: { path: regionPath, sha256: 'f'.repeat(64), sheet: '合成分区表', range: 'A5:B5', row: 5 } }] },
  ] };
  const privateSentinels = [...new Set([...candidates.flatMap(item => [item.name, item.source_job, item.source_teacher_level, item.source_staff_id_candidate]), ...batch.historical_exclusions.map(item => item.name)].filter(Boolean))];
  return { batch, candidatePolicy, roleSource, authorization, regionReference, privateSentinels };
}

// Later independently confirmed regional scopes; the same BP additionally manages one branch.
export function createConfirmedApprovalRoleFixture() {
  const data = createApprovalRoleFixture();
  data.authorization.version = 'SYNTHETIC-APPROVAL-POLICY-CONFIRMED-2';
  data.authorization.decision.bpScope = 'USER_CONFIRMED';
  data.authorization.scopeDecision = { path: '/synthetic-only/scope-decision.json', sha256: '9'.repeat(64) };
  data.authorization.bpRegionAssignments = [
    { reference: 'teacher-row-6', region: '合成区域二', decisionIndex: 0 },
    { reference: 'teacher-row-7', region: '合成区域一', decisionIndex: 1 },
  ];
  data.authorization.branchManagementAssignments = [{ reference: 'teacher-row-6', organization: '合成缺负责人分公司', decisionIndex: 0 }];
  // The old candidate still has no ID, while the BP source and new decision agree on an ID.
  data.roleSource.bp_approvers[1].staff_id = '00080007';
  data.privateSentinels.push('00080007');
  data.scopeDecision = {
    bp_region_assignments: data.authorization.bpRegionAssignments.map((link, index) => ({ name: data.roleSource.bp_approvers[index].name, source_staff_id: data.roleSource.bp_approvers[index].staff_id, region: link.region })),
    branch_management_overrides: [{ organization: '合成缺负责人分公司', manager_name: data.roleSource.bp_approvers[0].name, source_staff_id: data.roleSource.bp_approvers[0].staff_id }],
  };
  return data;
}
