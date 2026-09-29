import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { DEMO_FACTS, DEFAULT_RULE, DEFAULT_FILTER, OPTIONS, compute, fixed, decimal, exportCsv } from '../web/modules/reports/demo-model.js';

const [java, out] = process.argv.slice(2);
if (!java || !out) throw new Error('Use M06_check.sh to provide isolated compiled classes');
const cases = [];
for (const dateBasis of OPTIONS.dateBasis) for (const hourBasis of OPTIONS.hourBasis)
  for (const rankMetric of OPTIONS.rankMetric) for (const tieRule of OPTIONS.tieRule)
    for (const orgCodes of [[], ['DEMO-ORG-EAST']]) cases.push({ rules: { ...DEFAULT_RULE, dateBasis, hourBasis, rankMetric, tieRule, currency: 'CNY' }, filter: { ...DEFAULT_FILTER, orgCodes } });
cases.push({ rules: { ...DEFAULT_RULE, currency: 'CNY', includedStatuses: ['CANCELLED'] }, filter: DEFAULT_FILTER });
cases.push({ rules: { ...DEFAULT_RULE, currency: 'CNY', includedStatuses: ['SUPERSEDED'] }, filter: DEFAULT_FILTER });
cases.push({ rules: { ...DEFAULT_RULE, currency: 'CNY' }, filter: { ...DEFAULT_FILTER, start: '2027-01-01', end: '2027-01-31' } });
const file = join(out, 'M06-synthetic-parity.json');
writeFileSync(file, JSON.stringify({ dataMode: 'SYNTHETIC_DEMO', facts: DEMO_FACTS, cases }));
const probe = spawnSync(java, ['-cp', out, 'com.training.M06ReportsProbe', file], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
assert.equal(probe.status, 0, probe.stderr);
const backend = JSON.parse(probe.stdout);
const money = value => fixed(value);
const coreTotal = row => ({ records: row.records, courses: row.courses, hours: money(row.hours), fee: money(row.fee) });
const demoTotal = row => ({ records: row.records, courses: row.courses.size, hours: row.hours, fee: row.fee });
let successes = 0, errors = 0;
for (let i = 0; i < cases.length; i++) {
  const { rules, filter } = cases[i], front = compute(DEMO_FACTS, rules, filter), back = backend[i];
  assert.equal(front.errors.length === 0, back.ok, `case ${i}: validation agreement`);
  if (!back.ok) { errors++; continue; }
  successes++;
  const report = back.report;
  assert.deepEqual(coreTotal(report.total), demoTotal(front.totals), `case ${i}: totals`);
  assert.deepEqual(report.teachers.map(g => ({ code: g.code, rank: g.rank, ...coreTotal(g.total) })), front.rankings.map(g => ({ code: g.code, rank: g.rank, ...demoTotal(g) })), `case ${i}: ranking`);
  assert.deepEqual(report.organizations.map(g => ({ code: g.code, ...coreTotal(g.total) })), front.organizations.map(g => ({ code: g.code, ...demoTotal(g) })), `case ${i}: organizations`);
  assert.deepEqual(report.details.map(d => ({ recordCode: d.recordCode, date: d.date, hours: money(d.hours), fee: money(d.fee) })), front.details.map(d => ({ recordCode: d.source.recordCode, date: d.date, hours: d.hours, fee: d.fee })), `case ${i}: details`);
  for (const basis of OPTIONS.hourBasis) assert.equal(report.hourTotals[basis].hours === null ? null : money(report.hourTotals[basis].hours), front.hourTotals[basis], `case ${i}: ${basis}`);
  assert.equal(report.reconciliation.matches, true);
  for (const type of ['DETAIL', 'RANKING', 'ORG_SUMMARY']) {
    const csvRows = exportCsv(front, type).slice(1).trimEnd().split('\r\n').map(line => line.slice(1, -1).split('\",\"'));
    const headers = csvRows.shift(), h = headers.indexOf('hours'), f = headers.indexOf('historicalFee');
    assert.equal(csvRows.reduce((sum, row) => sum + fixed(row[h]), 0n), front.totals.hours, `case ${i}: ${type} CSV hours`);
    assert.equal(csvRows.reduce((sum, row) => sum + fixed(row[f]), 0n), front.totals.fee, `case ${i}: ${type} CSV fee`);
  }
}
const initial = compute(DEMO_FACTS, DEFAULT_RULE, DEFAULT_FILTER);
assert.equal(decimal(initial.totals.hours), '19.00');
assert.equal(decimal(initial.totals.fee), '7600.00');
console.error(`M06 Java/JS parity: ${cases.length} cases passed (${successes} results, ${errors} matching validation errors); CSV totals checked`);
