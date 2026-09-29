// Synthetic UI contracts by default; optional saved isolated HTTP responses.
// Neither mode accesses the network, a model, or production data.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const input = process.argv[2];
const source = fs.readFileSync('web/app.js', 'utf8');
const start = source.indexOf('  function railVerificationMarkup(');
const end = source.indexOf('  function adjacentScheduleMarkup(', start);
const oldStart = source.indexOf('    const planning = dispatchFit?.planning_reference;', start);
assert(start >= 0 && oldStart > start && end > oldStart);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
const context = {esc, icon: () => ''};
const render = vm.runInNewContext(`(${source.slice(start, end).trim()})`, context);
const before = vm.runInNewContext(`(function railVerificationMarkup(dispatchFit) {\n${source.slice(oldStart, end).trim()})`, context);
const clone = value => JSON.parse(JSON.stringify(value));
let checks = 0;
const ok = (value, message) => { checks++; assert(value, message); };
function fits(value, found = []) {
  if (!value || typeof value !== 'object') return found;
  if (Object.hasOwn(value, 'dispatch_fit')) found.push(value.dispatch_fit);
  for (const child of Object.values(value)) fits(child, found);
  return found;
}
const all = [];
if (input) {
  for (const name of ['before-off','current-off','valid','partial','bad-hash','bad-component','old-hard-failure']) {
    all.push(...fits(JSON.parse(fs.readFileSync(`${input}/${name}/responses.json`, 'utf8'))));
  }
} else {
  // Explicitly fictitious cities: this is a rendering fixture, not travel evidence.
  for (const [index, minutes, label, baseline] of [[1,45,'报道最快45分钟',false],[2,20,'约20分钟',false],[3,120,'现有2小时参考',true]]) {
    const kind = baseline ? 'context_current_baseline_shared_v2' : 'context_roundtrip_shared_v2';
    const reference = {
      reference_kind: kind, reference_status: 'active', endpoint_scope: 'city_summary', stations: [null,null],
      city_a: {id:`fixture-${index}-a`}, city_b: {id:`fixture-${index}-b`},
      independent_direction_observations: 0, shared_observation: {reference_minutes: minutes},
      source_published_on: '2026-08-01', reviewed_on: '2026-09-08', review_due_on: '2026-12-07',
      source_url: 'https://example.invalid/synthetic-ui-contract'
    };
    for (const key of ['transport_verified','strict_eligibility','value_is_proven_travel_upper_bound','rail_exclusion_complete','air_fallback_trigger','time_score_applicable','semantic_annotations_are_language_proof']) reference[key] = false;
    all.push({
      planning_included: true, planning_reference_kind: kind,
      planning_policy: baseline ? 'context_shared_prebid_opt_in_v2' : 'context_shared_prebid_opt_in_v1',
      context_planning_band_id: minutes <= 60 ? 'planning_0_1h' : 'planning_1_2h',
      context_planning_reference: reference,
      context_planning_display: {
        version: baseline ? 'context-baseline-planning-display-v2' : 'context-shared-planning-display-v1',
        label: `铁路出行参考 · ${label}`, endpoint_label: `测试甲${index}—测试乙${index}（未指定车站）`,
        notice: '用于投标前初筛，实际出行待核，市内接驳另行确认。',
        basis_note: '合成展示测试，不是实际交通资料。'
      }
    });
  }
  all.push({}, {planning_included: false}, {route_reference: {status: 'unknown'}});
}
ok(all.length > 0, input ? 'Actual dispatch fits loaded' : 'Synthetic UI contracts loaded');
const references = new Map();
for (const fit of all) {
  if (fit.context_planning_reference && fit.planning_included) {
    const ref = fit.context_planning_reference;
    references.set(`${ref.city_a.id}:${ref.city_b.id}`, fit);
  } else {
    ok(render(fit) === before(fit), 'Non-context rendering unchanged');
  }
}
ok(references.size === 3, 'Three distinct context reference fixtures');
for (const fit of references.values()) {
  const html = render(fit), display = fit.context_planning_display;
  ok(html.includes(esc(display.label)), 'Original precision/qualifier displayed');
  ok(html.split(esc(display.label)).length === 2, 'One shared duration label');
  ok(html.includes(esc(display.endpoint_label)), 'Unknown stations preserved');
  ok(html.includes('实际出行待核') && html.includes('市内接驳另行确认'), 'Honest planning notice');
  const [card, details] = html.split('<details>');
  ok(details.includes(esc(display.basis_note)), 'Source context preserved in details');
  for (const key of ['source_published_on','reviewed_on','review_due_on']) {
    ok(details.includes(fit.context_planning_reference[key]), 'Evidence date visible');
    ok(!card.includes(fit.context_planning_reference[key]), 'Dates do not crowd card');
  }
  for (const forbidden of ['去程：','返程：','已核实可达','undefined','null']) ok(!html.includes(forbidden), forbidden);
  for (const key of ['transport_verified','strict_eligibility','value_is_proven_travel_upper_bound','rail_exclusion_complete','air_fallback_trigger','time_score_applicable','semantic_annotations_are_language_proof']) {
    const changed = clone(fit); changed.context_planning_reference[key] = true;
    ok(render(changed).includes('城市交通参考待复核'), `Bad capability ${key}`);
  }
  for (const number of [0, -1, 240, 45.5, '45', null]) {
    const changed = clone(fit); changed.context_planning_reference.shared_observation.reference_minutes = number;
    ok(render(changed).includes('城市交通参考待复核'), 'Invalid duration is pending');
  }
  for (const key of ['planning_policy','planning_reference_kind','context_planning_band_id']) {
    const changed = clone(fit); changed[key] = '__proto__';
    ok(render(changed).includes('城市交通参考待复核'), `Invalid ${key}`);
  }
  for (const field of ['context_planning_display','context_planning_reference']) {
    const changed = clone(fit); changed[field] = null;
    ok(render(changed).includes('城市交通参考待复核'), 'Missing context is pending');
  }
  const evil = clone(fit);
  evil.context_planning_display.label = '<img src=x onerror=alert(1)>';
  evil.context_planning_display.basis_note = '<script>alert(1)</script>';
  evil.context_planning_reference.source_url = 'javascript:alert(1)';
  const escaped = render(evil);
  ok(escaped.includes('&lt;img') && escaped.includes('&lt;script'), 'Display strings escaped');
  ok(!escaped.includes('<img') && !escaped.includes('<script') && !escaped.includes('href='), 'No injected markup or URL');
  ok(!render({...fit, planning_included: false}).includes(esc(display.label)), 'Disabled context not displayed');
}
console.log(`Context planning UI: ${checks} checks PASS; ${input ? 'saved actual HTTP responses' : 'synthetic rendering contracts'}, old rendering unchanged`);
