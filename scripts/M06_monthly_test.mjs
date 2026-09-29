import assert from 'node:assert/strict';
import test from 'node:test';
import * as model from '../web/modules/reports/demo-model.js';
import { monthlySheets, exportMonthlyWorkbook, reportPeriod } from '../web/modules/reports/monthly-workbook.js';
const report = () => model.compute(model.DEMO_FACTS, model.DEFAULT_RULE, model.DEFAULT_FILTER);
function unpack(bytes) {
  const out = new Map(), view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); let offset = 0;
  while (view.getUint32(offset, true) === 0x04034b50) {
    const size = view.getUint32(offset + 18, true), nameLength = view.getUint16(offset + 26, true), extraLength = view.getUint16(offset + 28, true);
    const name = new TextDecoder().decode(bytes.slice(offset + 30, offset + 30 + nameLength));
    const start = offset + 30 + nameLength + extraLength;
    out.set(name, new TextDecoder().decode(bytes.slice(start, start + size))); offset = start + size;
  }
  assert.equal(view.getUint32(offset, true), 0x02014b50);
  assert.equal(view.getUint32(bytes.length - 22, true), 0x06054b50);
  return out;
}
test('monthly label includes leap February and excludes partial range', () => {
  assert.equal(reportPeriod(model.DEFAULT_FILTER), '2026年09月');
  assert.equal(reportPeriod({ start: '2024-02-01', end: '2024-02-29' }), '2024年02月');
  assert.equal(reportPeriod({ start: '2100-02-01', end: '2100-02-28' }), '2100年02月');
  assert.equal(reportPeriod({ start: '2026-09-02', end: '2026-09-30' }), '2026-09-02 至 2026-09-30');
});
test('four sheets reconcile to the selected snapshot with readable labels', () => {
  const r = report(), sheets = monthlySheets(r, model);
  assert.deepEqual(sheets.map(s => s.name), ['月度概览', '讲师排名', '机构汇总', '明细对账']);
  assert.equal(sheets[1].rows.length - 5, r.rankings.length);
  assert.equal(sheets[2].rows.length - 5, r.organizations.length);
  assert.equal(sheets[3].rows.length - 5, r.details.length);
  assert.equal(sheets[0].rows.find(row => row[0]?.value === '历史课酬合计')[1].value, '7600.00');
  for (const row of sheets[0].rows.filter(row => /课时$/.test(row[0]?.value))) assert.equal(row[2].value, '课时');
});
test('ordinary amounts are numbers while encoded identities stay text', async () => {
  const zip = unpack(await exportMonthlyWorkbook(report(), model)), detail = zip.get('xl/worksheets/sheet4.xml');
  assert.match(detail, /r="H6" s="4" t="n"><v>800.00<\/v>/);
  assert.match(detail, /t="inlineStr"><is><t xml:space="preserve">DEMO-REC-001/);
  assert(!detail.includes('recordId')); assert(!detail.includes('courseId'));
});
test('long precise amount stays text and input remains unchanged', async () => {
  const facts = structuredClone(model.DEMO_FACTS); facts[0].fee = '1234567890123456.12345678';
  const before = JSON.stringify(facts), r = model.compute(facts, model.DEFAULT_RULE, model.DEFAULT_FILTER);
  const zip = unpack(await exportMonthlyWorkbook(r, model));
  assert.match(zip.get('xl/worksheets/sheet4.xml'), /r="H6" s="6" t="inlineStr"><is><t xml:space="preserve">1234567890123456.12345678/);
  assert.equal(JSON.stringify(facts), before);
});
test('empty month keeps overview and all table headers', async () => {
  const r = model.compute(model.DEMO_FACTS, model.DEFAULT_RULE, { ...model.DEFAULT_FILTER, start: '2026-10-01', end: '2026-10-31' });
  const zip = unpack(await exportMonthlyWorkbook(r, model));
  assert.match(zip.get('xl/worksheets/sheet1.xml'), /2026年10月/);
  assert.match(zip.get('xl/worksheets/sheet4.xml'), /autoFilter ref="A5:I5"/);
  assert.equal(monthlySheets(r, model)[3].rows.length, 5);
});
test('invalid or non-demo result cannot produce a workbook', async () => {
  await assert.rejects(exportMonthlyWorkbook({ errors: ['missing input'] }, model));
  const r = report(); r.rule.version = 'M06-20260921-1';
  await assert.rejects(exportMonthlyWorkbook(r, model));
});
test('workbook is deterministic, no formulas/macros/external links', async () => {
  const a = await exportMonthlyWorkbook(report(), model), b = await exportMonthlyWorkbook(report(), model);
  assert.deepEqual(a, b);
  const zip = unpack(a); assert.equal(zip.size, 9);
  for (const [name, text] of zip) { assert(!name.includes('vba')); assert(!text.includes('<f>')); assert(!text.includes('TargetMode="External"')); }
  assert.match(zip.get('xl/workbook.xml'), /name="明细对账"/);
});
