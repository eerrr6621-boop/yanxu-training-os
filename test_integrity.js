/* 关键业务不变量回归：请只在隔离数据库上运行。 */
const BASE = process.env.TRAINING_API_BASE || 'http://localhost:8080/api';
let token = '';
let pass = 0;
let fail = 0;

async function call(path, body, auth = token) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers['X-Token'] = auth;
  const response = await fetch(BASE + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return response.json();
}

function check(name, condition, detail = '') {
  if (condition) {
    pass++;
    console.log('  PASS', name, detail);
  } else {
    fail++;
    console.log('  FAIL', name, detail);
  }
}

function closeEnough(a, b) {
  return Math.abs(Number(a || 0) - Number(b || 0)) < 0.001;
}

(async () => {
  let r = await call('/login', { username: 'admin', password: 'admin123' }, '');
  check('管理员登录', r.code === 0 && r.data && r.data.token);
  token = r.data.token;

  r = await call('/projects', {
    title: '完整性测试项目', unit: '测试单位', hours: 4, amount: 1000,
    start_date: '2026-08-03', end_date: '2026-08-04', owner: '测试员',
    participant_count: 10, delivery_mode: '线下集中', venue: '测试教室',
    contract_no: 'TEST-INTEGRITY-001', status: '已归档', remark: '',
  });
  check('新项目不能伪造归档状态', r.code === 0);
  const projectId = r.data;
  r = await call('/projects');
  let project = r.data.find((x) => x.id === projectId);
  check('新项目由服务端设为进行中', project && project.status === '进行中');

  r = await call('/teachers/checkout', { id: 5 });
  check('无待执行课程的师资可以出库', r.code === 0);
  r = await call('/dispatches', {
    project_id: projectId, teacher_id: 5, subject: '出库师资伪调度',
    teach_date: '2026-08-04', hours: 1, status: '待发送', remark: '',
  });
  check('出库师资不能参与新调度', r.code === 400);
  r = await call('/teachers/checkin', { id: 5 });
  check('师资重新入库', r.code === 0);

  r = await call('/dispatches', {
    project_id: projectId, teacher_id: 4, subject: '完整性测试课程',
    teach_date: '2026-08-04', hours: 4, material_status: '已就绪',
    status: '已完成', sent_at: '伪造', confirmed_at: '伪造', msg_log: '伪造', remark: '',
  });
  check('新调度不能伪造完成状态', r.code === 0);
  const dispatchId = r.data;
  r = await call('/dispatches?project_id=' + projectId);
  let dispatch = r.data.find((x) => x.id === dispatchId);
  check('新调度从待发送开始且审计字段为空', dispatch && dispatch.status === '待发送' && !dispatch.sent_at && !dispatch.confirmed_at && !dispatch.msg_log);

  r = await call('/dispatches/send', { id: dispatchId });
  check('发送授课邀请', r.code === 0);
  r = await call('/dispatches?project_id=' + projectId);
  dispatch = r.data.find((x) => x.id === dispatchId);
  r = await call('/dispatches', { ...dispatch, hours: 5 });
  check('邀请发送后不能偷改课时', r.code === 400);
  r = await call('/dispatches/confirm', { id: dispatchId, accept: 1 });
  check('记录师资确认', r.code === 0);
  r = await call('/dispatches/complete', { id: dispatchId });
  check('实际授课后标记完成', r.code === 0);
  r = await call('/dispatches/delete', { id: dispatchId });
  check('已完成课程不能删除', r.code === 400);

  r = await call('/fees', {
    project_id: projectId, teacher_id: 4, hours: 4, rate: 2200, amount: 1,
    status: '已发放', pay_date: '2026-08-04', remark: '伪造少付',
  });
  check('课酬金额必须等于课时乘标准', r.code === 400);

  r = await call('/fees/calc', { project_id: projectId });
  check('按已确认或完成课时生成课酬', r.code === 0 && r.data.length === 1 && closeEnough(r.data[0].hours, 4));
  const feeId = r.data[0].id;
  r = await call('/fees/pay', { id: feeId });
  check('已完成授课可以发放课酬', r.code === 0);
  r = await call('/fees/calc', { project_id: projectId });
  check('重新计算不重复生成已发课酬', r.code === 0 && r.data.length === 0);
  r = await call('/fees?project_id=' + projectId);
  check('项目只保留一笔已发课酬', r.code === 0 && r.data.length === 1 && r.data[0].status === '已发放');

  r = await call('/charges', {
    project_id: projectId, amount: 1000, received: 1000, status: '已结清',
    charge_date: '', invoice: '', remark: '',
  });
  check('新应收不能伪造实收与结清状态', r.code === 0);
  const chargeId = r.data;
  r = await call('/charges?project_id=' + projectId);
  let charge = r.data.find((x) => x.id === chargeId);
  check('新应收从零实收开始', charge && closeEnough(charge.received, 0) && charge.status === '未收费');
  r = await call('/charges/receive', { id: chargeId, amount: 1001 });
  check('收款不能超过剩余应收', r.code === 400);
  r = await call('/charges/receive', { id: chargeId, amount: 1000 });
  check('准确收款后结清', r.code === 0);
  r = await call('/charges?project_id=' + projectId);
  charge = r.data.find((x) => x.id === chargeId);
  r = await call('/charges', { ...charge, amount: 1200 });
  check('已有收款后不能改应收金额', r.code === 400);
  r = await call('/charges/delete', { id: chargeId });
  check('已有收款记录不能删除', r.code === 400);

  r = await call('/questionnaires', {
    title: '归档自动关闭测试问卷', target: '参训学员', project_id: projectId,
    questions: JSON.stringify([{ type: 'score', title: '总体满意度' }]), status: '已发布',
  });
  check('新问卷不能伪造发布状态', r.code === 0);
  const questionnaireId = r.data;

  r = await call('/projects/complete', { id: projectId });
  check('交付事实完整后项目可完成', r.code === 0 && r.data.status === '已完成');
  r = await call('/dispatches?project_id=' + projectId);
  dispatch = r.data.find((x) => x.id === dispatchId);
  r = await call('/dispatches', { ...dispatch, material_status: '准备中' });
  check('项目完成后既有授课记录也不可修改', r.code === 400);
  r = await call('/dispatches', {
    project_id: projectId, teacher_id: 4, subject: '完成后伪增课程',
    teach_date: '2026-08-04', hours: 1, status: '待发送', remark: '',
  });
  check('已完成项目不能新增排课', r.code === 400);

  r = await call('/projects', {
    title: '移动调度测试项目', unit: '测试单位', hours: 1, amount: 0,
    start_date: '2026-08-04', end_date: '2026-08-04', status: '进行中', remark: '',
  });
  const otherProjectId = r.data;
  r = await call('/dispatches', {
    project_id: otherProjectId, teacher_id: 4, subject: '待移动课程',
    teach_date: '2026-08-04', hours: 1, status: '待发送', remark: '',
  });
  const movableDispatchId = r.data;
  r = await call('/dispatches?project_id=' + otherProjectId);
  const movableDispatch = r.data.find((x) => x.id === movableDispatchId);
  r = await call('/dispatches', { ...movableDispatch, project_id: projectId });
  check('不能把已有调度移动进已完成项目', r.code === 400);

  r = await call('/projects/check?id=' + projectId + '&action=archive');
  check('回款和课酬闭环后满足归档条件', r.code === 0 && r.data.ready === true);
  r = await call('/projects/archive', { id: projectId });
  check('项目归档成功', r.code === 0 && r.data.status === '已归档');
  r = await call('/questionnaires?project_id=' + projectId);
  check('归档自动关闭未闭环问卷', r.code === 0 && r.data.find((x) => x.id === questionnaireId && x.status === '已关闭'));

  r = await call('/projects');
  project = r.data.find((x) => x.id === projectId);
  r = await call('/projects', { ...project, hours: 8, status: '进行中' });
  check('归档项目业务事实只读', r.code === 400);
  r = await call('/costs', { project_id: projectId, type: '其他', amount: 10, cost_date: '2026-08-04', note: '伪增' });
  check('归档项目不能新增成本', r.code === 400);
  r = await call('/fees/delete', { id: feeId });
  check('归档项目课酬不能删除', r.code === 400);
  r = await call('/fees/calc', { project_id: projectId });
  check('归档项目不能重新计算课酬', r.code === 400);

  const [allDispatches, allFees, stats] = await Promise.all([
    call('/dispatches'), call('/fees'), call('/stats/overview'),
  ]);
  const hoursByTeacher = new Map();
  allDispatches.data.filter((x) => ['已确认', '已完成'].includes(x.status)).forEach((x) => {
    hoursByTeacher.set(String(x.teacher_id), (hoursByTeacher.get(String(x.teacher_id)) || 0) + Number(x.hours || 0));
  });
  const feeByTeacher = new Map();
  allFees.data.forEach((x) => {
    feeByTeacher.set(String(x.teacher_id), (feeByTeacher.get(String(x.teacher_id)) || 0) + Number(x.amount || 0));
  });
  const teachers = await call('/teachers');
  const statsMatch = teachers.data.every((teacher) => {
    const row = stats.data.by_teacher.find((x) => x.name === teacher.name);
    return row && closeEnough(row.hours, hoursByTeacher.get(String(teacher.id)) || 0) &&
      closeEnough(row.fee, feeByTeacher.get(String(teacher.id)) || 0);
  });
  check('师资统计等于原始排课与课酬合计', stats.code === 0 && statsMatch);

  const oversized = await fetch(BASE + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ padding: 'x'.repeat(1024 * 1024) }),
  }).then((response) => response.json());
  check('超大请求体在入库前被拒绝', oversized.code === 413);

  console.log(`\n==== 完整性测试: ${pass} 通过, ${fail} 失败 ====`);
  process.exit(fail ? 1 : 0);
})().catch((error) => {
  console.error('测试异常:', error);
  process.exit(1);
});
