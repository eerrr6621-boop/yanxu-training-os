/* 端到端 API 测试：只允许显式指定本机 loopback 隔离实例。 */
function requireLoopbackApiBase() {
  const raw = String(process.env.TRAINING_API_BASE || '').trim();
  if (!raw) throw new Error('必须显式设置 TRAINING_API_BASE，例如 http://127.0.0.1:18081/api');
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
let pass = 0, fail = 0;

function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS', name, extra || ''); }
  else { fail++; console.log('  FAIL', name, extra || ''); }
}

async function call(path, body, tk) {
  const headers = { 'Content-Type': 'application/json' };
  if (tk !== undefined ? tk : token) headers['X-Token'] = tk || token;
  const res = await fetch(BASE + path, { method: body ? 'POST' : 'GET', headers, body: body ? JSON.stringify(body) : undefined });
  return res.json();
}

(async () => {
  // 1. 登录
  let r = await call('/login', { username: 'admin', password: 'admin123' }, '');
  ok('管理员登录', r.code === 0 && r.data.token);
  token = r.data.token;
  const fixture = await require('./scripts/IntegrationHttpFixtures.cjs').createIntegrationHttpFixtures(call, ok);

  // 2. 各模块列表
  for (const m of ['demands', 'bids', 'projects', 'teachers', 'teacher_evals', 'dispatches', 'questionnaires', 'charges', 'fees', 'costs', 'users']) {
    r = await call('/' + m);
    ok('列表 ' + m, r.code === 0 && Array.isArray(r.data), `(${r.data.length}条)`);
  }

  // 3. 需求草稿 → 原签报结果 → 团队受理生成项目
  let workflow = await fixture.draft({ title: '测试-数字化转型专题培训', unit: '测试单位A', contact: '测试员', phone: '13900000000', hours: 16, content: '数字化转型理论与实践', teacher_req: '有数字化咨询经验', expect_date: '2026-11-01' });
  ok('新增培训需求草稿', workflow.id > 0 && workflow.draft);
  const demandId = workflow.id;
  workflow = await fixture.submit(workflow);
  ok('投标需求提交后等待原签报结果', !workflow.draft && workflow.queue === 'waiting_bid_result');
  r = await call('/bids', { demand_id: demandId, amount: 45000, proposal: '测试投标方案', bid_date: '2026-07-30', status: '待评审' });
  ok('新流程投标禁止旧入口绕过签报登记', r.code === 409);
  r = await call('/bids/win', { id: 1, demand_id: demandId });
  ok('旧中标动作不能改绑新流程需求以绕过受理', r.code === 409);
  workflow = await fixture.won(workflow);
  ok('登记中标后仍须团队受理', workflow.queue === 'ready' && !workflow.project_id);
  workflow = await fixture.accept(workflow);
  const projId = workflow.project_id;
  ok('团队受理后生成待启动项目', projId > 0);
  await fixture.configureProject(projId, { amount: 45000 });
  r = await call('/demands?id=' + demandId);
  ok('需求状态已更新为已立项', r.code === 0 && r.data.find(d => d.id === demandId && d.status === '已立项'));

  // 4. 师资调度：创建 → 发送 → 确认 → 完成
  r = await call('/dispatches', { project_id: projId, teacher_id: 4, subject: '数字化战略', teach_date: '2026-08-04', hours: 8, status: '待发送', remark: '' });
  ok('新建师资调度', r.code === 0 && r.data > 0);
  const dpId = r.data;
  r = await call('/dispatches/send', { id: dpId });
  ok('记录已通知师资', r.code === 0);
  r = await call('/dispatches/confirm', { id: dpId, accept: 1 });
  ok('师资确认', r.code === 0);
  r = await call('/dispatches?id=&project_id=' + projId);
  const dp = r.data.find(d => d.id === dpId);
  ok('调度状态=已确认且含消息日志', dp && dp.status === '已确认' && dp.msg_log.includes('已确认'));
  const verifiedDelivery = await fixture.verifyDispatch(dpId, '360');
  r = await call('/dispatches/complete', { id: dpId, expected_version: verifiedDelivery.version });
  ok('实际授课后完成交付记录', r.code === 0);
  r = await call('/dispatches?project_id=' + projId);
  ok('调度状态=已完成', r.code === 0 && r.data.find(d => d.id === dpId && d.status === '已完成'));

  // 5. 新流程阻断旧费率；未迁移历史项目保留原财务兼容覆盖。
  r = await call('/fees/calc', { project_id: projId });
  ok('新流程正式规则未启用时不能套用旧师资费率', r.code === 409);
  r = await call('/fees?project_id=' + projId);
  ok('受控项目不生成旧课酬记录', r.code === 0 && r.data.length === 0);
  await fixture.completeLegacyQuestionnaireProject();

  // 6. 收费
  r = await call('/charges', { project_id: projId, amount: 45000, received: 0, charge_date: '', status: '未收费', invoice: '', remark: '' });
  ok('新增收费记录', r.code === 0 && r.data > 0);
  const chId = r.data;
  r = await call('/charges/receive', { id: chId, amount: 20000 });
  ok('部分收款', r.code === 0 && String(r.data).includes('部分收费'));
  r = await call('/charges/receive', { id: chId, amount: 25000 });
  ok('结清收款', r.code === 0 && String(r.data).includes('已结清'));

  // 7. 成本
  r = await call('/costs', { project_id: projId, type: '差旅费', amount: 1500, cost_date: '2026-07-30', note: '测试差旅' });
  ok('新增授课成本', r.code === 0 && r.data > 0);

  // 8. 问卷全流程：创建→发布→发送→公开作答→统计
  const questions = JSON.stringify([
    { type: 'score', title: '总体满意度' },
    { type: 'score', title: '师资授课水平' },
    { type: 'single', title: '是否愿意再次参加', options: ['愿意', '不愿意'] },
    { type: 'text', title: '意见与建议' },
  ]);
  r = await call('/questionnaires', { title: '新流程禁止问卷', target: '参训学员', project_id: projId, questions, status: '草稿' });
  ok('新流程项目不能创建发送问卷', r.code === 400 && /原问卷平台/.test(r.msg));
  // 旧问卷全流程只在原合成seed项目验证兼容。
  r = await call('/questionnaires', { title: '测试-培训效果评估问卷', target: '参训学员', project_id: 2, questions, status: '草稿' });
  ok('创建问卷', r.code === 0 && r.data > 0);
  const qid = r.data;
  r = await call('/q/publish', { id: qid });
  ok('发布问卷', r.code === 0);
  r = await call('/q/send', { id: qid, target_desc: '测试单位A学员群', send_count: 25 });
  ok('模拟微信发送问卷', r.code === 0 && r.data.link);
  const qtoken = r.data.link.split('token=')[1];
  r = await call('/q/pub?token=' + qtoken, null, '');
  ok('公开页获取问卷', r.code === 0 && r.data.questions.length === 4);
  r = await call('/q/answer', { token: qtoken, respondent: '异常答卷', answers: [{ value: 999 }, { value: 4 }, { value: '愿意' }, { value: '' }] }, '');
  ok('伪造评分被拒绝', r.code === 400);
  r = await call('/q/answer', { token: qtoken, respondent: '异常答卷', answers: [{ value: 5 }, { value: 4 }, { value: '伪造选项' }, { value: '' }] }, '');
  ok('伪造单选答案被拒绝', r.code === 400);
  r = await call('/q/answer', { token: qtoken, respondent: '学员甲', answers: [{ value: 5 }, { value: 4 }, { value: '愿意' }, { value: '课程很实用' }] }, '');
  ok('学员提交问卷', r.code === 0);
  r = await call('/q/answer', { token: qtoken, respondent: '学员乙', answers: [{ value: 4 }, { value: 5 }, { value: '愿意' }, { value: '' }] }, '');
  ok('学员2提交问卷', r.code === 0);
  r = await call('/stats/q?id=' + qid);
  ok('问卷统计分析', r.code === 0 && r.data.total === 2 && r.data.questions[0].avg === 4.5 && r.data.questions[2].options['愿意'] === 2, '均分=' + (r.data && r.data.avg_score));

  // 9. 师资评价 + 出入库
  r = await call('/teacher_evals', { teacher_id: 4, project_id: projId, score: 4.6, comment: '备课充分', evaluator: '测试员', eval_date: '2026-07-30' });
  ok('新增师资评价', r.code === 0);
  r = await call('/teachers/checkout', { id: 5 });
  ok('师资出库', r.code === 0);
  r = await call('/teachers/checkin', { id: 5 });
  ok('师资重新入库', r.code === 0);

  // 10. 统计看板 + 分析报告
  r = await call('/stats/overview');
  ok('统计看板', r.code === 0 && r.data.projects >= 4 && r.data.monthly.length > 0, '项目数=' + (r.data && r.data.projects));
  r = await call('/stats/report');
  ok('分析报告生成', r.code === 0 && String(r.data).includes('培训项目统计分析报告'));

  // 11. 权限：viewer 只读，manager 可写但不可管用户
  r = await call('/login', { username: 'viewer', password: 'viewer123' }, '');
  const vtoken = r.data.token;
  r = await call('/demands', { title: 'X' }, vtoken);
  ok('只读用户禁止写入', r.code === 403);
  r = await call('/demands', null, vtoken);
  ok('只读用户可查询', r.code === 0);
  r = await call('/login', { username: 'manager', password: 'manager123' }, '');
  const mtoken = r.data.token;
  r = await call('/users', null, mtoken);
  ok('业务管理员禁止用户管理', r.code === 403);
  r = await call('/costs', { project_id: projId, type: '物料费', amount: 100, cost_date: '2026-07-30', note: 'm测试' }, mtoken);
  ok('业务管理员可写业务模块', r.code === 0);

  // 12. 修改密码 + 重置
  r = await call('/password', { old: 'viewer123', new: 'viewer4567' }, vtoken);
  ok('修改本人密码', r.code === 0);
  r = await call('/login', { username: 'viewer', password: 'viewer4567' }, '');
  ok('新密码登录', r.code === 0);
  const changedViewerToken = r.data.token;
  r = await call('/users/resetpwd', { id: 3, password: 'viewer123' });
  ok('管理员重置密码', r.code === 0);
  r = await call('/me', undefined, changedViewerToken);
  ok('管理员重置密码后旧会话立即失效', r.code === 401);
  r = await call('/login', { username: 'viewer', password: 'viewer123' }, '');
  const restoredViewerToken = r.data.token;
  r = await call('/password', { old: 'wrong', new: 'xxxxxxxx' }, restoredViewerToken);
  ok('原密码错误被拒绝', r.code === 400);

  // 13. 未登录拦截
  r = await call('/demands', null, '');
  ok('未登录拦截401', r.code === 401);

  console.log(`\n==== 测试完成: ${pass} 通过, ${fail} 失败 ====`);
  process.exit(fail > 0 ? 1 : 0);
})().catch(e => { console.error('测试异常:', e.message); process.exit(1); });
