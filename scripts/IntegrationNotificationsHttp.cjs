'use strict';
// Actual Main/Api HTTP, synthetic identities and fresh H2 only. No app/data, production or notification channel.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process'), {promisify} = require('node:util');
const {workflowConfiguration} = require('./IntegrationWorkflowHttpFixture.cjs');
const run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(!classes.startsWith(path.join(repo, 'out')), 'Use isolated compiled classes');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationNotificationsHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-notifications-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration, serial = 0, configSerial = 0;
const actors = ['filler','leader','bp','team','outsider','auditor'];
const userIds = {};
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.notifications.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'filler', options = {}) {
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(options.headers || {})};
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: options.method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : options.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(10000)});
  const text = await response.text(); const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text};
}
async function api(route, body, actor = 'filler') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, options = {}) {
  const r = await request(route, body, actor, options);
  check(r.status === status && (options.method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  return r;
}
async function login(actor, username = 'synthetic-notifications-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationNotificationsHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated notifications service did not become ready');
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM');
  });
}
async function offline(operation) {
  assert.ok(!child, 'Offline fixture cannot run against an active service');
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationNotificationsHttpFixture', operation], {cwd: repo, env, timeout: 20000, maxBuffer: 65536});
  return operation === 'inspect' ? JSON.parse(result.stdout.trim()) : null;
}
const base='/notifications';
const detail=id=>base+'/'+id, target=id=>detail(id)+'/target', read=id=>detail(id)+'/read';
const page=actor=>api(base+'?offset=0&limit=100',undefined,actor);
const current=(d,actor='filler')=>api('/demands/workflow?id='+d.id,undefined,actor).then(r=>r.workflow);
const command=(d,key)=>({id:d.id,expected_version:d.version,request_id:key});
const approval=(d,action,key)=>({action,expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId:key,comment:'SYNTHETIC S01 审批意见'});
async function action(d,verb,key,actor){return(await api('/approvals/tasks/'+d.id+'/actions',approval(d,verb,key),actor)).workflow;}
async function draft(title){return(await api('/demands/draft',{request_id:'DRAFT-'+(++serial),title,unit:'SYNTHETIC CUSTOMER',business_path:'direct',organization_code:'001',internal_contact_code:'P1',category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'90',participant_count:'1',budget_amount:'0',objectives:'SYNTHETIC',content:'SYNTHETIC',teacher_req:'',remark:'SYNTHETIC'},'filler')).workflow;}
async function submitted(title){const d=await draft(title);return(await api('/demands/submit',command(d,'SUBMIT-'+(++serial)),'filler')).workflow;}
async function forDemand(actor,d){
 const all=await page(actor);for(const item of all.items){const t=(await api(target(item.id),undefined,actor)).target;if(t.params.recordId===String(d.id))return item;}
 throw new Error(phase+': missing visible notice for '+actor+' / '+d.id);
}
async function unavailable(actor,id,label){
 await denied(detail(id),undefined,actor,404,label+' detail');await denied(target(id),undefined,actor,404,label+' target');await denied(read(id),{},actor,404,label+' read');
 check(!(await page(actor)).items.some(row=>row.id===id),label+' list filtered before counts');
}
async function freshLogins(){for(const actor of actors)await login(actor);}
async function publish(next,label,affected=actors){
 next=structuredClone(next);next.version='synthetic-notifications-http-'+(++configSerial)+'-'+label;
 const result=await request('/organization/config',{expectedVersion:configuration?.version||null,configuration:next},'admin');
 assert.equal(result.status,200,label+' publish '+result.envelope?.msg);configuration=next;
 check(result.envelope.data.sessionInvalidated===false,label+' unbound configuration administrator keeps its own session');
 for(const actor of affected){await denied('/me',undefined,actor,401,label+' revokes previous '+actor+' session');await login(actor);}
 return result;
}
async function identities(){
 for(const actor of actors){userIds[actor]=await api('/users',{username:'synthetic-notifications-'+actor,name:'SYNTHETIC '+actor,role:actor==='team'?'manager':'viewer',status:1,password},'admin');await login(actor);}
 let c=workflowConfiguration(['filler','leader','bp','team','outsider'].map(a=>userIds[a]));
 const optional={required:false,allowedTargetRoles:['LEADER','BP'],targetMustCoverOrganization:false,allowSelf:false};
 c.roleCodes.push('AUDIT');c.relations.push({roleCode:'AUDIT',leader:optional,bp:optional});
 for(const[personCode,role]of[['P6','AUDIT'],['P8','LEADER'],['P9','AUDIT']])c.people.push({personCode,organizationCode:'002',roleCodes:[role],responsibleOrganizationCodes:role==='LEADER'?['001']:[],leaderPersonCode:null,bpPersonCode:null,enabled:true});
 c.accountBindings.push({accountId:userIds.auditor,personCode:'P6',enabled:true});
 for(const roleCode of['FILLER','LEADER','BP','TEAM','OUT'])c.grants.push({ruleId:'notify-'+roleCode,roleCode,resource:'notifications.read',action:'VIEW',effect:'ALLOW',scope:roleCode==='OUT'?'OWN_ORG':'NAMED_ORGS',organizationCodes:roleCode==='OUT'?[]:['001']});
 for(const resource of['notifications.audit','demand.read'])c.grants.push({ruleId:'audit-'+resource,roleCode:'AUDIT',resource,action:'VIEW',effect:'ALLOW',scope:'NAMED_ORGS',organizationCodes:['001']});
 await publish(c,'initial');
}
function protectedTables(snapshot){return Object.fromEntries(Object.entries(snapshot.tables).filter(([name])=>name!=='USERS'));}
async function runChecks(){
 await boot(true);tokens.expired=fs.readFileSync(path.join(own,'expired-token.txt'),'utf8');
 await denied(base,undefined,'anonymous',401,'Inbox requires a real session');await denied(base,undefined,'expired',401,'Actually expired issued token is rejected');
 same(await api('/organization/config',undefined,'admin'),{version:null,configuration:null},'Startup seeds no organization identity or grant');await identities();
 phase='explicit authorization and actual workflow events';
 await denied(base,undefined,'admin',403,'Unbound legacy admin has no implicit inbox identity');await denied(base+'/diagnostics',undefined,'admin',403,'Legacy admin has no implicit audit permission');
 await denied(base+'/diagnostics',undefined,'leader',403,'Notification recipient does not implicitly gain diagnostics');
 check((await api('/me',undefined,'leader')).role==='viewer','Real leader session keeps its original viewer role');
 for(const actor of actors)check((await page(actor)).total===0,'No inferred or backfilled inbox for '+actor);
 let main=await submitted('SYNTHETIC NOTIFICATION MAIN');const initial=await forDemand('leader',main),leaderId=initial.id;
 check(initial.type==='REVIEW_REQUIRED'&&initial.actionable===true&&initial.readAt===null,'Actual submission freezes an unread actionable leader notice');
 same(Object.keys(initial).sort(),['id','eventId','type','title','body','createdAt','readAt','actionable'].sort(),'Notification view exposes only the documented safe fields');
 for(const actor of['filler','bp','team','outsider','auditor'])check((await page(actor)).total===0,'Submission cannot guess or broadcast '+actor+' recipients');
 await unavailable('outsider',leaderId,'Other organization recipient');await unavailable('bp',leaderId,'Same authorized organization nonrecipient');
 const t=(await api(target(leaderId),undefined,'leader')).target;
 same(Object.keys(t).sort(),['moduleId','params'],'Target never exposes a free-form URL or action');same(Object.keys(t.params).sort(),['eventId','recordId','taskId','view'],'M03 target uses its strict internal navigation fields');
 check(t.moduleId==='M03'&&t.params.recordId===String(main.id)&&t.params.taskId===String(main.id)&&t.params.eventId===initial.eventId&&t.params.view==='task','Target identifies exact persisted demand and approval task');
 check((await api(detail(leaderId),undefined,'leader')).notification.readAt===null,'Opening a target never marks read');
 const beforeRead=await current(main,'leader');
 const exact='{}'+' '.repeat(1024*1024-2);
 await denied(read(leaderId),exact+' ','leader',413,'One byte above ordinary 1 MiB body limit is rejected',{raw:true});
 const firstRead=await request(read(leaderId),exact,'leader',{raw:true});check(firstRead.status===200,'Exactly 1 MiB empty JSON body is accepted');const readReceipt=firstRead.envelope.data;
 same(await api(read(leaderId),{},'leader'),readReceipt,'Repeated mark-read retains the original first timestamp');
 same(await current(main,'leader'),beforeRead,'Mark-read never approves or mutates the underlying workflow');
 check((await api(detail(leaderId),undefined,'leader')).notification.actionable===true&&(await page('leader')).unreadCount===0,'Read state and current actionable state remain separate');
 phase='strict method, media, identity and pagination boundaries';
 for(const query of['recipient=1','actor=P2','permission=admin','returnUrl=%2Fadmin','offset=-1','limit=0','limit=101','offset=2147483648','limit=1.5','offset=x'])await denied(base+'?'+query,undefined,'leader',400,'Query whitelist and safe pagination: '+query);
 for(const route of[detail(leaderId),read(leaderId),target(leaderId),base+'/channels'])await denied(route+'?personCode=P2',route.endsWith('/read')?{}:undefined,'leader',400,'Non-list routes reject injected identity');
 for(const field of['recipient','personCode','organization','role','permissions','readAt','actionable','content','target','returnUrl'])await denied(read(leaderId),{[field]:true},'leader',400,'Read body cannot forge '+field);
 for(const raw of['{','[]','null','{"recipient":1,"recipient":2}'])await denied(read(leaderId),raw,'leader',400,'Malformed and non-object JSON is rejected',{raw:true});
 await denied(read(leaderId),{},'leader',415,'Mark-read requires JSON content type',{headers:{'Content-Type':'text/plain'}});
 for(const[route,method,allow]of[[base,'POST','GET'],[detail(leaderId),'POST','GET'],[target(leaderId),'POST','GET'],[read(leaderId),'GET','POST'],[base+'/channels','POST','GET'],[base+'/diagnostics','POST','GET']]){const r=await denied(route,method==='POST'?{}:undefined,'leader',405,'Unsupported method '+method+' '+route,{method});check(r.headers.get('allow')===allow,'Method denial states exact Allow header');}
 await denied(base,undefined,'leader',405,'HEAD cannot bypass the explicit GET method',{method:'HEAD'});
 for(const id of['1',leaderId.toUpperCase(),'invalid-id'])await denied(detail(id),undefined,'leader',404,'Malformed opaque notification id is rejected');
 const missing='00000000-0000-0000-0000-000000000000';for(const route of[detail(missing),target(missing)])await denied(route,undefined,'leader',404,'Unknown notification stays indistinguishable from another owner');await denied(read(missing),{},'leader',404,'Unknown read target does not create a row');
 await denied(detail(leaderId)+'/send',{},'leader',404,'No external send route exists');await denied(detail(leaderId)+'/unknown',undefined,'leader',404,'Unknown notification subroute is rejected');
 same((await api(base+'/channels',undefined,'leader')).channels,[{channel:'IN_APP',status:'ready'},{channel:'EMAIL',status:'adapter_not_connected'},{channel:'PUBLIC_ACCOUNT',status:'unconfigured'}],'Channels report actual local-only connectivity');
 phase='leader, BP, team, return and resubmission';
 main=await action(main,'APPROVE','MAIN-LEADER','leader');const bpNotice=await forDemand('bp',main);
 check(bpNotice.type==='REVIEW_REQUIRED'&&bpNotice.actionable&&bpNotice.readAt===null,'Actual leader approval generates an independent BP task');
 const old=(await api(detail(leaderId),undefined,'leader')).notification;check(old.actionable===false&&old.readAt===readReceipt.readAt,'Resolved leader notice retains its read timestamp');check((await api(target(leaderId),undefined,'leader')).target.params.view==='detail','Resolved target switches to the historical detail');
 main=await action(main,'APPROVE','MAIN-BP','bp');check(main.approval.status==='READY_FOR_TEAM','Unconfigured team notification does not block BP approval');
 check((await page('team')).total===0,'Team is not inferred from managers or accepting permissions');
 let diag=await api(base+'/diagnostics?limit=100',undefined,'auditor');check(diag.items.some(r=>r.recordId===String(main.id)&&r.reason==='TEAM_RECIPIENTS_UNCONFIGURED'&&r.status==='BLOCKED'),'Explicit auditor sees blocked team delivery');
 check(diag.items.some(r=>r.reason==='HISTORICAL_BINDING_UNPROVEN'),'Old unprojected outbox remains diagnostically explicit');
 same(Object.keys(diag.items[0]).sort(),['eventId','recordId','createdAt','status','reason'].sort(),'Diagnostics expose no recipient identity or source payload');
 const acceptance=command(main,'MAIN-ACCEPT');acceptance.team_code='900';main=(await api('/demands/accept',acceptance,'team')).workflow;
 check(main.project_id>0,'A real team acceptance still succeeds while the team notification is blocked');const accepted=await forDemand('filler',main);
 check(accepted.type==='HANDOVER_ACCEPTED'&&!accepted.actionable&&accepted.readAt===null,'Acceptance creates a separate unread informational notice');
 const at=(await api(target(accepted.id),undefined,'filler')).target;check(at.moduleId==='M02'&&at.params.recordId===String(main.id)&&at.params.view==='detail'&&!('taskId'in at.params),'Acceptance target returns to the original M02 demand');
 let returned=await submitted('SYNTHETIC NOTIFICATION RETURN');returned=await action(returned,'RETURN','RETURN-LEADER','leader');const returnedNotice=await forDemand('filler',returned);
 check(returnedNotice.type==='RETURNED'&&returnedNotice.actionable&&returnedNotice.readAt===null,'Actual return notifies original filler and permits resubmission');
 returned=await action(returned,'RESUBMIT','RETURN-RESUBMIT','filler');check(!(await api(detail(returnedNotice.id))).notification.actionable&&(await api(detail(returnedNotice.id))).notification.readAt===null,'Resubmission resolves old return task without marking it read');
 const resubmitted=(await page('leader')).items;check(resubmitted.filter(r=>r.actionable).length===1,'Resubmission freezes one new leader task without reviving old notices');
 const revise={...command(returned,'RETURN-REVISE'),title:'SYNTHETIC NOTIFICATION REVISED',change_comment:'SYNTHETIC substantial revision'};returned=(await api('/demands/draft',revise,'filler')).workflow;
 check((await api(base+'/diagnostics?limit=100',undefined,'auditor')).items.some(r=>r.reason==='UNSUPPORTED_BUSINESS_MOMENT'),'Content revision remains valid but does not invent an approved reminder rule');
 const all=await page('leader'),p1=await api(base+'?offset=0&limit=1',undefined,'leader'),p2=await api(base+'?offset=1&limit=1',undefined,'leader');
 check(p1.items.length===1&&p2.items.length===1&&p1.items[0].id!==p2.items[0].id&&p1.total===all.total&&p2.unreadCount===all.unreadCount,'Pagination counts all visible records and never duplicates adjacent pages');
 check((await api(base+'?offset=2147483647&limit=1',undefined,'leader')).items.length===0,'Maximum valid offset remains bounded and returns an empty page');
 const con=await submitted('SYNTHETIC NOTIFICATION CONCURRENT'),conBody=approval(con,'APPROVE','CONCURRENT-IDENTICAL');
 const race=await Promise.all([api('/approvals/tasks/'+con.id+'/actions',conBody,'leader'),api('/approvals/tasks/'+con.id+'/actions',conBody,'leader')]);check(race.every(r=>r.workflow.approval.stage==='BP'),'Concurrent identical approvals both resolve to the same persisted BP state');
 check((await page('bp')).items.filter(r=>r.actionable).length===1,'Concurrent identical approval produces one actionable BP notice');
 phase='actual process restart, read-only scans and transaction rollback';
 const source=await submitted('SYNTHETIC NOTIFICATION SOURCE'),sourceNotice=await forDemand('leader',source),rollback=await draft('SYNTHETIC NOTIFICATION ROLLBACK');
 await stop();const persisted=await offline('inspect');check(persisted.outbox_statuses.length===1&&persisted.outbox_statuses[0].status==='PENDING','Every outbox remains PENDING: local projection never claims external delivery');
 await boot();await freshLogins();same(await api(read(leaderId),{},'leader'),readReceipt,'First read timestamp survives real Main restart');
 same(await api(detail(sourceNotice.id),undefined,'leader'),{notification:sourceNotice},'Untouched notification survives real process restart exactly');
 await api('/demands/accept',acceptance,'team');await api('/approvals/tasks/'+con.id+'/actions',conBody,'leader');
 for(const actor of actors){await page(actor);await api(base+'/channels',undefined,actor);}await api(base+'/diagnostics?offset=0&limit=1',undefined,'auditor');await api(target(sourceNotice.id),undefined,'leader');
 await stop();const afterReads=await offline('inspect');same(afterReads,persisted,'GET scans, unchanged read replay and repeated business receipts write no table data or backfill history');
 await offline('faults');const beforeRollback=await offline('inspect');await boot();await freshLogins();
 await unavailable('leader',sourceNotice.id,'Tampered source event');
 const rollbackBody=command(rollback,'ROLLBACK-SUBMIT');await denied('/demands/submit',rollbackBody,'filler',500,'Database notification failure aborts real business transaction');
 const remained=await current(rollback);check(remained.draft===true&&remained.approval===null,'Failed submission leaves the original draft without approval');
 await stop();same(await offline('inspect'),beforeRollback,'Failed notification INSERT rolls back business, approval, document, request, outbox and notification writes');
 await offline('restore');await boot();await freshLogins();same((await api(detail(sourceNotice.id),undefined,'leader')).notification,sourceNotice,'Restoring the exact synthetic source recovers source integrity without rewriting notice');
 const recovered=(await api('/demands/submit',rollbackBody,'filler')).workflow;await forDemand('leader',recovered);await api('/demands/submit',rollbackBody,'filler');check(true,'Retry of rolled-back request succeeds once after the fault is removed');
 phase='configuration publication, revocation and identity continuity';
 const currentLeader=await forDemand('leader',recovered),initialConfig=structuredClone(configuration);
 const invalid=structuredClone(configuration);invalid.version='synthetic-invalid';invalid.accountBindings[0].accountId=999999;
 await denied('/organization/config',{expectedVersion:configuration.version,configuration:invalid},'admin',400,'Failed invalid configuration cannot publish');check((await api('/me',undefined,'leader')).uid===userIds.leader,'Failed publication does not revoke a valid old session');
 const unchanged=structuredClone(configuration);unchanged.people.reverse();unchanged.accountBindings.reverse();unchanged.grants.reverse();await publish(unchanged,'reordered',[]);
 check((await api(detail(currentLeader.id),undefined,'leader')).notification.id===currentLeader.id,'Version-only and unordered list changes preserve session and continuous entitlement');
 const originalGrants=structuredClone(configuration.grants);
 await publish({...configuration,grants:originalGrants.filter(g=>g.ruleId!=='notify-LEADER')},'revoke-notify');await unavailable('leader',currentLeader.id,'Revoked notifications.read');
 const noGrant=await submitted('SYNTHETIC NOTIFICATION NO GRANT');check(noGrant.approval.status==='LEADER_PENDING','Missing notification grant never blocks permitted business submission');check((await api(base+'/diagnostics?limit=100',undefined,'auditor')).items.some(r=>r.recordId===String(noGrant.id)&&r.reason==='RECIPIENT_BINDING_OR_PERMISSION_UNPROVEN'),'Unproven recipient authorization produces a blocked receipt');
 await publish({...configuration,grants:originalGrants},'restore-notify');await unavailable('leader',currentLeader.id,'Restored notifications.read cannot revive broken historical chain');check((await page('leader')).total===0,'Blocked or revoked events never fill the restored inbox');
 const afterGrant=await submitted('SYNTHETIC NOTIFICATION AFTER RESTORE'),afterGrantNotice=await forDemand('leader',afterGrant);
 await publish({...configuration,grants:originalGrants.filter(g=>!(g.roleCode==='LEADER'&&g.resource==='demand.read'))},'revoke-demand');await unavailable('leader',afterGrantNotice.id,'Revoked demand.read');await publish({...configuration,grants:originalGrants},'restore-demand');await unavailable('leader',afterGrantNotice.id,'Restored demand.read never revives old notices');
 const beforeBind=await submitted('SYNTHETIC NOTIFICATION BEFORE BIND'),beforeBindNotice=await forDemand('leader',beforeBind);tokens.staleLeader=tokens.leader;
 const originalBindings=structuredClone(configuration.accountBindings),originalPeople=structuredClone(configuration.people);
 await publish({...configuration,accountBindings:originalBindings.map(b=>b.accountId===userIds.leader?{...b,personCode:'P8'}:b),people:originalPeople.map(p=>p.personCode==='P1'?{...p,leaderPersonCode:'P8'}:p)},'rebind',['leader','filler']);
 check((await api('/me',undefined,'bp')).uid===userIds.bp,'A personal binding change preserves unrelated BP session');await unavailable('leader',beforeBindNotice.id,'Same account rebound to new person');
 const newIdentity=await submitted('SYNTHETIC NOTIFICATION NEW IDENTITY'),newIdentityNotice=await forDemand('leader',newIdentity);await denied(detail(newIdentityNotice.id),undefined,'staleLeader',401,'Old pre-rebind login cannot inherit newly generated notice for the new identity');await denied('/workflow/context',undefined,'staleLeader',401,'Old pre-rebind login cannot inherit new business authority');
 await publish({...configuration,accountBindings:originalBindings,people:originalPeople},'binding-restored',['leader','filler']);await unavailable('leader',beforeBindNotice.id,'Binding restore cannot revive old identity history');await unavailable('leader',newIdentityNotice.id,'Restored original identity cannot read alternate-person notice');
 const beforeDisable=await submitted('SYNTHETIC NOTIFICATION BEFORE DISABLE'),disableNotice=await forDemand('leader',beforeDisable);
 await publish({...configuration,people:configuration.people.map(p=>p.personCode==='P2'?{...p,enabled:false}:p.personCode==='P1'?{...p,leaderPersonCode:'P8'}:p)},'person-disabled',['leader','filler']);await denied(base,undefined,'leader',403,'Fresh login cannot use disabled M01 person');
 await publish({...configuration,people:originalPeople},'person-enabled',['leader','filler']);await unavailable('leader',disableNotice.id,'Person re-enable does not revive the disabled interval');
 const active=await submitted('SYNTHETIC NOTIFICATION ACTIVE'),activeNotice=await forDemand('leader',active),leaderUser=(await api('/users',undefined,'admin')).find(u=>u.id===userIds.leader);
 await api('/users',{...leaderUser,status:0},'admin');for(const route of[base,detail(activeNotice.id),target(activeNotice.id)])await denied(route,undefined,'leader',401,'Disabled account loses all existing session read authority');await denied(read(activeNotice.id),{},'leader',401,'Disabled account cannot write read state');
 await api('/users',{...leaderUser,status:1},'admin');await denied(base,undefined,'leader',401,'Account re-enable cannot resurrect old Auth token');await login('leader');check((await api(detail(activeNotice.id),undefined,'leader')).notification.id===activeNotice.id,'Fresh account login preserves intact configured historical entitlement');
 phase='historical account references and publisher cookie';
 await publish({...configuration,accountBindings:configuration.accountBindings.filter(b=>b.accountId!==userIds.leader)},'unbind-before-delete',['leader']);
 await denied('/users/delete',{id:userIds.leader},'admin',409,'Unbound account with historical notifications cannot be deleted');check((await api('/users',undefined,'admin')).some(u=>u.id===userIds.leader),'Deletion guard retains account and all notification references');
 const adminId=(await api('/me',undefined,'admin')).uid,c=structuredClone(configuration);c.version='synthetic-notifications-http-publisher';c.accountBindings.push({accountId:adminId,personCode:'P9',enabled:true});
 const self=await request('/organization/config',{expectedVersion:configuration.version,configuration:c},'admin');check(self.status===200&&self.envelope.data.sessionInvalidated===true,'Committed publisher identity change returns success plus explicit sessionInvalidated');check((self.headers.get('set-cookie')||'').includes('Max-Age=0'),'Invalidated publisher cookie is cleared');await denied('/me',undefined,'admin',401,'Publisher old session is invalid after successful response');await login('admin','admin');configuration=c;
 check((await api(base+'/diagnostics?limit=100',undefined,'admin')).total>0,'New login uses only the newly explicit auditor binding');
 await stop();const final=await offline('inspect');check(final.outbox_statuses.length===1&&final.outbox_statuses[0].status==='PENDING','All final workflow outbox entries remain unsent');check(final.tables.S01_NOTIFICATIONS.count<final.tables.S01_NOTIFICATION_EVENTS.count,'Blocked event batches are durable without fabricated inbox recipients');
 return {main_demand_id:main.id,notification_count:final.tables.S01_NOTIFICATIONS.count,event_count:final.tables.S01_NOTIFICATION_EVENTS.count,inspected_tables:Object.keys(final.tables).length,read_only_snapshot_equal:true,rollback_snapshot_equal:true,configuration_version:configuration.version};
}
let evidence;runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{await stop();const summary={suite:'notifications-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;});
