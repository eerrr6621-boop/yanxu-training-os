'use strict';
// Synthetic acceptance only: real Main/Auth/Api, fresh synthetic H2, explicit frozen classes.
// HTTP_FORMAL.md plain-language evidence contract; unregistered technical IDs cannot substitute proof.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),net=require('node:net');
const assert=require('node:assert/strict'),{spawn,execFile}=require('node:child_process'),{promisify}=require('node:util');
const runFile=promisify(execFile);
const opts={};for(let i=2;i<process.argv.length;i+=2){assert.ok(['--java','--classes','--product-classes','--app','--mode'].includes(process.argv[i])&&process.argv[i+1]);opts[process.argv[i]]=process.argv[i+1];}
const java=opts['--java']||'java';
const app=fs.realpathSync(opts['--app']||path.resolve(__dirname,'..'));
const classes=fs.realpathSync(opts['--classes']||process.env.CLASSES_DIR||'');
const productClasses=fs.realpathSync(opts['--product-classes']||classes);
assert.ok(fs.existsSync(path.join(productClasses,'com/training/Db.class')),'Explicit product classes required');
const testClasspath=classes+path.delimiter+productClasses+path.delimiter+path.join(app,'lib','*');
assert.ok(fs.existsSync(path.join(classes,'com/training/IntegrationFormalSettlementHttpFixture.class')),'Compile private fixture into isolated test-out first');
const mode=opts['--mode']||'full';assert.ok(['full','prepare'].includes(mode));
const root=fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'yanxu-formal-settlement-http-'));fs.chmodSync(root,0o700);fs.mkdirSync(path.join(root,'data'),{mode:0o700});
// A browser runner may hold the same random secret in memory while preparing and resuming its own fixture.
const password=process.env.INTEGRATION_FORMAL_PASSWORD||crypto.randomBytes(24).toString('hex');assert.match(password,/^[a-f0-9]{48}$/);
const tokens={},failures=[],readPhases=[],pids=[],evidence={};
const env={...process.env,JAVA_TOOL_OPTIONS:'',JDK_JAVA_OPTIONS:'',_JAVA_OPTIONS:'',YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:'',YANXU_LOGIN_MAIL_TRANSPORT:'disabled'};
let child,port,metadata,phase='boot',checks=0,fatal=null,configuration;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const id=prefix=>'SYNTHETIC-'+prefix+'-'+crypto.randomUUID();
function check(ok,label){checks++;if(!ok)failures.push(phase+': '+label);}
function same(actual,expected,label){let ok=true;try{assert.deepEqual(actual,expected);}catch{ok=false;}check(ok,label);}
function decimal(actual,expected,label){check(typeof actual==='string'&&scaled(actual)===scaled(expected),label);}
function scaled(value){assert.match(value,/^-?\d+(\.\d{1,8})?$/);let [whole,fraction='']=value.split('.');return BigInt(whole)*100000000n+(value.startsWith('-')?-1n:1n)*BigInt(fraction.padEnd(8,'0'));}
function persist(name,value){fs.writeFileSync(path.join(root,name),JSON.stringify(value,null,2),{mode:0o600});}
async function request(route,body,actor='reader',overrides={}) {
  const headers={...(tokens[actor]?{'X-Token':tokens[actor]}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})};
  const response=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:overrides.method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:overrides.raw?body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  const text=await response.text();let envelope=null;if(text){try{envelope=JSON.parse(text);}catch{throw Error(`${phase}: ${route}: non-JSON HTTP ${response.status}`);}}
  return {status:response.status,envelope,headers:response.headers};
}
async function api(route,body,actor='reader'){const r=await request(route,body,actor);assert.equal(r.status,200,`${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);assert.equal(r.envelope?.code,0);return r.envelope.data;}
async function denied(route,body,actor,status,label,overrides={}){const r=await request(route,body,actor,overrides);check(r.status===status&&r.envelope?.code===status,`${label} (${r.status}/${r.envelope?.code})`);return r;}
async function login(actor){const username=actor==='admin'?'admin':metadata.accounts[actor].username;tokens[actor]=(await api('/login',{username,password},'anonymous')).token;assert.ok(tokens[actor]);}
async function control(operation,extra={}) {
  const nonce=crypto.randomUUID(),file=path.join(root,'control-request.json'),result=path.join(root,'control-result.json');
  fs.writeFileSync(file+'.next',JSON.stringify({nonce,operation,...extra}),{mode:0o600});fs.renameSync(file+'.next',file);
  for(let i=0;i<300;i++){if(fs.existsSync(result)){const r=JSON.parse(fs.readFileSync(result,'utf8'));if(r.nonce===nonce){assert.ok(!r.error,operation+': '+r.error);return r.data;}}await delay(20);}
  throw Error('Isolated fixture control timed out: '+operation);
}
const snapshot=()=>control('snapshot');
async function readonly(label,work){const before=await snapshot();await work();const after=await snapshot();same(after,before,label+' leaves every PUBLIC table unchanged');readPhases.push({label,tables:Object.keys(before).length,unchanged:JSON.stringify(before)===JSON.stringify(after)});}
function onlyChanged(before,after,allowed,label){const changed=Object.keys(after).filter(k=>JSON.stringify(before[k])!==JSON.stringify(after[k]));check(changed.every(k=>allowed.includes(k)),label+': only permitted tables change');return changed;}
async function boot(resume=false){
  const server=net.createServer();await new Promise((res,rej)=>{server.once('error',rej);server.listen(0,'127.0.0.1',res);});port=server.address().port;await new Promise(res=>server.close(res));
  Object.keys(tokens).forEach(key=>delete tokens[key]);const log=fs.openSync(path.join(root,resume?'server-restart.log':'server.log'),'a',0o600);
  child=spawn(java,['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dlogin.email.mode=legacy','-Dbootstrap.admin.password='+password,'-Dintegration.formal.settlement.root='+root,'-Dintegration.formal.settlement.resume='+resume,'-Ddata.dir='+path.join(root,'data'),'-Dsemantic.port=','-Dsemantic.token=','-Daccount.import.manifest=','-Daccount.import.manifest.sha256=','-cp',testClasspath,'com.training.IntegrationFormalSettlementHttpFixture',String(port)],{cwd:app,env,stdio:['ignore',log,log]});
  fs.closeSync(log);pids.push(child.pid);let error;child.once('error',e=>error=e);
  for(let i=0;i<200;i++){if(error)throw error;assert.equal(child.exitCode,null,'Isolated Main exited before ready');if(fs.existsSync(path.join(root,'metadata.json')))metadata=JSON.parse(fs.readFileSync(path.join(root,'metadata.json'),'utf8'));if(metadata){try{await login('admin');return;}catch{}}await delay(100);}
  throw Error('Isolated Main did not become ready');
}
async function stop(){const running=child;child=null;if(!running||running.exitCode!==null||running.signalCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>running.kill('SIGKILL'),2500);running.once('exit',()=>{clearTimeout(timer);resolve();});running.kill('SIGTERM');});}
async function loginAll(){for(const actor of Object.keys(metadata.accounts))await login(actor);}
async function acceptedProject(org,title,amount){
  const actor=org==='001'?'organizer':'otherorganizer',contact=metadata.accounts[actor].person_code;
  let d=(await api('/demands/draft',{request_id:id('DRAFT'),title,unit:'SYNTHETIC CUSTOMER',business_path:'direct',organization_code:org,internal_contact_code:contact,category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'90',participant_count:'1',budget_amount:'0',objectives:'SYNTHETIC',content:'SYNTHETIC',teacher_req:'',remark:'SYNTHETIC'},actor)).workflow;
  const command=()=>({id:d.id,expected_version:d.version,request_id:id('WORKFLOW')});d=(await api('/demands/submit',command(),actor)).workflow;
  for(const reviewer of ['leader','bp'])d=(await api('/approvals/tasks/'+d.id+'/actions',{action:'APPROVE',expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId:id('APPROVAL'),comment:'SYNTHETIC'},reviewer)).workflow;
  d=(await api('/demands/accept',{...command(),team_code:'900'},'team')).workflow;
  const project=(await api('/projects',undefined,'team')).find(p=>p.id===d.project_id);assert.ok(project?.delivery_controlled);
  await api('/projects',{...project,hours:org==='001'?2.33:1,amount,contract_no:'SYNTHETIC-CONTRACT-'+project.id,owner:'SYNTHETIC',start_date:metadata.service_date,end_date:metadata.service_date,delivery_mode:'线上'},'team');
  await api('/projects/start',{id:project.id},'team');return project.id;
}
async function addDispatch(project,teacher,subject){const dispatch=await api('/dispatches',{project_id:project,teacher_id:teacher,subject,teach_date:metadata.service_date,start_time:'09:00',end_time:'10:00',hours:99,status:'待发送',material_status:'已就绪',remark:'SYNTHETIC'},'team');await api('/dispatches/send',{id:dispatch},'team');await api('/dispatches/confirm',{id:dispatch,accept:1},'team');return dispatch;}
const detail=(dispatch,actor='reader')=>api('/delivery-settlement?dispatch_id='+dispatch,undefined,actor);
const flow=(dispatch,actor='reader')=>api('/delivery-settlement/workflow?dispatch_id='+dispatch,undefined,actor);
async function fact(dispatch,minutes,payable,org='001',complete=true){const writer=org==='001'?'delivery':'outsider',verifier=org==='001'?'verifier':'outsider';let current=await detail(dispatch,writer);current=await api('/delivery-settlement/save',{dispatch_id:dispatch,expected_version:current.version,request_id:id('FACT'),estimated_hours:'5.00',planned_hours:'4.00',actual_minutes:minutes,payable_hours:payable},writer);current=await api('/delivery-settlement/verify',{dispatch_id:dispatch,expected_version:current.version,request_id:id('VERIFY'),evidence_code:id('TEACHING')},verifier);if(complete)await api('/dispatches/complete',{id:dispatch,expected_version:current.version},verifier);return current;}
function proof(kind){return {evidence_note:'仅用于隔离验收：已核对本次'+kind+'的业务内容、金额和凭证，记录与本合成申请一致。',evidence_reference:'合成验收凭证登记簿 / '+kind+' / 第1条'};}
function claim(grade='LECTURER',research='NO'){
  const c={grade,day_type:'WORKDAY',research_team:research,appointed_on:metadata.service_date.replace(/^\d{4}/,year=>String(Number(year)-1)),annual_plan:'YES',customer_paid:'NO',service_date_applicable:'YES'};
  const fields=['grade','day_type','research_team','appointment','annual_plan','customer_paid','applicability'];
  c.evidence_notes=Object.fromEntries(fields.map(name=>[name,{note:'仅用于隔离验收：已核对本次业务的 '+name+'，材料内容与当前合成申报一致。',reference:'合成验收资料登记簿 / '+name+' / 第1条'}]));
  return c;
}
async function command(dispatch,extra={}){const v=await flow(dispatch);return {dispatch_id:dispatch,expected_version:v.version,expected_fact_version:v.fact_version,request_id:id('MONEY'),...extra};}
const operation=(op,body,actor)=>api('/delivery-settlement/workflow/'+op,body,actor);
async function readyClaim(dispatch,contents=claim()) {await operation('save',await command(dispatch,{claim:contents}),'submitter');await operation('submit',await command(dispatch,proof('主办提交')),'submitter');return operation('review',await command(dispatch,{decision:'APPROVE',...proof('授权审核')}),'reviewer');}
async function confirmBody(dispatch){const v=await flow(dispatch);return command(dispatch,{expected_settings_version:v.settings_version});}
async function paymentBody(dispatch){const v=await flow(dispatch);return command(dispatch,{expected_snapshot_code:v.financial.head_snapshot_code,payment_date:metadata.payment_date,amount:v.financial.balance,...proof('实际付款')});}
async function source(project,actor='reporter',exporting=false){return control('financial-source',{project_id:project,token:tokens[actor],export:exporting});}
async function publishWithoutPay(){const current=await api('/organization/config',undefined,'admin');configuration=structuredClone(current.configuration);const next=structuredClone(configuration);next.version='synthetic-formal-revoked-'+crypto.randomUUID();next.grants=next.grants.filter(g=>!(g.roleCode==='PAY'&&g.resource==='settlement.pay'));await api('/organization/config',{expectedVersion:current.version,configuration:next},'admin');return next;}
async function restorePermissions(previous){await login('admin');const next={...configuration,version:'synthetic-formal-restored-'+crypto.randomUUID()};await api('/organization/config',{expectedVersion:previous.version,configuration:next},'admin');await login('admin');await loginAll();}

const caseRead=(key,actor='reader')=>api('/delivery-settlement/cases?case_code='+encodeURIComponent(key),undefined,actor);
const caseOperation=(op,body,actor)=>api('/delivery-settlement/cases/'+op,body,actor);
async function caseCommand(key,extra={}){const v=await caseRead(key);return {project_id:v.project_id,case_code:key,expected_version:v.version,request_id:id('CASE-REQUEST'),...extra};}
function newCase(project,kind,payload,key=id('CASE')){return {project_id:project,case_code:key,expected_version:0,request_id:id('CASE-SAVE'),kind,payload};}
async function caseSubmit(key){return caseOperation('submit',await caseCommand(key,proof('独立事项主办提交')),'submitter');}
async function caseReview(key){return caseOperation('review',await caseCommand(key,{decision:'APPROVE',...proof('独立事项授权审核')}),'reviewer');}
async function caseConfirm(key,actor='confirmer'){return caseOperation('confirm',await caseCommand(key),actor);}
async function completeCase(project,kind,payload,actor='confirmer'){const b=newCase(project,kind,payload);await caseOperation('save',b,'submitter');await caseSubmit(b.case_code);await caseReview(b.case_code);return caseConfirm(b.case_code,actor);}
async function returnCase(key){return caseOperation('review',await caseCommand(key,{decision:'RETURN',...proof('独立事项退回'),reason_note:'仅用于隔离验收：现有资料不足以完成核准，退回主办单位补充。'}),'reviewer');}
async function withdrawCase(key){return caseOperation('withdraw',await caseCommand(key,{reason_note:'仅用于隔离验收：经核对该草稿无需继续办理，保留历史并作废。',reason_reference:'合成作废登记簿/第1条'}),'submitter');}
function headOf(view,chain){const row=view.financial.find(f=>!chain||f.chain.chain_code===chain);assert.ok(row,'Current case chain required');return row.chain;}
async function casePayBody(key,chain,amount){const v=await caseRead(key),head=headOf(v,chain);return caseCommand(key,{chain_code:head.chain_code,expected_entry_code:head.current_entry_code,payment_date:metadata.payment_date,amount,...proof('独立事项实际付款或退款')});}
async function casePay(key,chain,amount){return caseOperation('payment',await casePayBody(key,chain,amount),'payer');}
const reasonProof=()=>({reason_note:'仅用于隔离验收：原记录经逐项复核存在差异，需保留原核准和实际付款并作更正。',reason_reference:'合成更正登记簿/第1条',...proof('更正事实核实')});
function adjustment(head,extra={}){return {chain_code:head.chain_code,expected_entry_code:head.current_entry_code,...reasonProof(),...extra};}
function development(code,joint=false){
  const p={activity:joint?'JOINT_DEVELOPMENT':'SOLO_DEVELOPMENT',service_date:metadata.service_date,completed_on:metadata.service_date,deliverable_code:code,deliverable_version:'V1',lead_teacher_id:metadata.teacher_id,grade:'SENIOR',research_team:'NO',appointed_on:claim().appointed_on,annual_plan:'YES',customer_paid:'UNKNOWN',service_date_applicable:'YES',payable_hours:joint?'2.00':'1.33',development_path:'CUSTOMER_REQUESTED',repetition_percent:'30',previous_grant:'NO',customer_written_payment_agreement:'YES',customer_acceptance:'YES',company_approval:'YES',customer_acceptance_on:metadata.service_date,company_approval_on:metadata.service_date};
  const fields=['grade','research_team','appointment','annual_plan','applicability','payable_hours','repetition','previous_grant','customer_written_payment_agreement','customer_acceptance','company_approval','completion','deliverable'];
  p.evidence_notes=Object.fromEntries(fields.map(field=>[field,{note:'仅用于隔离验收：已查阅并核实开发成果的 '+field+'，与当前合成记录一致。',reference:'合成开发资料登记簿/'+field+'/第1条'}]));
  if(joint){p.allocations=[{teacher_id:metadata.teacher_id,amount:'180.00'},{teacher_id:metadata.alternate_teacher_id,amount:'120.00'}];p.evidence_notes.allocation={note:'仅用于隔离验收：本次明确核准主负责人180元、成员120元，合计300元。',reference:'合成成员金额核准表/第1条'};}return p;
}
function migration(source){return {legacy_source_code:source,legacy_rule_code:'合成旧制课酬原规则〔2025〕第3号',service_date:metadata.service_date,teacher_id:metadata.teacher_id,amount:'250.00',source_note:'仅用于隔离验收：原始台账逐项核对无误，原核准应付250元，不按当前费率重算。',source_reference:'合成旧台账/第3页/第2行',original_approval_note:'仅用于隔离验收：原制度及审批单确认本笔应付250元。',original_approval_reference:'合成旧审批单/第8号',historical_payment:{payment_date:metadata.service_date,amount:'150.00',note:'仅用于隔离验收：按原实际付款回单核对已付150元。',reference:'合成旧付款回单/第8号'}};}
async function negativeCaseApproval(project,kind,payload,label){const b=newCase(project,kind,payload);await caseOperation('save',b,'submitter');await caseSubmit(b.case_code);await readonly(label,async()=>{await denied('/delivery-settlement/cases/review',await caseCommand(b.case_code,{decision:'APPROVE',...proof('拒绝条件核验')}),'reviewer',409,label);});await returnCase(b.case_code);await withdrawCase(b.case_code);return b.case_code;}
async function runCases(project,waiverDispatch,teachingDispatch,mainProject){
  phase='cases read boundary';const base='/delivery-settlement/cases',ledgerBefore=await snapshot();
  await readonly('case list, choices and strict authorization',async()=>{
    same((await api(base+'?project_id='+project)).items,[],'Fresh project has no inferred monetary cases');const options=await api(base+'?project_id='+project+'&view=options');check(options.dispatches.some(d=>d.dispatch_id===waiverDispatch),'Choices use real project dispatches');
    for(const actor of ['admin','outsider','disabled','reporter'])await denied(base+'?project_id='+project,undefined,actor,403,actor+' cannot read case project');await denied(base+'?project_id='+project,undefined,'anonymous',401,'Case read requires session');
    for(const q of ['project_id='+project+'&project_id='+project,'project_id='+project+'&%70roject_id='+project,'project_id='+project+'&view=options&view=options','project_id='+project+'&actor_code=P1','case_code=x&project_id='+project])await denied(base+'?'+q,undefined,'reader',400,'Case query rejects duplicate/unknown combination');
  });
  phase='incomplete and withdrawn cases';
  const partial=newCase(project,'DEVELOPMENT',{}),saved=await caseOperation('save',partial,'submitter');check(saved.state==='DRAFT'&&saved.missing_items.length>0&&saved.payload.service_date===null&&saved.payload.research_team==='UNKNOWN','Missing development facts remain explicitly unknown');
  await readonly('draft replay and untrusted authority',async()=>{same(await caseOperation('save',partial,'submitter'),saved,'Exact draft retry returns same content');await denied(base+'/save',{...partial,payload:{payable_hours:'2.00'}},'submitter',409,'Same request cannot change content');await denied(base+'/save',{...partial,request_id:id('STALE-CASE')},'submitter',409,'A new request cannot reuse an old case version');await denied(base+'/save',{...newCase(project,'DEVELOPMENT',{}),approved:true},'submitter',400,'Client cannot inject approved flag');await denied(base+'/save',newCase(project,'DEVELOPMENT',{rate:'1'}),'submitter',400,'Client cannot inject rate');});
  await caseSubmit(partial.case_code);await readonly('unknown submitted facts and withdrawal',async()=>{await denied(base+'/review',await caseCommand(partial.case_code,{decision:'APPROVE',...proof('未知事实审核')}),'reviewer',409,'Missing facts block approval');await denied(base+'/withdraw',await caseCommand(partial.case_code,{reason_note:'合成用例：提交后的事项不能直接作废。'}),'submitter',409,'Submitted case must return before withdrawal');});
  await returnCase(partial.case_code);const withdrawn=await withdrawCase(partial.case_code);same(withdrawn.state,'WITHDRAWN','Returned case may be withdrawn');check(withdrawn.confirmation===null&&withdrawn.financial.length===0,'Withdrawal creates no accounting');
  await readonly('withdrawn state is immutable',async()=>{await denied(base+'/save',{...await caseCommand(partial.case_code),kind:'DEVELOPMENT',payload:{}},'submitter',409,'Withdrawn draft cannot be revived');});
  const mainDraft=newCase(mainProject,'DEVELOPMENT',{});await caseOperation('save',mainDraft,'submitter');const mainWithdraw=await caseCommand(mainDraft.case_code,{reason_note:'仅用于隔离验收：无需继续办理的草稿，作废后不能阻断已结清项目归档。'});await denied(base+'/withdraw',mainWithdraw,'confirmer',403,'Withdrawal requires submit permission');await caseOperation('withdraw',mainWithdraw,'submitter');await readonly('withdrawal retry creates no duplicate revision or proof',async()=>{same((await caseOperation('withdraw',mainWithdraw,'submitter')).state,'WITHDRAWN','Withdrawal exact retry remains successful');});
  const afterWithdraw=await snapshot();for(const name of ['m05_financial_entries','m05_financial_chains','m05_payment_events'])same(afterWithdraw[name],ledgerBefore[name],'Draft, return and withdrawal preserve '+name);
  phase='development requirements and explicit allocations';
  const badProof=development('SYNTHETIC-UNREGISTERED-PROOF');delete badProof.evidence_notes;badProof.evidence={grade:'UNREGISTERED-SYNTHETIC-EVIDENCE'};await readonly('unregistered evidence rollback',async()=>{await denied(base+'/save',newCase(project,'DEVELOPMENT',badProof),'submitter',409,'Unregistered technical evidence is not proof');});
  const badJoint=development('SYNTHETIC-BAD-ALLOCATION',true);badJoint.allocations[1].amount='119.99';await negativeCaseApproval(project,'DEVELOPMENT',badJoint,'Explicit member amounts must sum to exact pool');
  const noAcceptance=development('SYNTHETIC-NO-ACCEPTANCE');noAcceptance.customer_acceptance='UNKNOWN';delete noAcceptance.customer_acceptance_on;delete noAcceptance.evidence_notes.customer_acceptance;await negativeCaseApproval(project,'DEVELOPMENT',noAcceptance,'Customer acceptance cannot be inferred');
  const repeats=development('SYNTHETIC-REPEATED-RESULT');repeats.previous_grant='YES';await negativeCaseApproval(project,'DEVELOPMENT',repeats,'Previously paid result cannot be paid again');
  const solo=await completeCase(project,'DEVELOPMENT',development('SYNTHETIC-SOLO-ORIGIN'));const soloChain=headOf(solo).chain_code;decimal(headOf(solo).current_amount,'199.50','Solo development uses senior rate times 1.33');
  await negativeCaseApproval(project,'DEVELOPMENT',development('SYNTHETIC-SOLO-ORIGIN'),'Same actual deliverable version cannot be charged twice');
  const self=development('SYNTHETIC-SELF-ORIGIN');Object.assign(self,{development_path:'SELF_INITIATED',customer_written_payment_agreement:'UNKNOWN',customer_acceptance:'UNKNOWN',customer_acceptance_on:null,company_need:'YES',annual_review_passed:'YES',annual_review_on:metadata.service_date});self.evidence_notes.company_need='仅用于隔离验收：本成果符合明确公司需要。';self.evidence_notes.annual_review_passed='仅用于隔离验收：已完成实际年度评审并通过。';
  const selfCase=await completeCase(project,'DEVELOPMENT',self);decimal(headOf(selfCase).current_amount,'199.50','Self-initiated development uses actual qualified review');
  const research=development('SYNTHETIC-RESEARCH-ORIGIN');research.research_team='YES';research.grade=null;delete research.evidence_notes.grade;const researchCase=await completeCase(project,'DEVELOPMENT',research);decimal(headOf(researchCase).current_amount,'133.00','Research solo does not invent missing personal grade');
  phase='paid cross-date development correction and refund';
  await casePay(solo.case_code,soloChain,'199.50');const paidSolo=await caseRead(solo.case_code),oldSoloPayments=paidSolo.financial[0].payment_entries;
  const changedSolo=development('SYNTHETIC-SOLO-ORIGIN');changedSolo.payable_hours='1.00';for(const field of ['service_date','completed_on','customer_acceptance_on','company_approval_on'])changedSolo[field]=metadata.payment_date;
  const soloCorrectionPayload=adjustment(headOf(paidSolo),{service_date:metadata.payment_date,development:changedSolo});const soloCorrection=await completeCase(project,'ADJUSTMENT',soloCorrectionPayload,'adjuster');
  same(soloCorrection.financial[0].payment_entries,oldSoloPayments,'Original developer payment survives correction');same(soloCorrection.financial[0].accrual_entries.map(e=>e.kind),['CONFIRMED','REVERSAL','REBOOK'],'Cross-date correction is paired reversal and rebook');const pair=soloCorrection.financial[0].accrual_entries.slice(-2);check(pair[0].correction_group_code&&pair[0].correction_group_code===pair[1].correction_group_code,'Cross-date entries share one correction group');decimal(pair[0].amount,'-199.50','Original business date fully reversed');decimal(pair[1].amount,'150.00','New business date receives new full amount');decimal(headOf(soloCorrection).balance,'-49.50','Original paid amount creates a real refund balance');
  await readonly('refund direction and amount',async()=>{for(const amount of ['1.00','-49.51'])await denied(base+'/payment',await casePayBody(soloCorrection.case_code,soloChain,amount),'payer',409,'Refund must match outstanding credit');});
  await casePay(soloCorrection.case_code,soloChain,'-20.00');const refund=await casePayBody(soloCorrection.case_code,soloChain,'-29.50'),refundRace={...refund,request_id:id('REFUND-RACE')},refundBefore=await snapshot();
  const refundResults=await Promise.all([request(base+'/payment',refund,'payer'),request(base+'/payment',refundRace,'payer')]);same(refundResults.map(r=>r.status).sort(),[200,409],'Concurrent final refund has one financial effect');const refundAfter=await snapshot();check(refundAfter.m05_payment_events.rows-refundBefore.m05_payment_events.rows===1,'Exactly one final refund cash entry');const refundWinner=refundResults[0].status===200?refund:refundRace;await readonly('refund exact replay',async()=>{await caseOperation('payment',refundWinner,'payer');});decimal(headOf(await caseRead(soloCorrection.case_code)).balance,'0.00','Partial refunds settle exact remaining credit');
  phase='coauthor full-group correction and cancellation';
  const joint=await completeCase(project,'DEVELOPMENT',development('SYNTHETIC-JOINT-ORIGIN',true));same(joint.financial.map(f=>f.chain.current_amount),['180.00','120.00'],'Explicit unequal allocation is retained');check(joint.financial.every(f=>f.accrual_entries[0].hours.PAYABLE===null),'Joint recipients do not each inherit the entire pool hours');
  const jointHeads=joint.financial.map(f=>f.chain),jointPrimary=jointHeads[0].chain_code,jointOther=jointHeads[1].chain_code;await casePay(joint.case_code,jointPrimary,'50.00');
  await negativeCaseApproval(project,'ADJUSTMENT',adjustment(headOf(await caseRead(joint.case_code),jointPrimary),{cancellation:true}),'Coauthor group cannot omit another member');
  const changedJoint=development('SYNTHETIC-JOINT-ORIGIN',true);changedJoint.payable_hours='3.00';changedJoint.allocations=[{teacher_id:metadata.teacher_id,amount:'270.00'},{teacher_id:metadata.alternate_teacher_id,amount:'180.00'}];changedJoint.evidence_notes.allocation='仅用于隔离验收：更正后明确核准主负责人270元、成员180元，合计450元。';for(const field of ['service_date','completed_on','customer_acceptance_on','company_approval_on'])changedJoint[field]=metadata.payment_date;
  const groupCurrent=await caseRead(joint.case_code),groupPayload=adjustment(headOf(groupCurrent,jointPrimary),{service_date:metadata.payment_date,development:changedJoint,expected_entries:{[jointOther]:headOf(groupCurrent,jointOther).current_entry_code}});const groupBody=newCase(project,'ADJUSTMENT',groupPayload);await caseOperation('save',groupBody,'submitter');await caseSubmit(groupBody.case_code);await caseReview(groupBody.case_code);
  await readonly('adjustment requires both confirm and correct',async()=>{for(const actor of ['confirmer','corrector','payer'])await denied(base+'/confirm',await caseCommand(groupBody.case_code),actor,403,actor+' lacks one required adjustment permission');});
  const groupCorrected=await caseConfirm(groupBody.case_code,'adjuster');same(groupCorrected.financial.map(f=>f.chain.current_amount),['270.00','180.00'],'Full-group correction retains members and explicit amounts');check(groupCorrected.financial.every(f=>f.accrual_entries.slice(-2).map(e=>e.kind).join(',')==='REVERSAL,REBOOK'),'Every member receives an atomic cross-date pair');
  const groupCancel=await completeCase(project,'ADJUSTMENT',adjustment(headOf(groupCorrected,jointPrimary),{cancellation:true,expected_entries:{[jointOther]:headOf(groupCorrected,jointOther).current_entry_code}}),'adjuster');check(groupCancel.financial.every(f=>scaled(f.chain.current_amount)===0n),'Cancellation covers all joint members');decimal(headOf(groupCancel,jointPrimary).balance,'-50.00','Cancellation preserves actual payment for refund');await casePay(groupCancel.case_code,jointPrimary,'-50.00');
  const researchJoint=development('SYNTHETIC-RD-JOINT-ORIGIN',true);Object.assign(researchJoint,{research_team:'YES',grade:null,joint_reference_grade:'LECTURER',allocations:[{teacher_id:metadata.teacher_id,amount:'120.00'},{teacher_id:metadata.alternate_teacher_id,amount:'80.00'}]});delete researchJoint.evidence_notes.grade;researchJoint.evidence_notes.joint_basis='仅用于隔离验收：本案研发负责人合作开发总池按讲师标准逐案核准。';researchJoint.evidence_notes.allocation='仅用于隔离验收：主负责人120元、成员80元明确分配，合计200元。';const rdJoint=await completeCase(project,'DEVELOPMENT',researchJoint);same(rdJoint.financial.map(f=>f.chain.current_amount),['120.00','80.00'],'Explicit research coauthor basis retains unequal member amounts');
  phase='proven original legacy balance';
  const legacyBody=newCase(project,'MIGRATION',migration('合成旧系统/2025课酬台账/第8笔'));await caseOperation('save',legacyBody,'submitter');await caseSubmit(legacyBody.case_code);await caseReview(legacyBody.case_code);
  await readonly('historical cash import double permission',async()=>{await denied(base+'/confirm',await caseCommand(legacyBody.case_code),'confirmer',403,'Historical payment import requires settlement.pay as well as confirm');});
  const legacy=await caseConfirm(legacyBody.case_code,'importer'),legacyChain=headOf(legacy).chain_code;decimal(headOf(legacy).current_amount,'250.00','Migration preserves proven original amount');decimal(headOf(legacy).paid_amount,'150.00','Only proven historical payment is imported');decimal(headOf(legacy).balance,'100.00','Original outstanding balance retained');check(legacy.financial[0].payment_entries[0].historical===true,'Historical payment explicitly identified');same(legacy.financial[0].accrual_entries[0].hours,{ESTIMATED:null,PLANNED:null,ACTUAL:null,PAYABLE:null},'Unknown old hours stay unknown');
  await negativeCaseApproval(project,'MIGRATION',migration('合成旧系统/2025课酬台账/第8笔'),'Original old ledger source cannot be imported twice');
  await readonly('confirmed legacy cannot be edited or withdrawn',async()=>{await denied(base+'/save',{...await caseCommand(legacyBody.case_code),kind:'MIGRATION',payload:migration('合成旧系统/2025课酬台账/第8笔')},'submitter',409,'Confirmed source cannot overwrite original facts');await denied(base+'/withdraw',await caseCommand(legacyBody.case_code,{reason_note:'合成已确认事项不能直接作废，须有核准调整。'}),'submitter',409,'Confirmed cash-bearing case needs adjustment');});
  const legacyChange=adjustment(headOf(legacy),{service_date:metadata.payment_date,amount:'200.00',legacy_rule_code:'合成旧制课酬原规则〔2025〕第3号',source_note:'仅用于隔离验收：原旧制台账复核后应付更正为200元，保留原制度。',original_approval_note:'仅用于隔离验收：原规则下更正核准应付200元。'});const legacyCorrection=await completeCase(project,'ADJUSTMENT',legacyChange,'adjuster');decimal(headOf(legacyCorrection).balance,'50.00','Legacy correction retains previous payment and original rule');same(legacyCorrection.financial[0].payment_entries,legacy.financial[0].payment_entries,'Legacy old payment remains untouched');await casePay(legacyCorrection.case_code,legacyChain,'50.00');
  phase='waiver and already-paid teaching correction';
  const waiver=await completeCase(project,'WAIVER',{dispatch_id:waiverDispatch,reason_note:'仅用于隔离验收：该次实际授课已逐案核准不计课酬。',...proof('免付授课事实')});decimal(headOf(waiver).current_amount,'0.00','Explicit waiver is a zero accrual');decimal(waiver.financial[0].accrual_entries[0].hours.ACTUAL,'1.00','Waiver keeps actual teaching');decimal(waiver.financial[0].accrual_entries[0].hours.PAYABLE,'0.00','Waiver explicitly sets payable zero');same(waiver.financial[0].payment_entries,[],'Waiver invents no cash payment');
  const originalFact=await detail(teachingDispatch),teaching=await flow(teachingDispatch);const teachingPayload=adjustment(teaching.financial.chain,{service_date:metadata.payment_date,claim:claim(),hours:{estimated:'5.00',planned:'4.00',actual_minutes:'90',payable:'2.00'}});const teachingCase=await completeCase(mainProject,'ADJUSTMENT',teachingPayload,'adjuster');
  decimal(headOf(teachingCase).balance,'125.00','Previously paid senior teaching changes from 75 to lecturer 200 with exact supplement');same(await detail(teachingDispatch),originalFact,'Independent adjustment preserves original verified teaching facts and snapshots');await casePay(teachingCase.case_code,headOf(teachingCase).chain_code,'125.00');
  await readonly('frozen cases and source only',async()=>{const opts=await api(base+'?project_id='+mainProject+'&view=options');check(opts.deliverables.some(d=>d.project_id===project&&d.deliverable_code==='SYNTHETIC-SOLO-ORIGIN'),'Same-organization real deliverables visible across trusted projects');const current=await caseRead(legacyBody.case_code);check(current.payload.legacy_rule_code==='合成旧制课酬原规则〔2025〕第3号','Original Chinese rule identifier retained');await denied(base+'?case_code='+legacy.case_code,undefined,'outsider',403,'Case-code read rechecks stored institution');const copied=structuredClone(current.payload);await denied(base+'/save',newCase(project,'MIGRATION',copied),'submitter',409,'Evidence codes cannot be copied to a different case');});
  evidence.cases={project_id:project,withdrawn_case:mainDraft.case_code,solo:solo.case_code,solo_correction:soloCorrection.case_code,joint:joint.case_code,joint_correction:groupCorrected.case_code,joint_cancel:groupCancel.case_code,self:selfCase.case_code,research:researchCase.case_code,research_joint:rdJoint.case_code,legacy:legacy.case_code,legacy_correction:legacyCorrection.case_code,waiver:waiver.case_code,paid_teaching:teachingCase.case_code};
}

async function binary(route,actor='reporter') {const response=await fetch(`http://127.0.0.1:${port}/api${route}`,{headers:tokens[actor]?{'X-Token':tokens[actor]}:{},signal:AbortSignal.timeout(15000)});return {status:response.status,headers:response.headers,bytes:Buffer.from(await response.arrayBuffer())};}
function reportQuery(extra={}){return new URLSearchParams({start:metadata.service_date,end:metadata.payment_date,date_basis:'TEACHING',organizations:'001',...extra}).toString();}
async function readWorkbook(filename){const result=await runFile(java,['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dlogin.email.mode=legacy','-Dbootstrap.admin.password='+password,'-Dintegration.formal.settlement.root='+root,'-Ddata.dir='+path.join(root,'data'),'-cp',testClasspath,'com.training.IntegrationFormalSettlementHttpFixture','xlsx',filename],{cwd:app,env,timeout:15000,maxBuffer:2*1024*1024});return JSON.parse(result.stdout);}
async function reports(){
  phase='real financial report HTTP and export gate';const route='/management-settlement-reports',query=reportQuery();
  await readonly('formal report read and missing-code export',async()=>{
    const r=await api(route+'?'+query,undefined,'reporter');same(r.source_coverage,'APPROVED_FROZEN_LEDGER_ONLY','Reporting uses approved frozen source only');decimal(r.totals.amount,'1282.50','Accrual report reconciles known independent totals after all replacements and cancellations');check(r.code_gaps.length>0&&r.export_available===false&&r.can_export===true,'Missing formal codes block export without denying amount visibility');
    check(r.details.some(d=>d.activity==='LEGACY')&&r.details.some(d=>d.activity==='SOLO_DEVELOPMENT')&&r.details.some(d=>d.activity==='JOINT_DEVELOPMENT')&&r.details.some(d=>d.activity==='TEACHING'),'Every formal business branch reaches original report endpoint');
    check(r.details.every(d=>d.teacher_code===null&&d.course_code===null),'Unknown formal IDs remain null');same(r.totals.hours.ACTUAL.total,null,'Unknown development and original hours are not silently zeroed');
    const exported=await binary(route+'/export?'+query+'&snapshot_version='+r.snapshot_version);same(exported.status,409,'Uncoded real entries cannot produce official export');check(!exported.headers.has('content-disposition')&&exported.headers.get('content-type')?.includes('application/json'),'Failed export is JSON without attachment headers');
    const p=await api(route+'?'+reportQuery({date_basis:'PAYMENT',rank_metric:'FEE'}),undefined,'reporter');decimal(p.totals.amount,'750.00','Cash report reconciles 400 teaching + 150 developer + 200 legacy net payments');decimal(p.totals.negative_amount,'-99.50','Cash report includes actual developer and coauthor refunds exactly once');check(p.details.some(d=>d.kind==='REFUND')&&p.details.some(d=>d.kind==='PAYMENT'),'Actual refunds and payments are independently visible');check(Object.values(p.totals.hours).every(h=>h.total===null&&h.known_subtotal===null&&h.missing_count===null&&h.applicable===false),'Payment statistics never duplicate teaching hours');
    for(const actor of ['admin','reader','outsider'])await denied(route+'?'+query,undefined,actor,403,actor+' cannot view outside reporting permissions');await denied(route+'?'+query,undefined,'anonymous',401,'Reporting requires true session');
    await denied(route+'?'+reportQuery({organizations:'001,002'}),undefined,'reporter',403,'Report selection cannot include another institution');
    for(const suffix of ['&organizations=001','&%6frganizations=001','&amount=100'])await denied(route+'?'+query+suffix,undefined,'reporter',400,'Report rejects duplicate or injected facts');
    await denied(route+'?'+reportQuery({date_basis:'PAYMENT',rank_metric:'HOURS'}),undefined,'reporter',400,'Payment cannot rank duplicated hours');
    evidence.reports={teaching:r,payment:p};
  });
  const empty={start:'1900-01-01',end:'1900-01-31'},emptyQuery=reportQuery(empty);
  await readonly('empty formal month produces safe real XLSX',async()=>{
    const current=await api(route+'?'+emptyQuery,undefined,'reporter');decimal(current.totals.amount,'0.00','Empty month amount is zero');same(current.details,[],'Empty month contains no synthetic placeholder facts');check(current.export_available,'Empty month has no missing-code exception to invent');
    await denied(route+'/export?'+emptyQuery,undefined,'reporter',400,'Download requires current snapshot');await denied(route+'/export?'+emptyQuery+'&snapshot_version='+'0'.repeat(64),undefined,'reporter',409,'Download compares current snapshot');
    const readOnly=await api(route+'?'+emptyQuery,undefined,'reportreader');check(readOnly.permissions.read&&!readOnly.permissions.export,'Read-only report account has no export');await denied(route+'/export?'+emptyQuery+'&snapshot_version='+readOnly.snapshot_version,undefined,'reportreader',403,'Export requires separate current permission');
    const file=await binary(route+'/export?'+emptyQuery+'&snapshot_version='+current.snapshot_version);same(file.status,200,'Empty month downloads actual workbook');check(file.headers.get('content-type')?.startsWith('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),'Real XLSX content type');check(file.headers.get('content-disposition')?.includes('settlement-teaching-19000101-19000131.xlsx'),'Safe ASCII attachment name');check(file.headers.get('cache-control')?.includes('no-store'),'Sensitive workbook is not cached');check(file.bytes.subarray(0,4).equals(Buffer.from([80,75,3,4])),'Response is ZIP bytes');
    const filename=path.join(root,'empty-formal-month.xlsx');fs.writeFileSync(filename,file.bytes,{mode:0o600});const book=await readWorkbook(filename);same(book.sheets,['月度概览','讲师排名','机构汇总','对账明细','说明'],'All five workbook sheets parse as XML');check(book.formulas===0&&book.external_links===0&&!book.entries.some(n=>/vba|macro|externalLink|embedded|oleObject/i.test(n)),'Workbook contains no formulas, macros or external connections');check(!/SYNTHETIC FORMAL TEACHER|合成旧台账|合成旧审批单|实际付款或退款|@|TEACHER-/.test(book.text),'Workbook excludes names, proof text, contacts and system teacher IDs');evidence.workbook={bytes:file.bytes.length,sha256:crypto.createHash('sha256').update(file.bytes).digest('hex'),sheets:book.sheets};
  });
}

async function run(){
  await boot();await loginAll();phase='trusted HTTP project setup';
  const project=await acceptedProject('001','SYNTHETIC FORMAL PROJECT',500),other=await acceptedProject('002','SYNTHETIC OTHER PROJECT',0),caseProject=await acceptedProject('001','SYNTHETIC INDEPENDENT CASE PROJECT',0);
  const first=await addDispatch(project,metadata.teacher_id,'SYNTHETIC TEACHING A'),second=await addDispatch(project,metadata.alternate_teacher_id,'SYNTHETIC TEACHING B'),foreign=await addDispatch(other,metadata.teacher_id,'SYNTHETIC OTHER TEACHING');
  const waiverDispatch=await addDispatch(caseProject,metadata.teacher_id,'SYNTHETIC WAIVER TEACHING');
  const a=await fact(first,'60','1.33'),b=await fact(second,'45','0.50');await fact(foreign,'45','1.00','002');await fact(waiverDispatch,'45',null);
  decimal(a.fact.hours.actual,'1.33','60 minutes use exact 45-minute conversion');decimal(b.fact.hours.payable,'0.50','Payable hours remain independent of actual');
  evidence.setup={project_id:project,dispatch_ids:[first,second],other_project_id:other,other_dispatch_id:foreign,cases_project_id:caseProject,waiver_dispatch_id:waiverDispatch};persist('http-setup.json',evidence.setup);
  if(mode!=='full')return;
  phase='strict read and authorization';
  await readonly('fresh formal read and rejections',async()=>{
    const v=await flow(first);same([v.version,v.fact_version,v.state,v.claim],[0,a.version,'DRAFT',null],'No automatic monetary claim');same(v.financial.amount,null,'Missing claim never becomes zero');
    for(const actor of ['admin','disabled','outsider','reporter'])await denied('/delivery-settlement/workflow?dispatch_id='+first,undefined,actor,403,actor+' has no implicit financial read');
    await denied('/delivery-settlement/workflow?dispatch_id='+first,undefined,'anonymous',401,'Real authentication required');
    for(const query of ['dispatch_id='+first+'&dispatch_id='+first,'dispatch_id='+first+'&%64ispatch_id='+first,'dispatch_id='+first+'&organization_code=001','dispatch_id=1.5','dispatch_id=9007199254740992'])await denied('/delivery-settlement/workflow?'+query,undefined,'reader',400,'Strict workflow query');
    for(const op of ['configure','confirm','correct'])await denied('/delivery-settlement/'+op,{},'settings',409,'Legacy '+op+' remains closed');
  });
  const invariantBefore=await snapshot();
  phase='claim authorization and input';
  const initial=await command(first,{claim:claim()});
  await readonly('claim input errors and separation of duties',async()=>{
    for(const actor of ['admin','reviewer','confirmer','payer','outsider','disabled'])await denied('/delivery-settlement/workflow/save',initial,actor,403,actor+' cannot submit claim');
    for(const injected of [{...initial,amount:'0.01'},{...initial,actor_code:'P1'},{...initial,claim:{...initial.claim,unit_rate:'0.01'}},{...initial,expected_fact_version:initial.expected_fact_version-1}])await denied('/delivery-settlement/workflow/save',injected,'submitter',Object.hasOwn(injected,'expected_fact_version')&&injected.expected_fact_version!==initial.expected_fact_version?409:400,'Untrusted rate/actor or stale fact rejected');
  });
  const saved=await operation('save',initial,'submitter');decimal(saved.calculation_preview.amount,'133.00','Server workday rate, not legacy teacher fee_rate');check(saved.calculation_preview.confirmed===false&&saved.financial.amount===null,'Preview creates no confirmed amount');
  await readonly('identical saved claim replay',async()=>{const again=await operation('save',initial,'submitter');same(again.version,saved.version,'Replay does not create claim revision');});
  await operation('submit',await command(first,proof('主办提交')),'submitter');
  await readonly('approval is independent',async()=>{const review=await command(first,{decision:'APPROVE',...proof('授权审核')});for(const actor of ['submitter','confirmer','payer'])await denied('/delivery-settlement/workflow/review',review,actor,403,actor+' cannot approve');await denied('/delivery-settlement/workflow/confirm',await confirmBody(first),'confirmer',409,'Unreviewed request cannot confirm');});
  await operation('review',await command(first,{decision:'APPROVE',...proof('授权审核')}),'reviewer');
  const staleConfirm=await confirmBody(first);await fact(first,'60','1.33');
  await readonly('fact revision invalidates monetary approval',async()=>{await denied('/delivery-settlement/workflow/confirm',staleConfirm,'confirmer',409,'Old reviewed fact version cannot confirm');await denied('/delivery-settlement/workflow/confirm',await confirmBody(first),'confirmer',409,'Fresh request version cannot reuse old approval');});
  await readyClaim(first);
  phase='concurrent confirmation';
  const confirm1=await confirmBody(first),confirm2={...confirm1,request_id:id('RACING-CONFIRM')},beforeConfirm=await snapshot();
  const results=await Promise.all([request('/delivery-settlement/workflow/confirm',confirm1,'confirmer'),request('/delivery-settlement/workflow/confirm',confirm2,'confirmer')]);
  same(results.map(r=>r.status).sort(),[200,409],'Concurrent distinct confirmations create exactly one snapshot');
  evidence.concurrent_confirm=results.map((r,i)=>({request_id:[confirm1,confirm2][i].request_id,status:r.status,response:r.envelope}));
  const afterConfirm=await snapshot();check(afterConfirm.m05_financial_entries.rows-beforeConfirm.m05_financial_entries.rows===1&&afterConfirm.m05_settlement_snapshots.rows-beforeConfirm.m05_settlement_snapshots.rows===1,'Confirmation inserts one immutable financial entry and snapshot');
  const firstSnapshot=(await detail(first)).snapshots;assert.equal(firstSnapshot.length,1);const head=(await flow(first)).financial.head_snapshot_code;
  await readonly('confirmation idempotency and approved-version compare',async()=>{const winner=results[0].status===200?confirm1:confirm2;await operation('confirm',winner,'confirmer');await denied('/delivery-settlement/workflow/confirm',{...winner,request_id:id('DUPLICATE-CONFIRM')},'confirmer',409,'New request cannot re-confirm old revision');});
  phase='same-date unpaid correction';
  const reason={reason_note:'仅用于隔离验收：重新核对合成授课原始记录后，实际分钟及计酬课时需按当前已核对版本更正。',reason_reference:'合成验收更正登记簿 / 第1条'};
  const unchanged=await confirmBody(first);await denied('/delivery-settlement/workflow/correct',{...unchanged,expected_snapshot_code:head,...reason},'corrector',409,'Same fact cannot bypass a new reviewed correction');
  await fact(first,'90','2.00');await readyClaim(first);
  const correction={...await confirmBody(first),expected_snapshot_code:head,...reason};
  await readonly('correction snapshot and settings compare',async()=>{await denied('/delivery-settlement/workflow/correct',{...correction,expected_snapshot_code:'SYNTHETIC-STALE'},'corrector',409,'Wrong head cannot correct');await denied('/delivery-settlement/workflow/correct',{...correction,expected_settings_version:'SYNTHETIC-STALE'},'corrector',409,'Wrong settings version cannot correct');});
  const corrected=await operation('correct',correction,'corrector');decimal(corrected.financial.amount,'200.00','Correction stores replacement total');decimal(corrected.financial.balance,'200.00','Replacement total is outstanding before payment');
  same((await detail(first)).snapshots[0],firstSnapshot[0],'First snapshot remains immutable after correction');
  let financial=await source(project);same(financial.schema_version,'M05-FINANCIAL-1','Formal reporting source version');same(financial.accrual_entries.map(e=>e.kind),['CONFIRMED','ADJUSTMENT'],'Same-date correction appends difference only');decimal(financial.accrual_entries[1].amount,'67.00','Difference is 200 - 133');decimal(financial.accrual_entries[1].hours.ACTUAL,'0.67','Actual-hour difference preserves 2.00 - 1.33');
  phase='second teaching approval and project totals';
  await readyClaim(second,claim('SENIOR'));await operation('confirm',await confirmBody(second),'confirmer');
  let projectView=(await api('/projects',undefined,'team')).find(row=>row.id===project);decimal(projectView.delivery_settlement.confirmed_amount,'275.00','Project adds both current teaching amounts, not original plus replacement');decimal(projectView.delivery_settlement.paid_amount,'0.00','Approved is independent of paid');
  const invariantAfter=await snapshot();onlyChanged(invariantBefore,invariantAfter,Object.keys(invariantAfter).filter(name=>name.startsWith('m05_')),'Monetary workflow preserves original users, roles, teacher, business, fees and outbox tables');
  phase='payment permissions and fresh revalidation';
  const payment=await paymentBody(first);
  await readonly('payment invalid requests',async()=>{
    for(const actor of ['admin','submitter','reviewer','confirmer','outsider'])await denied('/delivery-settlement/workflow/payment',payment,actor,403,actor+' cannot register payment');
    for(const [change,status] of [[{amount:'200.01'},409],[{amount:'-200.00'},400],[{amount:200},400],[{expected_snapshot_code:head},409],[{expected_version:payment.expected_version-1},409]])await denied('/delivery-settlement/workflow/payment',{...payment,...change},'payer',status,'Invalid amount or stale monetary basis rejected: '+JSON.stringify(change));
  });
  await control('revoke',{actor:'payer'});await denied('/delivery-settlement/workflow/payment',payment,'payer',401,'Revoked real session cannot pay');await login('payer');
  const revoked=await publishWithoutPay();await denied('/delivery-settlement/workflow/payment',payment,'payer',401,'Configuration publication revokes stale sessions');await login('payer');await denied('/delivery-settlement/workflow/payment',payment,'payer',403,'Fresh session still lacks revoked pay permission');await restorePermissions(revoked);
  phase='concurrent payment and no duplicate finance';
  const payment2={...payment,request_id:id('RACING-PAY')},beforePay=await snapshot();
  const paid=await Promise.all([request('/delivery-settlement/workflow/payment',payment,'payer'),request('/delivery-settlement/workflow/payment',payment2,'payer')]);same(paid.map(r=>r.status).sort(),[200,409],'Concurrent distinct payments record one cash event');
  evidence.concurrent_payment=paid.map((r,i)=>({request_id:[payment,payment2][i].request_id,status:r.status,response:r.envelope}));
  const afterPay=await snapshot();same(afterPay.m05_financial_entries,beforePay.m05_financial_entries,'Payment does not repeat accrual or teaching hours');check(afterPay.m05_payment_events.rows-beforePay.m05_payment_events.rows===1,'One payment event stored');
  const winningPay=paid[0].status===200?payment:payment2;
  await readonly('payment replay remains authorized and single-use financial effect',async()=>{const v=await operation('payment',winningPay,'payer');same(v.state,'PAID','Exact replay returns completed state');decimal(v.financial.balance,'0.00','Fully paid balance is zero');});
  await operation('payment',await paymentBody(second),'payer');
  financial=await source(project,'reporter',true);same(financial.accrual_entries.map(e=>e.amount),['133.00','67.00','75.00'],'Accrual history retains full first value and one difference');same(financial.payment_entries.map(e=>e.amount),['200.00','75.00'],'Cash records stay independent of accrual entries');
  check(financial.payment_entries.every(e=>e.kind==='PAYMENT'&&e.historical===false&&e.actor_code===metadata.accounts.payer.person_code),'Payment records identify actual authorized payer');check(financial.accrual_entries.every(e=>e.teacher_code===null&&e.course_code===null&&e.system_teacher_code.startsWith('TEACHER-')),'System teacher references never masquerade as formal personnel/course codes');
  await readonly('reporting permission and immutable source',async()=>{same(await source(project,'outsider'),{status:403},'Other organization cannot receive financial source');same(await source(project,'reader'),{status:403},'Delivery read is not reports read');same(await source(project),financial,'Report source excludes export permission from frozen content');});
  const casesBefore=await snapshot();await runCases(caseProject,waiverDispatch,second,project);const casesAfter=await snapshot();onlyChanged(casesBefore,casesAfter,Object.keys(casesAfter).filter(name=>name.startsWith('m05_')),'Independent cases never mutate original business, identity, teacher or old fee tables');await reports();
  phase='receivables and archive';
  await api('/projects/complete',{id:project},'team');let gate=await api('/projects/check?id='+project+'&action=archive',undefined,'team');check(!gate.ready&&gate.blockers.some(b=>b.code==='no_charge'),'Paid remuneration does not excuse missing receivable');await denied('/projects/archive',{id:project},'team',400,'Original project transition contract blocks uncollected contract');
  const charge=await api('/charges',{project_id:project,amount:400,invoice:'SYNTHETIC',remark:'SYNTHETIC'},'team');gate=await api('/projects/check?id='+project+'&action=archive',undefined,'team');check(!gate.ready&&gate.blockers.some(b=>b.code==='charge_mismatch'),'Receivable total must match contract');
  const chargeRow=(await api('/charges',undefined,'team')).find(row=>row.id===charge);await api('/charges',{...chargeRow,amount:500},'team');await api('/charges/receive',{id:charge,amount:499},'team');gate=await api('/projects/check?id='+project+'&action=archive',undefined,'team');check(!gate.ready&&gate.blockers.some(b=>b.code==='outstanding'),'Partial receipt cannot archive');
  await api('/charges/receive',{id:charge,amount:1},'team');gate=await api('/projects/check?id='+project+'&action=archive',undefined,'team');check(gate.ready,'All reviewed delivery, payments and contract collection allow archive');await api('/projects/archive',{id:project},'team');
  await readonly('archived project mutation guards',async()=>{await denied('/delivery-settlement/workflow/save',await command(first,{claim:claim()}),'submitter',409,'Archived claim cannot change');await denied('/delivery-settlement/workflow/payment',{...winningPay,request_id:id('ARCHIVED-PAY')},'payer',409,'Archived payment cannot create another cash entry');const d=await detail(first);await denied('/delivery-settlement/save',{dispatch_id:first,expected_version:d.version,request_id:id('ARCHIVED-FACT'),actual_minutes:'45'},'delivery',409,'Archived teaching facts cannot change');});
  const coldSource=await source(project),coldViews=[await flow(first),await flow(second)],coldCases=await api('/delivery-settlement/cases?project_id='+caseProject),beforeRestart=await snapshot();await stop();await boot(true);await loginAll();
  phase='cold restart';await readonly('cold restart retains frozen state',async()=>{same(await source(project),coldSource,'Financial source survives restart exactly');same([await flow(first),await flow(second)],coldViews,'Workflow claims and capabilities survive restart');same(await api('/delivery-settlement/cases?project_id='+caseProject),coldCases,'Independent cases and audit persist on restart');same(await snapshot(),beforeRestart,'Restart/init does not write existing data');});
  evidence.financial_source=coldSource;evidence.final_snapshot=await snapshot();
}

(async()=>{
  try{await run();}
  catch(error){fatal=String(error?.stack||error).replaceAll(password,'[REDACTED]');}
  finally{await stop();const stopped=pids.every(pid=>{try{process.kill(pid,0);return false;}catch{return true;}});check(stopped,'Every temporary Main process stopped');persist('read-only-phases.json',readPhases);persist('synthetic-evidence.json',evidence);const summary={suite:'formal-settlement-http',mode,checks,failures,read_only_phases:readPhases.length,tables_per_read_phase:readPhases[0]?.tables??0,...(fatal?{error:fatal}:{}),classes,artifacts:root,child_processes_stopped:stopped,production_touched:false,notifications_sent:false};persist('result.json',summary);console.log(JSON.stringify(summary));if(fatal||failures.length)process.exitCode=1;}
})();
