'use strict';
// Real Main/Api only; explicit isolated classes, random credentials, loopback and fresh temporary H2.
// Compile scripts/IntegrationPolicyPreviewHttpFixture.java with the actual src tree before running.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn,execFile}=require('node:child_process'),{promisify}=require('node:util');
const {workflowConfiguration}=require('./IntegrationWorkflowHttpFixture.cjs');
const run=promisify(execFile),args={};
for(let i=2;i<process.argv.length;i+=2){assert.ok(['--java','--classes'].includes(process.argv[i])&&process.argv[i+1],'Use --java PATH --classes ISOLATED_DIRECTORY');args[process.argv[i]]=process.argv[i+1];}
assert.ok(args['--java']&&args['--classes'],'Explicit Java and isolated classes are required');
const repo=path.resolve(__dirname,'..'),classes=fs.realpathSync(args['--classes']);
assert.ok(classes!==repo&&!classes.startsWith(repo+path.sep),'Compiled classes must be outside repository');
assert.ok(fs.existsSync(path.join(classes,'com/training/IntegrationPolicyPreviewHttpFixture.class')),'Compile the scripts-only fixture first');
const own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-policy-preview-http-')),data=path.join(own,'data');fs.mkdirSync(data);
const password=crypto.randomBytes(24).toString('hex'),tokens={},failures=[];
let child,port,checks=0,phase='startup',fatal=null,configuration;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function check(value,label){checks++;if(!value)failures.push(phase+': '+label);}
function same(actual,expected,label){let ok=true;try{assert.deepEqual(actual,expected);}catch{ok=false;}check(ok,label);}
function options(){return ['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dbootstrap.admin.password='+password,'-Dintegration.policy.preview.root='+own,'-Ddata.dir='+data,'-Dsemantic.port=','-Dsemantic.token=','-cp',classes+path.delimiter+path.join(repo,'lib','*')];}
const env={...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''};
async function request(route,body,actor='worker',overrides={}){
 const headers={...(tokens[actor]?{'X-Token':tokens[actor]}:{})};if(body!==undefined)headers['Content-Type']='application/json';
 const r=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:overrides.method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
 const text=await r.text();let envelope=null;if(text){try{envelope=JSON.parse(text);}catch{throw new Error(route+': non-JSON HTTP '+r.status);}}
 return {status:r.status,text,envelope};
}
async function api(route,body,actor='worker'){
 const r=await request(route,body,actor);assert.equal(r.status,200,phase+': '+route+': HTTP '+r.status+': '+r.envelope?.msg);assert.equal(r.envelope?.code,0,route);return r.envelope.data;
}
async function denied(route,body,actor,status,label,overrides={}){
 const r=await request(route,body,actor,overrides);check(r.status===status&&r.envelope?.code===status,`${label} (HTTP ${r.status}, code ${r.envelope?.code}, ${r.envelope?.msg})`);return r.envelope;
}
async function login(actor){tokens[actor]=(await api('/login',{username:actor==='admin'?'admin':'synthetic-policy-'+actor,password},'anonymous')).token;}
async function boot(fixture=false){
 Object.keys(tokens).forEach(k=>delete tokens[k]);const listener=net.createServer();
 await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});port=listener.address().port;await new Promise(resolve=>listener.close(resolve));
 child=spawn(args['--java'],[...options(),fixture?'com.training.IntegrationPolicyPreviewHttpFixture':'com.training.Main',String(port)],{cwd:repo,env,stdio:'ignore'});
 let launchError;child.once('error',error=>{launchError=error;});
 for(let i=0;i<150;i++){if(launchError)throw launchError;assert.equal(child.exitCode,null,'Isolated Main exited before login');try{await login('admin');if(tokens.admin)return;}catch{}await pause(100);}
 throw new Error('Isolated Main did not become ready');
}
async function stop(){const running=child;child=null;if(!running||running.exitCode!==null||running.signalCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{if(running.exitCode===null&&running.signalCode===null)running.kill('SIGKILL');},2000);running.once('exit',()=>{clearTimeout(timer);resolve();});running.kill('SIGTERM');});}
async function inspect(){const r=await run(args['--java'],[...options(),'com.training.IntegrationPolicyPreviewHttpFixture','inspect'],{cwd:repo,env,timeout:15000,maxBuffer:32768});return JSON.parse(r.stdout.trim());}
const actors=['admin','leader','bp','team','outsider','worker','reader','unbound'];
async function logins(){for(const actor of actors)await login(actor);}
async function readOnlyWindow(label,work){
 phase=label;await stop();const before=await inspect();await boot();await logins();await work();await stop();const after=await inspect();
 for(const table of Object.keys(before))same(after[table],before[table],label+' leaves full '+table+' content unchanged');
 fs.writeFileSync(path.join(own,label.replaceAll(' ','-')+'-database.json'),JSON.stringify({before,after},null,2),{mode:0o600});
 await boot();await logins();return after;
}
const cmd=(id,version,request_id)=>({dispatch_id:id,expected_version:version,request_id});
const preview=id=>api('/delivery-settlement/policy-preview?dispatch_id='+id);
const issue=(value,code)=>value.issues.some(x=>x.code===code);
const nullMoney=['rate','raw_amount','final_amount','pool_raw_amount','pool_final_amount','individual_raw_amount','individual_final_amount'];
function basis(view){
 check(view.preview_only===true&&view.can_confirm===false&&view.creates_configuration===false&&view.creates_snapshot===false,'Preview never confers formal confirmation or writes');
 check(view.evidence_version===null,'Default HTTP host does not invent a trusted evidence version');same(view.evidence_references,{},'Default HTTP host has no fabricated evidence references');
 const b=view.execution_basis;check(b.execution_version==='M05-USER-APPROVED-SOURCE-C7E8BAAA-V1'&&b.adoption_date==='2026-09-22','Preview identifies user-approved execution basis');
 check(b.source_consultation_draft===true&&typeof b.source_sha256==='string'&&/^[a-f0-9]{64}$/.test(b.source_sha256),'Original source marking and SHA256 provenance are preserved');
 check(b.source_official_document_number===null&&b.source_publication_date===null&&b.effective_from===null&&b.adoption_is_publication_date===false&&b.retroactive_application_assumed===false,'Adoption date does not invent publication or retrospective applicability');
 check(b.minutes_per_class_hour===45&&b.hour_rounding_determines_money_rounding===false&&b.evaluation_multipliers_apply_to_money===false,'45-minute conversion is separate from money rounding and development evaluation multipliers');
 for(const field of ['issues','formal_missing_items'])check(Array.isArray(view[field])&&view[field].every(i=>typeof i.code==='string'&&typeof i.message==='string'&&i.message.length>0),field+' has typed actionable reasons');
}
function conditional(view,hours){
 basis(view);check(view.status==='INCOMPLETE'&&view.rate===null,'Missing trusted grade and day preserves incomplete top-level status');
 for(const field of nullMoney)check(view[field]===null,'Default host does not manufacture '+field);
 check(view.money_rounding===null&&issue(view,'MONEY_ROUNDING_NOT_CONFIGURED'),'Class-hour precision never supplies a money rounding rule');
 check(view.payable_hours===hours,'Only separately persisted payable hours enter conditional scenarios');
 for(const code of ['GRADE_MISSING','TEACHING_DAY_TYPE_MISSING','RESEARCH_TEAM_MEMBERSHIP_UNKNOWN','APPOINTMENT_FACT_UNKNOWN','PROJECT_SCOPE_UNKNOWN','SERVICE_DATE_APPLICABILITY_UNKNOWN','ORGANIZER_SUBMISSION_UNKNOWN','SHARED_DELIVERY_APPROVAL_UNKNOWN'])check(issue(view,code),'Unverified evidence stays missing: '+code);
 check(!view.issues.some(i=>/CANDIDATE|PUBLICATION|DRAFT/.test(i.code)),'Original consultation-draft label does not globally disable approved preview');
 check(view.formal_missing_items.some(i=>i.code==='FORMAL_RULE_CONFIGURATION_NOT_CREATED')&&view.formal_missing_items.some(i=>i.code==='FORMAL_SETTLEMENT_CONFIRMATION_REQUIRED'),'Formal configuration and confirmation remain distinct missing steps');
 const rates={LECTURER:['100','200','300'],SENIOR:['150','300','450'],SPECIAL:['200','400','600'],DISTINGUISHED:['200','400','600']},days=['WORKDAY','REST_DAY','STATUTORY_HOLIDAY'];
 check(view.scenarios.length===12,'All twelve teaching grade/day scenarios remain available');
 for(const [grade,values]of Object.entries(rates))for(let i=0;i<days.length;i++){
  const rows=view.scenarios.filter(s=>s.reference_grade===grade&&s.teaching_day_type===days[i]);check(rows.length===1,'Exactly one conditional '+grade+'/'+days[i]+' scenario');const row=rows[0];
  check(row?.unit_rate===values[i]&&row.activity==='TEACHING'&&row.conditional===true&&typeof row.condition==='string'&&row.condition.length>0,'Scenario identifies exact approved rate and unresolved condition '+grade+'/'+days[i]);
  // 1.25 hours is tested as exact integer quarters; never use a floating amount to form expectations.
  const amount=hours===null?null:(BigInt(values[i])*125n).toString();
  const expected=amount===null?null:amount.slice(0,-2)+'.'+amount.slice(-2);
  check(row?.payable_hours===hours&&row.raw_amount===expected,'Scenario amount uses independent payable hours exactly '+grade+'/'+days[i]);
 }
}
async function setup(){
 const ids=[(await api('/me',undefined,'admin')).uid],accounts={};
 for(const [actor,role]of [['leader','viewer'],['bp','viewer'],['team','manager'],['outsider','viewer'],['worker','viewer'],['reader','viewer'],['unbound','manager']]){
  accounts[actor]=await api('/users',{username:'synthetic-policy-'+actor,name:'SYNTHETIC POLICY '+actor,role,status:1,password},'admin');if(ids.length<5)ids.push(accounts[actor]);await login(actor);
 }
 configuration=workflowConfiguration(ids);configuration.version='SYNTHETIC-POLICY-HTTP-V1';configuration.grants=configuration.grants.filter(g=>!g.resource.startsWith('delivery.')&&!g.resource.startsWith('settlement.'));
 const optional={required:false,allowedTargetRoles:['POLICY','READER'],targetMustCoverOrganization:false,allowSelf:false};configuration.roleCodes.push('POLICY','READER');
 for(const [actor,personCode,role]of [['worker','P6','POLICY'],['reader','P7','READER']]){
  configuration.people.push({personCode,organizationCode:'001',roleCodes:[role],responsibleOrganizationCodes:[],leaderPersonCode:null,bpPersonCode:null,enabled:true});configuration.accountBindings.push({accountId:accounts[actor],personCode,enabled:true});
 }
 for(const roleCode of ['POLICY','READER'])configuration.relations.push({roleCode,leader:optional,bp:optional});
 for(const roleCode of ['POLICY','READER','OUT'])for(const resource of roleCode==='POLICY'?['delivery.read','delivery.write','delivery.verify']:['delivery.read'])configuration.grants.push({ruleId:roleCode+'-'+resource,roleCode,resource,action:resource==='delivery.read'?'VIEW':'HANDLE',effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]});
 await api('/organization/config',{expectedVersion:null,configuration},'admin');await logins();
 let d=(await api('/demands/draft',{request_id:'POLICY-DRAFT',title:'SYNTHETIC POLICY REAL ACCEPTANCE',unit:'SYNTHETIC CUSTOMER',business_path:'direct',organization_code:'001',internal_contact_code:'P1',category_text:'合成验证',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'60',participant_count:'1',budget_amount:'999999',objectives:'SYNTHETIC',content:'SYNTHETIC',remark:'SYNTHETIC ONLY'},'admin')).workflow;
 d=(await api('/demands/submit',{id:d.id,expected_version:d.version,request_id:'POLICY-SUBMIT'},'admin')).workflow;
 for(const actor of ['leader','bp'])d=(await api('/approvals/tasks/'+d.id+'/actions',{action:'APPROVE',expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId:'POLICY-'+actor,comment:'SYNTHETIC'},actor)).workflow;
 d=(await api('/demands/accept',{id:d.id,expected_version:d.version,request_id:'POLICY-ACCEPT',team_code:'900'},'team')).workflow;
 const project=(await api('/projects',undefined,'team')).find(p=>p.id===d.project_id);check(project.workflow_source?.demand_id===d.id&&project.delivery_controlled===true,'Fixture creates new project through real approval and team acceptance');
 await api('/projects',{...project,owner:'SYNTHETIC',start_date:'2025-01-01',end_date:'2025-01-02',delivery_mode:'线上',hours:1.33,amount:999999,contract_no:'SYNTHETIC-POLICY-CONTRACT'},'team');await api('/projects/start',{id:project.id},'team');
 const teacher=await api('/teachers',{name:'SYNTHETIC POLICY NEW TEACHER',status:'在库',teacher_level:'特级讲师',fee_rate:99999,in_date:'2020-01-01',base_province:'浙江',base_city:'杭州'},'admin');
 const dispatch=await api('/dispatches',{project_id:project.id,teacher_id:teacher,subject:'SYNTHETIC POLICY NEW CLASS',teach_date:'2025-01-01',hours:9,status:'待发送',material_status:'已就绪'},'team');
 await api('/dispatches/send',{id:dispatch},'team');await api('/dispatches/confirm',{id:dispatch,accept:1},'team');
 const history=(await api('/dispatches',undefined,'team')).filter(row=>/^SYNTHETIC POLICY (PAID|PENDING) CLASS$/.test(row.subject));assert.equal(history.length,2);
 const legacy=(await api('/dispatches',undefined,'admin')).find(row=>row.subject==='SYNTHETIC POLICY UNMIGRATED CLASS');assert.ok(legacy);
 return {dispatch,project:project.id,teacher,history,legacy:legacy.id};
}
async function runChecks(){
 await boot(true);same(await api('/organization/config',undefined,'admin'),{version:null,configuration:null},'Fresh startup grants no implicit business authority');
 await denied('/delivery-settlement/policy-preview?dispatch_id=1',undefined,'admin',403,'Unconfigured administrator cannot read policy preview');
 const fixture=await setup(),id=fixture.dispatch,route='/delivery-settlement/policy-preview?dispatch_id='+id;
 await readOnlyWindow('empty fact preview',async()=>{
  const p=await preview(id);conditional(p,null);check(p.fact_version===0&&p.actual_minutes===null&&p.actual_hours===null&&p.teaching_verified===false,'Preview before first save leaves all actual evidence unknown');
  check(issue(p,'PAYABLE_HOURS_MISSING')&&issue(p,'TEACHING_NOT_VERIFIED'),'Empty facts retain independent payable and teaching verification gaps');
  await denied('/delivery-settlement/preview?dispatch_id='+id,undefined,'worker',409,'Original formal preview still requires saved facts');
  for(const actor of ['admin','unbound','outsider','team'])await denied(route,undefined,actor,403,actor+' cannot replace exact scoped delivery.read permission');
  await denied(route,undefined,'anonymous',401,'New preview requires actual HTTP login');tokens.forged='SYNTHETIC-NOT-A-SESSION';await denied(route,undefined,'forged',401,'Fabricated request token cannot access preview');
  same(await api(route,undefined,'reader'),p,'Read-only bound viewer can read policy preview without write or settlement grant');
  await denied('/delivery-settlement/policy-preview?dispatch_id='+fixture.legacy,undefined,'worker',409,'Unmigrated legacy project cannot infer trusted source');
  await denied('/delivery-settlement/policy-preview?dispatch_id=900000',undefined,'worker',404,'Missing dispatch returns explicit not-found');
  for(const bad of ['', '-1','1.25','true','9007199254740992'])await denied('/delivery-settlement/policy-preview?dispatch_id='+bad,undefined,'worker',400,'Invalid dispatch identifier rejected: '+bad);
  await denied('/delivery-settlement/policy-preview',undefined,'worker',400,'No implicit dispatch selection');
  for(const fragment of ['grade=SPECIAL','organization_code=999','payable_hours=100','evidence_version=FORGED','facts=%7B%22appointedBeforeWork%22%3Atrue%7D','research_team=true','day_type=WORKDAY','legacyRuleRequired=false','historicalFrozen=false'])await denied(route+'&'+fragment,undefined,'worker',400,'HTTP query cannot supply trusted evidence: '+fragment);
  await denied(route+'&dispatch_id='+id,undefined,'worker',400,'Duplicate query identifier is rejected');
  await denied(route+'&dispatch_id='+fixture.history[0].id,undefined,'worker',400,'Conflicting duplicate identifier cannot silently select another dispatch');
  await denied(route+'&dispatch%5Fid='+id,undefined,'worker',400,'URL-encoded duplicate identifier is rejected after decoding');
  for(const method of ['POST','PUT','DELETE'])await denied(route,method==='POST'?{appointed:true,grade:'SPECIAL',evidence_version:'FORGED'}:undefined,'worker',405,'Read-only route rejects '+method,{method});
  const head=await request(route,undefined,'worker',{method:'HEAD'});check(head.status===405&&head.text==='','GET-only policy preview rejects HEAD without response body');
 });
 phase='server actual evidence';
 let fact=await api('/delivery-settlement/save',{...cmd(id,0,'POLICY-FACT-1'),actual_minutes:'60',estimated_hours:'2.50',planned_hours:'9.00',payable_hours:null,dimensions:{grade:'DISTINGUISHED',time_band:'STATUTORY_HOLIDAY',form:'TEACHING',hour_unit:'CLASS45'}});
 check(fact.version===1&&fact.fact.hours.actual==='1.33','Real save preserves 60 to 1.33 independently of old schedule hours');
 fact=await api('/delivery-settlement/verify',{...cmd(id,1,'POLICY-VERIFY-1'),evidence_code:'SYNTHETIC-POLICY-TEACHING'});
 check(fact.version===2&&fact.fact.verification.actor_code==='P6','Real verified fact is attributed to bound M01 operator');
 await readOnlyWindow('verified actual no payable',async()=>{
  const p=await preview(id);conditional(p,null);check(p.fact_version===2&&p.actual_minutes==='60'&&p.actual_hours==='1.33'&&p.teaching_verified===true,'New route exposes live verified actual facts without inferring payable quantity');
  check(!issue(p,'TEACHING_NOT_VERIFIED')&&issue(p,'PAYABLE_HOURS_MISSING'),'Teaching verification does not satisfy payable-hours evidence');
  check(p.organization_code==='001'&&p.project_id===fixture.project&&p.teacher_id===fixture.teacher&&p.service_date==='2025-01-01','Policy preview identity and service date are from accepted source chain');
  const old=await api('/delivery-settlement/preview?dispatch_id='+id);check(old.status==='NOT_CONFIGURED'&&old.amount===null,'Original preview still uses explicit formal configuration and no approved-policy default');
  same(await api('/delivery-settlement?dispatch_id='+id),fact,'Read-only preview preserves full fact and live capability response');
 });
 phase='independent payable evidence';
 fact=await api('/delivery-settlement/save',{...cmd(id,2,'POLICY-FACT-2'),payable_hours:'1.25'});
 const unverified=await preview(id);check(unverified.teaching_verified===false&&issue(unverified,'TEACHING_NOT_VERIFIED')&&unverified.fact_version===3,'Saving independent payable quantity clears server verification');
 fact=await api('/delivery-settlement/verify',{...cmd(id,3,'POLICY-VERIFY-2'),evidence_code:'SYNTHETIC-POLICY-REVIEW'});
 const snapshots=await readOnlyWindow('conditional amount and history',async()=>{
  const p=await preview(id);conditional(p,'1.25');check(p.actual_minutes==='60'&&p.actual_hours==='1.33'&&p.teaching_verified===true&&p.fact_version===4,'Independent payable 1.25 coexists with actual 1.33');
  check(!issue(p,'PAYABLE_HOURS_MISSING'),'Saving payable quantity resolves only its own missing condition');
  check(p.scenarios.find(s=>s.reference_grade==='SPECIAL'&&s.teaching_day_type==='REST_DAY').raw_amount==='500.00','Approved 400 rate times independent 1.25 yields exact conditional 500.00');
  const old=await api('/delivery-settlement/preview?dispatch_id='+id);check(old.status==='NOT_CONFIGURED'&&old.amount===null,'Old preview remains unconfigured even when new rate scenarios can calculate');
  for(const row of fixture.history){
   const protectedView=await preview(row.id);basis(protectedView);check(protectedView.status==='HISTORY_PROTECTED'&&issue(protectedView,'LEGACY_RULE_AND_ENTITLEMENT_REQUIRED'),'Old '+row.subject+' prevents new pricing');
   for(const field of nullMoney)check(protectedView[field]===null,'Historical '+row.subject+' leaves '+field+' null');
   same(protectedView.scenarios,[],'Historical fees expose no new-price scenarios');
   await denied('/fees/calc',{project_id:row.project_id},'team',409,'Policy preview does not reopen old fee calculation');
   const fees=(await api('/fees',undefined,'team')).filter(f=>f.project_id===row.project_id);assert.equal(fees.length,1);
   await denied('/fees/pay',{id:fees[0].id},'team',409,'Historical old payment shortcut remains closed');
  }
  for(const operation of ['configure','confirm','correct'])await denied('/delivery-settlement/'+operation,{...cmd(id,4,'POLICY-FORMAL-'+operation)},'worker',409,'Approved preview does not enable formal '+operation);
  const again=await preview(id);same(again,p,'Repeated reads are deterministic and do not advance fact/evidence versions');
 });
 for(const table of ['m05_policy_versions','m05_settlement_configs','m05_settlement_snapshots'])check(snapshots[table].count===0,'Policy HTTP never persists '+table);
 check(snapshots.m05_delivery_facts.count===1&&snapshots.m05_fact_revisions.count===4&&snapshots.m05_requests.count===4&&snapshots.fees.count===2,'Only four explicitly requested fact revisions and original historical fees exist');
 phase='live authorization changes';
 const previous=configuration.version;configuration={...configuration,version:'SYNTHETIC-POLICY-HTTP-V2',grants:configuration.grants.filter(g=>!(g.roleCode==='POLICY'&&g.resource==='delivery.read'))};
 await api('/organization/config',{expectedVersion:previous,configuration},'admin');
 await denied(route,undefined,'worker',401,'Configuration change invalidates previous authenticated session');await login('worker');
 await denied(route,undefined,'worker',403,'Fresh session cannot use revoked exact delivery.read permission');await login('reader');
 check((await api(route,undefined,'reader')).fact_version===4,'Unchanged read grant remains usable after fresh authentication');
 await api('/logout',{},'reader');await denied(route,undefined,'reader',401,'Logged-out token cannot access old preview output');
 await stop();const finalState=await inspect();same(finalState,snapshots,'Revocation and authentication checks leave all settlement and legacy business rows unchanged');
 fs.writeFileSync(path.join(own,'final-database.json'),JSON.stringify(finalState,null,2),{mode:0o600});
}
runChecks().catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{
 await stop();const summary={suite:'approved-policy-preview-http',checks,failures,...(fatal?{error:fatal}:{}),production_touched:false,notifications_sent:false,artifacts:own};
 fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;
});
