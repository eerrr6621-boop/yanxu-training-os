'use strict';
// Synthetic Main/Auth HTTP. Never reads actual import sources, app/data, keychain or mail transport.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const crypto=require('node:crypto'),assert=require('node:assert/strict'),{spawn,execFile}=require('node:child_process'),{promisify}=require('node:util');
const run=promisify(execFile),args={};
for(let i=2;i<process.argv.length;i+=2){assert.ok(['--java','--classes'].includes(process.argv[i])&&process.argv[i+1],'Use --java PATH --classes DIRECTORY or CLASSES_DIR');args[process.argv[i]]=process.argv[i+1];}
const java=args['--java']||'java',classes=fs.realpathSync(args['--classes']||process.env.CLASSES_DIR||''),repo=path.resolve(__dirname,'..');
assert.ok(classes!==repo&&!classes.startsWith(repo+path.sep),'Compiled classes must be isolated outside repository');
assert.ok(fs.existsSync(path.join(classes,'com/training/IntegrationAccountEmailHttpFixture.class')),'Compile the scripts-only account email fixture first');
const own=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-account-email-http-')),data=path.join(own,'data');fs.mkdirSync(data);
const password=crypto.randomBytes(24).toString('hex'),tokens={},failures=[],servicePids=[],sockets=new Set();
const env={...process.env,YANXU_SEMANTIC_PORT:'',YANXU_SEMANTIC_TOKEN:''};
let child,port,checks=0,phase='startup',fatal=null;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms)),uuid=()=>crypto.randomUUID();
function check(ok,label){checks++;if(!ok)failures.push(phase+': '+label);}
function same(a,b,label){let ok=true;try{assert.deepEqual(a,b);}catch{ok=false;}check(ok,label);}
function options(resume=false){return ['-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dbootstrap.admin.password='+password,'-Dintegration.account.email.root='+own,'-Dintegration.account.email.resume='+resume,'-Ddata.dir='+data,'-Dsemantic.port=','-Dsemantic.token=','-Daccount.import.manifest=','-Daccount.import.manifest.sha256=','-cp',classes+path.delimiter+path.join(repo,'lib','*')];}
async function request(route,body,actor='admin',overrides={}){
 const headers={...(tokens[actor]?{'X-Token':tokens[actor]}:{})};if(body!==undefined&&overrides.contentType!==null)headers['Content-Type']=overrides.contentType||'application/json';
 const r=await fetch(`http://127.0.0.1:${port}/api${route}`,{method:overrides.method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:overrides.raw?body:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
 const text=await r.text();let envelope=null;if(text){try{envelope=JSON.parse(text);}catch{throw new Error(route+': non-JSON response '+r.status);}}
 return {status:r.status,envelope,text,headers:r.headers};
}
async function api(route,body,actor='admin'){const r=await request(route,body,actor);assert.equal(r.status,200,phase+': '+route+': '+r.status+': '+r.envelope?.msg);assert.equal(r.envelope?.code,0,route);return r.envelope.data;}
async function denied(route,body,actor,status,label,overrides={}){
 const r=await request(route,body,actor,overrides),statuses=Array.isArray(status)?status:[status];
 check(statuses.includes(r.status)&&(overrides.method==='HEAD'?r.text==='':r.envelope?.code===r.status),label+' (HTTP '+r.status+': '+r.envelope?.msg+')');
 check(!r.headers.has('content-disposition'),label+' never becomes an attachment');return r;
}
async function login(actor){tokens[actor]=(await api('/login',{username:actor==='admin'?'admin':'synthetic-email-'+actor,password},'anonymous')).token;}
async function boot(resume=false){
 for(const key of Object.keys(tokens))delete tokens[key];const listener=net.createServer();await new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(0,'127.0.0.1',resolve);});port=listener.address().port;await new Promise(resolve=>listener.close(resolve));
 child=spawn(java,[...options(resume),'com.training.IntegrationAccountEmailHttpFixture',String(port)],{cwd:repo,env,stdio:'ignore'});if(child.pid)servicePids.push(child.pid);
 let launchError;child.once('error',error=>{launchError=error;});
 for(let i=0;i<150;i++){if(launchError)throw launchError;assert.equal(child.exitCode,null,'Isolated fixture service exited before login');try{await login('admin');break;}catch{}await pause(100);}
 assert.ok(tokens.admin,'Isolated Main did not become ready');for(const actor of ['admin2','manager','viewer'])await login(actor);
}
async function stop(){for(const socket of sockets)socket.destroy();sockets.clear();const running=child;child=null;if(!running||running.exitCode!==null||running.signalCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{if(running.exitCode===null&&running.signalCode===null)running.kill('SIGKILL');},2000);running.once('exit',()=>{clearTimeout(timer);resolve();});running.kill('SIGTERM');});}
async function inspect(){assert.ok(!child,'Inspect only after Main stopped');const r=await run(java,[...options(true),'com.training.IntegrationAccountEmailHttpFixture','inspect'],{cwd:repo,env,timeout:20000,maxBuffer:65536});return JSON.parse(r.stdout.trim());}
async function control(operation){
 const nonce=uuid(),temporary=path.join(own,'control-request.next');fs.writeFileSync(temporary,JSON.stringify({operation,nonce}),{mode:0o600});fs.renameSync(temporary,path.join(own,'control-request.json'));
 for(let i=0;i<150;i++){try{const ack=JSON.parse(fs.readFileSync(path.join(own,'control-result.json'),'utf8'));if(ack.nonce===nonce&&ack.ok)return;}catch{}await pause(20);}throw new Error('Synthetic control did not acknowledge '+operation);
}
const base='/account-email-preparation',read=(id=5,actor='admin')=>api(base+'?user_id='+id,undefined,actor);
const command=(id,revision,email,request_id=uuid())=>({user_id:id,expected_revision:revision,request_id,email});
function structure(view,id,revision,email,historyLength){
 check(view.user_id===id&&view.revision===revision&&view.email===email,'Current email identity, version and value match');
 check(view.status===(email===null?'MISSING':'PENDING_VERIFICATION')&&view.can_save===true&&typeof view.duplicate_email==='boolean','Candidate status is typed and never asserts email ownership');
 check(Array.isArray(view.history)&&view.history.length===historyLength,'History has only actual saved revisions');
 for(const item of view.history)check(Number.isSafeInteger(item.revision)&&item.revision>0&&[1,2].includes(item.actor_user_id)&&typeof item.recorded_at==='string'&&Number.isFinite(Date.parse(item.recorded_at))&&item.status===(item.email===null?'MISSING':'PENDING_VERIFICATION'),'Historical row records server actor, time and candidate-only status');
 const expected=['can_save','duplicate_email','email','history','revision','status','user_id'];same(Object.keys(view).sort(),expected,'Response exposes no duplicate account identities or activation claims');
}
async function slowRequest(body,actor,{contentType='application/json',incomplete=true,route=base,timeout=2500}={}){
 const bytes=Buffer.from(body),socket=net.createConnection({host:'127.0.0.1',port});sockets.add(socket);let received='';
 await new Promise((resolve,reject)=>{socket.once('connect',resolve);socket.once('error',reject);});
 let finishResponse;const response=new Promise(resolve=>{finishResponse=resolve;});
 const timer=setTimeout(()=>{finishResponse({status:0,timed_out:true});socket.destroy();},timeout);
 socket.on('data',chunk=>{received+=chunk.toString('utf8');if(received.includes('\r\n\r\n')){clearTimeout(timer);const status=Number(/^HTTP\/1\.[01] (\d{3})/.exec(received)?.[1]);finishResponse({status,timed_out:false});socket.destroy();}});
 socket.on('error',()=>{clearTimeout(timer);finishResponse({status:0,socket_error:true});});socket.on('close',()=>{sockets.delete(socket);});
 socket.write('POST /api'+route+' HTTP/1.1\r\nHost: 127.0.0.1:'+port+'\r\nConnection: close\r\nContent-Length: '+bytes.length+'\r\n'+(contentType===null?'':'Content-Type: '+contentType+'\r\n')+(tokens[actor]?'X-Token: '+tokens[actor]+'\r\n':'')+'\r\n');
 socket.write(incomplete?bytes.subarray(0,Math.max(1,bytes.length-1)):bytes);
 return {response,finish:()=>{if(!socket.destroyed)socket.write(bytes.subarray(bytes.length-1));},close:()=>socket.destroy()};
}
async function revalidation(body,operation,restore,status,label){
 const slow=await slowRequest(JSON.stringify(body),'admin');await pause(100);await control(operation);slow.finish();const result=await slow.response;
 check(result.status===status,label+' rechecks after slow body (HTTP '+result.status+')');
 await denied(base+'?user_id=5',undefined,'admin',status,label+' current GET rechecks authorization/target');
 await denied(base,command(5,body.expected_revision,'denied@example.test'),'admin',status,label+' current POST rechecks authorization/target');
 if(restore)await control(restore);await login('admin');
}
async function runChecks(){
 await boot();const initial=await read();structure(initial,5,0,null,0);same(await read(6),{...initial,user_id:6},'Both imported pending accounts begin missing without hidden activation');
 check((await api('/organization/config')).configuration===null,'Email preparation needs no inferred M01 grant or personnel binding');
 await stop();const baseline=await inspect();
 for(const [name,value]of Object.entries(baseline))if(/ACCOUNT.*EMAIL|EMAIL.*PREPARATION/.test(name))check(value.count===0,'Initial GET leaves '+name+' empty');
 await boot(true);
 phase='authentication before query or request body';
 for(const actor of ['anonymous','manager','viewer']){
  const status=actor==='anonymous'?401:403;
  await denied(base+'?unknown=1',undefined,actor,status,actor+' authenticates before query validation');
  await denied(base+'?unknown=1','{',actor,status,actor+' authenticates before malformed JSON',{raw:true});
  await denied(base,'not-json',actor,status,actor+' authenticates before content type',{raw:true,contentType:'text/plain'});
  const slow=await slowRequest('{"unfinished":"a"}',actor,{contentType:null,route:base+'?unknown=1',timeout:1500});const result=await slow.response;
  check(result.status===status&&!result.timed_out,actor+' rejects before waiting for unfinished POST body');
 }
 for(const target of [7,8,9,10,900000]){
  await denied(base+'?user_id='+target,undefined,'admin',target===900000?404:[404,409],'Ineligible target '+target+' GET is unavailable');
  await denied(base,command(target,0,'candidate@example.test'),'admin',target===900000?404:[404,409],'Ineligible target '+target+' POST is unavailable');
 }
 phase='strict input and data minimization';
 for(const suffix of ['', '?user_id=-1','?user_id=0','?user_id=1.5','?user_id=9007199254740992','?user_id=5&other=1','?user_id=5&user_id=6','?user_id=5&%75ser_id=5','?user_id=5&user_id=5'])await denied(base+suffix,undefined,'admin',400,'GET rejects missing, invalid or duplicate query '+suffix);
 for(const suffix of ['?user_id=5','?extra=1','?'])await denied(base+suffix,command(5,0,'candidate@example.test'),'admin',400,'POST accepts no query '+suffix);
 for(const method of ['PUT','DELETE','HEAD'])await denied(base+'?user_id=5',undefined,'admin',405,'Method '+method+' is rejected',{method});
 for(const field of ['verified','actor_user_id','status','password','role','organization_code','history'])await denied(base,{...command(5,0,'candidate@example.test'),[field]:true},'admin',400,'Client cannot assert '+field);
 for(const invalid of [null,true,1.5,-1,'1.5','9007199254740992'])await denied(base,{...command(5,0,'candidate@example.test'),expected_revision:invalid},'admin',400,'Invalid revision type/value '+JSON.stringify(invalid));
 for(const invalid of ['','not-uuid',123,null])await denied(base,{...command(5,0,'candidate@example.test'),request_id:invalid},'admin',400,'Invalid request identity rejected');
 const exactJson=JSON.stringify(command(5,0,'candidate@example.test'));
 const strictBodies=[
  ['fractional revision must not round to integer',exactJson.replace('"expected_revision":0','"expected_revision":1.0000000000000001')],
  ['fractional target must not round to integer',exactJson.replace('"user_id":5','"user_id":5.0000000000000001')],
  ['duplicate target key',exactJson.replace('"user_id":5','"user_id":5,"user_id":6')],
  ['same duplicate revision key',exactJson.replace('"expected_revision":0','"expected_revision":0,"expected_revision":0')],
  ['escaped duplicate target key',exactJson.replace('"user_id":5','"user_id":5,"\\u0075ser_id":5')],
  ['escaped duplicate email key',exactJson.replace('"email":"candidate@example.test"','"email":"candidate@example.test","\\u0065mail":"other@example.test"')],
  ['vertical tab is not JSON whitespace','\u000b'+exactJson],
  ['form feed is not JSON whitespace','\u000c'+exactJson],
  ['embedded vertical tab is not JSON whitespace',exactJson.replace('{','{\u000b')],
  ['embedded form feed is not JSON whitespace',exactJson.replace(':',':\u000c')]
 ];
 for(const [label,body]of strictBodies)await denied(base,body,'admin',400,'Strict JSON: '+label,{raw:true});
 for(const email of ['a@example.test\r\nBcc:other@example.test','a@example.test\n','a@example.test,b@example.test','a@example.test;b@example.test','Display <a@example.test>','a b@example.test','@example.test','a@','x'.repeat(300)+'@example.test',true,3,{},[]])await denied(base,command(5,0,email),'admin',400,'Invalid address structure is rejected');
 same(await read(),initial,'Rejected writes leave initial value and audit history unchanged');
 const media=await denied(base,JSON.stringify(command(5,0,'candidate@example.test')),'admin',415,'Authenticated POST requires JSON media',{raw:true,contentType:'text/plain'});check(media.headers.get('cache-control')?.includes('no-store'),'Private error response is not cacheable');
 phase='CAS audit idempotency and duplicates';
 const removable=await api('/users',{username:'synthetic-email-removable',name:'SYNTHETIC REMOVABLE ADMIN',role:'admin',status:1,password});
 check(Number.isSafeInteger(removable)&&removable>10,'A synthetic administrator without email audit references can be created');
 await api('/users/delete',{id:removable});
 await denied('/login',{username:'synthetic-email-removable',password},'anonymous',401,'Unreferenced administrator retains original delete behavior');
 structure(await api(base,command(6,0,null),'admin2'),6,0,null,0);
 await denied('/users/delete',{id:2},'admin',409,'Even an initial empty-to-empty request retains its administrator reference');
 check((await api('/me',undefined,'admin2')).uid===2,'Rejected deletion preserves request-only administrator and session');
 const first=command(5,0,'  Case.Tag@EXAMPLE.TEST  '),firstResult=await api(base,first);structure(firstResult,5,1,'Case.Tag@example.test',1);
 check(firstResult.history[0].actor_user_id===1,'First audit revision is attributed to real admin account');
 await denied('/users/delete',{id:1},'admin2',409,'Email audit administrator deletion gives a controlled conflict');
 same(await read(),firstResult,'Rejected audit administrator deletion preserves exact email history');
 check((await api('/me')).uid===1,'Rejected audit administrator deletion keeps the real administrator session');
 const readResponse=await request(base+'?user_id=5');check(readResponse.headers.get('cache-control')?.includes('no-store'),'Private read response disables caches');
 const unchanged=await api(base,command(5,1,'Case.Tag@example.test'));structure(unchanged,5,1,'Case.Tag@example.test',1);
 const duplicate=await api(base,command(6,0,'case.tag@example.test'),'admin2');structure(duplicate,6,1,'case.tag@example.test',1);check(duplicate.duplicate_email===true,'Case-insensitive duplicate is a generic warning');
 check((await read()).duplicate_email===true,'Duplicate warning is current on both pending candidates');
 await api(base,command(6,1,''),'admin2');check((await read()).duplicate_email===false,'Cleared other candidate removes current duplicate warning');
 const second=await api(base,command(5,1,'second@example.test'),'admin2');structure(second,5,2,'second@example.test',2);
 check(second.history.find(h=>h.revision===2)?.actor_user_id===2,'Another real administrator records their own revision');
 const replay=await api(base,first);same(replay,second,'Replaying old request returns latest value/history and never rolls back');
 await denied(base,{...first,email:'changed-replay@example.test'},'admin',409,'Same request ID cannot change payload');
 await denied(base,command(5,1,'stale@example.test'),'admin',409,'CAS rejects stale update from another administrator');
 const cleared=await api(base,command(5,2,''));structure(cleared,5,3,null,3);check(cleared.history.some(h=>h.email==='Case.Tag@example.test')&&cleared.history.some(h=>h.email==='second@example.test'),'Clear retains historical candidate revisions');
 structure(await api(base,command(5,3,null)),5,3,null,3);
 const left=command(5,3,'left@example.test'),right=command(5,3,'right@example.test');
 const race=await Promise.all([request(base,left,'admin'),request(base,right,'admin2')]);same(race.map(r=>r.status).sort(),[200,409],'Two concurrent administrators have exactly one CAS winner');
 const head=await read();structure(head,5,4,race[0].status===200?'left@example.test':'right@example.test',4);
 same(await api(base,first),head,'Old retry after concurrent replacement still returns current head');
 for(const actor of ['admin','admin2','manager','viewer'])check((await api('/me',undefined,actor)).uid>0,'Email changes do not revoke existing '+actor+' session');
 for(const name of ['pending-a','pending-b'])await denied('/login',{username:'synthetic-email-'+name,password},'anonymous',401,'Registering email does not activate '+name+' login');
 phase='live authentication and target revalidation';
 await revalidation(command(5,4,'slow-downgrade@example.test'),'downgrade-admin','restore-admin',403,'Live downgraded administrator');
 same(await read(),head,'Denied downgraded request creates no email revision');
 await revalidation(command(5,4,'slow-revoked@example.test'),'revoke-admin',null,401,'Revoked real session');
 await revalidation(command(5,4,'slow-disabled@example.test'),'disable-admin','restore-admin',401,'Disabled administrator');
 for(const [operation,label]of [['activate-target','Target activated'],['password-target','Target given password'],['role-target','Target changed role']]){
  await revalidation(command(5,4,'slow-target@example.test'),operation,'restore-target',409,label);same(await read(),head,label+' preserves exact stored email history');
 }
 // Retry must recheck current eligibility too; the original UUID is not an authorization token.
 await control('activate-target');await denied(base,first,'admin',409,'Old idempotent request cannot bypass current target eligibility');await control('restore-target');
 same(await read(),head,'Restoring synthetic target does not require or invent an email migration');
 phase='all-table invariants and persistent restart';
 const beforeRestart=await read(),secondBefore=await read(6);await stop();const after=await inspect();
 const emailTables=Object.keys(after).filter(name=>/ACCOUNT.*EMAIL|EMAIL.*PREPARATION/.test(name));check(emailTables.length>0,'Dedicated email preparation tables exist');
 same(Object.keys(after),Object.keys(baseline),'Email preparation does not add unrelated database tables');
 let unchangedTables=0;for(const [name,hash]of Object.entries(baseline))if(!emailTables.includes(name)){same(after[name],hash,'Email preparation leaves full '+name+' contents unchanged');unchangedTables++;}
 check(after.USERS?.sha256===baseline.USERS?.sha256&&after.ORGANIZATION_ACCOUNT_IMPORT_PEOPLE?.sha256===baseline.ORGANIZATION_ACCOUNT_IMPORT_PEOPLE?.sha256&&after.ORGANIZATION_ACCOUNT_IMPORT_BATCHES?.sha256===baseline.ORGANIZATION_ACCOUNT_IMPORT_BATCHES?.sha256,'Users/passwords/roles and imported person/receipt rows remain identical');
 fs.writeFileSync(path.join(own,'database-invariants.json'),JSON.stringify({before:baseline,after,email_tables:emailTables},null,2),{mode:0o600});
 await boot(true);same(await read(),beforeRestart,'Main restart retains current email and complete immutable audit history');same(await read(6),secondBefore,'Cleared second candidate remains missing after restart');same(await api(base,first),beforeRestart,'Persisted request replay after restart still returns current head');
 await stop();const restarted=await inspect();same(restarted,after,'Restart reads and durable replay do not rewrite any database row');
 return {unchanged_tables:unchangedTables,email_tables:emailTables,imported_pending_ids:[5,6]};
}
let evidence;
runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{
 await stop();const stopped=servicePids.every(pid=>{try{process.kill(pid,0);return false;}catch(error){return error.code==='ESRCH';}});check(stopped,'All synthetic Main processes are stopped');
 const summary={suite:'account-email-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),server_processes_stopped:stopped,production_touched:false,notifications_sent:false,artifacts:own};
 fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;
});
