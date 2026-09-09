// Pure rendering contract: no browser, live route data or production calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('web/app.js', 'utf8');
const start = source.indexOf('  function railVerificationMarkup(');
const end = source.indexOf('  function adjacentScheduleMarkup(', start);
assert(start >= 0 && end > start);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const render = vm.runInNewContext(`(${source.slice(start, end).trim()})`, { esc, icon: () => '' });
const leg = { from: {city:'测试甲'}, to:{city:'测试乙'}, from_endpoint:'测试甲东', to_endpoint:'测试乙南', source_url:'https://example.test/source', source_published_on:'2026-08-20', review_due_on:'2026-12-01' };
const planning = { status:'planning_reference', planning_band_id:'planning_1_2h', outbound:leg, inbound:{...leg, from:leg.to, to:leg.from, from_endpoint:leg.to_endpoint, to_endpoint:leg.from_endpoint} };
const fit = { planning_included:true, planning_reference:planning, route_reference:{status:'rail_reference_pending', eligibility:'unknown'} };
const html=render(fit);
assert.match(html,/铁路初筛 · 1–2小时档/);
assert.match(html,/去程：测试甲 → 测试乙/);
assert.match(html,/返程：测试乙 → 测试甲/);
assert.match(html,/测试乙南 → 测试甲东/);
assert.match(html,/2026-08-20/); assert.match(html,/2026-12-01/);
assert.match(html,/出行待核/); assert.match(html,/不是门到门耗时/);
assert.match(html,/不参与专业能力赋分/); assert.match(html,/仅用于投标前选人/);
assert(!html.includes('undefined')); assert(!html.includes('null')); assert(!html.includes('已核实可达'));
assert(!render({...fit,planning_included:false}).includes('铁路初筛'));
assert(!render({...fit,planning_reference:{...planning,status:'planning_pending'}}).includes('铁路初筛'));
assert.match(render({...fit,planning_reference:{...planning,planning_band_id:'unknown'}}),/待核/);
const evil=render({...fit,planning_reference:{...planning,outbound:{...leg,from:{city:'<script>x</script>'},from_endpoint:'<img src=x>',source_url:'javascript:alert(1)',review_due_on:'<b>x</b>'}}});
assert(!evil.includes('<script>')); assert(!evil.includes('<img')); assert(!evil.includes('javascript:')); assert(!evil.includes('<b>x</b>'));
for(const band of ['planning_0_1h','planning_2_3h','planning_3_4h']) assert.match(render({...fit,planning_reference:{...planning,planning_band_id:band}}),/铁路初筛/);
assert(!html.includes('包含30分钟规划缓冲')); assert.match(html,/不额外叠加缓冲/);
const shared = { verification_method:'dual_review_explicit_roundtrip_shared_v1', direction_semantics:'explicit_roundtrip_context', duration_scope:'single_journey_shared_summary', independent_direction_observations:0,
  value_is_proven_travel_upper_bound:false, transfer_time_estimated:false, semantic_annotations_are_language_proof:false,
  precision:'approximate', duration:{kind:'approximate',value:90,unit:'minute'}, endpoint_a:'北京通州站',endpoint_b:'秦皇岛',endpoint_scope:{a:'urban_subcentre_station',b:'city_summary'},
  source_url:'https://example.test/shared',source_published_on:'2026-07-16',reviewed_on:'2026-09-08',review_due_on:'2026-12-07' };
const sharedPlanning={status:'shared_city_planning_reference',reference_kind:'explicit_roundtrip_shared_v1',purpose:'prebid_city_planning_only',protected_envelope_validated:true,
  strict_eligibility:false,transport_verified:false,time_score_applicable:false,air_fallback_trigger:false,rail_exclusion_complete:false,semantic_annotations_are_language_proof:false,shared_reference:shared};
const sharedFit={planning_included:true,planning_policy:'explicit_roundtrip_shared_planning_v1',planning_reference_kind:'explicit_roundtrip_shared_v1',shared_planning_band_id:'planning_1_2h',planning_reference:sharedPlanning,route_reference:{status:'rail_reference_pending',eligibility:'unknown'}};
const copy=x=>JSON.parse(JSON.stringify(x));
const sharedHtml=render(sharedFit);
assert.match(sharedHtml,/铁路出行参考 · 约90分钟 · 1–2小时参考范围/);
assert.equal((sharedHtml.match(/约90分钟/g)||[]).length,1);
assert.match(sharedHtml,/北京通州站（城市副中心站点，非市中心耗时） ↔ 秦皇岛（城市概述，未指定站点）/);
for(const text of ['2026-07-16','2026-09-08','2026-12-07','实际出行待核','资料给出往返单程约值，未分别记录去返程','市内接驳另行确认'])assert(sharedHtml.includes(text));
const defaultCard=sharedHtml.split('<details>')[0], details=sharedHtml.split('<details>')[1];
assert.match(details,/<summary>查看依据<\/summary>/);
for(const text of ['2026-07-16','2026-09-08','2026-12-07','查看参考来源','未分别记录去返程']){assert(details.includes(text));assert(!defaultCard.includes(text));}
for(const text of ['单值','独立观测','严格上界','原精度','缓冲','能力赋分'])assert(!defaultCard.includes(text));
for(const text of ['去程：','返程：','已核实可达','undefined','null'])assert(!sharedHtml.includes(text));
assert(!render({...sharedFit,planning_included:false}).includes('铁路出行参考'));
for(const field of ['planning_policy','planning_reference_kind','shared_planning_band_id']) {const f=copy(sharedFit);f[field]='__proto__';assert.match(render(f),/共享城市交通参考待核/);}
for(const field of ['strict_eligibility','transport_verified','time_score_applicable','air_fallback_trigger','rail_exclusion_complete','semantic_annotations_are_language_proof']) {const f=copy(sharedFit);f.planning_reference[field]=true;assert.match(render(f),/共享城市交通参考待核/);}
for(const field of ['outbound','inbound','return','selection_reference','planning_band_id','reference_minutes','duration_bounds']) for(const outer of [true,false]) {
  const f=copy(sharedFit);(outer?f.planning_reference:f.planning_reference.shared_reference)[field]=90;assert.match(render(f),/共享城市交通参考待核/);
}
for(const value of [0,-1,90.5,'90',Infinity,NaN,240,9007199254740992]) {const f=copy(sharedFit);f.planning_reference.shared_reference.duration.value=value;assert.match(render(f),/共享城市交通参考待核/);}
for(const mutation of ['unit','extra','precision','scope','date']) {const f=copy(sharedFit),s=f.planning_reference.shared_reference;
  if(mutation==='unit')s.duration.unit='hour';if(mutation==='extra')s.duration.extra=true;if(mutation==='precision')s.precision='exact';if(mutation==='scope')s.endpoint_scope.a='__proto__';if(mutation==='date')delete s.reviewed_on;
  assert.match(render(f),/共享城市交通参考待核/);
}
for(const [kind,value,unit,label] of [['reported_minutes',90,'minute','90分钟（报道参考）'],['nominal_hour',2,'hour','2小时（名义值）'],['nominal_half_hour',3,'half_hour','1.5小时（名义半小时参考）']]) {
  const f=copy(sharedFit),s=f.planning_reference.shared_reference;s.precision=kind;s.duration={kind,value,unit};assert(render(f).includes(label));
}
const injected=copy(sharedFit);Object.assign(injected.planning_reference.shared_reference,{endpoint_a:'<img src=x onerror=alert(1)>',endpoint_b:'<script>x</script>',source_url:'javascript:alert(1)',source_published_on:'<b>x</b>',reviewed_on:'" onmouseover="x',review_due_on:'<svg/onload=x>'});
const injectedHtml=render(injected);for(const raw of ['<img','<script','javascript:','<b>x</b>','<svg','="x"'])assert(!injectedHtml.includes(raw));
assert.match(injectedHtml,/&lt;img/);assert.match(injectedHtml,/&lt;script/);assert.match(injectedHtml,/&lt;svg/);
for(const url of ['https://example.test/" onclick="x','https://example.test/<svg>','data:text/html,x']){const f=copy(sharedFit);f.planning_reference.shared_reference.source_url=url;assert(!render(f).includes('href='));}
console.log('City planning UI: all assertions passed');
