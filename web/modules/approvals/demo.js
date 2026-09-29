// This store is intentionally in-memory, synthetic and disconnected from request().
export const demoPeople = Object.freeze([
  { id: 'DEMO-LEADER', label: '合成负责人' },
  { id: 'DEMO-BP', label: '合成 BP' },
  { id: 'DEMO-SUBMITTER', label: '合成发起人' },
]);

const clone = (value) => JSON.parse(JSON.stringify(value));
export function createDemoStore() {
  const participants = { submitterId: 'DEMO-SUBMITTER', leaderId: 'DEMO-LEADER', bpId: 'DEMO-BP' };
  const records = [
    ['DEMO-TASK-001', 'DEMO-BIZ-001', '新任经理 · 管理能力提升', 'LEADER_PENDING', 'LEADER'],
    ['DEMO-TASK-002', 'DEMO-BIZ-002', '客户经营 · 实战工作坊', 'BP_PENDING', 'BP'],
    ['DEMO-TASK-003', 'DEMO-BIZ-003', '青年骨干 · 专题研修', 'RETURNED', 'NONE'],
    ['DEMO-TASK-004', 'DEMO-BIZ-004', '组织协作 · 团队共创', 'READY_FOR_TEAM', 'NONE'],
  ].map(([id, businessId, title, status, stage], index) => ({
    id, businessId, title, status, stage, version: index === 0 ? 1 : index === 3 ? 3 : 2, dataRevision: 1,
    participants: { ...participants },
    assigneeId: stage === 'LEADER' ? participants.leaderId : stage === 'BP' ? participants.bpId : status === 'RETURNED' ? participants.submitterId : null,
    history: [
      { action: 'SUBMIT', actorId: participants.submitterId, from: null, to: 'LEADER_PENDING', comment: '合成材料已提交，仅用于流程演示。', at: '2026-09-21T09:00:00+08:00', version: 1 },
      ...(index ? [{ action: index === 2 ? 'RETURN' : 'APPROVE', actorId: participants.leaderId, from: 'LEADER_PENDING', to: index === 2 ? 'RETURNED' : 'BP_PENDING', comment: index === 2 ? '演示：请补充课程目标。' : '演示：负责人审核通过。', at: '2026-09-21T09:30:00+08:00', version: 2 }] : []),
      ...(index === 3 ? [{ action: 'APPROVE', actorId: participants.bpId, from: 'BP_PENDING', to: 'READY_FOR_TEAM', comment: '演示：BP 审核通过，可交培训团队。', at: '2026-09-21T10:00:00+08:00', version: 3 }] : []),
    ],
  }));
  function present(task, actorId) {
    const pending = ['LEADER_PENDING', 'BP_PENDING'].includes(task.status);
    const canReview = pending && task.assigneeId === actorId;
    const own = task.participants.submitterId === actorId;
    return { ...clone(task), actions: [
      { action: 'APPROVE', label: '同意', enabled: canReview, reason: '仅当前节点的合成审批人可办理。' },
      { action: 'RETURN', label: '退回', enabled: canReview, reason: '仅当前节点的合成审批人可退回。' },
      { action: 'RESUBMIT', label: '重新提交', enabled: ['RETURNED', 'WITHDRAWN'].includes(task.status) && own, reason: '仅被退回或撤回单据的合成发起人可重提。' },
      { action: 'WITHDRAW', label: '撤回', enabled: (pending || task.status === 'RETURNED') && own, reason: '仅审批中或已退回单据的合成发起人可撤回。' },
    ] };
  }
  return {
    list(actorId) { return records.map(task => present(task, actorId)); },
    get(id, actorId) { const task = records.find(item => item.id === id); if (!task) throw new Error('演示单据不存在。'); return present(task, actorId); },
    act(id, payload, actorId) {
      const task = records.find(item => item.id === id);
      if (!task || task.version !== payload.expectedVersion || task.stage !== payload.expectedStage) throw new Error('演示状态已变化，请刷新。');
      if (!present(task, actorId).actions.some(item => item.action === payload.action && item.enabled)) throw new Error('当前合成身份不可执行此操作。');
      if (payload.action === 'RETURN' && !payload.comment.trim()) throw new Error('请填写退回原因。');
      if (payload.action === 'RESUBMIT' && (!Number.isSafeInteger(payload.dataRevision) || payload.dataRevision <= task.dataRevision)) throw new Error('重提材料版本须大于当前版本。');
      const from = task.status;
      if (payload.action === 'APPROVE') { task.status = task.stage === 'LEADER' ? 'BP_PENDING' : 'READY_FOR_TEAM'; task.stage = task.status === 'BP_PENDING' ? 'BP' : 'NONE'; }
      if (payload.action === 'RETURN') { task.status = 'RETURNED'; task.stage = 'NONE'; }
      if (payload.action === 'WITHDRAW') { task.status = 'WITHDRAWN'; task.stage = 'NONE'; }
      if (payload.action === 'RESUBMIT') { task.status = 'LEADER_PENDING'; task.stage = 'LEADER'; task.dataRevision = payload.dataRevision; }
      task.assigneeId = task.stage === 'LEADER' ? task.participants.leaderId : task.stage === 'BP' ? task.participants.bpId : task.status === 'RETURNED' ? task.participants.submitterId : null;
      task.version += 1;
      task.history.push({ action: payload.action, actorId, from, to: task.status, comment: payload.comment, at: new Date().toISOString(), version: task.version });
      return present(task, actorId);
    },
  };
}
