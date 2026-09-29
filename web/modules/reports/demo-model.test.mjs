import test from 'node:test';
import assert from 'node:assert/strict';
import { compute, fixed, decimal, exportCsv, exportManifest, DEMO_FACTS, DEFAULT_RULE, DEFAULT_FILTER, OPTIONS } from './demo-model.js';

const run = (facts = DEMO_FACTS, rule = {}, filter = {}) => compute(facts, { ...DEFAULT_RULE, ...rule }, { ...DEFAULT_FILTER, ...filter });
const first = (patch = {}) => ({ ...DEMO_FACTS[0], ...patch });
const ok = result => { assert.deepEqual(result.errors, []); return result; };
const bad = result => assert.ok(result.errors.length > 0);

test('default synthetic totals and independent distinct courses', () => {
  const r = ok(run());
  assert.equal(r.totals.records, 9); assert.equal(r.totals.courses.size, 7);
  assert.equal(decimal(r.totals.hours), '19.00'); assert.equal(decimal(r.totals.fee), '7600.00');
  assert.deepEqual(r.rankings.map(row => row.rank), [1, 2, 2, 2, 5]);
});
test('all four hour categories are separate totals', () => {
  const r = ok(run());
  assert.deepEqual(Object.fromEntries(Object.entries(r.hourTotals).map(([basis, value]) => [basis, decimal(value)])), { ESTIMATED: '18.50', PLANNED: '18.50', ACTUAL: '19.00', PAYABLE: '19.00' });
  for (const basis of OPTIONS.hourBasis) assert.equal(ok(run(undefined, { hourBasis: basis })).totals.hours, r.hourTotals[basis]);
});
test('confirmed totals exclude signed adjustment', () => {
  const r = ok(run(undefined, { includedStatuses: ['CONFIRMED'] }));
  assert.equal(r.totals.records, 8); assert.equal(decimal(r.totals.hours), '19.50'); assert.equal(decimal(r.totals.fee), '7800.00');
});
test('adjustments are signed net amounts', () => {
  const r = ok(run(undefined, { includedStatuses: ['ADJUSTMENT'] }));
  assert.equal(r.totals.records, 1); assert.equal(decimal(r.totals.hours), '-0.50'); assert.equal(decimal(r.totals.fee), '-200.00');
});
test('no unapproved non-adjustment sign restriction', () => {
  const r = ok(run([first({ hours: { ACTUAL: '-1.50' }, fee: '-600.00' })]));
  assert.equal(decimal(r.totals.hours), '-1.50'); assert.equal(decimal(r.totals.fee), '-600.00');
  assert.equal(r.hourTotals.PLANNED, null);
});
test('explicit historical inclusion is applied exactly', () => {
  const r = ok(run(undefined, { includedStatuses: [...OPTIONS.includedStatuses] }));
  assert.equal(r.totals.records, 11); assert.equal(r.totals.courses.size, 8); assert.equal(decimal(r.totals.hours), '23.50'); assert.equal(decimal(r.totals.fee), '9400.00');
});
test('range includes both end dates', () => {
  const r = ok(run(undefined, {}, { start: '2026-09-14', end: '2026-09-14' }));
  assert.equal(r.totals.records, 1); assert.equal(decimal(r.totals.hours), '2.50');
});
test('organization filter and date basis produce scoped totals', () => {
  const r = ok(run(undefined, { dateBasis: 'PAYMENT' }, { orgCodes: ['DEMO-ORG-EAST'] }));
  assert.equal(r.totals.records, 4); assert.equal(r.totals.courses.size, 3); assert.equal(decimal(r.totals.hours), '7.00');
});
test('missing selected payment date fails instead of skipping', () => {
  const r = run(undefined, { dateBasis: 'PAYMENT' }); bad(r);
  assert.ok(r.errors.some(error => error.includes('DEMO-REC-008'))); assert.equal(r.totals, undefined);
});
test('invalid actual calendar date is rejected', () => bad(run([first({ dates: { TEACHING: '2026-02-30' } })])));
test('missing selected hour rejects full result', () => bad(run([first({ hours: { PLANNED: '2.00' } })])));
test('missing historical fee rejects full result', () => bad(run([first({ fee: null })])));
test('unknown currency does not mix totals', () => bad(run([first({ currency: 'USD' })])));
test('out of range missing hours do not falsely join selected records', () => ok(run([first({ hours: {} })], {}, { start: '2027-01-01', end: '2027-01-31' })));
test('explicit empty result retains zero totals', () => {
  const r = ok(run(undefined, {}, { start: '2027-01-01', end: '2027-01-31' }));
  assert.equal(r.totals.records, 0); assert.equal(r.totals.courses.size, 0); assert.equal(r.totals.hours, 0n);
  assert.deepEqual(r.rankings, []);
});
test('no included statuses and repeated statuses fail', () => {
  bad(run(undefined, { includedStatuses: [] })); bad(run(undefined, { includedStatuses: ['CONFIRMED', 'CONFIRMED'] }));
});
test('all rule selections are mandatory validated enums', () => {
  for (const field of ['dateBasis', 'hourBasis', 'rankMetric', 'tieRule']) bad(run(undefined, { [field]: 'UNSUPPORTED' }));
  bad(run(undefined, { version: '=SUM(1)' }));
});
test('date and organization filters must be valid', () => {
  bad(run(undefined, {}, { start: '2026-09-30', end: '2026-09-01' }));
  bad(run(undefined, {}, { start: '2026-02-30' }));
  bad(run(undefined, {}, { orgCodes: ['ORG', 'ORG'] }));
  bad(run(undefined, {}, { orgCodes: ['=CMD'] }));
});
test('duplicate ids or record codes fail', () => {
  bad(run([first(), first({ recordCode: 'OTHER' })]));
  bad(run([first(), first({ id: 999 })]));
});
test('course id and course code must map one to one', () => {
  bad(run([first(), first({ id: 999, recordCode: 'OTHER', courseCode: 'OTHER-COURSE' })]));
  bad(run([first(), first({ id: 999, recordCode: 'OTHER', courseId: 999 })]));
});
test('same course in two groups only counts once globally', () => {
  const r = ok(run([first(), first({ id: 999, recordCode: 'OTHER', teacherCode: 'OTHER-TEACHER', orgCode: 'OTHER-ORG' })]));
  assert.equal(r.totals.courses.size, 1);
  assert.deepEqual(r.organizations.map(row => row.courses.size), [1, 1]);
  assert.ok(r.reconcile.every(row => row.courseDifference === 0));
});
test('codes are bounded allowlist strings and retain leading zeros', () => {
  ok(run([first({ teacherCode: '0'.repeat(96) })]));
  bad(run([first({ teacherCode: '0'.repeat(97) })]));
  for (const code of ['=CMD()', '-CMD', '+CMD', '@CMD', '姓名', 'A,B', 'A\nB', '']) bad(run([first({ teacherCode: code })]));
  const r = ok(run([first({ teacherCode: '000001' })])); assert.equal(r.rankings[0].code, '000001');
});
test('large or unsafe numeric identity does not round silently', () => {
  bad(run([first({ id: Number.MAX_SAFE_INTEGER + 1 })])); bad(run([first({ courseId: 1.5 })]));
});
test('fixed values preserve all eight decimal places', () => {
  assert.equal(decimal(fixed('0.10000001') + fixed('0.20000002')), '0.30000003');
  assert.equal(decimal(fixed('9999999999999999.12345678')), '9999999999999999.12345678');
  assert.equal(decimal(fixed('-0.00000001')), '-0.00000001');
  assert.equal(decimal(fixed('-0.00')), '0.00');
});
test('numeric grammar rejects floats, exponent, excess precision and scale', () => {
  for (const value of [0.1, '1e3', '01.2', 'NaN', 'Infinity', '1.123456789', '99999999999999999.12345678', '.2', '2.']) assert.throws(() => fixed(value));
});
test('ranking ties follow all three specified strategies', () => {
  assert.deepEqual(ok(run(undefined, { tieRule: 'DENSE' })).rankings.map(row => row.rank), [1, 2, 2, 2, 3]);
  assert.deepEqual(ok(run(undefined, { tieRule: 'ORDINAL' })).rankings.map(row => row.rank), [1, 2, 3, 4, 5]);
  assert.deepEqual(ok(run()).rankings.slice(1, 4).map(row => row.code), ['DEMO-TCH-001', 'DEMO-TCH-003', 'DEMO-TCH-004']);
});
test('historical fee rank comes directly from stored fee', () => {
  const r = ok(run([first({ fee: '99999.12' }), { ...DEMO_FACTS[1] }], { rankMetric: 'FEE' }));
  assert.equal(r.rankings[0].code, 'DEMO-TCH-001'); assert.equal(decimal(r.totals.fee), '101199.12');
});
test('all aggregate reconciliation differences are zero', () => {
  for (const rule of [{}, { includedStatuses: [...OPTIONS.includedStatuses] }, { rankMetric: 'FEE', hourBasis: 'ESTIMATED' }]) {
    for (const row of ok(run(undefined, rule)).reconcile) {
      assert.equal(row.recordDifference, 0); assert.equal(row.courseDifference, 0); assert.equal(row.hourDifference, 0n); assert.equal(row.feeDifference, 0n);
    }
  }
});
test('CSV is encoded-only metadata plus result rows without numeric identity', () => {
  const r = ok(run([first({ fullName: '禁止导出的姓名', memo: '禁止导出的自由文本', teacherCode: '000001' })]));
  const csv = exportCsv(r, 'DETAIL');
  assert.ok(csv.startsWith('\uFEFF')); assert.ok(csv.includes('"000001"')); assert.ok(csv.includes('DEMO-M06-1'));
  assert.ok(!csv.includes('recordId') && !csv.includes('courseId') && !csv.includes('姓名') && !csv.includes('自由文本'));
  assert.equal(csv.trimEnd().split('\r\n').length, 2);
});
test('all CSV types retain metadata and reject invalid types/results', () => {
  const r = ok(run());
  for (const type of ['DETAIL', 'RANKING', 'ORG_SUMMARY']) {
    const csv = exportCsv(r, type); assert.ok(csv.includes('"ruleVersion"')); assert.ok(csv.includes('"includedStatuses"'));
  }
  assert.throws(() => exportCsv(r, 'OTHER')); assert.throws(() => exportCsv({ errors: ['error'] }, 'DETAIL'));
});
test('empty CSV has header only and manifest retains complete scope', () => {
  const r = ok(run(undefined, {}, { start: '2027-01-01', end: '2027-01-31', orgCodes: ['DEMO-ORG-EAST'] }));
  for (const type of ['DETAIL', 'RANKING', 'ORG_SUMMARY']) assert.equal(exportCsv(r, type).trimEnd().split('\r\n').length, 1);
  const manifest = JSON.parse(exportManifest(r));
  assert.equal(manifest.ruleVersion, 'DEMO-M06-1'); assert.equal(manifest.filter.start, '2027-01-01');
  assert.deepEqual(manifest.filter.orgCodes, ['DEMO-ORG-EAST']); assert.equal(manifest.totals.recordCount, 0);
  assert.ok(manifest.exports.every(item => item.rowCount === 0)); assert.equal(manifest.totals.historicalFee, '0.00');
});
test('input facts and rule remain immutable', () => {
  const before = JSON.stringify({ facts: DEMO_FACTS, rule: DEFAULT_RULE, filter: DEFAULT_FILTER });
  ok(run()); assert.equal(JSON.stringify({ facts: DEMO_FACTS, rule: DEFAULT_RULE, filter: DEFAULT_FILTER }), before);
});
