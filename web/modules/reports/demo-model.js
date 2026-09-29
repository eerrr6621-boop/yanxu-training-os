/** Synthetic M06 facts only. No rates, names, identity mapping or network calls. */
export const RULE_VERSION = 'DEMO-M06-1';
export const OPTIONS = Object.freeze({
  dateBasis: ['TEACHING', 'CONFIRMATION', 'PAYMENT'],
  hourBasis: ['ESTIMATED', 'PLANNED', 'ACTUAL', 'PAYABLE'],
  rankMetric: ['HOURS', 'FEE'],
  tieRule: ['COMPETITION', 'DENSE', 'ORDINAL'],
  includedStatuses: ['CONFIRMED', 'CANCELLED', 'SUPERSEDED', 'ADJUSTMENT'],
});
export const DEFAULT_RULE = Object.freeze({ version: RULE_VERSION, dateBasis: 'TEACHING', hourBasis: 'ACTUAL', rankMetric: 'HOURS', tieRule: 'COMPETITION', includedStatuses: Object.freeze(['CONFIRMED', 'ADJUSTMENT']) });
export const DEFAULT_FILTER = Object.freeze({ start: '2026-09-01', end: '2026-09-30', orgCodes: Object.freeze([]) });
const fact = (id, courseId, teacherCode, orgCode, day, estimated, actual, fee, status = 'CONFIRMED', paid = true) => Object.freeze({
  id, courseId, recordCode: `DEMO-REC-${String(id).padStart(3, '0')}`, teacherCode, orgCode, courseCode: `DEMO-CRS-${courseId}`,
  dates: Object.freeze({ TEACHING: `2026-09-${day}`, CONFIRMATION: `2026-09-${String(Math.min(Number(day) + 1, 30)).padStart(2, '0')}`, PAYMENT: paid ? '2026-09-30' : null }),
  hours: Object.freeze({ ESTIMATED: estimated, PLANNED: estimated, ACTUAL: actual, PAYABLE: actual }), fee, currency: 'CNY', feeVersion: 'DEMO-FEE-1', status,
});
export const DEMO_FACTS = Object.freeze([
  fact(1, 101, 'DEMO-TCH-001', 'DEMO-ORG-EAST', '03', '2.00', '2.00', '800.00'),
  fact(2, 102, 'DEMO-TCH-002', 'DEMO-ORG-EAST', '07', '3.00', '3.00', '1200.00'),
  fact(3, 103, 'DEMO-TCH-003', 'DEMO-ORG-WEST', '09', '3.00', '3.00', '1200.00'),
  fact(4, 104, 'DEMO-TCH-001', 'DEMO-ORG-EAST', '14', '2.00', '2.50', '1000.00'),
  fact(5, 105, 'DEMO-TCH-002', 'DEMO-ORG-NORTH', '16', '2.00', '2.00', '800.00'),
  fact(6, 103, 'DEMO-TCH-003', 'DEMO-ORG-WEST', '17', '1.00', '1.00', '400.00'),
  fact(7, 104, 'DEMO-TCH-001', 'DEMO-ORG-EAST', '18', '-0.50', '-0.50', '-200.00', 'ADJUSTMENT'),
  fact(8, 106, 'DEMO-TCH-004', 'DEMO-ORG-NORTH', '21', '4.00', '4.00', '1600.00', 'CONFIRMED', false),
  fact(9, 107, 'DEMO-TCH-002', 'DEMO-ORG-EAST', '10', '2.00', '2.00', '800.00', 'CANCELLED'),
  fact(10, 104, 'DEMO-TCH-001', 'DEMO-ORG-EAST', '14', '2.50', '2.50', '1000.00', 'SUPERSEDED'),
  fact(11, 108, 'DEMO-TCH-005', 'DEMO-ORG-WEST', '22', '2.00', '2.00', '800.00'),
]);
export const DEMO_ORGS = Object.freeze([...new Set(DEMO_FACTS.map(row => row.orgCode))].sort());
const validCode = value => typeof value === 'string' && value.length <= 96 && /^[A-Za-z0-9][A-Za-z0-9_.:-]*$/.test(value);
const cmp = (a, b) => a < b ? -1 : a > b ? 1 : 0;
function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.valueOf()) && date.toISOString().slice(0, 10) === value;
}
const SCALE = 100000000n;
export function fixed(value) {
  if (typeof value !== 'string' || !/^-?(0|[1-9][0-9]*)(?:\.\d{1,8})?$/.test(value)) throw new Error('须为最多八位小数的十进制字符串');
  const negative = value.startsWith('-');
  const [whole, fraction = ''] = (negative ? value.slice(1) : value).split('.');
  if ((whole + fraction).replace(/^0+/, '').length > 24) throw new Error('数值最多24位有效数字');
  return (BigInt(whole) * SCALE + BigInt(fraction.padEnd(8, '0'))) * (negative ? -1n : 1n);
}
export function decimal(value) {
  const absolute = value < 0n ? -value : value;
  const fraction = String(absolute % SCALE).padStart(8, '0').replace(/0+$/, '').padEnd(2, '0');
  return (value < 0n ? '-' : '') + String(absolute / SCALE) + '.' + fraction;
}
function collect(rows) {
  return rows.reduce((sum, row) => ({ records: sum.records + row.records, hours: sum.hours + row.hours, fee: sum.fee + row.fee, courses: new Set([...sum.courses, ...row.courses]) }), { records: 0, hours: 0n, fee: 0n, courses: new Set() });
}
function group(rows, field) {
  const groups = new Map();
  for (const row of rows) {
    const code = row.source[field];
    const item = groups.get(code) || { code, records: 0, hours: 0n, fee: 0n, courses: new Set() };
    item.records += 1; item.hours += row.hours; item.fee += row.fee; item.courses.add(row.source.courseId);
    groups.set(code, item);
  }
  return [...groups.values()].sort((a, b) => cmp(a.code, b.code));
}
export function compute(facts, rule, filter) {
  const errors = [];
  if (!rule || !filter) throw new Error('必须显式提供统计规则和筛选条件');
  if (!validCode(rule.version)) errors.push('规则版本必须为有效编码。');
  for (const field of ['dateBasis', 'hourBasis', 'rankMetric', 'tieRule']) if (!OPTIONS[field].includes(rule[field])) errors.push(`统计规则字段 ${field} 无效。`);
  if (!Array.isArray(rule.includedStatuses) || !rule.includedStatuses.length || rule.includedStatuses.some(value => !OPTIONS.includedStatuses.includes(value)) || new Set(rule.includedStatuses).size !== rule.includedStatuses.length) errors.push('请显式选择至少一个有效且不重复的包含状态。');
  if (!validDate(filter.start) || !validDate(filter.end) || filter.start > filter.end) errors.push('请填写有效日期范围，开始日期不能晚于结束日期。');
  if (!Array.isArray(filter.orgCodes) || filter.orgCodes.some(code => !validCode(code)) || new Set(filter.orgCodes).size !== filter.orgCodes.length) errors.push('组织筛选必须为不重复的编码数组。');
  if (!Array.isArray(facts)) errors.push('事实数据必须为数组。');
  if (errors.length) return { errors };
  const ids = new Set(), codes = new Set(), courseCodes = new Map(), courseIds = new Map(), details = [];
  for (const row of facts) {
    if (!row || !Number.isSafeInteger(row.id) || row.id <= 0 || !Number.isSafeInteger(row.courseId) || row.courseId <= 0) { errors.push('事实记录 id 和 courseId 必须为正安全整数。'); continue; }
    if (ids.has(row.id) || codes.has(row.recordCode)) { errors.push(`记录 ${row.id} 重复，不能重复统计。`); continue; }
    ids.add(row.id); codes.add(row.recordCode);
    if (['recordCode', 'teacherCode', 'orgCode', 'courseCode', 'feeVersion'].some(field => !validCode(row[field]))) { errors.push(`记录 ${row.id} 含无效编码。`); continue; }
    if ((courseCodes.has(row.courseId) && courseCodes.get(row.courseId) !== row.courseCode) || (courseIds.has(row.courseCode) && courseIds.get(row.courseCode) !== row.courseId)) { errors.push(`${row.recordCode} 的课程数字 ID 与课程编码不是一一对应。`); continue; }
    courseCodes.set(row.courseId, row.courseCode); courseIds.set(row.courseCode, row.courseId);
    if (!OPTIONS.includedStatuses.includes(row.status)) { errors.push(`${row.recordCode} 的状态无效。`); continue; }
    if (!rule.includedStatuses.includes(row.status) || (filter.orgCodes.length && !filter.orgCodes.includes(row.orgCode))) continue;
    const date = row.dates?.[rule.dateBasis];
    if (!validDate(date)) { errors.push(`${row.recordCode} 缺少或含无效的 ${rule.dateBasis} 日期，无法判断是否入选。`); continue; }
    if (date < filter.start || date > filter.end) continue;
    if (row.currency !== 'CNY') { errors.push(`${row.recordCode} 的币种不是 CNY，不能混合汇总。`); continue; }
    let hours, fee;
    try { hours = fixed(row.hours?.[rule.hourBasis]); } catch { errors.push(`${row.recordCode} 缺少或含无效的 ${rule.hourBasis} 课时。`); }
    try { fee = fixed(row.fee); } catch { errors.push(`${row.recordCode} 缺少或含无效的历史课酬。`); }
    if (hours !== undefined && fee !== undefined) {
      details.push({ source: row, date, hours, fee, records: 1, courses: new Set([row.courseId]) });
    }
  }
  if (errors.length) return { errors };
  details.sort((a, b) => cmp(a.date, b.date) || cmp(a.source.recordCode, b.source.recordCode));
  const metric = rule.rankMetric === 'HOURS' ? 'hours' : 'fee';
  const rankings = group(details, 'teacherCode').sort((a, b) => cmp(b[metric], a[metric]) || cmp(a.code, b.code));
  let rank = 0, dense = 0, previous;
  rankings.forEach((item, index) => {
    if (previous !== item[metric]) { dense += 1; rank = index + 1; }
    item.rank = rule.tieRule === 'ORDINAL' ? index + 1 : rule.tieRule === 'DENSE' ? dense : rank;
    previous = item[metric];
  });
  const organizations = group(details, 'orgCode');
  const totals = collect(details);
  const hourTotals = Object.fromEntries(OPTIONS.hourBasis.map(basis => {
    try { return [basis, details.reduce((sum, row) => sum + fixed(row.source.hours?.[basis]), 0n)]; }
    catch { return [basis, null]; }
  }));
  const reconcile = [ ['DETAIL', totals], ['RANKING', collect(rankings)], ['ORG_SUMMARY', collect(organizations)] ].map(([type, value]) => ({ type, ...value, recordDifference: value.records - totals.records, hourDifference: value.hours - totals.hours, feeDifference: value.fee - totals.fee, courseDifference: value.courses.size - totals.courses.size }));
  return { errors: [], rule: { ...rule, includedStatuses: [...rule.includedStatuses] }, filter: { ...filter, orgCodes: [...filter.orgCodes] }, details, rankings, organizations, totals, hourTotals, reconcile, currency: 'CNY' };
}
const cell = value => `"${String(value ?? '').replaceAll('"', '""')}"`;
export function exportCsv(result, type) {
  if (!result || result.errors.length) throw new Error('统计结果无效，无法导出');
  const { rule, filter } = result;
  const commonHeaders = ['rowType', 'ruleVersion', 'dateBasis', 'hourBasis', 'rankMetric', 'tieRule', 'includedStatuses', 'start', 'end', 'orgCodes', 'currency'];
  const metadata = [rule.version, rule.dateBasis, rule.hourBasis, rule.rankMetric, rule.tieRule, rule.includedStatuses.join('|'), filter.start, filter.end, filter.orgCodes.length ? filter.orgCodes.join('|') : 'ALL', result.currency];
  let headers, rows;
  if (type === 'DETAIL') {
    headers = ['recordCode', 'teacherCode', 'orgCode', 'courseCode', 'basisDate', 'status', 'hours', 'historicalFee', 'feeVersion'];
    rows = result.details.map(({ source: row, date, hours, fee }) => [row.recordCode, row.teacherCode, row.orgCode, row.courseCode, date, row.status, decimal(hours), decimal(fee), row.feeVersion]);
  } else if (type === 'RANKING') {
    headers = ['rank', 'teacherCode', 'recordCount', 'uniqueCourseCount', 'hours', 'historicalFee'];
    rows = result.rankings.map(row => [row.rank, row.code, row.records, row.courses.size, decimal(row.hours), decimal(row.fee)]);
  } else if (type === 'ORG_SUMMARY') {
    headers = ['orgCode', 'recordCount', 'uniqueCourseCount', 'hours', 'historicalFee'];
    rows = result.organizations.map(row => [row.code, row.records, row.courses.size, decimal(row.hours), decimal(row.fee)]);
  } else throw new Error('导出类型无效');
  return '\uFEFF' + [[...commonHeaders, ...headers], ...rows.map(row => [type, ...metadata, ...row])].map(row => row.map(cell).join(',')).join('\r\n') + '\r\n';
}

export function exportManifest(result) {
  if (!result || result.errors.length) throw new Error('统计结果无效，无法导出清单');
  return JSON.stringify({
    schemaVersion: 'DEMO-M06-MANIFEST-1', mode: 'demo', ruleVersion: result.rule.version,
    rule: result.rule, filter: result.filter, currency: result.currency,
    totals: { recordCount: result.totals.records, uniqueCourseCount: result.totals.courses.size, hours: decimal(result.totals.hours), historicalFee: decimal(result.totals.fee) },
    hourTotals: Object.fromEntries(Object.entries(result.hourTotals).map(([basis, value]) => [basis, value === null ? null : decimal(value)])),
    exports: [ { type: 'DETAIL', rowCount: result.details.length }, { type: 'RANKING', rowCount: result.rankings.length }, { type: 'ORG_SUMMARY', rowCount: result.organizations.length } ],
    reconciliation: result.reconcile.map(row => ({ type: row.type, recordDifference: row.recordDifference, courseDifference: row.courseDifference, hourDifference: decimal(row.hourDifference), feeDifference: decimal(row.feeDifference) })),
  }, null, 2) + '\n';
}
