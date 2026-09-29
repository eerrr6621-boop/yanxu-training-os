// Pure rendering: no browser, network, model or live people. Actual JSON is opt-in.
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
function renderer(path) {const s=fs.readFileSync(path,'utf8'),a=s.indexOf('  function railVerificationMarkup('),b=s.indexOf('  function adjacentScheduleMarkup(',a);assert(a>=0&&b>a);return vm.runInNewContext(`(${s.slice(a,b).trim()})`,{esc,icon:()=>''});}
const render=renderer('web/app.js'), copy=x=>JSON.parse(JSON.stringify(x));let checks=0;
const check=(b,label)=>{checks++;assert(b,label);};
function fixture(kind='approximate',value=90,unit='minute',qualifier='reported_fastest') {
  const from={id:'C001',city:'测试甲'},to={id:'C002',city:'测试乙'};
  const leg=(a,b,precision,source_document)=>({from:a,to:b,from_endpoint:a.city,to_endpoint:b.city,endpoint_scope:{from:'city_summary',to:'city_summary'},scope:'city_summary',precision,source_document,source_url:'https://example.invalid/source',source_published_on:'2026-07-07',reviewed_on:'2026-09-08',review_due_on:'2026-12-07'});
  const shown=(a,b,kind,value,unit,source_qualifier,doc)=>({from_registry_id:a.id,to_registry_id:b.id,source_document:doc,annotation_id:'SYNTHETIC',source_qualifier,precision:kind,duration:{kind,value,unit}});
  const minutes=value*(unit==='hour'?60:unit==='half_hour'?30:1);
  const planning={status:'planning_reference',purpose:'prebid_city_planning_only',planning_band_id:'planning_1_2h',transport_verified:false,time_score_applicable:false,air_fallback_trigger:false,rail_exclusion_complete:false,
    outbound:leg(from,to,kind,'source-a'),inbound:leg(to,from,'nominal_hour','source-b'),selection_reference:{basis:'unbuffered_city_reference_v1',buffer_applied:false,confirmed_itinerary:false,outbound:{reference_minutes:minutes,precision:kind,unit:'minute',upper_exclusive:false},inbound:{reference_minutes:60,precision:'nominal_hour',unit:'minute',upper_exclusive:false}},
    presentation:{version:'city-planning-presentation-v1',purpose:'presentation_only',affects_selection:false,status:'ready',revision:1,outbound:shown(from,to,kind,value,unit,qualifier,'source-a'),inbound:shown(to,from,'nominal_hour',1,'hour','general_reported','source-b')}};
  return {planning_included:true,planning_reference:planning,route_reference:{status:'rail_reference_pending',eligibility:'unknown'}};
}
const first=fixture(),html=render(first);check(html.includes('报道最快约90分钟'),'fastest approximate kept together');check(html.includes('返程：报道用时1小时（小时级参考）'),'independent nominal return');
check(html.includes('城市概述，未指定站点'),'city scope retained');check(html.includes('实际出行待核')&&html.includes('市内接驳另行确认'),'concise caveat');
const card=html.split('<details>')[0],details=html.split('<details>')[1];for(const text of ['2026-07-07','2026-09-08','2026-12-07','查看参考来源']){check(details.includes(text),'dates/source in details');check(!card.includes(text),'not stacked in default card');}
for(const text of ['原精度','严格上界','缓冲','能力赋分','哈希','单值'])check(!card.includes(text),'no engineering prose in card');
function rejected(edit,label){const f=copy(first);edit(f);const h=render(f);check(h.includes('时长说明待核'),label);check(!h.includes('报道最快约90分钟'),label+' no bare fallback time');}
for(const value of ['__proto__','exact','average'])rejected(f=>f.planning_reference.presentation.outbound.source_qualifier=value,'unknown qualifier');
for(const key of ['version','status','purpose'])rejected(f=>f.planning_reference.presentation[key]='unknown','unknown view '+key);
rejected(f=>f.planning_reference.presentation.affects_selection=true,'no decision annotation');
for(const key of ['from_registry_id','to_registry_id','source_document','precision'])rejected(f=>f.planning_reference.presentation.outbound[key]='wrong','wrong direction binding '+key);
for(const value of [0,-1,90.5,'90',240,241,9007199254740992])rejected(f=>f.planning_reference.presentation.outbound.duration.value=value,'malformed or mismatched value');
rejected(f=>f.planning_reference.presentation.outbound.duration.extra='x','extra duration field');
rejected(f=>f.planning_reference.selection_reference.outbound.reference_minutes=91,'cannot replace selection number');
rejected(f=>f.planning_reference.selection_reference.outbound.upper_exclusive=true,'approx not strict exclusive bound');
rejected(f=>f.planning_reference.outbound.endpoint_scope.from='county','unknown scope closed');
rejected(f=>delete f.planning_reference.outbound.reviewed_on,'date missing closed');
for(const [kind,value,unit,expected] of [['reported_minute',35,'minute','报道最快35分钟'],['reported_minutes',36,'minute','报道最快36分钟'],['nominal_hour',1,'hour','报道最快1小时（小时级参考）'],['nominal_half_hour',5,'half_hour','报道最快2.5小时（半小时级参考）'],['same_service_clocks',35,'minute','公告时刻差参考35分钟'],['computed_same_service',39,'minute','公告时刻差参考39分钟']])check(render(fixture(kind,value,unit,kind.includes('service')?'general_reported':'reported_fastest')).includes(expected),'precision '+kind);
const unknown=render(fixture('reported_minutes',80,'minute','unspecified'));check(unknown.includes('资料未单独标注是否最快'),'unknown explicit');check(!unknown.includes('报道最快'),'unknown never fastest');
const approx=render(fixture('approximate',40,'minute','general_reported'));check(approx.includes('报道用时约40分钟')&&!approx.includes('报道最快'),'ordinary approx not fastest');
for(const [kind,duration,label] of [['upper_bound',{kind:'upper_bound',upper:240,upper_inclusive:false,unit:'minute'},'不到240分钟'],['bounded_range',{kind:'bounded_range',lower:60,upper:90,lower_inclusive:false,upper_inclusive:true,unit:'minute'},'超过60至90分钟']]){
  const f=fixture(kind,1,'minute','general_reported'),p=f.planning_reference;p.outbound.precision=kind;p.presentation.outbound.precision=kind;p.presentation.outbound.duration=duration;p.selection_reference.outbound={reference_minutes:duration.upper,precision:kind,unit:'minute',upper_exclusive:!duration.upper_inclusive};check(render(f).includes(label),'bounds unchanged '+kind);
}
const malicious=copy(first),p=malicious.planning_reference;p.outbound.from_endpoint='<img src=x onerror=alert(1)>';p.outbound.to_endpoint='<script>x</script>';p.outbound.source_url='javascript:alert(1)';p.outbound.source_published_on='<svg/onload=x>';const escaped=render(malicious);for(const raw of ['<img','<script','javascript:','<svg'])check(!escaped.includes(raw),'escaped '+raw);check(escaped.includes('&lt;img'),'escaped endpoint visible');
const sub=copy(first);sub.planning_reference.outbound.from_endpoint='北京通州站';sub.planning_reference.outbound.endpoint_scope.from='urban_subcentre_station';check(render(sub).includes('城市副中心站点，非市中心耗时'),'subcentre retained');
if(process.argv.length>2){
  assert.equal(process.argv.length,4,'Usage: node test_city_presentation_ui.cjs actual-result.json app.before.js');const actual=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),before=renderer(process.argv[3]);let directions=0,shared=0;
  for(const saved of actual.planning_outputs){const reference=saved.reference,fit={planning_included:true,planning_reference:reference,route_reference:{status:'rail_reference_pending',eligibility:'unknown'}};
    if(saved.shared){Object.assign(fit,{planning_policy:'explicit_roundtrip_shared_planning_v1',planning_reference_kind:'explicit_roundtrip_shared_v1',shared_planning_band_id:'planning_1_2h'});check(render(fit)===before(fit),'real shared markup unchanged');shared++;continue;}
    const old=copy(fit);delete old.planning_reference.presentation;check(render(old)===before(old),'real old markup byte-for-byte');const h=render(fit);check(!h.includes('时长说明待核'),'real v1 markup ready');check(h.includes(esc(reference.outbound.from_endpoint))&&h.includes(esc(reference.outbound.to_endpoint)),'real original endpoints');check(h.includes(reference.outbound.source_published_on)&&h.includes(reference.inbound.source_published_on),'each real source date');directions++;
  }check(directions===36&&shared===1,'all actual directions and shared rendered');
}
console.log(`City planning presentation UI: ${checks} assertions passed; pure rendering only`);
