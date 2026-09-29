import { previewAccountImport } from './account-import-preview.js';

// Evidence preparation only. Display names and source references are not permission grants.
const text = value => typeof value === 'string' && value.trim().length > 0;
const sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const list = value => Array.isArray(value) ? value : [];
const rows = value => Array.isArray(value) && value.length === 2 && value.every(Number.isSafeInteger) &&
  value[0] > 0 && value[1] >= value[0] && value[1] - value[0] < 10000
  ? Array.from({ length: value[1] - value[0] + 1 }, (_, i) => value[0] + i) : [];
const sameSet = (a, b) => a.length === b.length && new Set(a).size === a.length && a.every(v => b.includes(v));
const roleLabel = { BRANCH_RESPONSIBLE: '分公司负责人组成员', BRANCH_LEAD: '分公司牵头人', BP: 'HR-BP' };

/**
 * authorization is an independently reviewed manifest, never extracted instructions.
 * Its assignments link explicit candidate references to approved role-source cells.
 * The offline caller must verify all pinned digests; a browser cannot establish trust.
 * No personal names, staff IDs, raw jobs, account bindings or executable grants are emitted.
 */
export function previewApprovalRoles(batch, candidatePolicy, roleSource, authorization, regionReference, scopeDecision = null) {
  const baseline = previewAccountImport(batch, candidatePolicy, regionReference);
  const issues = [];
  const problem = code => { if (!issues.some(i => i.code === code)) issues.push({ code }); };
  if (!baseline.scopeValidated) problem('ACCOUNT_PREPARATION_INVALID');
  const branchRows = rows(authorization?.branchSource?.rows);
  const bpRows = rows(authorization?.bpSource?.rows);
  const assignments = list(authorization?.assignments);
  const branchAssignments = assignments.filter(a => ['BRANCH_RESPONSIBLE', 'BRANCH_LEAD'].includes(a?.role));
  const bpAssignments = assignments.filter(a => a?.role === 'BP');
  const decision = authorization?.decision;
  const p = authorization;
  if (!text(p?.version) || !text(decision?.reference) || decision?.scope !== 'BRANCH_AND_BP_ONLY' ||
      decision?.ordinaryTeacherApproval !== false || !['PENDING_EVIDENCE', 'USER_CONFIRMED'].includes(decision?.bpScope) ||
      ![p?.candidatePolicySha256, p?.roleSourceSha256, p?.sourceAuditSha256, p?.regionSource?.sha256].every(sha) ||
      !text(p?.regionSource?.path) || !branchRows.length || !bpRows.length ||
      !text(p?.branchSource?.sheet) || !text(p?.bpSource?.sheet) || !text(p?.bpSource?.organization) ||
      p?.branchSource?.path !== candidatePolicy?.lead?.path || p?.bpSource?.path !== candidatePolicy?.lead?.path ||
      !Array.isArray(p?.branchSource?.leadRows) || new Set(p.branchSource.leadRows).size !== p.branchSource.leadRows.length ||
      !p.branchSource.leadRows.every(r => branchRows.includes(r)) || !Array.isArray(p?.assignments) ||
      !sameSet(branchAssignments.map(a => a.sourceRow), branchRows) || !sameSet(bpAssignments.map(a => a.sourceRow), bpRows) ||
      assignments.length !== branchRows.length + bpRows.length ||
      new Set(assignments.map(a => a?.reference)).size !== assignments.length ||
      !assignments.every(a => a && /^(teacher|lead)-row-[1-9][0-9]*$/.test(a.reference) && text(a.organization) && text(a.sourceOrganization))) {
    problem('REVIEWED_AUTHORIZATION_REQUIRED');
  }
  const candidateByRef = new Map(list(batch?.candidates).map(c => [c?.candidate_reference, c]));
  const baselineByRef = new Map(baseline.rows.map(r => [r.reference, r]));
  const regionByBranch = new Map();
  for (const region of list(regionReference?.regions)) for (const branch of list(region?.branches)) {
    if (!text(region?.display_name) || !text(branch?.display_name) || regionByBranch.has(branch.display_name)) {
      problem('REGION_REFERENCE_INVALID'); continue;
    }
    const source = branch.region_reference;
    const sourceRow = typeof source?.range === 'string' ? /^A([1-9][0-9]*):B\1$/.exec(source.range)?.[1] : null;
    if (!text(source?.sheet) || !sourceRow) problem('REGION_SOURCE_CELL_INVALID');
    regionByBranch.set(branch.display_name, { region: region.display_name,
      source: sourceRow && text(source?.sheet) ? { sheet: source.sheet, range: `A${sourceRow}:B${sourceRow}` } : null });
  }
  if (!regionByBranch.size) problem('REGION_REFERENCE_INVALID');
  if (!Array.isArray(roleSource?.branch_approvers) || !Array.isArray(roleSource?.bp_approvers) ||
      roleSource.branch_approvers.length !== branchRows.length || roleSource.bp_approvers.length !== bpRows.length) {
    problem('ROLE_SOURCE_SCOPE_MISMATCH');
  }
  const prepared = new Map();
  // Do not interpret incomplete policy or source metadata as instructions.
  if (!issues.length) for (const assignment of assignments) {
    const bp = assignment.role === 'BP';
    const sourcePolicy = bp ? p.bpSource : p.branchSource;
    const range = `B${assignment.sourceRow}:D${assignment.sourceRow}`;
    const candidates = list(bp ? roleSource.bp_approvers : roleSource.branch_approvers)
      .filter(r => r?.source_sheet === sourcePolicy.sheet && r.source_range === range);
    const candidate = candidateByRef.get(assignment.reference);
    const base = baselineByRef.get(assignment.reference);
    if (candidates.length !== 1 || !candidate || !base) { problem('ROLE_CANDIDATE_REFERENCE_MISMATCH'); continue; }
    const source = candidates[0];
    const expectedRole = bp ? 'BP' : p.branchSource.leadRows.includes(assignment.sourceRow) ? 'BRANCH_LEAD' : 'BRANCH_RESPONSIBLE';
    if (assignment.role !== expectedRole || source.approval_identity !== roleLabel[expectedRole]) problem('ROLE_EVIDENCE_MISMATCH');
    if (!text(source.name) || source.name !== candidate.name || candidate.organization !== assignment.organization) problem('PERSON_OR_ORGANIZATION_MISMATCH');
    // Missing old IDs retain candidates. Available, conflicting IDs must never be borrowed.
    const candidateId = candidate.source_staff_id_candidate;
    const sourceId = source.staff_id;
    if (sourceId != null && (!text(sourceId) || sourceId.trim() !== sourceId)) problem('SOURCE_ID_TYPE_INVALID');
    if (candidateId != null && sourceId != null && candidateId !== sourceId) problem('SOURCE_ID_CONFLICT');
    if (bp) {
      if (assignment.organization !== p.bpSource.organization || assignment.sourceOrganization !== p.bpSource.organization) problem('BP_MEMBERSHIP_MISMATCH');
      if (source.organization !== undefined && source.organization !== p.bpSource.organization) problem('BP_MEMBERSHIP_MISMATCH');
      if (source.responsible_region !== null) problem('BP_SCOPE_WITHOUT_EVIDENCE');
    } else {
      const region = regionByBranch.get(assignment.organization);
      if (!region || source.organization !== assignment.organization || source.region !== region.region ||
          source.source_organization !== assignment.sourceOrganization) problem('BRANCH_SCOPE_MISMATCH');
      if (assignment.sourceOrganization !== assignment.organization && !candidatePolicy.organizationResolutions.some(r =>
        r.reference === assignment.reference && r.organization === assignment.organization)) problem('ORGANIZATION_DECISION_REQUIRED');
      if (!source.teacher_source || source.teacher_source.path !== candidate.source.path ||
          source.teacher_source.sheet !== candidate.source.sheet || source.teacher_source.range !== candidate.source.range) problem('CANDIDATE_CELL_MISMATCH');
    }
    const digest = candidatePolicy.sourceFiles.find(s => s.path === sourcePolicy.path)?.sha256;
    prepared.set(assignment.reference, [{
      kind: assignment.role,
      source: { path: sourcePolicy.path, sha256: digest, sheet: sourcePolicy.sheet, range, row: assignment.sourceRow },
      sourceOrganization: assignment.sourceOrganization,
      currentOrganization: assignment.organization,
      organizationResolution: assignment.sourceOrganization !== assignment.organization ? 'USER_CONFIRMED' : 'SOURCE_RETAINED',
      sourceIdentityCheck: candidateId != null && sourceId != null ? 'SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING' : 'SOURCE_ID_PENDING_CANDIDATE_RETAINED',
      scopeStatus: bp ? 'PENDING_BP_REGION_EVIDENCE' : 'CORRESPONDING_BRANCH_ONLY',
      proposedBranches: bp ? [] : [assignment.organization],
      proposedRegions: [],
      canApprove: false,
    }]);
  }
  // A later explicit user decision adds scopes; the old roster remains unchanged evidence.
  const confirmed = decision?.bpScope === 'USER_CONFIRMED';
  if (!confirmed && (scopeDecision !== null || list(p?.bpRegionAssignments).length || list(p?.branchManagementAssignments).length || p?.scopeDecision)) {
    problem('SCOPE_DECISION_NOT_AUTHORIZED');
  }
  if (confirmed && !issues.length) {
    const bpLinks = list(p.bpRegionAssignments), managementLinks = list(p.branchManagementAssignments);
    const sourceBps = list(scopeDecision?.bp_region_assignments), sourceManagers = list(scopeDecision?.branch_management_overrides);
    const regionNames = new Set([...regionByBranch.values()].map(r => r.region));
    if (!text(p.scopeDecision?.path) || !sha(p.scopeDecision?.sha256) || !Array.isArray(p.bpRegionAssignments) ||
        !Array.isArray(p.branchManagementAssignments) || !Array.isArray(scopeDecision?.bp_region_assignments) ||
        !Array.isArray(scopeDecision?.branch_management_overrides) ||
        !sameSet(bpLinks.map(a => a?.reference), bpAssignments.map(a => a.reference)) ||
        !sameSet(bpLinks.map(a => a?.decisionIndex), sourceBps.map((_, i) => i)) ||
        !sameSet(managementLinks.map(a => a?.decisionIndex), sourceManagers.map((_, i) => i)) ||
        new Set(managementLinks.map(a => `${a?.reference}:${a?.organization}`)).size !== managementLinks.length) {
      problem('CONFIRMED_SCOPE_POLICY_INVALID');
    } else {
      const decisionIdentityMatches = (source, candidate, nameKey) => source && candidate && text(source[nameKey]) && source[nameKey] === candidate.name &&
        text(source.source_staff_id) && (candidate.source_staff_id_candidate == null || candidate.source_staff_id_candidate === source.source_staff_id);
      for (const link of bpLinks) {
        const source = sourceBps[link.decisionIndex], candidate = candidateByRef.get(link.reference);
        const role = prepared.get(link.reference)?.find(r => r.kind === 'BP');
        // Also compare against the original BP identity, even when the candidate lacks its old ID.
        const assignment = bpAssignments.find(a => a.reference === link.reference);
        const original = roleSource.bp_approvers.find(r => r.source_range === `B${assignment.sourceRow}:D${assignment.sourceRow}` && r.source_sheet === p.bpSource.sheet);
        if (!role || !decisionIdentityMatches(source, candidate, 'name') || !regionNames.has(link.region) || source.region !== link.region ||
            (original.staff_id != null && source.source_staff_id !== original.staff_id)) { problem('CONFIRMED_BP_SCOPE_MISMATCH'); continue; }
        role.scopeStatus = 'USER_CONFIRMED_REGION';
        role.proposedRegions = [link.region];
        role.proposedBranches = [...regionByBranch].filter(([, r]) => r.region === link.region).map(([b]) => b);
        role.scopeDecisionSource = { path: p.scopeDecision.path, sha256: p.scopeDecision.sha256, reference: `bp_region_assignments[${link.decisionIndex}]` };
      }
      for (const link of managementLinks) {
        const source = sourceManagers[link.decisionIndex], candidate = candidateByRef.get(link.reference);
        const roles = prepared.get(link.reference), bpRole = roles?.find(r => r.kind === 'BP');
        if (!bpRole || !decisionIdentityMatches(source, candidate, 'manager_name') || source.organization !== link.organization ||
            !regionByBranch.has(link.organization) || !bpRole.proposedBranches.includes(link.organization) ||
            source.source_staff_id !== sourceBps[bpLinks.find(a => a.reference === link.reference)?.decisionIndex]?.source_staff_id) {
          problem('CONFIRMED_BRANCH_MANAGER_MISMATCH'); continue;
        }
        roles.push({ kind: 'BRANCH_RESPONSIBLE', source: { path: p.scopeDecision.path, sha256: p.scopeDecision.sha256,
          reference: `branch_management_overrides[${link.decisionIndex}]` },
          sourceOrganization: candidate.organization, currentOrganization: candidate.organization,
          organizationResolution: 'USER_CONFIRMED_MANAGEMENT_SCOPE_HOME_ORGANIZATION_UNCHANGED',
          sourceIdentityCheck: candidate.source_staff_id_candidate != null ? 'SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING' : 'SOURCE_ID_PENDING_CANDIDATE_RETAINED',
          scopeStatus: 'CORRESPONDING_BRANCH_ONLY', proposedBranches: [link.organization], proposedRegions: [], canApprove: false });
      }
    }
  }
  const valid = issues.length === 0;
  const outputRows = baseline.rows.map(base => {
    const roles = valid ? prepared.get(base.reference) || [] : [];
    const bpRole = roles.find(r => r.kind === 'BP');
    return {
      reference: base.reference, candidateSource: base.source, identities: [...base.identities],
      organization: base.organization, region: base.region, accountDisposition: 'PREPARE_ACCOUNT_ONLY',
      approvalEligibility: !valid ? 'REVIEW_BLOCKED' : !roles.length ? 'NO_APPROVAL_ROLE' : bpRole ? confirmed ? 'BP_SCOPE_PREPARED' : 'BP_SCOPE_PENDING' : 'BRANCH_SCOPE_PREPARED',
      approvalRoles: roles, canApprove: false,
      combinedDutiesRequiresWorkflowReview: !!bpRole && roles.some(r => r.kind !== 'BP'),
      formalPersonCode: null, formalOrganizationCode: null, accountBinding: null,
    };
  });
  const coverage = valid ? [...regionByBranch].map(([organization, details]) => {
    const refs = outputRows.filter(r => r.approvalRoles.some(a => a.kind !== 'BP' && a.proposedBranches.includes(organization))).map(r => r.reference);
    return { organization, region: details.region, source: details.source, candidateReferences: refs,
      status: refs.length === 0 ? 'CURRENT_ASSIGNEE_MISSING' : refs.length > 1 ? 'MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING' : 'ROLE_EVIDENCE_PREPARED',
      defaultHandlerReference: null };
  }) : [];
  return {
    previewVersion: 'M01-APPROVAL-ROLE-PREVIEW-v1', policyVersion: valid ? p.version : null,
    scopeValidated: valid, accountScopeValidated: baseline.scopeValidated,
    canCreateAccounts: false, canPublishConfiguration: false, writesPerformed: false,
    summary: { candidates: outputRows.length, sourceBranchRolePeople: valid ? branchAssignments.length : 0,
      branchRolePeople: outputRows.filter(r => r.approvalRoles.some(a => a.kind !== 'BP')).length,
      branchLeads: valid ? assignments.filter(a => a.role === 'BRANCH_LEAD').length : 0,
      bpRolePeople: outputRows.filter(r => r.approvalRoles.some(a => a.kind === 'BP')).length,
      bpScopesPrepared: outputRows.filter(r => r.approvalEligibility === 'BP_SCOPE_PREPARED').length,
      uniqueApprovalPeople: outputRows.filter(r => r.approvalRoles.length).length,
      preparedRoleAssignments: outputRows.reduce((total, r) => total + r.approvalRoles.length, 0),
      peopleWithCombinedDuties: outputRows.filter(r => r.combinedDutiesRequiresWorkflowReview).length,
      ordinaryCandidatesWithoutApproval: outputRows.filter(r => r.approvalEligibility === 'NO_APPROVAL_ROLE').length,
      branchesTotal: regionByBranch.size, branchesCovered: coverage.filter(c => c.candidateReferences.length).length,
      branchesMissingAssignee: coverage.filter(c => !c.candidateReferences.length).length,
      branchesWithMultipleIdentities: coverage.filter(c => c.candidateReferences.length > 1).length,
      historicalExcluded: baseline.historicalExclusions.length, actualApprovalPermissionsAssigned: 0 },
    rows: outputRows, branchCoverage: coverage, historicalExclusions: baseline.historicalExclusions,
    decision: valid ? { reference: decision.reference, scope: 'BRANCH_AND_BP_ONLY', ordinaryTeacherApproval: false, bpScope: decision.bpScope } : null,
    issues, accountIssues: baseline.issues.map(i => ({ reference: i.reference, code: i.code })),
    requiredBeforePublication: [...(!confirmed ? ['CONFIRM_BP_SCOPE_RELATIONS'] : []),
      ...(coverage.some(c => !c.candidateReferences.length) ? ['RESOLVE_MISSING_BRANCH_ASSIGNEES'] : []),
      'CONFIRM_MULTI_PERSON_ROUTING', ...(outputRows.some(r => r.combinedDutiesRequiresWorkflowReview) ? ['HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW'] : []),
      'ASSIGN_FORMAL_PERSON_AND_ORGANIZATION_CODES', 'RECONCILE_EXISTING_ACCOUNTS', 'EXPLICIT_ACCOUNT_BINDINGS',
      'SERVER_AUTHORIZATION_VALIDATION_AND_VERSION_CHECK'],
  };
}
