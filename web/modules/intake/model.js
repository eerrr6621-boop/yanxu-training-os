// M02 demonstration model. No persistence, requests, or authority decisions.
export const DRAFT_FIELDS = Object.freeze([
  'title', 'business_path', 'organization_code', 'internal_contact_code',
  'customer_contact_code', 'category_text', 'delivery_mode_text', 'period_text',
  'duration_minutes', 'participant_count', 'budget_amount', 'expected_start_date',
  'expected_end_date', 'objectives', 'external_approval_ref', 'demand_code',
]);

export const FIELD_LABELS = Object.freeze({
  title: '培训主题', business_path: '承接路径', organization_code: '组织编码',
  internal_contact_code: '内部对接人编码', customer_contact_code: '客户对接人编码',
  category_text: '授课分类', delivery_mode_text: '授课方式', period_text: '授课时段',
  duration_minutes: '授课时长', participant_count: '参训人数', budget_amount: '预算金额',
  expected_start_date: '计划开始日期', expected_end_date: '计划结束日期',
  objectives: '培训目标', external_approval_ref: '原办公签报引用', demand_code: '需求编码',
});

export const INITIAL_DRAFT = Object.freeze({ business_path: 'direct', period_text: '待协调' });
// Strictly aligned with DeliverySettlementHours.currentUserRule(); this is not a pay rule.
export const BASIC_HOURS_RULE = Object.freeze({
  version: 'M05-CLASS45-2DP-V1', minutesPerUnit: 45, scale: 2, rounding: 'HALF_UP',
  evidence: 'USER-20260921-60MIN-1.33CLASS', decision: 'IMPLEMENTATION-2DP-HALF-UP',
});
export const SUGGESTED_VALUES = Object.freeze({
  category_text: Object.freeze(['通用能力', '管理能力', '业务技能', '专业技术', '其他']),
  delivery_mode_text: Object.freeze(['线下', '线上', '混合']),
  period_text: Object.freeze(['上午', '下午', '晚间', '全天', '待协调']),
});

const REQUIRED = ['title', 'business_path', 'organization_code', 'internal_contact_code',
  'category_text', 'delivery_mode_text', 'period_text',
  'duration_minutes', 'participant_count', 'objectives'];
const DECIMAL = /^\d+(?:\.\d+)?$/;
const INTEGER = /^\d+$/;

export function fieldLimit(key) {
  return ['duration_minutes', 'participant_count', 'budget_amount'].includes(key) || key.endsWith('_code') ? 120 : key === 'objectives' ? 4000 : 500;
}

export function createDraft(input = {}) {
  const source = input && typeof input === 'object' && !Array.isArray(input) ? input : {};
  return Object.fromEntries(DRAFT_FIELDS.map(key => [key, Object.hasOwn(source, key) && typeof source[key] === 'string' ? source[key].trim() : '']));
}

function isDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  if (y < 1 || m < 1 || m > 12 || d < 1) return false;
  const leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0);
  return d <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1];
}

export function validateDraft(input, intent = 'draft') {
  const draft = createDraft(input);
  const errors = Object.create(null);
  if (intent !== 'draft' && intent !== 'submit') throw new Error('不支持的校验意图');
  if (!input || typeof input !== 'object' || Array.isArray(input)) errors.body = '需求内容须为字段对象';
  else for (const [key, value] of Object.entries(input)) {
    if (!DRAFT_FIELDS.includes(key)) errors[key] = '不允许由表单写入该字段';
    else if (value != null && typeof value !== 'string') errors[key] = '字段须使用字符串；数字保留原始十进制文本';
  }
  if (intent === 'submit') {
    for (const key of REQUIRED) if (!draft[key].trim()) errors[key] = `请填写${FIELD_LABELS[key]}`;
    if (draft.business_path === 'bid' && !draft.external_approval_ref.trim()) {
      errors.external_approval_ref = '投标路径需填写原办公签报引用';
    }
  }
  if (draft.business_path && !['direct', 'bid'].includes(draft.business_path)) errors.business_path = '请选择直接承接或投标路径';
  if (draft.business_path === 'direct' && draft.external_approval_ref) errors.external_approval_ref = '直接承接不填写投标签报引用';
  for (const key of DRAFT_FIELDS) {
    if (draft[key].length > fieldLimit(key)) errors[key] = `内容超过 ${fieldLimit(key)} 字符的技术输入上限`;
  }
  if (draft.duration_minutes && (!DECIMAL.test(draft.duration_minutes) || !/[1-9]/.test(draft.duration_minutes))) {
    errors.duration_minutes = '时长须为大于 0 的普通十进制数（分钟），不接受指数或正负号';
  }
  if (draft.participant_count && (!INTEGER.test(draft.participant_count) || !/[1-9]/.test(draft.participant_count))) {
    errors.participant_count = '人数须为大于 0 的整数';
  }
  if (draft.budget_amount && !DECIMAL.test(draft.budget_amount)) errors.budget_amount = '预算须为非负的普通十进制数，不接受指数或正负号';
  for (const key of ['expected_start_date', 'expected_end_date']) {
    if (draft[key] && !isDate(draft[key])) errors[key] = '请填写有效日期（YYYY-MM-DD）';
  }
  if (draft.expected_start_date && draft.expected_end_date && !errors.expected_start_date && !errors.expected_end_date && draft.expected_end_date < draft.expected_start_date) {
    errors.expected_end_date = '结束日期不能早于开始日期';
  }
  return { valid: Object.keys(errors).length === 0, errors, draft };
}

function gcd(a, b) { while (b) [a, b] = [b, a % b]; return a; }

export function lessonUnits(raw) {
  if (typeof raw === 'string') raw = raw.trim();
  if (!raw) return { valid: false, text: '填写分钟后自动换算', repeating: false };
  if (typeof raw !== 'string' || raw.length > 120 || !DECIMAL.test(raw) || !/[1-9]/.test(raw)) {
    return { valid: false, text: '请先填写有效时长', repeating: false };
  }
  const [whole, decimal = ''] = raw.split('.');
  const n = BigInt(whole + decimal), d = 45n * 10n ** BigInt(decimal.length);
  const factor = gcd(n, d), numerator = n / factor, denominator = d / factor;
  let rest = denominator;
  while (rest % 2n === 0n) rest /= 2n;
  while (rest % 5n === 0n) rest /= 5n;
  // Integer arithmetic avoids floating-point errors at .005 and carry boundaries.
  const scaled = numerator * 100n;
  const hundredths = scaled / denominator + ((scaled % denominator) * 2n >= denominator ? 1n : 0n);
  const classHours = `${hundredths / 100n}.${String(hundredths % 100n).padStart(2, '0')}`;
  return { valid: true, text: `${classHours} 基本课时（原始时长 ${raw} 分钟）`,
    repeating: rest !== 1n, numerator: String(numerator), denominator: String(denominator),
    originalMinutes: raw, classHours, ruleVersion: BASIC_HOURS_RULE.version,
    scale: BASIC_HOURS_RULE.scale, roundingPolicy: BASIC_HOURS_RULE.rounding };
}

export const STATUS_LABELS = Object.freeze({
  leader_pending: '负责人待审', bp_pending: 'BP 待审', approved: '审批已通过 · 待受理',
  bid_pending: '投标待结果', won: '已中标 · 待受理', lost: '未中标 · 留档', accepted: '团队已受理',
});

export function canAccept(record) {
  return Boolean(record?.synthetic && ((record.business_path === 'direct' && record.status === 'approved') || (record.business_path === 'bid' && record.status === 'won')));
}

export function canRegisterBidResult(record) {
  return Boolean(record?.synthetic && record.business_path === 'bid' && record.status === 'bid_pending');
}

export function acceptSynthetic(record) {
  if (!canAccept(record)) return { ok: false, error: record?.status === 'accepted' ? '已受理，请勿重复接单' : '当前状态不可受理；需完成对应审批或中标登记', record };
  return { ok: true, record: { ...record, status: 'accepted' } };
}

export function registerSyntheticBidResult(record, result, reference) {
  if (!canRegisterBidResult(record)) return { ok: false, error: '该记录当前不可登记投标结果', record };
  if (!['won', 'lost'].includes(result)) return { ok: false, error: '请选择中标或未中标', record };
  if (typeof reference !== 'string' || !reference.trim()) return { ok: false, error: '请填写结果依据引用（演示）', record };
  if (reference.length > 500) return { ok: false, error: '结果依据引用超过 500 字符的技术输入上限', record };
  return { ok: true, record: { ...record, status: result, bid_result: result, bid_result_ref: reference } };
}

export function createSyntheticSubmission(input, id, fillerLabel) {
  const result = validateDraft(input, 'submit');
  if (!result.valid) return { ok: false, errors: result.errors };
  // Direct intake has no bid result; the independent external reference is omitted.
  const { external_approval_ref, ...fields } = result.draft;
  const record = { ...fields, id, synthetic: true, filler_label: fillerLabel,
    status: fields.business_path === 'direct' ? 'leader_pending' : 'bid_pending' };
  if (fields.business_path === 'bid') record.external_approval_ref = external_approval_ref;
  return { ok: true, record };
}

export function createSyntheticQueue() {
  const base = { synthetic: true, organization_code: 'DEMO-ORG-A', duration_minutes: '90', participant_count: '24', filler_label: '合成填报人 A' };
  return [
    { ...base, id: 'SAMPLE-01', title: '合成需求 · 新员工沟通训练', business_path: 'direct', status: 'leader_pending' },
    { ...base, id: 'SAMPLE-02', title: '合成需求 · 网点服务研讨', business_path: 'direct', status: 'bp_pending' },
    { ...base, id: 'SAMPLE-03', title: '合成需求 · 团队协作练习', business_path: 'direct', status: 'approved' },
    { ...base, id: 'SAMPLE-04', title: '合成投标 · 讲师表达训练', business_path: 'bid', status: 'bid_pending', external_approval_ref: 'DEMO-OA-04' },
    { ...base, id: 'SAMPLE-05', title: '合成投标 · 客户服务专题', business_path: 'bid', status: 'won', external_approval_ref: 'DEMO-OA-05', bid_result: 'won', bid_result_ref: 'DEMO-RESULT-05' },
    { ...base, id: 'SAMPLE-06', title: '合成投标 · 经营分析专题', business_path: 'bid', status: 'lost', external_approval_ref: 'DEMO-OA-06', bid_result: 'lost', bid_result_ref: 'DEMO-RESULT-06' },
  ];
}
