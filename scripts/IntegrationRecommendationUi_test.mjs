import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url), __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const source = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(source.slice(0, source.lastIndexOf('\n(async () => {')) + '\n({harness,NodeStub});', { require, __dirname, console, structuredClone, AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate });
const module = await import('../web/modules/course-catalog/recommendation.js');
const { NodeStub } = support;
Object.defineProperty(NodeStub.prototype, 'checked', { get() { return this._checked ?? this.hasAttribute('checked'); }, set(value) { this._checked = Boolean(value); } });
Object.defineProperty(NodeStub.prototype, 'validity', { get() { return { badInput: false }; } });
const descriptor = Object.getOwnPropertyDescriptor(NodeStub.prototype, 'innerHTML');
Object.defineProperty(NodeStub.prototype, 'innerHTML', { get: descriptor.get, set(value) { descriptor.set.call(this, value); if (this.tagName === 'SELECT') this.value = this.children.find(child => child.tagName === 'OPTION' && child.hasAttribute('selected'))?.value ?? this.children.find(child => child.tagName === 'OPTION')?.value ?? ''; this.querySelectorAll('textarea').forEach(el => { el.value = el.textContent; }); this.querySelectorAll('[hidden]').forEach(el => { el.hidden = true; }); } });
NodeStub.prototype.insertAdjacentHTML = function (position, value) { assert.equal(position, 'beforeend'); const holder = new NodeStub(); holder.innerHTML = value; holder.children.forEach(child => this.appendChild(child)); };
const extract = (start, end) => { const a = app.indexOf(start), b = app.indexOf(end, a); assert.ok(a >= 0 && b > a, start); return app.slice(a, b); };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const scopeRows = [{ scope_id: 1, organization_code: 'ORG-SYNTH' }, { scope_id: 2, organization_code: 'ORG-EMPTY' }];
const snapshot = (id = 1, version = 3) => ({ scope_id: id, version, status: 'READY', catalog: { courses: [{ course_code: '001', course_name: '服务课程 <svg>', active: true }, { course_code: '002', course_name: '停用课程', active: false }] } });
const selection = () => ({ scope_id: 1, expected_version: 3, course_code: '001', as_of: '2026-09-22', accepted_levels: [], allowed_cities: [] });
const qualification = (selected, status = 'READY') => ({ schema_version: 'm04_recommendation_qualification_v1', selected: Boolean(selected), status: selected ? status : 'NOT_SELECTED', message: selected ? '本次已核验具体课程资格' : '未选择标准课程，未核验课程认证，沿用原推荐规则', eligible_count: selected ? 4 : null, scoring_pool_count: 4, qualification_context: selected ? { schema_version: 'm04_qualification_context_v1', scope_id: selected.scope_id, version: selected.expected_version, course_code: selected.course_code, as_of: selected.as_of, accepted_levels: selected.accepted_levels, allowed_cities: selected.allowed_cities } : null, gaps: selected ? Array.from({ length: 25 }, (_, i) => ({ teacher_id: 50 + i, teacher_code: 'T-' + i, reasons: [{ message: '该课未认证 <script>' }] })) : [], pool_exclusions: [], issues: [] });
const result = body => ({ recommendations: [8, 5, 7, 6].map(id => ({ teacher_id: id, teacher_name: '合成讲师 ' + id, model_score: 80, dispatch_fit: { residence_complete: true }, system_metrics: {} })), shortfall: 0, returned_count: 4, analysis: { topics: ['服务课程'], dispatch_preferences: { selection: { ties_included: 1 }, ranking_policy: '原排序与并列保留' } }, course_qualification: qualification(body.standard_course) });
function harness({ fetchOverride, writer = true, scopes = scopeRows, moduleFailure = false, moduleWait } = {}) {
  const h = support.harness({ page: 'teachers', writer, fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      if (url === '/api/course-catalog/scopes') return respond({ scopes });
      if (url === '/api/course-catalog/scopes/1') return respond(snapshot());
      if (url === '/api/course-catalog/scopes/2') return respond({ scope_id: 2, version: 0, status: 'NOT_CONFIGURED' });
      if (url.startsWith('/api/teacher-resumes/dispatch-priorities?')) return respond({ organizations: [], priorities: [] });
      if (url === '/api/teacher-recommendations') return respond(result(JSON.parse(opts.body)));
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond) : fallback();
  } });
  const s = h.sandbox; s.state.teacherTab = 'recommend'; s.state.teacherRequirementDraft = ''; s.state.cache = {};
  s.state.teacherRecommendationForm = { demandId: '', maxResults: '3', maxFeeRate: '', hardBudget: false, inputMode: 'guided', guided: { topic: '服务课程' } };
  s.REGION_PROVINCES = ['浙江']; s.refreshRegionSuggestions = () => {};
  h.instances = []; h.moduleCalls = [];
  s.loadRecommendationModule = async url => { h.moduleCalls.push(url); if (moduleWait) await moduleWait.promise; if (moduleFailure) throw Error('synthetic import failure'); return { mountRecommendationCourse(root, host) { const instance = module.mountRecommendationCourse(root, host); h.instances.push(instance); return instance; } }; };
  vm.runInContext(extract('  let teacherRecommendationSequence =', '  function teacherResumeItems(') + extract('  function listText(', '  function resumeProfileText(') + extract('  function resumeClaimFacts(', '  async function openResumeDetails(') + extract('  function demandRequirementText(', '  async function pageTeachers(').replace("await import('/modules/course-catalog/recommendation.js?v=20260922recommend1')", "await loadRecommendationModule('/modules/course-catalog/recommendation.js?v=20260922recommend1')"), s);
  h.root = h.document.createElement('section'); h.content.appendChild(h.root);
  h.context = { c: h.content, epoch: s.routeEpoch, demands: [{ id: 9, title: '原需求', expect_date: '2026-10-01', content: '需求正文' }], rows: [8, 5, 7, 6].map(id => ({ id, name: '合成讲师 ' + id })), projects: [], dispatches: [] };
  h.render = () => s.renderTeacherRecommendation(h.root, h.context);
  h.mount = async () => { h.render(); await tick(); if (h.instances.length) await h.instances.at(-1).ready; await tick(); };
  h.el = id => h.root.querySelector('#' + id);
  h.course = key => h.root.querySelector('[data-k="' + key + '"]');
  h.edit = async (el, value, event = 'input') => { if (typeof value === 'boolean') el.checked = value; else el.value = value; await h.emit(el, event); };
  h.enable = () => h.edit(h.root.querySelector('[data-course-enabled]'), true, 'change');
  h.select = async () => { await h.enable(); await h.edit(h.course('scope_id'), '1', 'change'); await h.edit(h.course('course_code'), '001', 'change'); await h.edit(h.course('as_of'), '2026-09-22'); };
  h.run = () => h.el('recommend-run').onclick(); h.posts = () => h.calls.filter(call => call.url === '/api/teacher-recommendations');
  h.output = () => h.el('recommend-output');
  return h;
}
const h = harness(); await h.mount();
check(h.course('scope_id') && h.course('scope_id').value === '' && !h.root.querySelector('[data-course-enabled]').checked, 'Real exported selector starts unselected without guessing a scope');
equal(h.calls.filter(call => call.url.includes('/course-catalog')).map(call => call.url), ['/api/course-catalog/scopes'], 'Initial form reads only authorized scope list and never a guessed directory');
await h.run(); const legacy = JSON.parse(h.posts()[0].opts.body);
check(!Object.hasOwn(legacy, 'standard_course') && h.output().textContent.includes('未核验课程认证'), 'Unselected course omits the optional field entirely and clearly labels legacy recommendations unverified');
equal(h.output().querySelectorAll('.teacher-match-card').map(card => card.querySelector('h3').textContent.split(' ')[1]), ['8', '5', '7', '6'], 'Original server ranking and all third-place ties remain in their original order');
check(h.el('recommend-count').disabled && !h.sandbox.state.cache.teacherRecommendations, 'Fixed top-three rule stays disabled and no local result cache is saved');
await h.enable(); check(h.output().hidden && h.output().textContent === '', 'Enabling a course clears the prior unverified result');
await h.run(); check(h.posts().length === 1 && h.el('recommend-status').textContent.includes('明确选择'), 'Enabled but incomplete course selection never silently falls back to legacy recommendation');
await h.edit(h.course('scope_id'), '1', 'change');
check(h.course('course_code').options.length === 2 && !h.root.querySelector('svg') && h.course('course_code').value === '', 'Only confirmed active courses are offered, safely escaped, without auto-selection');
await h.edit(h.course('course_code'), '001', 'change'); await h.run(); check(h.posts().length === 1 && h.el('recommend-status').textContent.includes('判定日期'), 'Selected course requires an explicit date, never today or inferred free text');
await h.edit(h.course('as_of'), '2026-02-30'); await h.run(); check(h.posts().length === 1, 'Impossible calendar date is rejected');
await h.edit(h.course('as_of'), '2026-09-22'); await h.edit(h.course('accepted_levels'), '讲师\n讲师'); await h.run(); check(h.posts().length === 1 && h.el('recommend-status').textContent.includes('不重复'), 'Duplicate restriction entries cannot be silently normalized into a different request');
await h.edit(h.course('accepted_levels'), '讲师\n高级讲师'); await h.edit(h.course('allowed_cities'), '杭州\n上海');
await h.edit(h.el('recommend-max-fee'), '800.50'); await h.edit(h.el('recommend-hard-budget'), true, 'change'); await h.run();
const payload = JSON.parse(h.posts()[1].opts.body);
equal(payload.standard_course, { ...selection(), accepted_levels: ['讲师', '高级讲师'], allowed_cities: ['杭州', '上海'] }, 'Actual original POST submits the exact six selected course fields with scope/version from confirmed server snapshot');
check(payload.max_fee_rate === 800.5 && payload.hard_budget === true && payload.max_results === 3 && payload.prefer_local === true && payload.requirement_contract.schema_version === 'guided_requirement_v1', 'Existing budget, local preference, guided evidence contract and three/tie behavior are preserved');
check(!Object.keys(payload).some(key => /qualification|candidate|context_id/.test(key)), 'Client never supplies qualification claims, candidates or internal context');
const qualifications = h.output().querySelector('[data-recommend-qualification]');
check(qualifications.textContent.includes('课程资格缺口') && qualifications.querySelector('tbody').children.length === 20 && qualifications.textContent.includes('<script>') && !qualifications.querySelector('script'), 'Server qualification gaps are escaped and bounded to twenty rows per page');
await h.emit(qualifications.querySelector('[data-qualification-next]'), 'click'); check(qualifications.querySelector('tbody').children.length === 5, 'Actual qualification gap paging reaches the remaining rows');
for (const [element, value, event] of [[h.el('recommend-guide-topic'), '更新主题', 'input'], [h.el('recommend-requirement'), '原话更新', 'input'], [h.el('recommend-max-fee'), '900', 'input'], [h.el('recommend-hard-budget'), false, 'change'], [h.el('recommend-prefer-local'), false, 'change'], [h.course('as_of'), '2026-09-23', 'input'], [h.course('accepted_levels'), '', 'input'], [h.course('allowed_cities'), '', 'input'], [h.el('recommend-organization'), '', 'change']]) {
  await h.run(); check(!h.output().hidden, 'Fresh analysis is visible before editing a condition'); await h.edit(element, value, event); check(h.output().hidden && h.output().textContent === '', 'Any form or course condition change clears prior result');
}
await h.run(); h.context.stopTeacherTabPolling(); await h.mount();
check(h.output().hidden && !h.output().textContent && h.course('scope_id').value === '' && h.root.querySelector('[data-course-enabled]').checked, 'Returning to recommendation never re-renders cached results or silently drops an earlier course requirement');
const beforeReturnRun = h.posts().length; await h.run(); check(h.posts().length === beforeReturnRun, 'Returning with course enabled requires new explicit directory/course selection');

const empty = harness({ scopes: [] }); await empty.mount(); check(empty.root.textContent.includes('没有可查看的课程目录'), 'No authorized directory gives a local truthful empty state'); await empty.run(); check(!Object.hasOwn(JSON.parse(empty.posts()[0].opts.body), 'standard_course'), 'Explicit unselected mode can still use old recommendation without claiming certification');
const unconfigured = harness(); await unconfigured.mount(); await unconfigured.enable(); await unconfigured.edit(unconfigured.course('scope_id'), '2', 'change'); await unconfigured.run(); check(!unconfigured.posts().length && unconfigured.root.textContent.includes('尚无已确认课程'), 'Unconfigured selected scope blocks recommendation without removing the condition'); await unconfigured.edit(unconfigured.root.querySelector('[data-course-enabled]'), false, 'change'); await unconfigured.run(); check(unconfigured.posts().length === 1, 'Only an explicit opt-out permits unverified legacy recommendation after no catalog');
for (const status of [403, 409]) {
  const failed = harness({ fetchOverride: (url, opts, normal, response) => url === '/api/teacher-recommendations' ? response(null, status) : normal() }); await failed.mount(); await failed.select(); await failed.run();
  check(failed.course('course_code').value === '' && failed.course('scope_id').value === '' && failed.root.textContent.includes('请重新读取目录'), status + ': invalidated authority clears old course context and result');
  await failed.run(); check(failed.posts().length === 1 && failed.root.querySelector('[data-course-enabled]').checked, status + ': another click cannot use old snapshot or silently omit the course');
}
const noQualification = harness({ fetchOverride: (url, opts, normal, response) => { if (url !== '/api/teacher-recommendations') return normal(); const value = result(JSON.parse(opts.body)); delete value.course_qualification; return response(value); } }); await noQualification.mount(); await noQualification.select(); await noQualification.run(); check(!noQualification.output().querySelector('.teacher-match-card') && noQualification.output().textContent.includes('未返回所选课程'), 'Selected course cannot display a legacy response lacking qualification');
// Run the actual original submit handler against altered server responses, not a copy of its guard.
const wrongQualification = [
  ['unknown status', value => { value.status = 'CERTIFIED'; }],
  ['unselected status for selected course', value => { value.status = 'NOT_SELECTED'; }],
  ['selected flag', value => { value.selected = false; }],
  ['summary schema', value => { value.schema_version = 'other_v1'; }],
  ['missing context', value => { delete value.qualification_context; }],
  ['context schema', value => { value.qualification_context.schema_version = 'other_v1'; }],
  ['scope', value => { value.qualification_context.scope_id = 2; }],
  ['scope type', value => { value.qualification_context.scope_id = '1'; }],
  ['version', value => { value.qualification_context.version = 4; }],
  ['course', value => { value.qualification_context.course_code = '002'; }],
  ['date', value => { value.qualification_context.as_of = '2026-09-23'; }],
  ['missing level', value => { value.qualification_context.accepted_levels = ['讲师']; }],
  ['additional level', value => { value.qualification_context.accepted_levels.push('特级讲师'); }],
  ['duplicate level', value => { value.qualification_context.accepted_levels.push(' 讲师 '); }],
  ['invalid level type', value => { value.qualification_context.accepted_levels = [1, '高级讲师']; }],
  ['missing cities', value => { delete value.qualification_context.allowed_cities; }],
  ['different city', value => { value.qualification_context.allowed_cities = ['上海', '宁波']; }],
  ['empty city restriction', value => { value.qualification_context.allowed_cities = []; }],
  ['blank city', value => { value.qualification_context.allowed_cities.push(' '); }],
];
for (const [label, alter] of wrongQualification) {
  let tamper = false;
  const guarded = harness({ fetchOverride: (url, opts, normal, response) => { if (url !== '/api/teacher-recommendations') return normal(); const value = result(JSON.parse(opts.body)); if (tamper) alter(value.course_qualification); return response(value); } });
  await guarded.mount(); await guarded.select(); await guarded.edit(guarded.course('accepted_levels'), '讲师\n高级讲师'); await guarded.edit(guarded.course('allowed_cities'), '杭州\n上海');
  await guarded.run(); assert.equal(guarded.output().querySelectorAll('.teacher-match-card').length, 4, label + ': valid result initially visible');
  tamper = true; await guarded.run();
  check(!guarded.output().querySelector('.teacher-match-card') && !guarded.output().querySelector('[data-recommend-qualification]') && guarded.output().textContent.includes('请重新分析') && !guarded.sandbox.state.cache.teacherRecommendations, label + ': mismatched response clears previous candidates and qualification detail');
}
const reordered = harness({ fetchOverride: (url, opts, normal, response) => { if (url !== '/api/teacher-recommendations') return normal(); const value = result(JSON.parse(opts.body)); for (const key of ['accepted_levels', 'allowed_cities']) value.course_qualification.qualification_context[key] = value.course_qualification.qualification_context[key].slice().reverse().map(item => ' ' + item + ' '); return response(value); } });
await reordered.mount(); await reordered.select(); await reordered.edit(reordered.course('accepted_levels'), '讲师\n高级讲师'); await reordered.edit(reordered.course('allowed_cities'), '杭州\n上海'); await reordered.run();
check(reordered.output().querySelectorAll('.teacher-match-card').length === 4, 'Equivalent normalized restriction sets keep original server ranking and ties');
for (const status of ['NO_ELIGIBLE', 'NOT_CONFIGURED', 'INVALID_SELECTION']) {
  const leaked = harness({ fetchOverride: (url, opts, normal, response) => { if (url !== '/api/teacher-recommendations') return normal(); const value = result(JSON.parse(opts.body)); value.course_qualification.status = status; value.candidates = value.recommendations; value.recommendations = []; return response(value); } });
  await leaked.mount(); await leaked.select(); await leaked.run(); check(!leaked.output().querySelector('.teacher-match-card') && leaked.output().textContent.includes('不能显示'), status + ': alternate candidate field cannot bypass empty-pool state');
}
const unknownLegacy = harness({ fetchOverride: (url, opts, normal, response) => { if (url !== '/api/teacher-recommendations') return normal(); const value = result(JSON.parse(opts.body)); value.course_qualification.status = 'CERTIFIED'; return response(value); } }); await unknownLegacy.mount(); await unknownLegacy.run();
check(!unknownLegacy.output().querySelector('.teacher-match-card') && unknownLegacy.output().textContent.includes('请重新分析'), 'Unknown returned status is also refused when no course was selected');

const noEligible = harness({ fetchOverride: (url, opts, normal, response) => url === '/api/teacher-recommendations' ? response({ ...result(JSON.parse(opts.body)), recommendations: [], shortfall: 3, returned_count: 0, course_qualification: { ...qualification(JSON.parse(opts.body).standard_course, 'NO_ELIGIBLE'), eligible_count: 0, scoring_pool_count: 0, message: '没有符合课程条件的师资' } }) : normal() }); await noEligible.mount(); await noEligible.select(); await noEligible.run(); check(!noEligible.output().querySelector('.teacher-match-card') && noEligible.output().textContent.includes('没有符合课程条件') && noEligible.output().textContent.includes('资格缺口'), 'No-eligible server result preserves empty recommendations and explains actual gaps');

const pendingScope = deferred(); const racingScope = harness({ fetchOverride: (url, opts, normal, response) => url === '/api/course-catalog/scopes/1' ? pendingScope.promise.then(response) : normal() }); await racingScope.mount(); await racingScope.enable(); const firstScope = racingScope.edit(racingScope.course('scope_id'), '1', 'change'); await tick(); await racingScope.edit(racingScope.course('scope_id'), '2', 'change'); pendingScope.resolve(snapshot()); await firstScope; check(racingScope.course('course_code').disabled && racingScope.course('scope_id').value === '2' && racingScope.calls.find(call => call.url.endsWith('/scopes/1')).opts.signal.aborted, 'Late scope response cannot override a newer selection');
for (const departure of ['input', 'route', 'tab', 'identity']) {
  const pending = deferred(); const late = harness({ fetchOverride: (url, opts, normal, response) => url === '/api/teacher-recommendations' ? pending.promise.then(response) : normal() }); await late.mount(); await late.select(); const running = late.run(); await tick();
  if (departure === 'input') await late.edit(late.course('as_of'), '2026-09-23');
  if (departure === 'route') { late.sandbox.beginRouteEpoch(); late.sandbox.state.page = 'dashboard'; }
  if (departure === 'tab') { late.context.stopTeacherTabPolling(); late.sandbox.state.teacherTab = 'library'; }
  if (departure === 'identity') late.sandbox.state.user = { uid: 99, role: 'admin' };
  pending.resolve(result(JSON.parse(late.posts()[0].opts.body))); await running;
  check(!late.output().querySelector('.teacher-match-card') && !late.sandbox.state.cache.teacherRecommendations, departure + ': stale result never appears or enters cache');
  if (departure !== 'identity') check(late.posts()[0].opts.signal.aborted, departure + ': pending recommendation is aborted');
}
const pendingModule = deferred(); const unloaded = harness({ moduleWait: pendingModule }); unloaded.render(); unloaded.context.stopTeacherTabPolling(); unloaded.sandbox.state.teacherTab = 'library'; pendingModule.resolve(); await tick(); check(!unloaded.instances.length && !unloaded.calls.some(call => call.url.includes('course-catalog')), 'Late optional module import cannot mount or fetch after tab cleanup');
const denied = harness({ writer: false }); await denied.mount(); check(!denied.calls.length && !denied.moduleCalls.length, 'Readonly original account remains outside recommendation and optional catalog loading');
console.log(JSON.stringify({ ok: true, suite: 'original recommendation course qualification UI', checks, transport: 'synthetic', browser: false, database: false }));
