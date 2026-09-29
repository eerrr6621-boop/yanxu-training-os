// 合成演示专用。这里的配置不是正式制度、身份或权限来源。
export const DEMO_VERSION = 'demo-m01-v1';

const relationRules = required => ({
  managerCode: { required, allowedTargetRoles: ['ROLE-LEADER'], targetMustCoverOrganization: true, allowSelf: false },
  bpCode: { required, allowedTargetRoles: ['ROLE-BP'], targetMustCoverOrganization: true, allowSelf: false },
});
const grant = (scope, organizations = []) => ({ scope, organizations });
const scopeLabels = { OWN_ORG: '本人所属机构', RESPONSIBLE_ORGS: '明确负责机构', NAMED_ORGS: '指定机构' };

export const demoConfiguration = Object.freeze({
  officialRuleVersion: null,
  resource: 'training.record',
  roleAggregation: 'single-role',
  organizations: [
    { code: 'ORG-000', label: '合成总部', parentCode: null, active: true },
    { code: 'ORG-001', label: '合成机构甲', parentCode: 'ORG-000', active: true },
    { code: 'ORG-002', label: '合成机构乙', parentCode: 'ORG-000', active: true },
  ],
  roles: [
    { code: 'ROLE-STAFF', label: '合成填报岗位', active: true, relations: relationRules(true), grants: { view: grant('OWN_ORG') } },
    { code: 'ROLE-LEADER', label: '合成负责人岗位', active: true, relations: relationRules(false), grants: { view: grant('RESPONSIBLE_ORGS'), handle: grant('RESPONSIBLE_ORGS') } },
    { code: 'ROLE-BP', label: '合成 BP 岗位', active: true, relations: relationRules(false), grants: { view: grant('RESPONSIBLE_ORGS'), handle: grant('RESPONSIBLE_ORGS') } },
    { code: 'ROLE-TRAINING', label: '合成培训岗位', active: true, relations: relationRules(false), grants: { view: grant('NAMED_ORGS', ['ORG-001', 'ORG-002']), handle: grant('NAMED_ORGS', ['ORG-001', 'ORG-002']), export: grant('NAMED_ORGS', ['ORG-001', 'ORG-002']) } },
    // UI 专属补充，用于直观看见“权限缺失时不允许”，不在 Java 基础样例中。
    { code: 'ROLE-UNCONFIGURED', label: '待配置岗位（UI 补充）', active: true, relations: relationRules(false), grants: {} },
  ],
});

export function createDemoPeople() {
  return [
    { recordId: 101, personCode: 'PERSON-001', organizationCode: 'ORG-001', responsibleOrganizationCodes: [], managerCode: 'PERSON-002', bpCode: 'PERSON-003', roleCode: 'ROLE-STAFF', active: true },
    { recordId: 102, personCode: 'PERSON-002', organizationCode: 'ORG-001', responsibleOrganizationCodes: ['ORG-001'], managerCode: null, bpCode: null, roleCode: 'ROLE-LEADER', active: true },
    { recordId: 103, personCode: 'PERSON-003', organizationCode: 'ORG-000', responsibleOrganizationCodes: ['ORG-001', 'ORG-002'], managerCode: null, bpCode: null, roleCode: 'ROLE-BP', active: true },
    { recordId: 104, personCode: 'PERSON-004', organizationCode: 'ORG-000', responsibleOrganizationCodes: [], managerCode: null, bpCode: null, roleCode: 'ROLE-TRAINING', active: true },
    { recordId: 105, personCode: 'PERSON-005', organizationCode: 'ORG-002', responsibleOrganizationCodes: [], managerCode: null, bpCode: null, roleCode: 'ROLE-TRAINING', active: false },
    { recordId: 106, personCode: 'PERSON-006', organizationCode: 'ORG-002', responsibleOrganizationCodes: [], managerCode: null, bpCode: null, roleCode: 'ROLE-UNCONFIGURED', active: true },
  ];
}

export function resolveDemoScope(person, rule) {
  if (rule.scope === 'OWN_ORG') return [person.organizationCode];
  if (rule.scope === 'RESPONSIBLE_ORGS') return person.responsibleOrganizationCodes;
  if (rule.scope === 'NAMED_ORGS') return rule.organizations;
  return null;
}

// 仅用于界面演示：不接收 context.user，不执行认证，不能供后端复用为鉴权。
export function evaluateDemoAccess(people, configuration, input) {
  const person = people.find(item => item.personCode === input.personCode);
  if (!person) return { allowed: false, title: '不允许 · 人员未配置', reason: '合成配置中不存在该人员。人员编码不能用作登录凭据。' };
  if (!person.active) return { allowed: false, title: '不允许 · 人员已停用', reason: '合成配置要求演示主体处于启用状态。' };
  const ownOrganization = configuration.organizations.find(item => item.code === person.organizationCode);
  if (!ownOrganization?.active) return { allowed: false, title: '不允许 · 所属机构不可用', reason: '演示主体的所属机构未配置或已停用。' };
  const organization = configuration.organizations.find(item => item.code === input.organizationCode);
  if (!organization) return { allowed: false, title: '不允许 · 机构未配置', reason: '未知机构没有默认权限。' };
  if (!organization.active) return { allowed: false, title: '不允许 · 机构已停用', reason: '合成配置要求目标机构处于启用状态。' };
  const role = configuration.roles.find(item => item.code === person.roleCode);
  if (!role || !role.active) return { allowed: false, title: '不允许 · 岗位未配置或停用', reason: '有效岗位配置缺失。' };
  if (!Object.hasOwn(role.grants, input.action)) return { allowed: false, title: '未配置 · 不允许', reason: `${role.code} 尚未配置此操作；不自动推定权限。` };
  const rule = role.grants[input.action];
  const organizationCodes = resolveDemoScope(person, rule);
  if (!organizationCodes) return { allowed: false, title: '未配置 · 不允许', reason: '机构范围类型未配置。' };
  if (!organizationCodes.includes(organization.code)) return { allowed: false, title: '不允许 · 不在明确授权范围', reason: organizationCodes.length ? `该人员的${scopeLabels[rule.scope]}为 ${organizationCodes.join('、')}。` : '已配置的机构范围在此人员上为空。' };
  const actionLabel = { view: '可看', handle: '可办', export: '可导出' }[input.action];
  return { allowed: true, title: '合成配置允许', reason: `${role.label}的“${actionLabel}”按${scopeLabels[rule.scope]}明确包含 ${organization.code}。此结果仅是前端模拟。` };
}

// 保存前检查整份临时人员集，避免修改负责人角色或机构范围后破坏其他人员的关系。
export function validateDemoPerson(candidate, people, configuration) {
  const errors = [];
  const original = people.find(item => item.recordId === candidate.recordId);
  if (!original || original.personCode !== candidate.personCode) errors.push('人员编码与记录 ID 不可在本演示中修改。');
  const pending = people.map(item => item.recordId === candidate.recordId ? candidate : item);
  const organizationCodes = configuration.organizations.map(item => item.code);
  for (const person of pending) {
    const prefix = `${person.personCode}：`;
    if (!organizationCodes.includes(person.organizationCode)) errors.push(`${prefix}所属机构必须已配置。`);
    if (person.responsibleOrganizationCodes.some(code => !organizationCodes.includes(code))) errors.push(`${prefix}负责机构包含未配置机构。`);
    if (new Set(person.responsibleOrganizationCodes).size !== person.responsibleOrganizationCodes.length) errors.push(`${prefix}负责机构不能重复。`);
    const role = configuration.roles.find(item => item.code === person.roleCode && item.active);
    if (!role) { errors.push(`${prefix}角色必须选择已配置的启用岗位。`); continue; }
    for (const [key, label] of [['managerCode', '负责人'], ['bpCode', 'BP']]) {
      const rule = role.relations?.[key];
      if (!rule) { errors.push(`${prefix}${label}关系规则未配置。`); continue; }
      if (!person[key]) { if (rule.required) errors.push(`${prefix}该合成岗位必须配置${label}。`); continue; }
      if (!rule.allowSelf && person[key] === person.personCode) errors.push(`${prefix}${label}不能引用本人。`);
      const referenced = pending.find(item => item.personCode === person[key]);
      if (!referenced) { errors.push(`${prefix}${label}未配置。`); continue; }
      if (!rule.allowedTargetRoles.includes(referenced.roleCode)) errors.push(`${prefix}${label}的目标岗位应为 ${rule.allowedTargetRoles.join('、')}。`);
      const referencedOrganization = configuration.organizations.find(item => item.code === referenced.organizationCode);
      if (person.active && (!referenced.active || !referencedOrganization?.active)) errors.push(`${prefix}启用人员关联的${label}不可用，不能停用仍被引用的人员。`);
      if (rule.targetMustCoverOrganization && !referenced.responsibleOrganizationCodes.includes(person.organizationCode)) errors.push(`${prefix}${label}未明确负责所属机构 ${person.organizationCode}。`);
    }
  }
  for (const person of pending) {
    const visited = new Set();
    let current = person;
    while (current) {
      if (visited.has(current.personCode)) { errors.push('负责人关系存在循环，不能保存。'); break; }
      visited.add(current.personCode);
      current = pending.find(item => item.personCode === current.managerCode);
    }
  }
  return [...new Set(errors)];
}
