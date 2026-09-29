'use strict';
// Render actual outputs of the synthetic Java protected-envelope test. No browser,
// network, live people or fabricated source admissions. Legacy JSON is opt-in.
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
function renderer(file){const src=fs.readFileSync(file,'utf8'),a=src.indexOf('  function railVerificationMarkup('),b=src.indexOf('  function adjacentScheduleMarkup(',a);assert(a>=0&&b>a);return vm.runInNewContext(`(${src.slice(a,b).trim()})`,{esc:escape,icon:()=>''});}
assert([3,5].includes(process.argv.length),'Usage: node test_ui_sample_dates.cjs synthetic-java-output.json [legacy-output.json app.before.js]');
const data=JSON.parse(fs.readFileSync(process.argv[2],'utf8')), render=renderer('web/app.js');
assert.equal(data.schema,'synthetic_ui_date_rendering_v1');assert.equal(data.synthetic_only,true);
assert.equal(data.samples.length,data.samples.every(s=>s.kind==='popup_table')?4:8);
const copy=x=>JSON.parse(JSON.stringify(x));let checks=0;
const check=(condition,why)=>{checks++;assert(condition,why);};
const day=(date,delta)=>new Date(Date.parse(date+'T00:00:00Z')+delta*86400000).toISOString().slice(0,10);
function pending(sample,change,why){const f=copy(sample.fit);change(f.planning_reference);const html=render(f);check(html.includes('时长说明待核'),why);check(!html.includes('：高铁参考时长'),why+' cannot expose a bare duration');}
for(const sample of data.samples){
  const p=sample.fit.planning_reference, html=render(sample.fit);
  const unchanged=JSON.stringify(sample.fit);
  check(!html.includes('时长说明待核')&&html.includes('：高铁参考时长'),sample.kind+' backend output renders');
  const [card,details]=html.split('<details>');check(!!details,'evidence is collapsible');
  for(const side of ['outbound','inbound']){
    const leg=p[side];check(!/查询日|乘车日期|查询\/服务日|未提供发布日期|复核到期/.test(html),'raw research dates hidden throughout ordinary UI');
    check(details.includes('资料更新于 '+leg.reviewed_on),'only reader-friendly updated date shown');
    check(!card.includes(leg.source_query_date)&&!card.includes(leg.source_service_date),'date metadata stays out of main card');
    check(html.includes(escape(leg.from_endpoint))&&html.includes(escape(leg.to_endpoint)),'original station endpoints retained');
  }
  check(html.includes('不代表最快、典型用时或未来行程保证'),'honest source scope');
  check(JSON.stringify(sample.fit)===unchanged,'rendering preserves backend audit dates and all data');
  pending(sample,p=>p.outbound.source_date_basis='unknown','unrecognized date version');
  pending(sample,p=>p.outbound.source_service_date=day(p.outbound.source_query_date,-1),'service date before observation');
  pending(sample,p=>p.outbound.source_service_date=day(p.outbound.source_query_date,15),'beyond supported capture window');
  pending(sample,p=>p.outbound.source_query_date='2026-02-30','invalid calendar date');
  pending(sample,p=>p.outbound.source_query_date=null,'missing query date');
  pending(sample,p=>p.outbound.reviewed_on=day(p.outbound.source_query_date,-1),'review before observation');
  pending(sample,p=>p.outbound.review_due_on=day(p.outbound.source_query_date,91),'review cannot extend observation age');
  pending(sample,p=>p.outbound.source_published_on=p.outbound.source_query_date,'publication cannot be fabricated');
  pending(sample,p=>p.outbound.source_url='https://kyfw.12306.cn.evil.invalid/otn/leftTicket/init','source host suffix attack');
  pending(sample,p=>p.outbound.source_url='javascript:alert(1)','unsafe source protocol');
  pending(sample,p=>p.outbound.endpoint_scope.from='county','county scope cannot be broadened');
  pending(sample,p=>p.outbound.source_duration.calculated_minutes++,'source time mismatch');
  pending(sample,p=>p.protected_envelope_validated=false,'unvalidated source envelope');
  for(const flag of ['transport_verified','air_fallback_trigger','rail_exclusion_complete'])pending(sample,p=>p[flag]=true,'unsupported stronger claim '+flag);
  if(sample.days>0)pending(sample,p=>p.outbound.source_date_basis='query_service_day_v1','legacy version still requires equal dates');
  if(['stop_table','popup_table'].includes(sample.kind)){
    pending(sample,p=>p.outbound.source_type='official_public_rail_ui_sample','stop cells cannot masquerade as results-row source');
    pending(sample,p=>p.outbound.query_display_equivalence_claimed=true,'no fabricated query/display train alias');
    pending(sample,p=>p.outbound.displayed_service_id='K123','stop-table train type retained');
    if(p.presentation)pending(sample,p=>p.presentation.outbound.date_basis.query_date=day(p.outbound.source_query_date,-1),'presentation date must match validated source');
    if(sample.kind==='popup_table')pending(sample,p=>p.outbound.query_service_id='G999','popup cannot alias a different queried train');
  }
}
if(process.argv.length===5){
  const prior=renderer(process.argv[4]),legacy=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));assert.equal(legacy.length,100);
  for(const row of legacy){const fit={planning_included:true,planning_reference:row.result,route_reference:{status:'rail_reference_pending',eligibility:'unknown'}};
    const old=prior(fit),now=render(fit);
    if(old.includes('：页面样本')){check(!/查询日|乘车日期|查询\/服务日/.test(now),'legacy UI samples also hide research dates');}
    else check(now===old,'unrelated legacy markup byte-identical');
  }
  const sample=copy(data.samples.find(s=>s.kind==='named'&&s.days===0).fit);
  for(const side of ['outbound','inbound'])sample.planning_reference[side].source_date_basis='query_service_day_v1';
  check(!/查询日|乘车日期|查询\/服务日/.test(render(sample))&&render(sample).includes('：高铁参考时长'),'legacy same-day sample uses simplified user wording');
}
const direct={route_reference:{status:'rail_time_reference',outbound:{from_station:'甲站',to_station:'乙站',train_no:'G999',rail_minutes:60,departure_time:'08:00',arrival_time:'09:00',source_service_date:'2026-09-10',observed_on:'2026-09-09',source_url:'https://kyfw.12306.cn/otn/leftTicket/init'}}};
const directBefore=JSON.stringify(direct),directHtml=render(direct);
check(directHtml.includes('高铁参考时长 60 分钟')&&!/2026-09-10|G999|08:00|09:00|网页查询/.test(directHtml),'legacy rail reference hides sampled itinerary');
check(JSON.stringify(direct)===directBefore,'hiding sampled itinerary never deletes backend metadata');
console.log(`UI sample dates: ${checks} checks PASS; synthetic Java-to-renderer integration, no production change.`);
