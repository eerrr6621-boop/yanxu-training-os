'use strict';
// Genuine Auth/M01/M05/M08 HTTP, random loopback port and a fresh synthetic H2 only.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn,execFile}=require('node:child_process'),{promisify}=require('node:util'),run=promisify(execFile),{workflowConfiguration}=require('./IntegrationWorkflowHttpFixture.cjs'),args={};
for(let i=2;i<process.argv.length;i+=2){assert.ok(['--java','--classes'].includes(process.argv[i])&&process.argv[i+1],'Use --java PATH --classes DIRECTORY or CLASSES_DIR');args[process.argv[i]]=process.argv[i+1];}
const java=args['--java']||'java',repo=path.resolve(__dirname,'..'),classes=fs.realpathSync(args['--classes']||process.env.CLASSES_DIR||'');
assert.ok(classes!==repo&&!classes.startsWith(repo+path.sep),'Use isolated classes outside repository');assert.ok(fs.existsSync(path.join(classes,'com/training/IntegrationSummaryComparisonHttpFixture.class')),'Compile scripts-only comparison fixture');
const own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-summary-comparison-http-')),data=path.join(own,'data');fs.mkdirSync(data);
const password=crypto.randomBytes(24).toString('hex'),tokens={},failures=[],servicePids=[],readEvidence=[],revisions=new Map(),actors=['admin','leader','bp','team','outsider','editor','textonly','readonly'];
const env={...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''};let child,port,checks=0,serial=0,configuration,phase='setup',fatal=null;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function check(ok,label){checks++;if(!ok)failures.push(phase+': '+label);}
function same(a,b,label){let ok=true;try{assert.deepEqual(a,b);}catch{ok=false;}check(ok,label);}
function options(resume=false){return ['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dbootstrap.admin.password='+password,'-Dintegration.summary.comparison.root='+own,'-Dintegration.summary.comparison.resume='+resume,'-Ddata.dir='+data,'-Dsemantic.port=','-Dsemantic.token=','-Daccount.import.manifest=','-Daccount.import.manifest.sha256=','-cp',classes+path.delimiter+path.join(repo,'lib','*')];}
async function request(route,body,actor='editor',overrides={}){
 const headers={...(tokens[actor]?{'X-Token':tokens[actor]}:{})};if(body!==undefined)headers['Content-Type']='application/json';
 const response=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:overrides.method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
 const text=await response.text();let envelope=null;if(text){try{envelope=JSON.parse(text);}catch{throw new Error(route+': non-JSON HTTP '+response.status);}}return {status:response.status,text,envelope,headers:response.headers};
}
async function api(route,body,actor='editor'){const r=await request(route,body,actor);assert.equal(r.status,200,phase+': '+route+': '+r.status+': '+r.envelope?.msg);assert.equal(r.envelope?.code,0,route);return r.envelope.data;}
async function denied(route,body,actor,status,label,overrides={}){const r=await request(route,body,actor,overrides);check(r.status===status&&(overrides.method==='HEAD'?r.text==='':r.envelope?.code===status),label+' (HTTP '+r.status+': '+r.envelope?.msg+')');check(!r.headers.has('content-disposition')&&!r.envelope?.data?.before&&!r.envelope?.data?.after,'Failure exposes no revision side or attachment');check(!r.text.includes('SYNTHETIC FIRST BODY')&&!r.text.includes('SYNTHETIC SECOND BODY')&&!r.text.includes('SYNTHETIC-PRIVATE'),'Failure does not leak private content');return r;}
async function login(actor){tokens[actor]=(await api('/login',{username:actor==='admin'?'admin':'synthetic-summary-comparison-'+actor,password},'anonymous')).token;}
async function logins(){for(const actor of actors)await login(actor);}
async function boot(resume=false){for(const key of Object.keys(tokens))delete tokens[key];const listener=net.createServer();await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});port=listener.address().port;await new Promise(resolve=>listener.close(resolve));child=spawn(java,[...options(resume),'com.training.IntegrationSummaryComparisonHttpFixture',String(port)],{cwd:repo,env,stdio:'ignore'});if(child.pid)servicePids.push(child.pid);let error;child.once('error',e=>{error=e;});for(let i=0;i<150;i++){if(error)throw error;assert.equal(child.exitCode,null,'Synthetic comparison Main exited');try{await login('admin');return;}catch{}await pause(100);}throw new Error('Synthetic Main startup timeout');}
async function stop(){const running=child;child=null;if(!running||running.exitCode!==null||running.signalCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{if(running.exitCode===null&&running.signalCode===null)running.kill('SIGKILL');},2000);running.once('exit',()=>{clearTimeout(timer);resolve();});running.kill('SIGTERM');});}
async function control(operation){const nonce=crypto.randomUUID(),tmp=path.join(own,'control-request.next');fs.writeFileSync(tmp,JSON.stringify({nonce,operation}),{mode:0o600});fs.renameSync(tmp,path.join(own,'control-request.json'));for(let i=0;i<200;i++){try{const ack=JSON.parse(fs.readFileSync(path.join(own,'control-result.json'),'utf8'));if(ack.nonce===nonce&&ack.ok)return ack.data;}catch{}await pause(20);}throw new Error('Synthetic control timeout: '+operation);}
async function inspect(){assert.ok(!child);const r=await run(java,[...options(true),'com.training.IntegrationSummaryComparisonHttpFixture','inspect'],{cwd:repo,env,timeout:20000,maxBuffer:65536});return JSON.parse(r.stdout.trim());}
async function readOnly(label,operation){phase=label;const before=await control('snapshot');await operation();const after=await control('snapshot');same(Object.keys(after),Object.keys(before),'Read does not add or remove tables');for(const name of Object.keys(before))same(after[name],before[name],'Read leaves all '+name+' contents unchanged');readEvidence.push({phase:label,tables:Object.keys(before).length,before:crypto.createHash('sha256').update(JSON.stringify(before)).digest('hex'),after:crypto.createHash('sha256').update(JSON.stringify(after)).digest('hex')});}
async function publish(c,relogin=true){const next={...structuredClone(c),version:'synthetic-summary-comparison-'+(++serial)};await api('/organization/config',{expectedVersion:configuration?.version||null,configuration:next},'admin');configuration=next;if(relogin)await logins();else await login('admin');}
async function identities() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const actor of actors.slice(1)) ids.push(await api('/users', { username: 'synthetic-summary-comparison-' + actor, name: 'SYNTHETIC ' + actor, role: actor === 'team' ? 'manager' : 'viewer', status: 1, password }, 'admin'));
  const c = workflowConfiguration(ids.slice(0, 5));
  const optional = { required: false, allowedTargetRoles: ['EDITOR', 'TEXTONLY', 'READONLY'], targetMustCoverOrganization: false, allowSelf: false };
  for (const [index, role] of [[5, 'EDITOR'], [6, 'TEXTONLY'], [7, 'READONLY']]) {
    c.roleCodes.push(role); c.relations.push({ roleCode: role, leader: optional, bp: optional });
    c.people.push({ personCode: 'P' + (index + 1), organizationCode: '001', responsibleOrganizationCodes: [], roleCodes: [role], leaderPersonCode: null, bpPersonCode: null, enabled: true });
    c.accountBindings.push({ accountId: ids[index], personCode: 'P' + (index + 1), enabled: true });
    for (const [resource, action] of (role==='READONLY' ? [['summary.read','VIEW']] : [['summary.read', 'VIEW'], ['summary.edit', 'HANDLE']])) c.grants.push({ ruleId: role + '-' + resource, roleCode: role, resource, action, effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] });
  }
  c.grants.push({ ruleId: 'editor-delivery', roleCode: 'EDITOR', resource: 'delivery.read', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] }); c.grants.push({ruleId:'readonly-delivery',roleCode:'READONLY',resource:'delivery.read',action:'VIEW',effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]},{ruleId:'out-summary',roleCode:'OUT',resource:'summary.read',action:'VIEW',effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]}); await publish(c);
}
async function project(title) {
  const key = 'PROJECT-' + (++serial); let d = (await api('/demands/draft', { request_id: key, title, unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '180', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC' }, 'admin')).workflow;
  d = (await api('/demands/submit', { id: d.id, expected_version: d.version, request_id: key + '-SUBMIT' }, 'admin')).workflow;
  for (const actor of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', { action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: key + '-' + actor, comment: 'SYNTHETIC' }, actor)).workflow;
  const p = (await api('/demands/accept', { id: d.id, expected_version: d.version, request_id: key + '-ACCEPT', team_code: '900' }, 'team')).workflow.project_id;
  let row = (await api('/projects', undefined, 'team')).find(r => r.id === p);
  await api('/projects', { ...row, owner: 'SYNTHETIC OWNER', start_date: '2025-01-01', end_date: '2025-01-05', delivery_mode: '线上', hours: 4, amount: 0 }, 'team'); await api('/projects/start', { id: p }, 'team'); return p;
}
async function dispatch(project_id, teacher_id, day, rejected = false) {
  const id = await api('/dispatches', { project_id, teacher_id, subject: 'SYNTHETIC PRIVATE COURSE ' + day, teach_date: '2025-01-0' + day, hours: 9, status: '待发送', material_status: '已就绪' }, 'team');
  await api('/dispatches/send', { id }, 'team'); await api('/dispatches/confirm', { id, accept: rejected ? 0 : 1, ...(rejected ? { reason: 'SYNTHETIC REJECTION' } : {}) }, 'team'); return id;
}
async function fact(id, expected_version, minutes, payable, estimated = '2', planned = '1.5') {
  return api('/delivery-settlement/save', { dispatch_id: id, expected_version, request_id: 'FACT-' + (++serial), estimated_hours: estimated, planned_hours: planned, actual_minutes: minutes, payable_hours: payable }, 'team');
}
async function verify(id, saved) { return api('/delivery-settlement/verify', { dispatch_id: id, expected_version: saved.version, request_id: 'VERIFY-' + (++serial), evidence_code: 'SYNTHETIC-PRIVATE-VERIFICATION-EVIDENCE' }, 'team'); }
const base='/training-summaries',revision=(id,n)=>base+`/revision?project_id=${id}&revision=${n}`,compare=(id,from,to)=>base+`/comparison?project_id=${id}&from_revision=${from}&to_revision=${to}`;
const cmd=(project_id,expected_version,request_id)=>({project_id,expected_version,request_id});
const contentA={achievements:'SYNTHETIC FIRST BODY\n保留空行\n\n  空格',issues:'SYNTHETIC ORIGINAL ISSUE',nextSteps:'SYNTHETIC ORIGINAL NEXT',publicity:{title:'SYNTHETIC ORIGINAL TITLE',introduction:'SYNTHETIC INTRODUCTION',sections:[{heading:'第一段',body:'保留原段落'}],photoCaptions:['合成图注']}};
const contentB={achievements:'SYNTHETIC SECOND BODY\n新的正文',issues:'',nextSteps:'SYNTHETIC SECOND NEXT',publicity:{title:'SYNTHETIC REVISED TITLE',introduction:'',sections:[{heading:'第一段',body:'修订段落'},{heading:'第二段',body:'新增段落'}],photoCaptions:[]}};
const hiddenDelivery={status:'UNAVAILABLE',reason:'DELIVERY_READ_REQUIRED',value:null};
async function comparison(id,from,to,latest,actor='editor'){
 const view=await api(compare(id,from,to),undefined,actor);
 same(Object.keys(view).sort(),['after','before','draft_only','from_revision','latest_version','project_id','read_only','synthetic','to_revision'],'Comparison has only the exact frozen-pair contract');
 check(view.project_id===id&&view.from_revision===from&&view.to_revision===to&&view.latest_version===latest,'Comparison identifies the selected revisions and current latest version');
 check(view.read_only===true&&view.synthetic===false&&view.draft_only===true,'Comparison remains genuine draft-only read data');
 if(from===0)same(view.before,null,'Empty starting point is null, never an invented zero source');else same(view.before,await api(revision(id,from),undefined,actor),'Before exactly matches same-permission saved revision');
 same(view.after,await api(revision(id,to),undefined,actor),'After exactly matches same-permission saved revision');
 check(!('source_changed'in view)&&!('diff'in view)&&!('changes'in view),'Backend supplies no live source_changed or inferred diff');
 return view;
}
function assertHidden(view,label){
 for(const side of [view.before,view.after].filter(Boolean)){
  same(side.sources.delivery,hiddenDelivery,label+' suppresses delivery details');check(side.sources.source_version===null&&side.sources.visibility==='DELIVERY_HIDDEN',label+' hides combined source digest');
  const raw=JSON.stringify(side.sources);check(!raw.includes('actual')&&!raw.includes('payable')&&!raw.includes('estimated')&&!raw.includes('planned'),'Hidden side exposes no teaching value keys');
  for(const saved of revisions.values()){check(!raw.includes(saved.sources.source_version),'Hidden side omits original combined source hash');const digest=saved.sources.delivery.source_version;if(digest)check(!raw.includes(digest),'Hidden side omits original delivery source hash');}
 }
}
async function runChecks(){
 await boot();await identities();const original=structuredClone(configuration),p=await project('SYNTHETIC SUMMARY COMPARISON PRIMARY'),empty=await project('SYNTHETIC SUMMARY COMPARISON EMPTY');
 const teacher=await api('/teachers',{name:'SYNTHETIC-PRIVATE-TEACHER',status:'在库',field:'SYNTHETIC',base_province:'浙江',base_city:'杭州',fee_rate:987.65},'admin');
 const done=await dispatch(p,teacher,1),pending=await dispatch(p,teacher,2);await dispatch(p,teacher,3,true);
 let actual=await verify(done,await fact(done,0,'60','1.25000000','2.50000000','4.50000000'));await api('/dispatches/complete',{id:done,expected_version:actual.version},'team');await fact(pending,0,'45','0.75000000','3','4');
 await readOnly('empty project and authentication before any revision',async()=>{
  await denied(compare(empty,0,1),undefined,'editor',404,'No summary is missing history rather than synthetic zero');
  await denied(compare(p,0,1),undefined,'anonymous',401,'Anonymous comparison requires real Auth');
  await denied(base+'/comparison?unknown=1',undefined,'anonymous',401,'Authentication precedes invalid comparison query');
  await denied(compare(p,0,999),undefined,'outsider',403,'Cross-organization scope checked before revision existence');
  await denied(compare(p,0,1),undefined,'admin',403,'Old admin has no implicit summary.read grant');
 });
 phase='real frozen summary writes';await api(base+'/save',{...cmd(p,0,'FIRST'),content:contentA});revisions.set(1,await api(revision(p,1)));same(revisions.get(1).content,contentA,'First body retains exact original text, whitespace and sections');
 actual=await fact(done,actual.version,'90','1.75000000');await api(base+'/save',{...cmd(p,1,'SECOND'),content:contentB});revisions.set(2,await api(revision(p,2)));same(revisions.get(2).sources,revisions.get(1).sources,'Ordinary body save preserves the original frozen sources');
 actual=await verify(done,actual);let projectRow=(await api('/projects',undefined,'team')).find(row=>row.id===p);await api('/projects',{...projectRow,participant_count:7},'team');
 await api(base+'/refresh',cmd(p,2,'REFRESH'));revisions.set(3,await api(revision(p,3)));same(revisions.get(3).content,contentB,'Refresh preserves exact saved body');check(revisions.get(3).sources.project.value.participant_count===7&&revisions.get(3).sources.delivery.value.actual==='2.00','Refresh freezes corrected project and reviewed teaching sources');
 await readOnly('exact empty adjacent and non-adjacent comparisons',async()=>{
  for(const [from,to]of [[0,1],[0,3],[1,2],[2,3],[1,3]]){const view=await comparison(p,from,to,3);if(from)same(view.before,revisions.get(from),'Before equals captured old version');same(view.after,revisions.get(to),'After equals captured old version');}
  const readOnlyView=await comparison(p,1,3,3,'readonly');same(readOnlyView.after,revisions.get(3),'Read-only summary and delivery grants suffice');await denied(base+'/save',{...cmd(p,3,'READONLY-DENIED'),content:contentA},'readonly',403,'Comparison read permission does not grant editing');
 });
 await readOnly('strict comparison query and method boundary',async()=>{
  const good=`project_id=${p}&from_revision=0&to_revision=3`;
  for(const raw of ['',`project_id=${p}`,`project_id=${p}&from_revision=0`,`from_revision=0&to_revision=3`,good+'&unknown=1',good+`&project_id=${p}`,good+'&%70roject_id='+p,good+'&from_revision=1',good+'&%66rom_revision=0',good+'&to_revision=2',good+'&%74o_revision=3',good+'&',good.replace('from_revision=0','from_revision=-1'),good.replace('from_revision=0','from_revision=1.5'),good.replace('from_revision=0','from_revision=1e0'),good.replace('from_revision=0','from_revision=3'),good.replace('from_revision=0','from_revision=4'),good.replace('from_revision=0','from_revision=9007199254740992'),good.replace('to_revision=3','to_revision=0'),good.replace('to_revision=3','to_revision=1.0000000000000001'),good.replace('to_revision=3','to_revision=9007199254740992'),good.replace('project_id='+p,'project_id=0'),good.replace('project_id='+p,'project_id=1.5'),good.replace('project_id='+p,'project_id=9007199254740992'),good+'&organization_code=999',good+'&actor_code=P1',good+'&sources=client'])await denied(base+'/comparison'+(raw?'?'+raw:''),undefined,'editor',400,'Invalid/duplicate query rejected: '+raw);
  for(const method of ['POST','PUT','DELETE','HEAD'])await denied(compare(p,1,3),method==='POST'?{before:{sources:'client'}}:undefined,'editor',405,'Comparison rejects '+method,{method});
  await denied(compare(p,0,4),undefined,'editor',404,'Future version is unavailable');await denied(compare(p,4,5),undefined,'editor',404,'Missing pair is unavailable');
  for(const operation of ['submit','review','export'])await denied(base+'/'+operation,cmd(p,3,'FORMAL-'+operation),'editor',409,'Formal '+operation+' remains unavailable');
 });
 phase='new live sources and latest summary';actual=await verify(done,await fact(done,actual.version,'120','2.25000000'));projectRow=(await api('/projects',undefined,'team')).find(row=>row.id===p);await api('/projects',{...projectRow,participant_count:11},'team');
 await readOnly('current sources cannot replace frozen comparison',async()=>{check((await api(base+'?project_id='+p)).source_changed===true,'Live project and teaching have really changed');const view=await comparison(p,1,3,3);same(view.before,revisions.get(1),'Live source change leaves old before exact');same(view.after,revisions.get(3),'Live source change leaves old after exact');});
 await api(base+'/save',{...cmd(p,3,'FOURTH'),content:{achievements:'SYNTHETIC FOURTH BODY',issues:'',nextSteps:''}});revisions.set(4,await api(revision(p,4)));same(revisions.get(4).sources,revisions.get(3).sources,'Later ordinary save keeps preceding frozen source');
 let stable;
 await readOnly('latest version metadata and permission-projected sides',async()=>{
  stable=await comparison(p,1,3,4);same(stable.after,revisions.get(3),'Historical pair remains selected after later save');const zero=await comparison(p,0,4,4);same(zero.before,null,'Empty origin remains null with existing sources');
  for(const [from,to]of [[0,3],[1,2],[1,3]])assertHidden(await comparison(p,from,to,4,'textonly'),'Never-granted reader');
 });
 await publish({...original,grants:original.grants.filter(g=>g.ruleId!=='editor-delivery')},false);
 await readOnly('delivery revocation projects both sides consistently',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Grant publication invalidates old token');await login('editor');const pair=await comparison(p,1,3,4);assertHidden(pair,'Revoked teaching reader');same(pair.before.content,contentA,'Teaching hide preserves original body');same(pair.after.content,contentB,'Teaching hide preserves selected body');});await publish(original);
 await publish({...original,grants:original.grants.filter(g=>!(g.roleCode==='EDITOR'&&g.resource==='summary.read'))},false);
 await readOnly('summary read revoked for old and fresh sessions',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Old session revoked on summary policy change');await login('editor');await denied(compare(p,1,3),undefined,'editor',403,'Fresh login still needs summary.read');});await publish(original);
 const unbound=structuredClone(original);unbound.accountBindings.find(b=>b.personCode==='P6').enabled=false;await publish(unbound,false);
 await readOnly('binding removed for old and fresh sessions',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Unbinding invalidates old token');await login('editor');await denied(compare(p,1,3),undefined,'editor',403,'Fresh session has no person binding');});await publish(original);
 await control('disable-editor');await readOnly('account disabled after login',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Disabled account cannot compare');});await control('restore-editor');
 await readOnly('reactivation never restores revoked session',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Reactivated account still needs login');});await login('editor');
 await control('revoke-editor');await readOnly('explicit session revocation',async()=>{await denied(compare(p,1,3),undefined,'editor',401,'Revoked real token cannot compare');});await login('editor');
 await control('archive');await readOnly('archived project still permits exact read-only comparison',async()=>{same(await comparison(p,1,3,4),stable,'Archive does not rewrite frozen pair');await denied(base+'/save',{...cmd(p,4,'ARCHIVED-SAVE'),content:contentA},'editor',409,'Archived summary cannot save');await denied(base+'/refresh',cmd(p,4,'ARCHIVED-REFRESH'),'editor',409,'Archived summary cannot refresh sources');});await control('unarchive');
 await control('capture');let damageMessage;
 for(const fault of ['fault-content','fault-unselected','fault-source','fault-middle-hash','fault-head','fault-association','fault-missing']){
  await control(fault);await readOnly('damaged full chain '+fault,async()=>{
   for(const actor of ['editor','textonly']){const result=await denied(compare(p,2,3),undefined,actor,409,'No comparison from damaged or omitted revision');if(!damageMessage)damageMessage=result.envelope?.msg;else same(result.envelope?.msg,damageMessage,'Corrupt stored chain returns uniform safe message');}
   await denied(compare(p,1,3),undefined,'anonymous',401,'Auth precedes corruption details');await denied(compare(p,1,3),undefined,'outsider',403,'Cross-org scope precedes corruption details');
  });await control('restore');
 }
 await readOnly('restored synthetic chain',async()=>{same(await comparison(p,1,3,4),stable,'Exact frozen pair survives every restored fixture fault');});
 const beforeRestart=await control('snapshot');await stop();same(await inspect(),beforeRestart,'Offline inspector confirms complete database snapshot');await boot(true);await logins();
 await readOnly('persistent comparison across real Main restart',async()=>{same(await comparison(p,1,3,4),stable,'Restart retains exact selected snapshots and latest metadata');assertHidden(await comparison(p,1,3,4,'textonly'),'Restart hidden projection');});const finalTables=await control('snapshot');same(finalTables,beforeRestart,'Restart and comparisons alter no persisted table');
 fs.writeFileSync(path.join(own,'database-invariants.json'),JSON.stringify({before_restart:beforeRestart,final:finalTables,read_phases:readEvidence},null,2),{mode:0o600});fs.writeFileSync(path.join(own,'synthetic-comparison.json'),JSON.stringify(stable,null,2),{mode:0o600});return {read_only_phases:readEvidence.length,tables_per_read_phase:Object.keys(finalTables).length,summary_versions:4};
}
let evidence;runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{await stop();const stopped=servicePids.every(pid=>{try{process.kill(pid,0);return false;}catch(e){return e.code==='ESRCH';}});check(stopped,'All synthetic Main processes stopped');const result={suite:'summary-comparison-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),server_processes_stopped:stopped,production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(result,null,2),{mode:0o600});console.log(JSON.stringify(result));if(failures.length||fatal)process.exitCode=1;});
