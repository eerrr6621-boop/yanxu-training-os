/* 师资匹配产品回归。仅在全新、loopback 隔离实例运行，不含真实讲师资料。 */
'use strict';

const assert = require('node:assert/strict');
const target = new URL(String(process.env.TRAINING_API_BASE || 'invalid:'));
if (!['http:', 'https:'].includes(target.protocol) ||
    !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) ||
    target.username || target.password || target.search || target.hash ||
    target.pathname.replace(/\/+$/, '') !== '/api' || !target.port || target.port === '8081') {
  throw new Error('必须设置 TRAINING_API_BASE 为非生产端口的 loopback /api 隔离实例');
}
const BASE = target.origin + '/api';
let token = '';
let passed = 0;
let failed = 0;
const check = (name, condition) => {
  if (condition) { passed++; console.log('PASS', name); }
  else { failed++; console.error('FAIL', name); }
};
async function request(path, body) {
  const res = await fetch(BASE + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { 'X-Token': token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, ...(await res.json()) };
}
async function api(path, body) {
  const result = await request(path, body);
  assert.equal(result.code, 0, `${path}: ${result.msg || result.code}`);
  return result.data;
}
async function recommend(requirement, options = {}) {
  return api('/teacher-recommendations', { requirement, max_results: 20, ...options });
}
const found = (result, id) => result.recommendations.find((item) => Number(item.teacher_id) === Number(id));

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function zip(entries) {
  const localParts = [], centralParts = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const filename = Buffer.from(name), data = Buffer.from(text);
    const crc = crc32(data), local = Buffer.alloc(30), central = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(filename.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    localParts.push(local, filename, data); centralParts.push(central, filename);
    offset += local.length + filename.length + data.length;
  }
  const directory = Buffer.concat(centralParts), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, directory, end]);
}
const xmlEscape = (value) => String(value).replace(/[<>&]/g, (x) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[x]));
function pptx(paragraphs) {
  // Each paragraph can contain several formatting runs: plain text must survive that split.
  const body = paragraphs.map((paragraph) => `<a:p>${(Array.isArray(paragraph) ? paragraph : [paragraph]).map((run) => `<a:r><a:t>${xmlEscape(run)}</a:t></a:r>`).join('')}</a:p>`).join('');
  return zip([
    ['[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>'],
    ['ppt/presentation.xml', '<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>'],
    ['ppt/slides/slide1.xml', `<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody>${body}</p:txBody></p:sp></p:spTree></p:cSld></p:sld>`],
  ]);
}
function pdf(text) {
  const escaped = text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const stream = `BT /F1 12 Tf 72 720 Td (${escaped}) Tj ET\n`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}endstream`,
  ];
  let output = '%PDF-1.4\n';
  const offsets = [];
  for (const object of objects) { offsets.push(Buffer.byteLength(output)); output += `${offsets.length} 0 obj\n${object}\nendobj\n`; }
  const xref = Buffer.byteLength(output);
  output += `xref\n0 6\n0000000000 65535 f \n${offsets.map((n) => `${String(n).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(output);
}
async function upload(id, name, bytes) {
  const result = await fetch(`${BASE}/teacher-resumes/upload?teacher_id=${id}`, {
    method: 'POST', headers: {
      'X-Token': token, 'X-Resume-Name': Buffer.from(name).toString('base64url'),
      'Content-Type': name.endsWith('.pptx') ? 'application/vnd.openxmlformats-officedocument.presentationml.presentation' : 'application/pdf',
    }, body: bytes,
  }).then((res) => res.json());
  assert.equal(result.code, 0, result.msg);
  return result.data;
}
async function teacher(name, fee = 1800, field = '', title = '') {
  return api('/teachers', { name, org: '隔离测试机构', title, field, fee_rate: fee, intro: '', gender: '', phone: '', email: '', status: '在库', in_date: '2026-09-01', out_date: '', base_province: '浙江', base_city: '杭州' });
}
async function profile(id) { return api(`/teacher-resumes/profile?teacher_id=${id}`); }
async function reparse(id) {
  await api('/teacher-resumes/reparse', { teacher_id: id });
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const result = await profile(id);
    if (!['queued', 'processing'].includes(result.parse_status)) return result;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('隔离解析超时');
}

(async () => {
  token = (await api('/login', { username: 'admin', password: 'admin123' })).token;
  assert.equal((await api('/teacher-resumes/manage')).items.length, 0, '本套回归要求全新数据库：已有简历时拒绝运行');
  const bankId = await teacher('回归甲', 1800, '领导力', '副教授');
  const expensiveId = await teacher('回归乙', 4000);
  const unknownFeeId = await teacher('回归丙', 0);
  const pdfId = await teacher('回归丁', 2000);
  const negativeId = await teacher('回归戊', 1800);
  const original = pptx([
    '个人简介：副教授，持续开展员工职业能力培养与实践教学。',
    '通过案例研讨及小组协作帮助员工改善工作方法并提升服务品质。',
    '拥有丰富课堂组织和教学方案设计经验，可根据需求调整课程案例。',
    ['连续七年服务于星海银', '行大连分行，为柜员提供服务资格认证。'],
    '主讲课程：量子纠错实训、银行客户服务、领导力。',
    '累计授课700课时、12场，满意度95%。',
    '服务资格认证项目联系人：13912345678，faculty@example.test。',
  ]);
  await upload(bankId, '原始简历.pptx', original);
  const initial = await profile(bankId);
  check('PPTX文字格式分段后仍可提取完整服务机构', JSON.stringify(initial.profile.service_cases).includes('星海银行大连分行'));
  check('无书名号的主讲课程也能被提取', initial.profile.courses.includes('量子纠错实训'));
  check('副教授不会自动升级为教授', initial.profile.credentials.includes('副教授或副高') && !initial.profile.credentials.includes('教授'));
  check('原始简历自述独立保留', initial.profile.resume_claims.claimed_training_hours === '700' && initial.profile.resume_claims.claimed_satisfaction_percent === '95');

  await upload(expensiveId, '课程乙.pptx', pptx(['擅长银行客户服务与投诉处理，面向柜员的服务资格认证课程，通过情景演练帮助员工提高沟通能力和服务质量。']));
  await upload(unknownFeeId, '课程丙.pptx', pptx(['擅长银行客户服务与投诉处理，面向柜员的服务资格认证课程，通过情景演练帮助员工提高沟通能力和服务质量。']));
  await upload(pdfId, 'office.pdf', pdf('Professional training and course design. Practical Excel worksheets and pivot tables for office employees. More than ten years of classroom experience.'));
  const pdfProfile = await profile(pdfId);
  check('有效PDF仍可完成解析', pdfProfile.parse_status === 'ready' && Number(pdfProfile.page_count) === 1);
  check('英文training不会误识别为AI', !pdfProfile.profile.tags.includes('数字化与人工智能') && pdfProfile.profile.tags.includes('办公效能与Excel'));
  await upload(negativeId, '否定要求.pptx', pptx(['专业范围说明：不擅长人工智能，不包含领导力，不需要财务管理。不包含课程：量子引力绘图。实际专注职业形象和服务礼仪，采用角色扮演提升现场展示和服务质量。']));
  const negative = (await profile(negativeId)).profile;
  check('明确否定句不会成为正向专业标签', !negative.tags.includes('数字化与人工智能') && !negative.tags.includes('领导力与管理') && !negative.tags.includes('财务与税务'));
  check('明确否定的冷门课程不会经课程提取重新成为候选', !found(await recommend('量子引力绘图'), negativeId));

  let result = await recommend('希望开展量子纠错实训');
  check('词典外的具体课程仍能依据简历事实检索', Boolean(found(result, bankId)));
  result = await recommend('给星海银行大连分行做过服务资格认证');
  const bank = found(result, bankId);
  check('具体服务案例参与匹配而非丢在摘要外', Boolean(bank));
  check('证据选中第四段的需求相关服务案例', bank && bank.evidence.some((item) => item.text.includes('星海银行大连分行')));
  check('返回证据最多三段且不泄露手机号邮箱全文', bank && bank.evidence.length <= 3 && bank.evidence.every((item) => item.text.length <= 180) && !JSON.stringify(bank).includes('13912345678') && !JSON.stringify(bank).includes('faculty@example.test') && !JSON.stringify(bank).includes('extracted_text'));
  check('系统实绩不采用700课时95%自述且无记录不生成履约分', bank && bank.system_metrics.completed_sessions === 0 && bank.system_metrics.completed_hours === 0 && bank.system_metrics.evaluation_count === 0 && !Object.hasOwn(bank.score_breakdown, 'performance'));
  check('匹配结果明确给出证据等级和本地规则说明', bank && ['supported', 'limited'].includes(bank.evidence_strength) && result.notice.includes('本地规则'));
  result = await recommend('深海热液地质同位素测年和行星岩芯取样');
  check('没有专业相关证据时返回空结果', result.recommendations.length === 0 && result.notice.includes('没有找到'));

  const manual = '副教授。专业领域：银行客户服务、投诉处理。服务案例：星海银行大连分行服务资格认证。主讲课程：量子纠错实训。人工补充自述：累计授课9999课时。';
  await api('/teacher-resumes/profile', { teacher_id: bankId, manual_profile: manual });
  const reviewed = await profile(bankId);
  check('人工校准按NFKC保存完整画像且仍保存原简历自述', reviewed.manual_profile === manual.normalize('NFKC') && reviewed.profile.resume_claims.claimed_training_hours === '700');
  result = await recommend('领导力培训');
  check('人工删除专业后旧基础档案不能重新加回', !found(result, bankId));
  const reparsed = await reparse(bankId);
  check('重新解析不会复活人工删除的专业标签', reparsed.parse_status === 'ready' && reparsed.manual_profile === reviewed.manual_profile && !reparsed.profile.tags.includes('领导力与管理'));
  check('重解析仍使用原文自述而非系统实绩', reparsed.profile.resume_claims.claimed_training_hours === '700');
  const replacement = pptx(['新版简历：擅长领导力和战略管理，累计授课900课时、20场，满意度96%。曾为多家客户开展管理能力培养及案例研讨，帮助员工实现持续改进。']);
  await upload(bankId, '更新简历.pptx', replacement);
  const replaced = await profile(bankId);
  check('替换新版简历保留人工画像与审核时间', replaced.manual_profile === reviewed.manual_profile && replaced.reviewed_at === reviewed.reviewed_at && Boolean(replaced.reviewed_at));
  check('替换后采用人工校准专业并更新原文声明', !replaced.profile.tags.includes('领导力与管理') && replaced.profile.resume_claims.claimed_training_hours === '900');
  const staleEdit = await request('/teacher-resumes/profile', { id: reviewed.id, manual_profile: '旧版本编辑', base_province: '江苏', base_city: '南京' });
  check('保存已替换简历不能返回虚假成功', [404, 409].includes(staleEdit.status));
  check('旧简历校准失败不部分保存常驻地区', (await api('/teachers')).find((row) => Number(row.id) === bankId).base_city === '杭州');

  result = await recommend('银行客户服务', { max_fee_rate: 2000, hard_budget: true });
  check('硬性每课时预算排除超价和待确认课酬', found(result, bankId) && !found(result, expensiveId) && !found(result, unknownFeeId));
  result = await recommend('银行客户服务', { max_fee_rate: 2000 });
  const unknown = found(result, unknownFeeId);
  check('软预算下未知课酬不冒充免费且不加达标分', unknown && unknown.score_breakdown.budget === 0 && unknown.gaps.some((gap) => gap.includes('课酬尚未')));
  result = await recommend('银行客户服务，项目总预算2000元');
  check('总预算不会自动当作每课时上限', result.analysis.max_fee_rate === null && Boolean(found(result, expensiveId)));
  result = await recommend('培训主题：银行客户服务\n预计课时：6.5\n期望日期：2099-02-03');
  check('引导填写的课时和日期进入需求画像', result.analysis.hours === 6.5 && result.analysis.expected_date === '2099-02-03');
  result = await recommend('银行客户服务，预算6000元，讲师有600课时经验');
  check('预算和讲师经验不被当作本次培训课时', result.analysis.hours === null);
  result = await recommend('培训主题：银行客户服务\n预计课时：-6');
  check('无效负课时不会误识别为正课时', result.analysis.hours === null);
  result = await recommend('不需要人工智能，只需要銀行客户服务，地点北京，可以远程授课');
  check('否定主题不被当作客户必需主题且复杂约束有提示', !result.analysis.topics.includes('数字化与人工智能') && result.analysis.needs_manual_review.some((x) => x.includes('所在地')));

  const demandId = await api('/demands', { title: '隔离师资回归项目', unit: '测试客户', hours: 8, content: '银行客户服务', expect_date: '2099-01-01', status: '待处理' });
  const bidId = await api('/bids', { demand_id: demandId, amount: 20000, proposal: '隔离测试', bid_date: '2026-09-01', status: '待评审' });
  const projectId = (await api('/bids/win', { id: bidId })).project_id;
  const conflictId = await api('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务', teach_date: ' 2099-1-1 ', start_time: '09:00', end_time: '12:00', hours: 4, status: '待发送' });
  check('保存排课规范日期格式', (await api('/dispatches')).find((row) => Number(row.id) === conflictId).teach_date === '2099-01-01');
  check('非法排课日期在写入时拒绝', (await request('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务', teach_date: '2099-02-30', hours: 4, status: '待发送' })).status === 400);
  result = await recommend('银行客户服务，2099-01-01开课');
  check('确切日期已有排课时排除并给出原因', !found(result, bankId) && result.excluded.some((x) => Number(x.teacher_id) === Number(bankId) && x.reason.includes('2099-01-01')));
  const before = await api('/dispatches');
  await recommend('银行客户服务');
  check('推荐始终不自动增加排课', (await api('/dispatches')).length === before.length);
  const completedId = await api('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务实践', teach_date: '2026-01-01', start_time: '09:00', end_time: '12:00', hours: 3, status: '待发送' });
  await api('/dispatches/send', { id: completedId });
  await api('/dispatches/confirm', { id: completedId, accept: 1 });
  await api('/dispatches/complete', { id: completedId });
  await api('/teacher_evals', { teacher_id: bankId, project_id: projectId, score: 4.8, comment: '隔离回归实际评价', evaluator: '测试员', eval_date: '2026-01-01' });
  const measured = found(await recommend('银行客户服务'), bankId);
  check('系统实绩只计完成的排课且不计未来待发送课程', measured.system_metrics.completed_sessions === 1 && measured.system_metrics.completed_hours === 3);
  check('真实授课评价会进入推荐指标', measured.system_metrics.evaluation_count === 1 && measured.system_metrics.evaluation_score === 4.8);
  check('简历与人工自述不会覆盖实际完成课时', measured.resume_claims.claimed_training_hours === '900' && measured.system_metrics.completed_hours !== 9999);
  result = await recommend('深海热液地质同位素测年和行星岩芯取样');
  check('已有高评价的无关讲师也不能靠履约分入选', result.recommendations.length === 0);
  check('无匹配结果明确三人缺口', result.minimum_required === 3 && result.shortfall === 3 && result.returned_count === 0);
  check('推荐人数不能低于3', (await request('/teacher-recommendations', { requirement: '客户服务', max_results: 2 })).status === 400);

  const regularPayload = { name: '区域回归师资', org: '测试机构', fee_rate: 1000, field: '量子传感测绘实操', status: '在库' };
  check('新建师资强制常驻地区', (await request('/teachers', regularPayload)).status === 400);
  check('空白地区不允许保存', (await request('/teachers', { ...regularPayload, base_province: '浙江', base_city: '　' })).status === 400);
  const nearIds = [];
  for (const [province, city] of [['江苏', '南京'], ['浙江', '宁波'], ['浙江省', '杭州市']]) {
    nearIds.push(await api('/teachers', { ...regularPayload, name: `区域测试${city}`, base_province: province, base_city: city }));
  }
  const logistics = { training_province: '浙江省', training_city: '杭州市', training_mode: '线下', training_period: '上午', max_results: 3 };
  result = await recommend('量子传感测绘实操', logistics);
  check('师资充足时至少推荐三人且不重复', result.recommendations.length === 3 && new Set(result.recommendations.map((item) => item.teacher_id)).size === 3 && result.shortfall === 0);
  check('相同专业档案同城优先且不改专业分', Number(result.recommendations[0].teacher_id) === nearIds[2] && new Set(result.recommendations.map((item) => item.score)).size === 1);
  check('异地不按同省臆断远近', Number(result.recommendations[1].teacher_id) === nearIds[0] && found(result, nearIds[1]).dispatch_fit.label.includes('异地'));
  check('省市后缀规范化', result.analysis.dispatch_preferences.training_province === '浙江' && result.analysis.dispatch_preferences.training_city === '杭州' && found(result, nearIds[2]).base_city === '杭州');
  check('异地上午课提前到达待核实', found(result, nearIds[0]).dispatch_fit.arrival_day_before && !found(result, nearIds[0]).dispatch_fit.transport_verified);
  check('推荐算法独立升版不混同简历解析版本', result.algorithm_version === 'local-rules-v3-prebid-locality');
  const arrivalDispatchId = await api('/dispatches', { project_id: projectId, teacher_id: nearIds[0], subject: '量子传感测绘实操', teach_date: '2099-1-1', hours: 3, status: '待发送' });
  result = await recommend('量子传感测绘实操，2099-01-02开课', logistics);
  check('提前到达日已有课程保留候选并标记衔接待确认', Boolean(found(result, nearIds[0])?.dispatch_fit.arrival_day_conflict));
  result = await recommend('量子传感测绘实操，2099-01-01开课', logistics);
  check('目标三人不突破授课当天冲突', !found(result, nearIds[0]) && result.shortfall === 1);
  const neutral = await recommend('量子传感测绘实操', { ...logistics, training_mode: '线上' });
  check('线上专业同分恢复稳定顺序不做地区偏好', Number(neutral.recommendations[0].teacher_id) === nearIds[0] && !neutral.analysis.dispatch_preferences.local_preference_active && !found(neutral, nearIds[0]).dispatch_fit.arrival_day_before);
  const disabled = await recommend('量子传感测绘实操', { ...logistics, prefer_local: false });
  check('可以关闭同城偏好', Number(disabled.recommendations[0].teacher_id) === nearIds[0]);
  const unrelatedLocal = await teacher('本地无关老师', 500, '深海岩芯鉴定');
  check('同城不让无关专业入选', !found(await recommend('量子传感测绘实操', logistics), unrelatedLocal));
  await api('/dispatches/delete', { id: arrivalDispatchId }); // Remove only this suite's disposable pending fixture before check-out.
  await api('/teachers/checkout', { id: nearIds[0] });
  result = await recommend('量子传感测绘实操', logistics);
  check('只有两人时保留真实缺口不补回出库师资', result.recommendations.length === 2 && result.shortfall === 1 && !found(result, nearIds[0]));
  result = await recommend('量子传感测绘实操', { ...logistics, max_fee_rate: 999, hard_budget: true });
  check('三人目标不突破硬性预算', result.recommendations.length === 0 && result.shortfall === 3);
  const localRow = (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]);
  const legacyPayload = { ...localRow }; delete legacyPayload.base_province; delete legacyPayload.base_city;
  await api('/teachers', legacyPayload);
  check('旧客户端编辑不清空已知地区', (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]).base_city === '杭州');
  check('显式清空常驻地区被拒绝', (await request('/teachers', { ...localRow, base_city: '' })).status === 400);
  await api('/teachers/residence', { id: nearIds[2], base_province: '江苏', base_city: '南京', name: '不能改名', status: '出库' });
  const updatedRow = (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]);
  check('地区专用动作不改变姓名状态等其他资料', updatedRow.name === localRow.name && updatedRow.status === localRow.status && updatedRow.base_city === '南京');
  check('超长地区返回400而不是数据库错误', (await request('/teachers/residence', { id: nearIds[2], base_province: '江苏', base_city: '城'.repeat(65) })).status === 400);
  check('无效地区不修改旧值', (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]).base_city === '南京');
  const prebid = await api('/demands', { title: '投标前区域回归', unit: '区域测试客户', hours: 4, content: '量子传感测绘实操', status: '待处理', ...logistics });
  const businessBefore = await Promise.all(['/demands', '/bids', '/projects', '/dispatches', '/fees'].map((path) => api(path)));
  result = await api('/teacher-recommendations', { demand_id: prebid, max_results: 3 });
  check('投标前需求直接带入地点时段', result.analysis.dispatch_preferences.training_city === '杭州' && result.analysis.dispatch_preferences.training_period === '上午' && result.stage === '投标前师资推荐');
  const businessAfter = await Promise.all(['/demands', '/bids', '/projects', '/dispatches', '/fees'].map((path) => api(path)));
  check('投标前推荐不改变任何需求投标项目排课课酬', JSON.stringify(businessBefore) === JSON.stringify(businessAfter));
  result = await api('/teacher-recommendations', { demand_id: prebid, training_province: '北京', training_city: '北京', max_results: 3 });
  check('当前显式地区覆盖带入需求地区', result.analysis.dispatch_preferences.training_city === '北京');
  const prebidRow = (await api('/demands')).find((row) => Number(row.id) === prebid);
  const oldDemandPayload = { ...prebidRow }; for (const key of ['training_province', 'training_city', 'training_mode', 'training_period']) delete oldDemandPayload[key];
  await api('/demands', oldDemandPayload);
  check('旧需求编辑保留新增调度字段', (await api('/demands')).find((row) => Number(row.id) === prebid).training_city === '杭州');
  check('非法需求日期在写入时明确400', (await request('/demands', { title: '非法日期兼容测试', unit: '测试', content: '客户服务', hours: 3, expect_date: '2099-02-30' })).status === 400);
  const blankRegionDemand = await api('/demands', { title: '未知地区测试', unit: '测试', hours: 3, training_province: '　', training_city: ' ' });
  check('未知授课地区保存为空而不是空格', (await api('/demands')).find((row) => Number(row.id) === blankRegionDemand).training_city === '');
  const adminToken = token;
  token = (await api('/login', { username: 'viewer', password: 'viewer123' })).token;
  check('只读用户不能维护常驻地区', (await request('/teachers/residence', { id: nearIds[2], base_province: '浙江', base_city: '杭州' })).status === 403);
  token = ''; check('未登录不能维护常驻地区', (await request('/teachers/residence', { id: nearIds[2], base_province: '浙江', base_city: '杭州' })).status === 401);
  token = adminToken;
  console.log(`Faculty product regression: ${passed} passed, ${failed} failed`);
  process.exitCode = failed ? 1 : 0;
})().catch((error) => { console.error(error); process.exitCode = 1; });
