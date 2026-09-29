'use strict';
// Real Main/Auth/M01/M05 with wholly synthetic sources. Never reads app/data or starts a fixed port.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn,execFile}=require('node:child_process'),{promisify}=require('node:util'),run=promisify(execFile),args={};
for(let i=2;i<process.argv.length;i+=2){assert.ok(['--java','--classes','--fixture-classes'].includes(process.argv[i])&&process.argv[i+1],'Use --java PATH --classes DIRECTORY [--fixture-classes DIRECTORY] or CLASSES_DIR');args[process.argv[i]]=process.argv[i+1];}
const java=args['--java']||'java',repo=path.resolve(__dirname,'..'),classes=fs.realpathSync(args['--classes']||process.env.CLASSES_DIR||''),fixtureClasses=args['--fixture-classes']?fs.realpathSync(args['--fixture-classes']):classes;
for(const value of [classes,fixtureClasses])assert.ok(value!==repo&&!value.startsWith(repo+path.sep),'Use isolated classes outside repository');
assert.ok(fs.existsSync(path.join(fixtureClasses,'com/training/IntegrationDeliveryHistoryHttpFixture.class')),'Compile the scripts-only history fixture first');
const own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-delivery-history-http-')),data=path.join(own,'data');fs.mkdirSync(data);
const password=crypto.randomBytes(24).toString('hex'),tokens={},failures=[],servicePids=[],readEvidence=[],publicationEvidence=[],versions=new Map();
const actors=['admin','worker','reader','outsider','unbound','binding-off','writer','wrong-action'],base='/delivery-settlement',history=base+'/history';
let child,port,checks=0,phase='startup',fatal=null,epochBaseline,expectedEpochRows;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms)),uuid=()=>crypto.randomUUID();
const env={...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''};
function check(ok,label){checks++;if(!ok)failures.push(phase+': '+label);}
function same(a,b,label){let ok=true;try{assert.deepEqual(a,b);}catch{ok=false;}check(ok,label);}
function options(resume=false){return ['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dbootstrap.admin.password='+password,'-Dintegration.delivery.history.root='+own,'-Dintegration.delivery.history.resume='+resume,'-Ddata.dir='+data,'-Dsemantic.port=','-Dsemantic.token=','-Daccount.import.manifest=','-Daccount.import.manifest.sha256=','-cp',fixtureClasses+path.delimiter+classes+path.delimiter+path.join(repo,'lib','*')];}
async function request(route,body,actor='worker',overrides={}){
 const headers={...(tokens[actor]?{'X-Token':tokens[actor]}:{})};if(body!==undefined)headers['Content-Type']='application/json';
 const r=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:overrides.method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
 const text=await r.text();let envelope=null;if(text){try{envelope=JSON.parse(text);}catch{throw new Error(route+': non-JSON HTTP '+r.status);}}
 return {status:r.status,envelope,text,headers:r.headers};
}
async function api(route,body,actor='worker'){const r=await request(route,body,actor);assert.equal(r.status,200,phase+': '+route+': '+r.status+': '+r.envelope?.msg);assert.equal(r.envelope?.code,0,route);return r.envelope.data;}
async function denied(route,body,actor,status,label,overrides={}){
 const r=await request(route,body,actor,overrides);check(r.status===status&&(overrides.method==='HEAD'?r.text==='':r.envelope?.code===status),label+' (HTTP '+r.status+': '+r.envelope?.msg+')');
 check(!r.headers.has('content-disposition')&&!r.envelope?.data?.items,'Error contains no partial history or attachment');
 check(!r.text.includes('HISTORY-CHECK-')&&!r.text.includes('9999999999999999')&&!r.text.includes('SYNTHETIC PRIVATE MATERIAL'),'Error does not leak private stored values');return r;
}
async function login(actor){tokens[actor]=(await api('/login',{username:actor==='admin'?'admin':'synthetic-history-'+actor,password},'anonymous')).token;}
async function boot(resume=false){
 for(const key of Object.keys(tokens))delete tokens[key];const listener=net.createServer();await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});port=listener.address().port;await new Promise(resolve=>listener.close(resolve));
 child=spawn(java,[...options(resume),'com.training.IntegrationDeliveryHistoryHttpFixture',String(port)],{cwd:repo,env,stdio:'ignore'});if(child.pid)servicePids.push(child.pid);let error;child.once('error',e=>{error=e;});
 for(let i=0;i<150;i++){if(error)throw error;assert.equal(child.exitCode,null,'Synthetic history Main exited before login');try{await login('admin');break;}catch{}await pause(100);}assert.ok(tokens.admin,'Synthetic Main did not become ready');for(const actor of actors.slice(1))await login(actor);
}
async function stop(){const running=child;child=null;if(!running||running.exitCode!==null||running.signalCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{if(running.exitCode===null&&running.signalCode===null)running.kill('SIGKILL');},2000);running.once('exit',()=>{clearTimeout(timer);resolve();});running.kill('SIGTERM');});}
async function control(operation){const nonce=uuid(),tmp=path.join(own,'control-request.next');fs.writeFileSync(tmp,JSON.stringify({nonce,operation}),{mode:0o600});fs.renameSync(tmp,path.join(own,'control-request.json'));for(let i=0;i<200;i++){try{const ack=JSON.parse(fs.readFileSync(path.join(own,'control-result.json'),'utf8'));if(ack.nonce===nonce&&ack.ok)return ack.data;}catch{}await pause(20);}throw new Error('Synthetic control timed out: '+operation);}
async function inspect(){assert.ok(!child);const r=await run(java,[...options(true),'com.training.IntegrationDeliveryHistoryHttpFixture','inspect'],{cwd:repo,env,timeout:15000,maxBuffer:65536});return JSON.parse(r.stdout.trim());}
async function readOnly(label,operation){phase=label;const before=await control('snapshot');await operation();const after=await control('snapshot');same(Object.keys(after),Object.keys(before),'Read does not create or remove any table');for(const name of Object.keys(before))same(after[name],before[name],'Read leaves full '+name+' content unchanged');readEvidence.push({phase:label,tables:Object.keys(before).length,before:crypto.createHash('sha256').update(JSON.stringify(before)).digest('hex'),after:crypto.createHash('sha256').update(JSON.stringify(after)).digest('hex')});}
const query=(version,id=1,page=1,size=20)=>history+`?dispatch_id=${id}&expected_version=${version}&page=${page}&page_size=${size}`;
const get=(version,id=1,page=1,size=20,actor='worker')=>api(query(version,id,page,size),undefined,actor);
const cmd=(version,extra={})=>({dispatch_id:1,expected_version:version,request_id:'HISTORY-'+uuid(),...extra});
async function save(version,fields){const detail=await api(base+'/save',cmd(version,fields));versions.set(detail.version,detail);return detail;}
async function verify(version){const detail=await api(base+'/verify',cmd(version,{evidence_code:'HISTORY-CHECK-'+(version+1)}));versions.set(detail.version,detail);return detail;}
function flattened(detail){const f=detail?.fact,h=f?.hours,v=f?.verification;return {estimated_hours:h?.estimated??null,planned_hours:h?.planned??null,actual_minutes:detail?.conversion?.minutes??null,actual_hours:h?.actual??null,payable_hours:h?.payable??null,verification_status:f?(v?'VERIFIED':'UNVERIFIED'):null,verification_actor_code:v?.actor_code??null,verification_checked_at:v?.checked_at??null,verification_evidence_code:v?.evidence_code??null};}
function verifyPage(page,version,number,size,total=version,id=1){
 check(page.dispatch_id===id&&page.project_id===1&&page.teacher_id===1&&page.organization_code==='001','History uses the trusted dispatch/project/teacher/organization association');
 check(page.current_version===version&&page.page===number&&page.page_size===size&&page.total===total,'Pagination describes the requested current revision snapshot');
 const expected=Array.from({length:Math.max(0,Math.min(size,total-(number-1)*size))},(_,i)=>total-(number-1)*size-i);same(page.items.map(row=>row.version),expected,'Versions are complete and descend from latest');
 for(const item of page.items){
  const current=versions.get(item.version),previous=versions.get(item.version-1),flat=flattened(current),before=flattened(previous);
  check(item.event_type===(current.fact.verification?'VERIFY':'SAVE')&&item.actor_code==='P1'&&item.account_id===2,'History uses persisted event and actor account');
  check(typeof item.created_at==='string'&&Number.isFinite(Date.parse(item.created_at)),'History includes parseable stored event time');
  for(const key of ['estimated_hours','planned_hours','actual_minutes','actual_hours','payable_hours']){same(item[key],flat[key],key+' retains the exact original decimal or null');check(item[key]===null||typeof item[key]==='string',key+' never uses floating point JSON numbers');}
  same(item.verification,current.fact.verification,'Verification matches the actual saved revision');
  const changes=Object.keys(flat).filter(key=>flat[key]!==before[key]).map(field=>({field,before:before[field],after:flat[field]}));
  same(item.changes,changes,'Changes compare with the actual preceding revision, including page boundaries');
  check(item.verification_invalidated===Boolean(previous?.fact?.verification&&!current.fact.verification),'SAVE explains whether an existing verification was invalidated');
 }
 const text=JSON.stringify(page);check(!/SYNTHETIC PRIVATE MATERIAL|SYNTHETIC HISTORY TEACHER|amount|fee_rate|password|token|@/.test(text),'History exposes no fee calculation, contact, credential, course text or notes');
}
async function publish(mutator,label,affected){
 const config=(await api('/organization/config',undefined,'admin')).configuration,copy=structuredClone(config),before=await control('snapshot'),epochs=await control('device-epochs');
 same(epochs,expectedEpochRows,label+': prepublication epochs match the expected baseline without intervening writes');
 const expected=new Map(expectedEpochRows.map(row=>[row.user_id,{...row}]));for(const id of new Set(affected))expected.set(id,{user_id:id,epoch:(expected.get(id)?.epoch||0)+1});
 const expectedRows=[...expected.values()].sort((a,b)=>a.user_id-b.user_id);copy.version='synthetic-history-'+label;mutator(copy);await api('/organization/config',{expectedVersion:config.version,configuration:copy},'admin');
 same(await control('device-epochs'),expectedRows,label+': exactly affected account epochs increment once, absent epochs begin at one and every other account remains unchanged');
 const after=await control('snapshot');same(after,{...before,ORGANIZATION_ACCESS_CONFIG:after.ORGANIZATION_ACCESS_CONFIG,S01_TRUSTED_DEVICE_EPOCHS:after.S01_TRUSTED_DEVICE_EPOCHS},label+': publication changes only configuration and precisely verified device epochs');
 check(after.ORGANIZATION_ACCESS_CONFIG.count===before.ORGANIZATION_ACCESS_CONFIG.count+1,label+': exactly one configuration revision is published');
 epochBaseline=after.S01_TRUSTED_DEVICE_EPOCHS;expectedEpochRows=expectedRows;publicationEvidence.push({publication:label,affected_account_ids:[...new Set(affected)].sort((a,b)=>a-b),epochs_before:epochs,epochs_after:expectedRows});return config;
}
async function relogin(){for(const actor of actors)await login(actor);}
async function runChecks(){
 await boot();const initialTables=await control('snapshot');epochBaseline=initialTables.S01_TRUSTED_DEVICE_EPOCHS;expectedEpochRows=await control('device-epochs');
 await readOnly('empty history and real authority',async()=>{
  const empty=await get(0,2);verifyPage(empty,0,1,20,0,2);same(empty.items,[],'Unsaved dispatch has a genuine empty history');
  for(const actor of ['admin','unbound','binding-off','writer','wrong-action','outsider'])await denied(query(0,2),undefined,actor,403,actor+' has no implicit delivery.read VIEW permission');
  await denied(query(0,2),undefined,'anonymous',401,'Anonymous history request requires real login');
  await denied(query(0,4),undefined,'worker',403,'Own-org reader cannot inspect another organization');
  await denied(query(0,5),undefined,'worker',409,'Legacy row without trusted acceptance is unavailable');
  await denied(query(0,999999),undefined,'worker',404,'Absent dispatch is unavailable');
  check((await api('/me')).role==='viewer','Dedicated delivery role still uses the original viewer account');
  await denied(base+'/save',{dispatch_id:2,expected_version:0,request_id:'READER-DENIED',actual_minutes:'60'},'reader',403,'History reader cannot write facts');
 });
 await readOnly('strict query protocol',async()=>{
  const valid='dispatch_id=2&expected_version=0';
  for(const raw of ['', 'dispatch_id=2','expected_version=0',valid+'&unknown=1',valid+'&dispatch_id=2',valid+'&dispatch_id=1',valid+'&%64ispatch_id=2',valid+'&%65xpected_version=0',valid+'&page=1&page=2',valid+'&page_size=20&%70age_size=20',valid+'&',valid.replace('dispatch_id=2','dispatch_id=0'),valid.replace('dispatch_id=2','dispatch_id=1.5'),valid.replace('dispatch_id=2','dispatch_id=9007199254740992'),valid.replace('expected_version=0','expected_version=-1'),valid.replace('expected_version=0','expected_version=1.0000000000000001'),valid+'&page=0',valid+'&page=-1',valid+'&page=1.5',valid+'&page=9007199254740992',valid+'&page_size=0',valid+'&page_size=51',valid+'&page_size=1.5',valid+'&organization_code=999',valid+'&actor_code=P1',valid+'&actual_hours=999'])await denied(history+(raw?'?'+raw:''),undefined,'worker',400,'Malformed/duplicate/untrusted query is rejected: '+raw);
  for(const method of ['POST','PUT','DELETE','HEAD'])await denied(query(0,2),method==='POST'?{actual_hours:'999'}:undefined,'worker',405,'History rejects '+method,{method});
  const defaults=await api(history+'?'+valid);verifyPage(defaults,0,1,20,0,2);
  await denied(query(1,2),undefined,'worker',409,'Expected version cannot claim an unsaved future fact');
 });
 phase='genuine HTTP revision writes';
 await save(0,{estimated_hours:'9999999999999999.12345678',planned_hours:'2.50000000',actual_minutes:'60.00000000',payable_hours:null});await verify(1);await save(2,{planned_hours:'2.75000000'});await verify(3);
 await readOnly('SAVE VERIFY SAVE invalidation VERIFY history',async()=>{const page=await get(4);verifyPage(page,4,1,20);verifyPage(await get(4,1,1,2),4,1,2);verifyPage(await get(4,1,2,2),4,2,2);same(await get(4,1,1,20,'reader'),page,'Read-only grant sees identical authorized history');});
 phase='pagination invalidation and null preservation writes';await save(4,{planned_hours:'2.75000000'});
 await readOnly('new write invalidates previous page snapshot',async()=>{await denied(query(4,1,2,2),undefined,'worker',409,'Old page token fails after a newer write');verifyPage(await get(5),5,1,20);});
 await save(5,{planned_hours:'2.75000000'});await save(6,{actual_minutes:null,estimated_hours:null,payable_hours:'0.00000000'});await save(7,{actual_minutes:'61',planned_hours:null});await verify(8);
 let complete;
 await readOnly('exact complete chain and all page predecessors',async()=>{
  complete=await get(9);verifyPage(complete,9,1,20);check(complete.items.find(row=>row.version===6).changes.length===0,'Same-value unverified SAVE remains a revision with no invented changes');
  for(let page=1;page<=6;page++)verifyPage(await get(9,1,page,2),9,page,2);verifyPage(await get(9,1,1,50),9,1,50);
  const preview=await api(base+'/policy-preview?dispatch_id=1');check(preview.evidence_version===null&&preview.preview_only===true&&preview.can_confirm===false,'History does not install a trusted approved-policy provider');
  const old=await api(base+'/preview?dispatch_id=1');check(old.status==='NOT_CONFIGURED'&&old.amount===null,'Existing calculation preview remains unconfigured');
 });
 await control('revoke-worker');await readOnly('revoked session recheck',async()=>{await denied(query(9),undefined,'worker',401,'Revoked token cannot read saved history');});await login('worker');
 await control('disable-worker');await readOnly('disabled account recheck',async()=>{await denied(query(9),undefined,'worker',401,'Disabled account cannot use existing history session');});await control('restore-worker');
 await readOnly('reactivation does not resurrect a session',async()=>{await denied(query(9),undefined,'worker',401,'Reactivated account must login again');});await login('worker');
 await publish(c=>{c.accountBindings.find(b=>b.accountId===2).enabled=false;},'unbind',[2]);
 await readOnly('binding revoked old and fresh sessions',async()=>{await denied(query(9),undefined,'worker',401,'Binding publication revokes old session');await login('worker');await denied(query(9),undefined,'worker',403,'New session still has no disabled person binding');});
 await publish(c=>{c.accountBindings.find(b=>b.accountId===2).enabled=true;},'rebind',[2]);await relogin();
 const full=(await api('/organization/config',undefined,'admin')).configuration;
 await publish(c=>{c.grants=c.grants.filter(g=>!(g.roleCode==='DELIVERY'&&g.resource==='delivery.read'));},'read-revoked',full.accountBindings.map(binding=>binding.accountId));
 await readOnly('delivery read grant revoked',async()=>{await denied(query(9),undefined,'worker',401,'Policy publication invalidates stale granted session');await login('worker');await denied(query(9),undefined,'worker',403,'Fresh session needs current delivery.read permission');});
 await publish(c=>{c.grants=full.grants;},'read-restored',full.accountBindings.map(binding=>binding.accountId));await relogin();
 await control('archive');await readOnly('archived project retains read-only history',async()=>{same(await get(9),complete,'Archived project retains identical genuine history');await denied(base+'/save',cmd(9,{planned_hours:'9'}),'worker',409,'Archived history cannot become a fact edit');});await control('unarchive');
 await control('capture');let corruptionMessage;
 for(const fault of ['fault-missing','fault-event','fault-actor','fault-source','fault-oversize','fault-synthetic','fault-association','fault-verification','fault-head']){
  await control(fault);await readOnly('fail closed '+fault,async()=>{for(const page of [1,3]){const result=await denied(query(9,1,page,2),undefined,'worker',409,'Corrupt complete chain returns no partial page');if(fault==='fault-oversize')check(result.envelope?.msg?.includes('上限')&&result.envelope.msg.includes('32000'),'Capacity refusal states the bound instead of truncating history');else if(!corruptionMessage)corruptionMessage=result.envelope?.msg;else same(result.envelope?.msg,corruptionMessage,'All damaged chains use the same safe conflict message');}});await control('restore');
 }
 await readOnly('restored fixture history is intact',async()=>{same(await get(9),complete,'Exact restored full history is identical after all isolated fault tests');});
 const beforeRestart=await control('snapshot');await stop();same(await inspect(),beforeRestart,'Offline inspector confirms the same full database');await boot(true);
 await readOnly('persistent real Main restart',async()=>{same(await get(9),complete,'Restart preserves every historical value, difference and verification');same(await get(9,1,1,20,'reader'),complete,'Read-only authorization remains explicit after restart');});
 const finalTables=await control('snapshot');same(finalTables,beforeRestart,'History reads and restart leave all database rows unchanged');
 const expectedChanges=new Set(['M05_DELIVERY_FACTS','M05_FACT_REVISIONS','M05_REQUESTS','ORGANIZATION_ACCESS_CONFIG']);let unrelated=0;
 same(Object.keys(finalTables),Object.keys(initialTables),'The complete table inventory is preserved');same(await control('device-epochs'),expectedEpochRows,'Exactly verified publication epochs persist through reads and restart');
 for(const name of Object.keys(initialTables))if(!expectedChanges.has(name)){same(finalTables[name],name==='S01_TRUSTED_DEVICE_EPOCHS'?epochBaseline:initialTables[name],name==='S01_TRUSTED_DEVICE_EPOCHS'?'Final device epoch table matches the last exactly verified publication baseline':'Synthetic history setup and tests preserve unrelated '+name);unrelated++;}
 fs.writeFileSync(path.join(own,'database-invariants.json'),JSON.stringify({initial:initialTables,before_restart:beforeRestart,final:finalTables,read_phases:readEvidence,publications:publicationEvidence,verified_epoch_baseline:epochBaseline},null,2),{mode:0o600});
 fs.writeFileSync(path.join(own,'synthetic-history.json'),JSON.stringify(complete,null,2),{mode:0o600});return {read_only_phases:readEvidence.length,tables_per_read_phase:Object.keys(finalTables).length,unrelated_tables_unchanged:unrelated,history_versions:9};
}
let evidence;
runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{
 await stop();const stopped=servicePids.every(pid=>{try{process.kill(pid,0);return false;}catch(e){return e.code==='ESRCH';}});check(stopped,'All isolated Main processes stopped');
 const summary={suite:'delivery-history-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),server_processes_stopped:stopped,production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;
});
