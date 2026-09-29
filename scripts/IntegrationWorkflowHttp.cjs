'use strict';
// Loopback HTTP, synthetic users, fresh temporary H2; never uses the production database.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const crypto=require('node:crypto'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const {workflowConfiguration}=require('./IntegrationWorkflowHttpFixture.cjs');
const args=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>i%2?a:[...a,[v,all[i+1]]],[]));
assert.ok(args['--java']&&args['--classes'],'Explicit java and isolated classes are required');
const repo=path.resolve(__dirname,'..'),own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-workflow-http-'));
const secret=crypto.randomBytes(24).toString('hex');let child,port,checks=0;const failures=[],tokens={};
function check(value,label){checks++;if(!value)failures.push(label);}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function request(route,body,actor='owner') {const response=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(tokens[actor]?{'X-Token':tokens[actor]}:{})},body:body===undefined?undefined:JSON.stringify(body)});return response.json();}
async function api(route,body,actor) {const r=await request(route,body,actor);assert.equal(r.code,0,`${route}: ${r.msg}`);return r.data;}
async function denied(route,body,actor,label,codes=[403,409]){const r=await request(route,body,actor);check(codes.includes(r.code),`${label} (${r.code})`);}
async function renewPublishedSessions(label) {
  for(const actor of ['owner','leader','bp','team','outsider']) {
    await denied('/me',undefined,actor,label+' invalidates old '+actor+' token',[401]);
    tokens[actor]=(await api('/login',{username:actor==='owner'?'admin':'synthetic-'+actor,password:secret},'anonymous')).token;
  }
}
const action=(d,requestId,action='APPROVE')=>({action,expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId,comment:'合成验证意见'});
const command=(d,request_id)=>({id:d.id,expected_version:d.version,request_id});
const draft=(request_id,path='direct')=>({request_id,title:'SYNTHETIC PRIVATE ORG: 文档编号复核',unit:'SYNTHETIC PRIVATE CUSTOMER',business_path:path,organization_code:'001',internal_contact_code:'P1',category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'67.50',participant_count:'12',budget_amount:'5000',objectives:'编号核对',content:'编号核对',teacher_req:'',remark:'synthetic source',...(path==='bid'?{external_approval_ref:'SYNTHETIC-OFFICE-REF'}:{})});
async function waitJob(job,actor='owner') {for(let i=0;i<100;i++){const response=await request('/teacher-recommendations/jobs?id='+encodeURIComponent(job.job_id),undefined,actor);if(response.code!==0)return response; if(response.data.terminal)return response;await pause(50);}throw new Error('Synthetic recommendation job did not complete');}
(async()=>{
  const listener=net.createServer();await new Promise(r=>listener.listen(0,'127.0.0.1',r));port=listener.address().port;await new Promise(r=>listener.close(r));
  child=spawn(args['--java'],['-Dbind.address=127.0.0.1','-Dbootstrap.admin.password='+secret,'-Ddata.dir='+path.join(own,'data'),'-Dsemantic.port=','-Dsemantic.token=','-cp',args['--classes']+':lib/*','com.training.Main',String(port)],{cwd:repo,env:{...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''},stdio:'ignore'});
  for(let i=0;i<100;i++){assert.equal(child.exitCode,null,'Isolated service exited');try{const r=await request('/login',{username:'admin',password:secret});if(r.code===0){tokens.owner=r.data.token;break;}}catch{}await pause(100);}assert.ok(tokens.owner,'Synthetic login failed');
  await denied('/demands/draft',draft('unconfigured'),'owner','Admin without business binding cannot create', [403]);
  const ids=[(await api('/me')).uid];
  for(const [actor,role] of [['leader','viewer'],['bp','viewer'],['team','manager'],['outsider','manager']]) {ids.push(await api('/users',{username:'synthetic-'+actor,name:'SYNTHETIC '+actor,role,status:1,password:secret}));tokens[actor]=(await api('/login',{username:'synthetic-'+actor,password:secret})).token;}
  let configuration=workflowConfiguration(ids);await api('/organization/config',{expectedVersion:null,configuration});await renewPublishedSessions('Initial binding');
  const context=await api('/workflow/context');check(context.people.find(p=>p.code==='P2')?.responsible_organization_codes?.includes('001'),'Cross-org responsible contact is selectable without extra scopes');
  await denied('/demands',{title:'bypass',unit:'x',hours:1},'owner','Generic demand creation blocked');await denied('/projects',{title:'bypass',unit:'x',hours:1},'owner','Generic project creation blocked');
  let d=(await api('/demands/draft',draft('draft'))).workflow;const demandId=d.id;
  await denied('/demands/draft',{...command(d,'injection'),filler_code:'P5'},'owner','Client identity claim rejected',[422]);
  d=(await api('/demands/submit',command(d,'submit'))).workflow;
  await denied('/approvals/tasks/'+d.id+'/actions',action(d,'early-bp'),'bp','BP cannot act before leader',[403]);
  await denied('/demands/accept',{...command(d,'early-team'),team_code:'900'},'team','Team cannot accept incomplete approvals');
  await denied('/demands/delete',{id:d.id},'owner','Managed demand generic delete blocked');
  await denied('/bids',{demand_id:d.id,amount:100,bid_date:'2025-01-01',status:'待评审'},'owner','Old bid creation cannot bridge into new workflow');
  const firstApproval=action(d,'leader-approve');d=(await api('/approvals/tasks/'+d.id+'/actions',firstApproval,'leader')).workflow;
  const replay=(await api('/approvals/tasks/'+d.id+'/actions',firstApproval,'leader')).workflow;check(replay.approval.version===d.approval.version,'Same approval request is idempotent');
  await denied('/approvals/tasks/'+d.id+'/actions',{...firstApproval,requestId:'stale'},'leader','Stale approval version rejected',[409]);
  d=(await api('/approvals/tasks/'+d.id+'/actions',action(d,'bp-approve'),'bp')).workflow;
  const accept={...command(d,'team-accept'),team_code:'900'};d=(await api('/demands/accept',accept,'team')).workflow;
  check((await api('/demands/accept',accept,'team')).workflow.project_id===d.project_id,'Duplicate acceptance returns original project');
  await denied('/demands/accept',{...accept,request_id:'stale-accept'},'team','Old demand version cannot accept again',[409]);
  const projectId=d.project_id;let project=(await api('/projects')).find(p=>p.id===projectId);
  check(project.bid_id===null&&project.amount===0&&project.participant_count===12,'Project has direct source, preserved count, no inferred contract value');
  check(project.workflow_source?.can_delete===false&&project.workflow_source?.demand_id===demandId,'Project includes server-owned source summary');
  await denied('/projects',{...project,demand_id:0,bid_id:0},'owner','Cannot strip project source',[409]);
  await denied('/projects/delete',{id:projectId},'owner','Cannot delete accepted project',[409]);
  await api('/projects',{...project,owner:'SYNTHETIC OWNER',start_date:'2025-01-01',end_date:'2025-01-05',delivery_mode:'线上'});
  await api('/projects/start',{id:projectId},'team');
  const teacherId=await api('/teachers',{name:'SYNTHETIC TEACHER',status:'在库',base_province:'浙江',base_city:'杭州',field:'文档编号复核',intro:'本人主讲文档编号复核课程。',fee_rate:500});
  const dispatchId=await api('/dispatches',{project_id:projectId,teacher_id:teacherId,subject:'SYNTHETIC PRIVATE CLASS',teach_date:'2025-01-01',start_time:'09:00',end_time:'10:30',venue:'SYNTHETIC PRIVATE VENUE',hours:1.5,status:'待发送',material_status:'已齐备'},'team');
  await api('/dispatches/send',{id:dispatchId},'team');await api('/dispatches/confirm',{id:dispatchId,accept:1},'team');const savedDelivery=await api('/delivery-settlement/save',{dispatch_id:dispatchId,expected_version:0,request_id:'synthetic-delivery-save',actual_minutes:'67.5'},'team');
  const verifiedDelivery=await api('/delivery-settlement/verify',{dispatch_id:dispatchId,expected_version:savedDelivery.version,request_id:'synthetic-delivery-verify',evidence_code:'SYNTHETIC-EVIDENCE'},'team');
  await api('/dispatches/complete',{id:dispatchId,expected_version:verifiedDelivery.version},'team');
  await api('/teacher_evals',{project_id:projectId,teacher_id:teacherId,score:4.5,comment:'SYNTHETIC PRIVATE EVAL',evaluator:'SYNTHETIC',eval_date:'2025-01-02'},'team');
  await api('/costs',{project_id:projectId,type:'SYNTHETIC PRIVATE COST',amount:123.45,cost_date:'2025-01-02',note:'private'},'team');
  await api('/charges',{project_id:projectId,amount:345.67,received:0,charge_date:'2025-01-02',status:'未收费'},'team');
  for(const module of ['demands','projects','dispatches','teacher_evals','costs','charges'])check((await api('/'+module,undefined,'outsider')).length===0,'Unauthorized list hides '+module);
  for(const [route,body] of [['/demands/workflow?id='+demandId,undefined],['/approvals/tasks/'+demandId,undefined],['/projects/check?id='+projectId,undefined],['/projects/start',{id:projectId}],['/dispatches/complete',{id:dispatchId,expected_version:verifiedDelivery.version}],['/costs',{project_id:projectId,type:'x',amount:1}],['/fees/calc',{project_id:projectId}]])await denied(route,body,'outsider','Unauthorized source or child route denied: '+route,[403]);
  check((await api('/approvals/tasks',undefined,'outsider')).tasks.length===0,'Unauthorized approval task list empty');
  const overview=await api('/stats/overview',undefined,'outsider');
  for(const key of ['demands','projects','teach_hours','eval_count','cost_amount','charge_amount'])check(overview[key]===0,'Unauthorized overview aggregate excludes '+key);
  const report=await api('/stats/report',undefined,'outsider');check(!report.includes('SYNTHETIC PRIVATE')&&report.includes('累计培训需求 0 项；已立项项目 0 个'),'Report has no private names/counts');
  const ownOverview=await api('/stats/overview');check(ownOverview.projects===1&&ownOverview.eval_count===1&&ownOverview.teach_hours===1.5,'Authorized aggregates still contain own records');
  await denied('/teacher-recommendations',{demand_id:demandId,requirement:'文档编号复核',training_mode:'线上'},'outsider','Recommendation demand reference scope enforced',[403]);
  await denied('/teacher-recommendations/jobs',{demand_id:demandId,requirement:'文档编号复核',training_mode:'线上'},'outsider','Unauthorized demand job is rejected before submission',[403]);
  const req={requirement:'培训主题：文档编号复核\n培训日期：2025-01-02',training_mode:'线上',max_results:3};
  const publicResult=await api('/teacher-recommendations',req,'outsider');const all=[...(publicResult.recommendations||[]),...(publicResult.review_candidates||[])];const match=all.find(p=>p.teacher_id===teacherId);
  check(Boolean(match),'Public teacher remains recommendable');check(match?.performance?.evaluation_count===0&&match?.performance?.completed_hours===0,'Recommendation excludes private evaluation and delivery aggregates');
  check(!JSON.stringify(publicResult).includes('SYNTHETIC PRIVATE VENUE'),'Recommendation hides private adjacent schedule venue');
  const offlineResult=await api('/teacher-recommendations',{...req,training_mode:'线下',training_province:'浙江',training_city:'杭州',training_period:'下午'},'outsider');
  const findCandidate=value=>{if(!value||typeof value!=='object')return null;if(value.teacher_id===teacherId&&value.dispatch_fit)return value;for(const child of Object.values(value)){const found=findCandidate(child);if(found)return found;}return null;};
  const offline=findCandidate(offlineResult);
  check(offline?.dispatch_fit?.departure_uncertain===true&&offline?.dispatch_fit?.local_priority===false,'Hidden adjacent booking still disables automatic local priority');
  check(!JSON.stringify(offlineResult).includes('SYNTHETIC PRIVATE VENUE')&&offline?.dispatch_fit?.adjacent_schedule?.length===0,'Hidden adjacent booking exposes no venue or schedule details');
  const job=await api('/teacher-recommendations/jobs',{demand_id:demandId,...req});const complete=await waitJob(job);check(complete.code===0&&complete.data.result?.demand_id===demandId,'Owner async recommendation completes');
  await denied('/teacher-recommendations/jobs?id='+encodeURIComponent(job.job_id),undefined,'outsider','Different actor cannot read job',[404,403]);
  const generalJob=await api('/teacher-recommendations/jobs',req);const generalComplete=await waitJob(generalJob);check(generalComplete.code===0&&Boolean(generalComplete.data.result),'Owner no-demand job completes');
  const oldVersion=configuration.version;configuration={...configuration,version:'synthetic-workflow-http-v2',grants:configuration.grants.filter(g=>!(g.roleCode==='FILLER'&&g.resource==='demand.read'))};await api('/organization/config',{expectedVersion:oldVersion,configuration});await renewPublishedSessions('Source grant revoked');
  await denied('/teacher-recommendations/jobs?id='+encodeURIComponent(job.job_id),undefined,'owner','Revoked source scope cannot read cached job',[403,409]);
  await denied('/teacher-recommendations/jobs?id='+encodeURIComponent(generalJob.job_id),undefined,'owner','Changed scope cannot read stale no-demand aggregates',[403,409]);
  const summary={suite:'workflow-http',checks,failures,production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));assert.equal(failures.length,0,failures.join('; '));
})().catch(e=>{console.error(e.message);process.exitCode=1;}).finally(async()=>{if(child&&child.exitCode===null){child.kill('SIGTERM');await new Promise(r=>{child.once('exit',r);setTimeout(()=>{if(child.exitCode===null)child.kill('SIGKILL');r();},2000).unref();});}});
