'use strict';
// Own fresh loopback process and temporary DB only. Synthetic PDF, no real data.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),vm=require('node:vm');
const net=require('node:net'),crypto=require('node:crypto'),{spawn}=require('node:child_process'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'..'),options=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>i%2?a:[...a,[v,all[i+1]]],[]));
assert.ok(options['--java'] && options['--classes'],'Explicit --java and --classes required');
const fixtures=fs.readFileSync(path.join(repo,'test_faculty.js'),'utf8');
const pdf=vm.runInNewContext(fixtures.slice(fixtures.indexOf('function pdf('),fixtures.indexOf('async function upload('))+'\npdf;',{Buffer});
const draftRoot=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-draft-http-'));
const password=crypto.randomBytes(24).toString('hex');let child,token='',checks=0;
const check=(ok,label)=>{assert.ok(ok,label);checks++;};
(async()=>{
  const server=net.createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const port=server.address().port;await new Promise(resolve=>server.close(resolve));
  const env={...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''};
  child=spawn(options['--java'],['-Dbind.address=127.0.0.1','-Dbootstrap.admin.password='+password,'-Ddata.dir='+path.join(draftRoot,'data'),
    '-Dsemantic.port=','-Dsemantic.token=','-cp',options['--classes']+':lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar','com.training.Main',String(port)],{cwd:repo,env,stdio:'ignore'});
  const base=`http://127.0.0.1:${port}/api`;
  async function request(route,body){const r=await fetch(base+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-Token':token}:{})},body:body===undefined?undefined:JSON.stringify(body)});return r.json();}
  async function api(route,body){const r=await request(route,body);assert.equal(r.code,0,r.msg);return r.data;}
  for(let i=0;i<100;i++) {
    assert.equal(child.exitCode,null,'Owned isolated server exited');
    try{token=(await api('/login',{username:'admin',password})).token;break;}catch(error){await new Promise(r=>setTimeout(r,100));}
  }
  assert.ok(token,'Isolated login failed');assert.equal((await api('/teachers')).length,0,'Fresh DB required');
  const id=await api('/teachers',{name:'【灰度测试】待完善讲师',status:'待完善',base_province:'浙江',base_city:'',field:'服务礼仪',fee_rate:null});
  let row=(await api('/teachers')).find(r=>r.id===id);
  check(row.status==='待完善' && row.base_province==='浙江' && !row.base_city,'Province-only draft persists');
  check(row.fee_rate===null && row.teacher_level===null,'SQL round trip preserves unknown fee and grade as null');
  check((await request('/teachers/checkin',{id})).code!==0,'Cannot activate before city completion');
  check((await request('/teachers',{name:'【灰度测试】普通档案',status:'在库'})).code!==0,'Non-draft remains strict');
  const bytes=pdf('Synthetic instructor profile. This fixture is used only for isolated API tests. I teach customer service and communication courses.');
  const uploaded=await fetch(base+`/teacher-resumes/upload?teacher_id=${id}`,{method:'POST',headers:{'X-Token':token,'X-Resume-Name':Buffer.from('synthetic-draft.pdf').toString('base64url'),'Content-Type':'application/pdf'},body:bytes}).then(r=>r.json());
  check(uploaded.code===0,'Draft accepts synthetic resume upload without guessed city');
  const profile=await api(`/teacher-resumes/profile?teacher_id=${id}`);
  check(profile.parse_status==='ready','Synthetic draft resume parsed');
  await api('/teacher-resumes/profile',{teacher_id:id,manual_profile:'主讲服务礼仪课程，包含客户接待与沟通练习。'});
  check(true,'Draft manual profile can be reviewed before city is known');
  const recommendations=await api('/teacher-recommendations',{requirement:'培训主题：服务礼仪',training_mode:'线上',max_results:3});
  check(recommendations.total_in_library===0 && !recommendations.recommendations.some(r=>r.teacher_id===id),'Draft is excluded from actual recommendation query');
  await api('/teachers/residence',{id,base_province:'浙江',base_city:'杭州'});
  row=(await api('/teachers')).find(r=>r.id===id);
  check(row.status==='待完善','Filling residence does not silently activate draft');
  await api('/teachers/checkin',{id});row=(await api('/teachers')).find(r=>r.id===id);
  check(row.status==='在库' && row.base_city==='杭州','Explicit validated check-in completes draft');
  check(row.fee_rate===null && row.teacher_level===null,'Check-in does not invent fee or grade');
  check((await request('/teacher-resumes/profile',{teacher_id:id,manual_profile:'主讲服务礼仪。',base_province:'',base_city:''})).code!==0,'In-library profile update cannot erase residence');
  check((await request('/teachers',{id,status:'待完善',base_province:'',base_city:''})).code!==0,'Cannot impersonate draft to erase real location');
  await api('/teachers',{id,fee_rate:2300,teacher_level:'高级讲师'});
  await api('/teachers',{id,intro:'补充已确认的专业资料'});
  row=(await api('/teachers')).find(r=>r.id===id);
  check(row.fee_rate===2300 && row.teacher_level==='高级讲师' && row.base_city==='杭州','Partial update retains confirmed values');
  console.log(JSON.stringify({ok:true,suite:'teacher-draft-http',checks,production_touched:false,real_resumes_used:false,isolated_artifacts:draftRoot}));
})().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(async()=>{
  if(child && child.exitCode===null){child.kill('SIGTERM');await new Promise(resolve=>{child.once('exit',resolve);setTimeout(()=>{if(child.exitCode===null)child.kill('SIGKILL');resolve();},2000).unref();});}
});
