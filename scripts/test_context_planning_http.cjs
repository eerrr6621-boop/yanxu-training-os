'use strict';
// Actual recommend route; synthetic teachers in separate fresh H2 databases, semantic service OFF.
const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto');
const {spawn}=require('node:child_process'),assert=require('node:assert/strict');
const opt=Object.fromEntries(process.argv.slice(2).reduce((r,v,i,a)=>i%2?r:[...r,[v,a[i+1]]],[]));
for(const k of ['--java','--classes','--before-classes','--workspace','--config-result','--out'])assert.ok(opt[k],k+' required');
const repo=path.resolve(opt['--repo']||path.join(__dirname,'..')),base=path.resolve(opt['--workspace']),out=path.resolve(opt['--out']);
fs.mkdirSync(out);let checks=0,child=null;const observed={},runs=[];
function check(ok,label){checks++;assert.ok(ok,label);}
function equal(a,b,label){checks++;assert.deepEqual(a,b,label);}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const receipt=JSON.parse(fs.readFileSync(opt['--config-result'],'utf8'));
assert.equal(sha(receipt.config_file),receipt.config_sha256,'Config receipt pinned');
const config=JSON.parse(fs.readFileSync(receipt.config_file,'utf8'));
const oldBaseline=path.join(base,'.codex-tmp/yanxu-planning19-shared1.uShL9f/actual-collection-result.json');
assert.equal(sha(oldBaseline),'73e217decbd8a1f34ef483f2338ad1dd896325994729b771929251339393f47a');
const baseline=JSON.parse(fs.readFileSync(oldBaseline,'utf8')),pins={...baseline.input_pins};
for(const p of [oldBaseline,baseline.collection_file,baseline.collection_file+'.integrity.json',baseline.sidecar_file,baseline.sidecar_file+'.integrity.json',receipt.config_file])pins[p]=sha(p);
for(const p of receipt.original_input_pins){assert.equal(sha(p.path),p.sha256);pins[p.path]=p.sha256;}
const strict=path.join(base,'.codex-tmp/yanxu-jinan-natural-clock.njabjQz8/prepared-v2/public-city-nineteen-merged.private.json');
const cfgProp='dispatch.city.planning.context.config.file',shaProp='dispatch.city.planning.context.config.sha256';
const invalid=structuredClone(config);invalid.current_baseline_v2.anchor_sha256='0'.repeat(64);
const invalidFile=path.join(out,'FAULT-component.private.json');fs.writeFileSync(invalidFile,JSON.stringify(invalid)+'\n',{flag:'wx',mode:0o600});
const places=[['北京','北京'],['天津','天津'],['河北','唐山'],['河北','承德'],['辽宁','沈阳'],['辽宁','抚顺'],['湖北','武汉'],['湖北','宜昌'],['河南','郑州'],['安徽','合肥'],['四川','眉山']];
const targets={beijing:['北京','北京'],shenyang:['辽宁','沈阳'],wuhan:['湖北','武汉']};
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
assert.ok(today>='2026-09-08'&&today<='2026-10-31','Real positive HTTP assertions require currently fresh sources; expiry uses pure explicit-date tests');
async function stop(){if(!child)return;const active=child;child=null;if(active.exitCode!==null||active.signalCode!==null)return;
  await new Promise(resolve=>{let timer;active.once('exit',()=>{clearTimeout(timer);resolve();});active.kill('SIGTERM');timer=setTimeout(()=>active.kill('SIGKILL'),2000);});}
function selection(r){return r.analysis.dispatch_preferences.selection;}
function rows(r){return [...r.recommendations,...selection(r).travel_pending,...selection(r).scoring_pending];}
function byCity(r,city){return rows(r).find(t=>t.base_city===city);}
function withoutSummary(r){const copy=structuredClone(r);for(const k of ['analysis','recognized_requirement'])delete copy[k].dispatch_preferences.selection.context_configuration;return copy;}
async function scenario(name,settings,classes){
  const own=path.join(out,name);fs.mkdirSync(own);const password=crypto.randomBytes(24).toString('hex');let token='';
  const server=net.createServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  const port=server.address().port;await new Promise(r=>server.close(r));
  const props={'bind.address':'127.0.0.1','bootstrap.admin.password':password,'bootstrap.demo':'false','data.dir':path.join(own,'data'),
    'semantic.port':'','semantic.token':'','dispatch.timetable.file':'','dispatch.transport.file':'','dispatch.transport.dir':'','dispatch.routes.file':'','dispatch.organizations.file':'',
    'dispatch.city.references.file':'','dispatch.city.references.dir':'','dispatch.public.city.references.file':strict,'dispatch.public.city.references.dir':'',
    'dispatch.city.planning.references.file':'','dispatch.city.planning.collection.file':baseline.collection_file,
    'dispatch.city.planning.presentation.version':'city-planning-presentation-v2','dispatch.city.planning.presentation.file':baseline.sidecar_file,
    'dispatch.nearby.max.minutes':'240',[cfgProp]:'',[shaProp]:'',...settings};
  const log=fs.openSync(path.join(own,'server.log'),'wx',0o600);
  child=spawn(opt['--java'],[...Object.entries(props).map(([k,v])=>'-D'+k+'='+v),'-cp',classes+':lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar','com.training.Main',String(port)],
    {cwd:repo,env:{...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:'',JAVA_TOOL_OPTIONS:'',JDK_JAVA_OPTIONS:''},stdio:['ignore',log,log]});fs.closeSync(log);
  async function request(route,body){const response=await fetch(`http://127.0.0.1:${port}/api`+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-Token':token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});const json=await response.json();assert.equal(json.code,0,json.msg);return json.data;}
  try{
    for(let i=0;i<100;i++){if(child.exitCode!==null)throw Error('Isolated process exited; '+name);try{token=(await request('/login',{username:'admin',password})).token;break;}catch{await new Promise(r=>setTimeout(r,100));}}
    assert.ok(token,'Isolated login required');equal(await request('/teachers'),[],'Fresh empty database: '+name);
    for(const [province,city]of places)await request('/teachers',{name:'【灰度测试】'+city+'文控',field:'文档编号复核',intro:'本人主讲文档编号复核课程。',status:'在库',fee_rate:500,teacher_level:'讲师',base_province:province,base_city:city});
    const result={};
    for(const [target,[province,city]]of Object.entries(targets)){
      const body={requirement:'培训主题：文档编号复核',training_mode:'线下',training_province:province,training_city:city,training_period:'下午',prefer_local:true,max_results:3};
      const normal=await request('/teacher-recommendations',body),injected=await request('/teacher-recommendations',{...body,
        [cfgProp]:receipt.config_file,[shaProp]:receipt.config_sha256,context_config:config,context_catalog:{valid:true},as_of:'2099-01-01',referenceDay:'2025-01-01',
        planning_policy:'context_shared_prebid_opt_in_v2',planning_included:true,context_planning_reference:{shared_observation:{reference_minutes:1}},context_configuration:{status:'ready'}});
      equal(injected,normal,'Body cannot configure pins/date/success: '+name+'/'+target);
      check(normal.analysis.semantic_matching.status==='disabled'&&selection(normal).scoring_mode==='unscored_evidence_fallback','Honest disabled-model fallback: '+name+'/'+target);
      check(!JSON.stringify(normal).includes('/Users/')&&!JSON.stringify(normal).includes(receipt.config_sha256),'No filesystem or config-pin disclosure: '+name+'/'+target);
      check(selection(normal).air_candidates.length===0,'No fabricated air eligibility: '+name+'/'+target);
      check(byCity(normal,'眉山')&&!byCity(normal,'眉山').dispatch_fit.context_planning_reference,'Unknown city stays pending: '+name+'/'+target);
      const state=selection(normal).context_configuration;
      if(name==='before-off'||name==='current-off')check(state===undefined,'Default adds no configuration output: '+name);
      else check(state&&state.as_of===today&&state.status===(name==='valid'||name==='old-hard-failure'?'ready':'pending'),'Explicit configuration state/date: '+name);
      result[target]=normal;
    }
    const online=await request('/teacher-recommendations',{requirement:'培训主题：文档编号复核',training_mode:'线上',max_results:3,[cfgProp]:receipt.config_file,[shaProp]:receipt.config_sha256});
    check(!selection(online).context_configuration,'Inactive preference does not enable configured local path: '+name);
    check((await request('/demands')).length===0,'Recommendation created no demand: '+name);
    fs.writeFileSync(path.join(own,'responses.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx',mode:0o600});observed[name]=result;runs.push({name,responses:path.join(own,'responses.json')});
  }finally{await stop();}
}
(async()=>{
  // Deliberately wait until a controlled child has already exited by signal: stop must not wait for a past event.
  child=spawn(process.execPath,['-e','process.kill(process.pid,"SIGTERM")'],{stdio:'ignore'});
  await new Promise((resolve,reject)=>{child.once('exit',resolve);child.once('error',reject);});
  check(child.exitCode===null&&child.signalCode==='SIGTERM','Controlled child has already exited by signal');
  let cleanupTimer;try{await Promise.race([stop(),new Promise((_,reject)=>{cleanupTimer=setTimeout(()=>reject(Error('Cleanup waited for an already delivered exit event')),500);})]);}finally{clearTimeout(cleanupTimer);}
  check(child===null,'Signal-ended child cleanup completes without waiting again');
  const on={[cfgProp]:receipt.config_file,[shaProp]:receipt.config_sha256};
  await scenario('before-off',{},opt['--before-classes']+':'+opt['--classes']);
  await scenario('current-off',{},opt['--classes']);equal(observed['current-off'],observed['before-off'],'Full real recommend default output equals compiled exact before source');
  await scenario('valid',on,opt['--classes']);await scenario('partial',{[cfgProp]:receipt.config_file},opt['--classes']);
  await scenario('bad-hash',{...on,[shaProp]:'0'.repeat(64)},opt['--classes']);
  await scenario('bad-component',{[cfgProp]:invalidFile,[shaProp]:sha(invalidFile)},opt['--classes']);
  await scenario('old-hard-failure',{...on,'dispatch.city.planning.references.file':path.join(out,'not-a-source.json')},opt['--classes']);
  for(const target of Object.keys(targets)){
    const off=observed['current-off'][target],valid=observed.valid[target];
    for(const name of ['partial','bad-hash','bad-component']){
      const bad=observed[name][target];equal(bad.recommendations,off.recommendations,'Bad additive config preserves all old selected output: '+name+'/'+target);
      for(const teacher of rows(bad))check(!teacher.dispatch_fit.context_planning_reference,'Invalid component has no positive context: '+name);
    }
    for(const old of off.recommendations){const newer=valid.recommendations.find(t=>t.teacher_id===old.teacher_id);check(Boolean(newer),'Old nearby candidate still present: '+target);const a=structuredClone(old),b=structuredClone(newer);delete a.rank;delete b.rank;equal(a,b,'Old candidate full fields unchanged except pool rank: '+target);}
    check(selection(valid).pool_size===(target==='shenyang'?2:4),'All nearby candidates in one actual recommend pool: '+target);
    check(valid.recommendations.length===selection(valid).pool_size,'Fallback equal evidence cutoff ties retained: '+target);
    const city=target==='beijing'?'承德':target==='shenyang'?'抚顺':'合肥',teacher=byCity(valid,city),fit=teacher.dispatch_fit,ref=fit.context_planning_reference;
    check(valid.recommendations.includes(teacher)&&ref,'Actual context route serialized: '+target);
    equal(ref.stations,[null,null],'Unknown stations remain unknown: '+target);check(!('outbound'in ref)&&!('inbound'in ref)&&ref.independent_direction_observations===0,'One shared observation: '+target);
    equal(ref.shared_observation.precision,target==='beijing'?'reported_minutes':target==='shenyang'?'approximate':'nominal_hour','Original precision: '+target);
    equal(ref.shared_observation.reference_minutes,target==='beijing'?45:target==='shenyang'?20:120,'Original shared scalar: '+target);
    equal(ref.source_published_on,target==='beijing'?'2026-01-24':target==='shenyang'?'2025-10-31':'2026-06-03','Original source date: '+target);
    check(ref.source_url.startsWith('https://')&&fit.context_planning_display.notice.includes('实际出行待核')&&fit.context_planning_display.endpoint_label.includes('未指定车站'),'Source and honest display: '+target);
    check(ref.air_fallback_trigger===false&&ref.rail_exclusion_complete===false&&ref.transport_verified===false,'No stronger transport capability: '+target);
    const hard=observed['old-hard-failure'][target],blocked=byCity(hard,city);check(blocked&&!blocked.dispatch_fit.context_planning_reference&&blocked.dispatch_fit.planning_reference.reason==='planning_configuration_conflict','Old hard failure not bypassed: '+target);
  }
  check(observed['old-hard-failure'].beijing.recommendations.some(t=>t.base_city==='天津'),'Independent strict route survives planning configuration conflict');
  check(observed['old-hard-failure'].wuhan.recommendations.some(t=>t.base_city==='宜昌'),'Other independent strict route survives');
  for(const [p,h]of Object.entries(pins))equal(sha(p),h,'Original input remains pinned');
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'PASS_isolated_recommend_fallback_not_model',checks,runs,as_of:today,config_file:receipt.config_file,config_sha256:receipt.config_sha256,
    original_input_pins:pins,real_profiles_used:false,real_model_called:false,production_changed:false,default_config_changed:false,collection_added_pairs:0},null,2)+'\n',{flag:'wx',mode:0o600});
  console.log('Context planning HTTP: '+checks+' checks PASS; isolated recommend, semantic disabled, production untouched');
})().catch(error=>{fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({error:error.stack,checks,runs},null,2)+'\n',{flag:'wx',mode:0o600});console.error(error.stack);process.exitCode=1;}).finally(stop);
