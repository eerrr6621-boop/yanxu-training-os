/* 关键业务不变量回归：请只在隔离数据库上运行。 */
function requireLoopbackApiBase() {
  const raw = String(process.env.TRAINING_API_BASE || '').trim();
  if (!raw) throw new Error('必须显式设置 TRAINING_API_BASE，例如 http://127.0.0.1:18082/api');
  let url;
  try { url = new URL(raw); } catch { throw new Error('TRAINING_API_BASE 不是有效 URL'); }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('TRAINING_API_BASE 仅支持 http/https');
  if (url.username || url.password || url.search || url.hash) throw new Error('TRAINING_API_BASE 不得包含凭据、查询参数或片段');
  const host = url.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  const ipv4 = host.split('.').map(Number);
  const isLoopback = host === 'localhost' || host === '::1' || host === '0:0:0:0:0:0:0:1' ||
    (ipv4.length === 4 && ipv4.every((part) => Number.isInteger(part) && part >= 0 && part <= 255) && ipv4[0] === 127);
  if (!isLoopback) throw new Error(`拒绝非 loopback 测试目标: ${url.hostname}`);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (path !== '/api') throw new Error('TRAINING_API_BASE 路径必须为 /api');
  return url.origin + path;
}

let BASE;
try { BASE = requireLoopbackApiBase(); }
catch (error) { console.error('安全检查失败:', error.message); process.exit(2); }
let token = '';
let pass = 0;
let fail = 0;

async function callWithStatus(path, body, auth = token) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers['X-Token'] = auth;
  const response = await fetch(BASE + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

async function call(path, body, auth = token) {
  return (await callWithStatus(path, body, auth)).body;
}

async function rawCall(path, options = {}) {
  const method = options.method || (options.rawBody === undefined ? 'GET' : 'POST');
  const headers = { ...(options.headers || {}) };
  const auth = options.auth === undefined ? token : options.auth;
  if (auth) headers['X-Token'] = auth;
  if (options.cookie) headers.Cookie = options.cookie;
  if (options.contentType !== null && options.rawBody !== undefined)
    headers['Content-Type'] = options.contentType || 'application/json';
  const response = await fetch(BASE + path, { method, headers, body: options.rawBody });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  return { status: response.status, headers: response.headers, text, body };
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

  let http = await callWithStatus('/dispatches?project_id=not-a-number');
  check('非法 project_id 返回 HTTP 400', http.status === 400 && http.body.code === 400);
  http = await callWithStatus('/dispatches?teacher_id=not-a-number');
  check('非法 teacher_id 返回 HTTP 400', http.status === 400 && http.body.code === 400);
  http = await callWithStatus('/stats/q?id=not-a-number');
  check('非法 questionnaire id 返回 HTTP 400', http.status === 400 && http.body.code === 400);

  const demandPayload = (title, status = '待处理') => ({
    title, unit: '状态原子性测试单位', contact: '测试员', phone: '13900000001', hours: 4,
    content: '验证项目启动与需求状态的一致性', teacher_req: '测试师资',
    expect_date: '2026-08-10', status, remark: '',
  });
  const startableProject = (title, demandId) => ({
    demand_id: demandId, bid_id: 0, title, unit: '状态原子性测试单位', hours: 4, amount: 0,
    start_date: '2026-08-10', end_date: '2026-08-11', owner: '测试员',
    participant_count: 8, delivery_mode: '线下集中', venue: '测试教室', contract_no: '',
    status: '进行中', remark: '',
  });

  r = await call('/demands', demandPayload('新需求不能伪造完成', '已完成'));
  check('新需求不能伪造完成状态', r.code === 400);
  r = await call('/demands', demandPayload('新需求不能伪造流标', '已流标'));
  check('新需求不能伪造流标状态', r.code === 400);

  r = await call('/demands', demandPayload('合法启动需求'));
  check('创建合法待处理需求测试数据', r.code === 0);
  const startableDemandId = r.data;
  r = await call('/bids', { demand_id: startableDemandId, amount: 1000, proposal: '完整性测试方案', bid_date: '2026-08-01', status: '待评审', review: '' });
  check('合法需求可创建投标', r.code === 0);
  const startableBidId = r.data;
  r = await call('/bids/win', { id: startableBidId });
  check('中标流程自动创建待启动项目', r.code === 0 && r.data && r.data.project_id > 0);
  const startableProjectId = r.data.project_id;
  const startableProjects = await call('/projects');
  const startableProjectRow = startableProjects.data.find((x) => x.id === startableProjectId);
  r = await call('/projects', {
    ...startableProjectRow, owner: '测试员', participant_count: 8,
    delivery_mode: '线下集中', venue: '测试教室', end_date: '2026-08-11',
    contract_no: 'TEST-STARTABLE-001',
  });
  check('补齐自动立项项目的启动资料', r.code === 0);
  http = await callWithStatus('/projects/start', { id: startableProjectId });
  check('关联已立项需求的项目可合法启动', http.status === 200 && http.body.code === 0);
  const [startedProjects, startedDemands] = await Promise.all([call('/projects'), call('/demands')]);
  check('合法启动后项目进入进行中', startedProjects.data.find((x) => x.id === startableProjectId && x.status === '进行中'));
  check('合法启动后关联需求进入进行中', startedDemands.data.find((x) => x.id === startableDemandId && x.status === '进行中'));

  r = await call('/projects', {
    title: '完整性测试项目', unit: '测试单位', hours: 4, amount: 1000,
    start_date: '2026-08-03', end_date: '2026-08-04', owner: '测试员',
    participant_count: 10, delivery_mode: '线下集中', venue: '测试教室',
    contract_no: 'TEST-INTEGRITY-001', status: '已归档', remark: '',
  });
  check('新项目不能伪造归档状态', r.code === 0);
  const projectId = r.data;
  r = await call('/projects', {
    demand_id: -1, bid_id: -1, title: '负数来源不能绕过校验', unit: '测试单位', hours: 4, amount: 1000,
    start_date: '2026-08-03', end_date: '2026-08-04', owner: '测试员',
    participant_count: 10, delivery_mode: '线下集中', venue: '测试教室',
    contract_no: 'TEST-NEGATIVE-SOURCE', status: '待启动', remark: '',
  });
  check('新项目拒绝负数需求与投标来源编号', r.code === 400);
  r = await call('/projects');
  let project = r.data.find((x) => x.id === projectId);
  check('新项目由服务端设为待启动', project && project.status === '待启动');
  r = await call('/projects/start', { id: projectId });
  check('资料完整后可启动项目', r.code === 0);

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
  check('记录已通知师资', r.code === 0);
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

  r = await call('/teacher_evals', {
    teacher_id: 5, project_id: otherProjectId, score: 5,
    comment: '伪造未授课评价', evaluator: '测试员', eval_date: '2026-08-04',
  });
  check('没有已完成授课记录的师资不能被评价', r.code === 400);

  const users = await call('/users');
  const currentAdmin = users.data.find((x) => x.username === 'admin');
  const manager = users.data.find((x) => x.username === 'manager');
  r = await call('/users', { ...currentAdmin, role: 'viewer', status: 1, password: '' });
  check('管理员不能变更自己的角色', r.code === 400);

  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  check('会话撤销测试账号登录', r.code === 0 && r.data && r.data.token);
  let managerToken = r.data.token;

  http = await callWithStatus('/users', {
    ...manager, name: '短密码不应保存', role: 'viewer', status: 0, password: '123',
  });
  check('用户编辑带短密码返回 HTTP 400', http.status === 400 && http.body.code === 400);
  let refreshedUsers = await call('/users');
  let refreshedManager = refreshedUsers.data.find((x) => x.id === manager.id);
  check('短密码失败后姓名不发生部分更新', refreshedManager && refreshedManager.name === manager.name);
  check('短密码失败后角色不发生部分更新', refreshedManager && refreshedManager.role === manager.role);
  check('短密码失败后状态不发生部分更新', refreshedManager && Number(refreshedManager.status) === Number(manager.status));
  r = await call('/me', undefined, managerToken);
  check('短密码失败不误撤销既有会话', r.code === 0);

  r = await call('/users', { ...manager, name: '业务管理员-姓名变更', password: '' });
  check('管理员可单独修改其他用户姓名', r.code === 0);
  r = await call('/me', undefined, managerToken);
  check('仅修改姓名不撤销既有会话', r.code === 0);
  r = await call('/users', { ...manager, password: '' });
  check('姓名测试数据已恢复', r.code === 0);

  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  managerToken = r.data && r.data.token;
  r = await call('/users', { ...manager, role: 'viewer', status: 1, password: '' });
  check('管理员可调整其他用户角色', r.code === 0);
  r = await call('/me', undefined, managerToken);
  check('修改角色会撤销既有会话', r.code === 401);
  r = await call('/users', { ...manager, role: 'manager', status: 1, password: '' });
  check('角色测试数据已恢复', r.code === 0);

  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  managerToken = r.data && r.data.token;
  r = await call('/users', { ...manager, status: 0, password: '' });
  check('管理员可停用其他用户', r.code === 0);
  r = await call('/me', undefined, managerToken);
  check('修改状态会撤销既有会话', r.code === 401);
  r = await call('/users', { ...manager, status: 1, password: '' });
  check('状态测试数据已恢复', r.code === 0);

  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  managerToken = r.data && r.data.token;
  r = await call('/users', { ...manager, username: 'manager_integrity_test', password: '' });
  check('管理员可修改其他用户名', r.code === 0);
  r = await call('/me', undefined, managerToken);
  check('修改用户名会撤销既有会话', r.code === 401);
  r = await call('/users', { ...manager, username: 'manager', password: '' });
  check('用户名测试数据已恢复', r.code === 0);

  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  managerToken = r.data && r.data.token;
  r = await call('/users', { ...manager, password: 'manager4567' });
  check('管理员可修改其他用户密码', r.code === 0);
  r = await call('/me', undefined, managerToken);
  check('修改密码会撤销既有会话', r.code === 401);
  r = await call('/users', { ...manager, password: 'manager123' });
  check('密码测试数据已恢复', r.code === 0);
  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  check('恢复后测试账号可正常登录', r.code === 0 && r.data && r.data.token);
  const finalManagerToken = r.data && r.data.token;

  // 公开培训资料中心：访客免登录下载，只有系统管理员可以维护。
  let materialsHttp = await rawCall('/materials/public', { auth: '', method: 'GET' });
  check('未登录访客可读取公开资料目录', materialsHttp.status === 200 && materialsHttp.body.code === 0 && Array.isArray(materialsHttp.body.data));

  const materialMeta = Buffer.from(JSON.stringify({
    title: '完整性测试领导力学习包', category: '管理课程', summary: '用于验证公开资料下载闭环',
    version: '2026测试版', status: '上架', file_name: '../../领导力讲义.pdf',
  })).toString('base64url');
  const materialBytes = Buffer.from('%PDF-1.4\nYanxu public material integrity test\n%%EOF');
  materialsHttp = await rawCall('/materials/upload', {
    auth: finalManagerToken, method: 'POST', contentType: 'application/pdf',
    headers: { 'X-Material-Meta': materialMeta }, rawBody: materialBytes,
  });
  check('业务管理员不能上传公开资料', materialsHttp.status === 403 && materialsHttp.body.code === 403);

  materialsHttp = await rawCall('/materials/upload', {
    method: 'POST', contentType: 'application/pdf', headers: { 'X-Material-Meta': materialMeta }, rawBody: materialBytes,
  });
  check('系统管理员可上传公开学习包', materialsHttp.status === 200 && materialsHttp.body.code === 0 && materialsHttp.body.data.id > 0);
  const materialId = materialsHttp.body.data.id;

  const invalidMeta = Buffer.from(JSON.stringify({
    title: '伪装文件测试', category: '安全测试', status: '上架', file_name: '伪装.pdf',
  })).toString('base64url');
  materialsHttp = await rawCall('/materials/upload', {
    method: 'POST', contentType: 'application/pdf', headers: { 'X-Material-Meta': invalidMeta }, rawBody: Buffer.from('not a pdf'),
  });
  check('扩展名与内容不符的上传被拒绝', materialsHttp.status === 415 && materialsHttp.body.code === 415);

  materialsHttp = await rawCall('/materials/manage', { method: 'GET' });
  const managedMaterial = materialsHttp.body.data.items.find((item) => item.id === materialId);
  check('管理员目录记录文件校验与安全文件名', materialsHttp.status === 200 && managedMaterial && managedMaterial.sha256.length === 64 && managedMaterial.file_name === '领导力讲义.pdf');
  check('失败上传不产生资料记录', materialsHttp.body.data.items.length === 1);

  materialsHttp = await rawCall('/materials/public', { auth: '', method: 'GET' });
  check('上架资料立即出现在公开目录', materialsHttp.body.code === 0 && materialsHttp.body.data.some((item) => item.id === materialId));

  materialsHttp = await rawCall('/materials/download?id=' + materialId, { auth: '', method: 'HEAD' });
  check('公开下载支持 HEAD 且不泄露内联执行类型', materialsHttp.status === 200 && materialsHttp.headers.get('content-type') === 'application/octet-stream' && materialsHttp.headers.get('x-content-type-options') === 'nosniff');
  materialsHttp = await rawCall('/materials/download?id=' + materialId, { auth: '', method: 'GET', headers: { Range: 'bytes=0-7' } });
  check('公开下载支持断点续传', materialsHttp.status === 206 && materialsHttp.text === '%PDF-1.4' && String(materialsHttp.headers.get('content-range')).startsWith('bytes 0-7/'));

  r = await call('/materials/update', {
    id: materialId, title: '领导力学习工具包', category: '管理工具', summary: '已更新简介', version: 'V2', status: '下架',
  });
  check('管理员可编辑并下架学习包', r.code === 0);
  materialsHttp = await rawCall('/materials/public', { auth: '', method: 'GET' });
  check('下架资料不再出现在公开目录', materialsHttp.body.code === 0 && !materialsHttp.body.data.some((item) => item.id === materialId));
  materialsHttp = await rawCall('/materials/download?id=' + materialId, { auth: '', method: 'GET' });
  check('下架资料的原下载地址立即失效', materialsHttp.status === 404 && materialsHttp.body.code === 404);
  materialsHttp = await rawCall('/materials/manage', { auth: finalManagerToken, method: 'GET' });
  check('业务管理员不能读取资料维护目录', materialsHttp.status === 403 && materialsHttp.body.code === 403);
  materialsHttp = await rawCall('/materials/update', { method: 'POST', contentType: 'text/plain', rawBody: JSON.stringify({ id: materialId }) });
  check('资料维护接口拒绝非 JSON 请求', materialsHttp.status === 415 && materialsHttp.body.code === 415);
  materialsHttp = await rawCall('/materials/upload', { method: 'HEAD' });
  check('资料上传接口拒绝非 POST 方法', materialsHttp.status === 405 && materialsHttp.headers.get('allow') === 'POST');

  r = await call('/materials/delete', { id: materialId });
  check('管理员可删除学习包', r.code === 0);
  materialsHttp = await rawCall('/materials/manage', { method: 'GET' });
  check('删除后资料记录与公开链接均失效', materialsHttp.body.code === 0 && !materialsHttp.body.data.items.some((item) => item.id === materialId));

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
