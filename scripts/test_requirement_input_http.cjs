'use strict';
// Actual guided text/contract builders -> isolated HTTP -> coverage criteria.
// Synthetic records and a fresh temporary database only; no external model.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),vm=require('node:vm');
const net=require('node:net'),crypto=require('node:crypto'),{spawn}=require('node:child_process'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'..'),options=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>i%2?a:[...a,[v,all[i+1]]],[]));
assert.ok(options['--java']&&options['--classes'],'Explicit --java and --classes required');
const app=fs.readFileSync(path.join(repo,'web/app.js'),'utf8'),context={};
const begin=app.indexOf('  function guidedRequirementText('),end=app.indexOf('  function demandGuidedFields(',begin);
assert.ok(begin>=0&&end>begin,'Actual frontend guided builder required');
vm.createContext(context);vm.runInContext(app.slice(begin,end),context);
const own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-requirement-http-'));
const password=crypto.randomBytes(24).toString('hex');let child,token='',checks=0;const failures=[];
function check(value,label){checks++;if(!value)failures.push(label);}
function payload(fields){return {requirement:context.guidedRequirementText(fields),
  ...(typeof context.guidedRequirementContract==='function'?{requirement_contract:context.guidedRequirementContract(fields)}:{}),
  training_mode:'线上',max_results:3};}
(async()=>{
  const portServer=net.createServer();await new Promise(r=>portServer.listen(0,'127.0.0.1',r));
  const port=portServer.address().port;await new Promise(r=>portServer.close(r));
  child=spawn(options['--java'],['-Dbind.address=127.0.0.1','-Dbootstrap.admin.password='+password,'-Ddata.dir='+path.join(own,'data'),
    '-Dsemantic.port=','-Dsemantic.token=','-cp',options['--classes']+':lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar','com.training.Main',String(port)],
    {cwd:repo,env:{...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''},stdio:'ignore'});
  async function request(route,body){const response=await fetch(`http://127.0.0.1:${port}/api`+route,{method:body===undefined?'GET':'POST',
    headers:{'Content-Type':'application/json',...(token?{'X-Token':token}:{})},body:body===undefined?undefined:JSON.stringify(body)});return response.json();}
  async function api(route,body){const result=await request(route,body);assert.equal(result.code,0,result.msg);return result.data;}
  for(let i=0;i<100;i++){assert.equal(child.exitCode,null,'Isolated process exited');try{token=(await api('/login',{username:'admin',password})).token;break;}catch{await new Promise(r=>setTimeout(r,100));}}
  assert.ok(token,'Isolated login failed');assert.equal((await api('/teachers')).length,0,'Fresh empty database required');
  const observed=[];
  for(const [topic,audience,other] of [['文档编号复核','见习文控员','资深文控员'],['量具编号登记','新任检验员','资深检验员']]){
    const ids=[];
    for(const actual of [audience,other])ids.push(await api('/teachers',{name:'【灰度测试】'+topic+actual,status:'在库',base_province:'浙江',base_city:'杭州',
      field:topic,intro:`本人主讲${topic}课程，培训对象：${actual}。`,fee_rate:500}));
    const fields={unit:'',topic,audience,goals:'',date:'',hours:'',preference:'',extra:''},body=payload(fields);
    const result=await api('/teacher-recommendations',body),selected=result.recommendations,review=result.review_candidates;
    const actual=selected.find(row=>row.teacher_id===ids[0]);
    check(Boolean(actual),'Guided true audience positive admitted: '+topic);
    check(selected.some(row=>row.teacher_id===ids[1]),'Future audience alone does not require same-audience history: '+topic);
    const criterion=actual?.audience_context?.find(row=>row.requested_audience===audience);
    check(criterion?.hard_requirement===false&&!('status' in criterion),'Guided future audience stays separate from hard conditions: '+topic);
    check(criterion?.requirement_source_span?.source==='requirement_canonical','Canonical criterion is not mislabeled as original: '+topic);
    check(criterion?.requirement_field_source?.field_path==='requirement_contract.fields.audience','Criterion maps to actual audience field: '+topic);
    const input=result.analysis?.requirement_input;
    check(input?.schema_version==='guided_requirement_v1'&&input.original_text===body.requirement,'Original guided renderer text retained: '+topic);
    check(input?.field_ledger?.length===8,'All eight fields have a ledger entry: '+topic);
    check(input?.field_ledger?.find(row=>row.field==='audience')?.raw_value===audience,'Audience original field value retained: '+topic);
    const raw=await api('/teacher-recommendations',{requirement:`培训主题：${topic}\n培训对象：${audience}`,training_mode:'线上',max_results:3});
    check(ids.every(id=>raw.recommendations.some(row=>row.teacher_id===id)),'Legacy plain text preserves future audience semantics: '+topic);
    const hard=await api('/teacher-recommendations',{requirement:`培训主题：《${topic}》\n《${topic}》必须适配${audience}`,training_mode:'线上',max_results:3});
    check(hard.recommendations.some(row=>row.teacher_id===ids[0]),'Explicit named-course audience suitability can be supported: '+topic);
    check(!hard.recommendations.some(row=>row.teacher_id===ids[1]),'Explicit suitability is not downgraded to future context: '+topic);
    const guidedHard=await api('/teacher-recommendations',payload({...fields,extra:`《${topic}》必须适配${audience}`}));
    check(!guidedHard.recommendations.some(row=>row.teacher_id===ids[0])&&guidedHard.review_candidates.some(row=>row.teacher_id===ids[0]),'Guided free-text extras retain their existing explicit review gate: '+topic);
    const different=selected.find(row=>row.teacher_id===ids[1]);
    check(different?.audience_context?.every(row=>!row.source_audience_type_supported),'Different audience is not mislabeled as supported evidence: '+topic);
    observed.push({topic,selected:selected.map(r=>r.teacher_id),review:review.map(r=>r.teacher_id),positive_id:ids[0],negative_id:ids[1],
      selected_criteria:selected.map(r=>({id:r.teacher_id,criteria:r.requirement_coverage})),requirement_input:input});
  }
  check(typeof context.guidedRequirementContract==='function','Frontend sends a versioned contract');
  check(/body\.requirement_contract\s*=\s*guidedRequirementContract\(readGuided\(\)\)/.test(app),'Real submit handler attaches the actual guided contract');
  const invalid=payload({unit:'',topic:'文档编号复核',audience:'见习文控员',goals:'',date:'',hours:'',preference:'',extra:''});
  invalid.requirement_contract={schema_version:'guided_requirement_v1',fields:{unit:'',topic:'文档编号复核',audience:'见习文控员',goals:'',date:'',hours:'',preference:'',extra:'',unsupported:'必须现场演示'}};
  check((await request('/teacher-recommendations',invalid)).code!==0,'Unknown nonempty contract field is rejected rather than dropped');
  const normal={unit:'',topic:'文档编号复核',audience:'见习文控员',goals:'',date:'',hours:'',preference:'',extra:''};
  for(const [label,mutate] of [
    ['extra original text',body=>{body.requirement+='\n必须提供真实授课记录';}],
    ['unknown version',body=>{body.requirement_contract.schema_version='guided_requirement_v99';}],
    ['unknown contract property',body=>{body.requirement_contract.unexpected=true;}],
    ['missing field',body=>{delete body.requirement_contract.fields.extra;}],
    ['numeric field',body=>{body.requirement_contract.fields.audience=12;}],
    ['null field',body=>{body.requirement_contract.fields.extra=null;}],
    ['conflicting original alias',body=>{body.requirement_text='另一份需求';}],
  ]){
    const body=payload(normal);mutate(body);check((await request('/teacher-recommendations',body)).code===400,'HTTP rejects '+label);
  }
  for(const [key,value] of [['goals','必须要求学员当堂完成尚未结构化的产出'],['goals','课堂让学员制作独立成果'],['preference','必须具备特定而尚未核对的经历'],['extra','不能省略的补充内容']]){
    const result=await api('/teacher-recommendations',payload({...normal,[key]:value}));
    check(result.recommendations.length===0,'Incomplete field not treated as satisfied: '+key);
    check(result.analysis.requirement_input.review_fields.includes(key),'Incomplete field remains in ledger: '+key);
    check(result.review_candidates.some(row=>row.requirement_coverage.some(item=>item.requirement_field_source?.field_path==='requirement_contract.fields.'+key&&item.status==='needs_evidence')),'Pending candidate retains exact requirement origin: '+key);
  }
  const goals=await api('/teacher-recommendations',payload({...normal,goals:'帮助学员掌握编号管理'}));
  check(goals.recommendations.some(row=>row.teacher_id===observed[0].positive_id),'Ordinary future goal does not impose invented past experience');
  const oldDemand={title:'【灰度测试】旧的独立培训主题',unit:'【灰度测试】原客户',contact:'测试员',phone:'13900000001',hours:8,
    content:'必须完整覆盖另一套旧主题',teacher_req:'必须有旧课程实际记录',expect_date:'2026-10-01',status:'待处理',remark:''};
  const demandId=await api('/demands',oldDemand),edited=payload({...normal,date:'2026-10-03',hours:'2.5'});edited.demand_id=demandId;
  const fromDemand=await api('/teacher-recommendations',edited);
  check(fromDemand.demand_id===demandId&&fromDemand.analysis.requirement_input.source_demand_id===demandId,'Linked demand source retained');
  check(!fromDemand.analysis.requirement_input.matching_text.includes(oldDemand.content)&&!fromDemand.analysis.requirement_input.matching_text.includes(oldDemand.title),'Old demand content not appended as new hard requirements');
  check(fromDemand.recommendations.some(row=>row.teacher_id===observed[0].positive_id),'Edited guided requirement is authoritative');
  check(fromDemand.analysis.expected_date==='2026-10-03'&&fromDemand.analysis.hours===2.5,'Edited schedule overrides old demand schedule');
  const cleared=payload(normal);cleared.demand_id=demandId;
  const clearedResult=await api('/teacher-recommendations',cleared);
  check(clearedResult.analysis.expected_date===null&&clearedResult.analysis.hours===null,'Explicit empty current schedule does not restore old schedule');
  const unchanged=(await api('/demands')).find(row=>row.id===demandId);
  check(unchanged.title===oldDemand.title&&unchanged.content===oldDemand.content&&unchanged.hours===8&&unchanged.expect_date==='2026-10-01','Recommendation never writes back the original demand');
  const report={suite:'requirement-input-http',checks,failures,observed,production_touched:false,real_resumes_used:false,model_executed:false,artifacts:own};
  fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(report,null,2),{mode:0o600});
  console.log(JSON.stringify({...report,observed:undefined}));
  assert.equal(failures.length,0,failures.join('; '));
})().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(async()=>{
  if(child&&child.exitCode===null){child.kill('SIGTERM');await new Promise(resolve=>{child.once('exit',resolve);setTimeout(()=>{if(child.exitCode===null)child.kill('SIGKILL');resolve();},2000).unref();});}
});
