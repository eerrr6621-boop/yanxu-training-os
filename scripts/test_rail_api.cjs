'use strict';
// Fresh loopback-only DB, fabricated teachers. Real public railway facts, no business data.
const assert=require('node:assert/strict');
const target=new URL(process.env.TRAINING_API_BASE || 'invalid:');
assert.ok(['http:','https:'].includes(target.protocol)&&['127.0.0.1','localhost','[::1]'].includes(target.hostname)&&target.port&&target.port!=='8081'&&target.pathname==='/api');
const BASE=target.origin+'/api';let token='',checks=0;
async function api(path,body) {
  const r=await fetch(BASE+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-Token':token}:{})},body:body===undefined?undefined:JSON.stringify(body)}).then(r=>r.json());
  assert.equal(r.code,0,path+': '+r.msg);return r.data;
}
function check(ok,label){assert.ok(ok,label);checks++;console.log('PASS',label);}
(async()=>{
  token=(await api('/login',{username:'admin',password:'admin123'})).token;
  const original=await api('/teachers');
  assert.ok(!original.some(t=>String(t.name).includes('【灰度测试】')),'Existing gray data: refuse to reuse this database');
  const beforeDemands=(await api('/demands')).length;
  const catalog=await api('/teacher-resumes/dispatch-priorities?province=安徽&city=合肥&date=2099-01-01');
  const nanjing=catalog.priorities.find(c=>c.city==='南京');
  const date=new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});
  if(date<'2026-09-07'||date>'2026-10-07') {
    check(!nanjing,'Outside snapshot freshness: no timeless railway guarantee');
    console.log('Rail snapshot expired/not yet observed; fixed audit-day behavior covered by RailTimetableTest');return;
  }
  check(nanjing?.outbound_reference_minutes===48&&nanjing?.return_reference_minutes===50,'API loads actual independently sourced Nanjing/Hefei minutes');
  check(nanjing.priority_score===85&&catalog.coverage==='partial','Actual route gives two-hour initial priority band without claiming full network');
  check(nanjing.travel_date_verified===false&&nanjing.transport_verified===false&&!('upper_minutes' in nanjing),'Future course date is not promoted to verified door-to-door availability');
  check(nanjing.outbound.source_url.startsWith('https://trains.ctrip.com/')&&!JSON.stringify(catalog).includes('/Users/'),'Source links returned, private filesystem paths excluded');
  const ids=[];const levels=['讲师','高级讲师','特级讲师','特聘讲师'];
  for(let i=0;i<4;i++)ids.push(await api('/teachers',{name:'【灰度测试】高铁排序'+i,org:'【灰度测试】虚构机构',field:'主讲课程：银行客户服务',intro:'专业领域：银行客户服务。主讲银行客户服务课程。',fee_rate:1000,status:'在库',in_date:'2026-09-07',base_province:'江苏',base_city:'南京',teacher_level:levels[i]}));
  const requirement={requirement:'培训主题：银行客户服务',training_mode:'线下',training_province:'安徽',training_city:'合肥',training_period:'下午',prefer_local:true,max_results:3};
  const result=await api('/teacher-recommendations',requirement);
  const selected=result.recommendations.filter(t=>ids.includes(t.teacher_id));
  check(selected.length===4,'All third-place score ties retained from actual railway catchment');
  check(selected.map(t=>t.teacher_id).join(',')===ids.slice().reverse().join(','),'Same content score uses human teacher level descending');
  check(selected.every(t=>t.dispatch_fit.route_reference.status==='rail_time_reference'&&t.dispatch_fit.priority_score===85),'Teacher recommendation path consumes the public timetable, not just catalog display');
  check(result.analysis.dispatch_preferences.selection.expanded_rail_reference_minutes===240,'Recommendation response exposes full sub-four-hour rail pool');
  if(process.env.TRAINING_EXPECT_LOCAL_SEMANTIC==='1') {
    check(result.analysis.semantic_matching.status==='ready'&&selected.every(t=>Number.isFinite(t.model_score)),'Real offline INT8 model scores the sourced nearest-city pool');
    check(selected.every(t=>t.model_score===selected[0].model_score),'Equal teaching statements produce equal model scores independent of teacher level');
  }
  const self=await api('/teacher-recommendations',{...requirement,training_province:'江苏',training_city:'南京'});
  check(self.recommendations.filter(t=>ids.includes(t.teacher_id)).every(t=>t.dispatch_fit.route_reference.status==='same_city'),'Same-city API response never asks for cross-city train verification');
  check((await api('/demands')).length===beforeDemands,'Recommendations do not auto-create a bid/project demand');
  console.log('Public rail snapshot + recommendation API: '+checks+' passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
