import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const load = name => import('data:text/javascript;base64,' + readFileSync(root + 'web/modules/catalog/' + name).toString('base64'));
const { preview, qualify } = await load('model.js');
const { createDemoCatalog, createDemoRequest } = await load('demo.js');
const classpath = process.argv[2];
assert.ok(classpath, 'Pass compiled M04 Java test class directory');
const cases = [];
function scenario(label, edit = () => {}) {
  const input = { catalog: createDemoCatalog(), request: createDemoRequest() };
  edit(input); cases.push({ label, input });
}
scenario('synthetic baseline');
scenario('null catalog and request', input => { input.catalog = null; input.request = null; });
for (const table of ['courses', 'teachers', 'certifications']) {
  scenario(table + ' duplicate', ({ catalog }) => catalog[table].push({ ...catalog[table][0] }));
  scenario(table + ' invalid row', ({ catalog }) => catalog[table].push(null));
  scenario(table + ' missing', ({ catalog }) => delete catalog[table]);
  scenario(table + ' too many', ({ catalog }) => catalog[table] = Array(5001).fill({}));
  scenario(table + ' empty', ({ catalog }) => catalog[table] = []);
}
for (const [table, field] of [ ['courses','course_code'], ['teachers','teacher_code'], ['certifications','teacher_code'], ['certifications','course_code'] ]) {
  for (const value of [null, 1, '', ' A', 'A ', '中国', 'DEMO-X', '001', 'demo-c01', '<a>', 'A'.repeat(65)])
    scenario(`${table}.${field}=${value}`, ({ catalog }) => catalog[table][0][field] = value);
}
for (const status of ['certified', 'unknown', 'not_certified', 'revoked', 'approved', null, false, ''])
  scenario('status ' + status, ({ catalog }) => catalog.certifications[0].status = status);
for (const source of ['', null, 1, '\u00a0', '\u200b', '\ufeff', '\u0085', 'SRC\u200b01', 'SRC\u202e01', 'CODE '])
  scenario('source ' + JSON.stringify(source), ({ catalog }) => catalog.certifications[0].source_ref = source);
for (const date of ['2026-01-01','2026-12-31','2025-12-31','2027-01-01','2026-02-29','2024-02-29','0000-01-01','0001-01-01','9999-12-31','2026-04-31','2026-1-01','',null,12]) {
  scenario('as of ' + date, ({ request }) => request.as_of = date);
  scenario('valid to ' + date, ({ catalog }) => catalog.certifications[0].valid_to = date);
}
for (const field of ['accepted_levels','allowed_cities']) {
  for (const values of [[], ['L1'], ['演示甲城'], ['', 'L1'], ['L1','L1'], [null], [3], null, '', Array(5001).fill('L1')])
    scenario(field + JSON.stringify(values?.slice?.(0,2) ?? values), ({ request }) => request[field] = values);
}
scenario('inactive course', ({ catalog }) => catalog.courses[0].active = false);
scenario('string active', ({ catalog }) => catalog.courses[0].active = 'true');
scenario('missing source', ({ catalog }) => delete catalog.certifications[0].source_ref);
scenario('missing dates', ({ catalog }) => { catalog.certifications[0].valid_from = ''; catalog.certifications[0].valid_to = ''; });
scenario('unsupported schema', ({ catalog }) => catalog.schema_version = 'v99');
scenario('unknown field catalog', ({ catalog }) => catalog.name = 'unaccepted field');
scenario('unknown field teacher', ({ catalog }) => catalog.teachers[0].phone = 'unaccepted field');
scenario('unknown field query', ({ request }) => request.min_level = 'L1');
scenario('leading zeros', ({ catalog }) => { catalog.teachers[0].teacher_code = '001'; catalog.certifications[0].teacher_code = '001'; });
for (const value of ['\u00a0','\u3000','\u200b','\ufeff',' A','A ','A\u0085B','有效名称','A B'])
  scenario('version text ' + JSON.stringify(value), ({ catalog }) => catalog.catalog_version = value);
scenario('all teacher rows unknown', ({ catalog }) => { catalog.teachers.forEach(t => {t.teacher_level=''; t.city='';}); });
scenario('combined exact restrictions', ({ request }) => {request.accepted_levels=['L1']; request.allowed_cities=['演示甲城'];});
const java = process.env.JAVA_BIN || 'java';
const run = spawnSync(java, ['-cp', classpath, 'com.training.M04CourseCatalogTest', '--evaluate'], {
  input: cases.map(c => JSON.stringify(c.input)).join('\n') + '\n', encoding: 'utf8', maxBuffer: 16 * 1024 * 1024
});
assert.equal(run.status, 0, run.stderr || String(run.error));
const outputs = run.stdout.trim().split('\n').map(JSON.parse);
assert.equal(outputs.length, cases.length);
function semantics(value) {
  if (Array.isArray(value)) return value.map(semantics);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'message').map(([key, val]) => [key, semantics(val)]));
  return value;
}
cases.forEach(({ label, input }, index) => {
  const client = { preview: preview(input.catalog), qualification: qualify(input.catalog, input.request) };
  assert.deepEqual(semantics(client), semantics(outputs[index]), label);
});
console.log(`M04 Java/browser conformance: ${cases.length} scenarios passed (full output except localized messages)`);
