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
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationSummariesHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-summaries-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration, serial = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.summaries.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-Dtraining.summary.review.order=', '-Dtraining.summary.review.policyVersion=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'worker', options = {}) {
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(options.headers || {})};
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: options.method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : options.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(10000)});
  const text = await response.text(); const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text};
}
async function api(route, body, actor = 'worker') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, options = {}) {
  const r = await request(route, body, actor, options);
  check(r.status === status && (options.method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  return r;
}
async function login(actor, username = 'synthetic-summaries-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationSummariesHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated summaries service did not become ready');
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
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationSummariesHttpFixture', operation], {cwd: repo, env, timeout: 20000, maxBuffer: 65536});
  return operation === 'inspect' ? JSON.parse(result.stdout.trim()) : null;
}
const base='/training-summaries';
const current=id=>base+'?project_id='+id;
const revision=(id,n)=>base+'/revision?project_id='+id+'&revision='+n;
const history=id=>base+'/history?project_id='+id;
const command=(project_id,expected_version,request_id)=>({project_id,expected_version,request_id});
const save=(id,version,key,content)=>({...command(id,version,key),content});
// Configuration publication is an explicit authentication boundary, never an API retry policy.
async function renewPublishedSessions(label, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'worker', 'peer', 'reader', 'writeonly', 'filler']) {
  for (const actor of actors) {
    if (!tokens[actor]) continue; // Some identities have not logged in since a process restart.
    await denied('/me', undefined, actor, 401, label + ' invalidates the old ' + actor + ' token');
    await login(actor, actor === 'admin' ? 'admin' : undefined);
  }
}
async function publish(changes,suffix,affected){const expectedVersion=configuration.version;configuration={...structuredClone(configuration),...changes,version:'synthetic-summaries-http-'+suffix};await api('/organization/config',{expectedVersion,configuration},'admin');await renewPublishedSessions(suffix,affected);}
async function identities(){
 const ids=[(await api('/me',undefined,'admin')).uid],accounts=[['leader','viewer'],['bp','viewer'],['team','manager'],['outsider','viewer'],['worker','viewer'],['peer','viewer'],['reader','viewer'],['writeonly','viewer'],['filler','manager'],['unbound','manager']];
 for(const[actor,role]of accounts){ids.push(await api('/users',{username:'synthetic-summaries-'+actor,name:'SYNTHETIC '+actor,role,status:1,password},'admin'));await login(actor);}
 configuration=workflowConfiguration(ids.slice(0,5));configuration.version='synthetic-summaries-http-v1';
 for(const person of configuration.people)if(['P2','P3','P4'].includes(person.personCode))person.responsibleOrganizationCodes.push('999');
 for(const grant of configuration.grants)if(grant.scope==='NAMED_ORGS'&&grant.organizationCodes.includes('001'))grant.organizationCodes.push('999');
 const optional={required:false,allowedTargetRoles:['EDITOR','READER','WRITER'],targetMustCoverOrganization:false,allowSelf:false};
 for(const[personCode,role,organizationCode,index]of[['P6','EDITOR','001',5],['P7','EDITOR','001',6],['P8','READER','001',7],['P9','WRITER','001',8],['P10','FILLER','999',9]]){
  configuration.people.push({personCode,roleCodes:[role],organizationCode,responsibleOrganizationCodes:[],leaderPersonCode:role==='FILLER'?'P2':null,bpPersonCode:role==='FILLER'?'P3':null,enabled:true});configuration.accountBindings.push({accountId:ids[index],personCode,enabled:true});
 }
 configuration.people.push({personCode:'P11',roleCodes:['EDITOR'],organizationCode:'001',responsibleOrganizationCodes:[],leaderPersonCode:null,bpPersonCode:null,enabled:true});
 for(const roleCode of['EDITOR','READER','WRITER']){configuration.roleCodes.push(roleCode);configuration.relations.push({roleCode,leader:optional,bp:optional});}
 for(const[roleCode,resource,action]of[['EDITOR','summary.read','VIEW'],['EDITOR','summary.edit','HANDLE'],['READER','summary.read','VIEW'],['WRITER','summary.edit','HANDLE'],['OUT','summary.read','VIEW'],['OUT','summary.edit','HANDLE']])configuration.grants.push({ruleId:roleCode+'-'+resource,roleCode,resource,action,effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]});
 await api('/organization/config',{expectedVersion:null,configuration},'admin');await renewPublishedSessions('Initial binding');return ids[5];
}
async function acceptedProject(actor,org,person,title){
 const tag='SUMMARY-'+(++serial);let d=(await api('/demands/draft',{request_id:tag+'-DRAFT',title,unit:'SYNTHETIC CUSTOMER',business_path:'direct',organization_code:org,internal_contact_code:person,category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'90',participant_count:'1',budget_amount:'0',objectives:'SYNTHETIC',content:'SYNTHETIC',teacher_req:'',remark:'SYNTHETIC'},actor)).workflow;
 d=(await api('/demands/submit',{id:d.id,expected_version:d.version,request_id:tag+'-SUBMIT'},actor)).workflow;
 for(const approver of['leader','bp'])d=(await api('/approvals/tasks/'+d.id+'/actions',{action:'APPROVE',expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId:tag+'-'+approver,comment:'SYNTHETIC'},approver)).workflow;
 return(await api('/demands/accept',{id:d.id,expected_version:d.version,request_id:tag+'-ACCEPT',team_code:'900'},'team')).workflow.project_id;
}
function capabilities(view,editable,label){check(view.capabilities.read===true&&view.capabilities.edit===editable&&view.capabilities.submit===false&&view.capabilities.review===false&&view.capabilities.export===false,label+' exposes only permitted operations for this unconfigured review fixture');check(view.draft_only===false&&view.synthetic===false&&view.photo_policy==='PROJECT_UPLOADS_VERSIONED',label+' exposes the real formal workflow and versioned project-photo contract');check(view.workflow.status==='DRAFT'&&view.workflow.policy_configured===false&&view.workflow.workflow_version===0,label+' retains an unsubmitted draft with no implicit review policy');}
function m08(tables){return Object.fromEntries(Object.entries(tables).filter(([key])=>key.startsWith('M08_')));}
async function relogin(){for(const actor of['worker','peer','reader','writeonly','outsider','team','unbound'])await login(actor);}
async function runChecks(){
 await boot(true);tokens.expired=fs.readFileSync(path.join(own,'expired-token.txt'),'utf8');
 await denied(current(1),undefined,'expired',401,'A genuinely issued but expired Auth token is rejected');await denied(current(1),undefined,'anonymous',401,'Summary reads require real login');
 const initialCounts=await api('/organization/config',undefined,'admin');same(initialCounts,{version:null,configuration:null},'Startup seeds no organization authorization');
 const workerId=await identities();
 const p=await acceptedProject('admin','001','P1','SYNTHETIC SUMMARY MAIN'),other=await acceptedProject('filler','999','P10','SYNTHETIC SUMMARY OTHER');
 const archived=await acceptedProject('admin','001','P1','SYNTHETIC SUMMARY ARCHIVE'),damaged=await acceptedProject('admin','001','P1','SYNTHETIC SUMMARY DAMAGED'),drift=await acceptedProject('admin','001','P1','SYNTHETIC SUMMARY DRIFT');
 const rows=await api('/projects',undefined,'team'),untrusted=rows.filter(row=>row.title.includes('SOURCE')||row.title==='SYNTHETIC LEGACY SUMMARY PROJECT');
 phase='true identity and source scope';
 const fresh=await api(current(p));check(fresh.version===0&&fresh.revision===0&&fresh.latest_version===0&&fresh.current===null,'Uncreated summary has version zero and no implicit persisted content');capabilities(fresh,true,'Explicit editor viewer');
 check(fresh.capabilities.refresh_sources===false,'Sources cannot be refreshed before a draft exists');
 const source=fresh.sources;check(source.project.value.project_id===p&&source.project.value.project_code===null&&source.project.value.course_codes===null,'Source keeps technical project id without invented formal codes');
 same(source.codes,{project:'UNASSIGNED',courses:'UNASSIGNED'},'Unassigned codes remain explicit');check(source.project.value.start_date===null&&source.project.value.end_date===null&&source.project.unavailable_fields.includes('start_date'),'Unavailable dates are not filled with today');
 same(source.feedback,{status:'UNAVAILABLE',reason:'SURVEY_READ_REQUIRED',value:null},'Ungranted formal M07 feedback exposes only the explicit hidden-source contract');check(source.delivery.reason==='DELIVERY_READ_REQUIRED'&&source.delivery.value===null&&source.source_version===null&&source.visibility==='DELIVERY_AND_FEEDBACK_HIDDEN','Ungranted M05 and M07 values and combined source digest remain hidden');
 check(!JSON.stringify(source).includes('SYNTHETIC CUSTOMER')&&!JSON.stringify(source).includes('SYNTHETIC SUMMARY MAIN'),'Frozen source omits customer and project free-text titles');
 const limitedProject=(await api('/projects',undefined,'worker')).find(row=>row.id===p);same(Object.keys(limitedProject||{}).sort(),['delivery_controlled','id','read_access','status','title'],'Summary-only project selector exposes exactly the limited project whitelist');same(limitedProject?.read_access,{limited:true,delivery:false,summary:true,feedback:false},'Project selector preserves independent module permissions');check(limitedProject?.delivery_controlled===true&&limitedProject.id===p,'Limited selector uses the verified managed project');same(await api('/demands',undefined,'worker'),[],'Summary-only viewer gains no demand browsing permission');
 capabilities(await api(current(p),undefined,'reader'),false,'Read-only summary viewer');
 for(const actor of['admin','team','writeonly','unbound'])await denied(current(p),undefined,actor,403,actor+' has no implicit summary.read grant');
 for(const path of[current(p),history(p),revision(p,1)])await denied(path,undefined,'outsider',403,'Other institution cannot read current or historical summary');
 await denied(current(other),undefined,'worker',403,'Explicit editor cannot cross institution');check((await api(current(other),undefined,'outsider')).version===0,'Other institution can view its own trusted project');
 for(const row of untrusted)await denied(current(row.id),undefined,'worker',403,'Untrusted legacy or stale source is rejected: '+row.title);
 await denied(base+'/refresh',command(p,0,'REFRESH-EMPTY'),'worker',409,'Refresh requires an existing saved draft');
 await denied(revision(p,1),undefined,'worker',404,'Missing historical revision is not fabricated');
 phase='strict JSON and media boundaries';
 const full={achievements:'SYNTHETIC 成效\n第二段🙂',issues:'SYNTHETIC 问题',nextSteps:'SYNTHETIC 下一步',publicity:{title:'SYNTHETIC 培训总结',introduction:'SYNTHETIC 引言',sections:[{heading:'学习与实践',body:'SYNTHETIC 正文\n保留换行'}],photoCaptions:['课堂授课','互动研讨']}};
 const first=save(p,0,'CREATE-ONE',full);
 for(const actor of['reader','writeonly','admin','outsider'])await denied(base+'/save',first,actor,403,actor+' cannot substitute read/edit/old role for both required permissions');
 await denied(base+'/save',first,'anonymous',401,'Unauthenticated write is rejected');
 for(const path of[current(p)+'&project_id='+p,current(p)+'&actor=P1',history(p)+'&limit=0',history(p)+'&limit=101',history(p)+'&offset=-1',revision(p,0),base+'?project_id=1.5'])await denied(path,undefined,'worker',400,'Strict query whitelist, duplicates and bounds are enforced');
 await denied(current(p),undefined,'worker',405,'HEAD cannot bypass explicit read method',{method:'HEAD'});await denied(base+'/save',undefined,'worker',405,'GET cannot save');await denied(base+'/save?project_id='+p,first,'worker',400,'POST query injection is rejected');
 await denied(base+'/save',first,'worker',415,'Summary writes require JSON',{headers:{'Content-Type':'text/plain'}});
 for(const raw of['{','[]','{"project_id":1,"project_id":1}'])await denied(base+'/save',raw,'worker',400,'Malformed or duplicate-key JSON is rejected',{raw:true});
 for(const key of['organization','actor','permissions','sources','feedback','synthetic','status'])await denied(base+'/save',{...first,[key]:true},'worker',400,'Client cannot forge '+key);
 for(const expected_version of['0',null,-1,0.5,true,9007199254740992])await denied(base+'/save',{...first,expected_version},'worker',400,'Version must be a nonnegative safe JSON integer');
 await denied(base+'/save',{...first,project_id:String(p)},'worker',400,'Project id must be a JSON number');await denied(base+'/save',{...first,request_id:' bad key '},'worker',400,'Request identity has a strict safe format');
 for(const content of[{...full,score:9},{publicity:{...full.publicity,mediaUrl:'https://example.invalid/p.jpg'}},{publicity:{sections:[{heading:'x',body:'y',actor:'P1'}]}},{publicity:{photoCaptions:['https://example.invalid/p.jpg']}},{publicity:{photoCaptions:['/tmp/private.png']}},{publicity:{photoCaptions:['data:image/png;base64,AAAA']}},{publicity:{photoCaptions:['a'.repeat(301)]}},{achievements:'a'.repeat(12001)},{achievements:'\u0001'},{publicity:{sections:Array.from({length:13},()=>({heading:'x',body:'y'}))}}])await denied(base+'/save',{...first,content},'worker',400,'Content rejects unknown fields, media paths and invalid lengths');
 for(const op of['submit','review','export']){await denied(base+'/'+op,{},'worker',400,'Formal '+op+' rejects an incomplete workflow command');await denied(base+'/'+op,{},'anonymous',401,'Formal '+op+' authenticates before validating its command');}
 await denied(base+'/delete',{},'worker',404,'No summary deletion route exists');
 const json=JSON.stringify(first),exact=json+' '.repeat(1024*1024-Buffer.byteLength(json));
 await denied(base+'/save',exact+' ','worker',413,'Summary body above ordinary 1 MiB host limit is rejected',{raw:true});
 const createdResponse=await request(base+'/save',exact,'worker',{raw:true});check(createdResponse.status===200&&createdResponse.envelope.code===0,'Exactly 1 MiB JSON is accepted without M07 larger-upload semantics');
 let saved=createdResponse.envelope.data;check(saved.version===1&&saved.revision===1&&saved.latest_version===1&&!saved.replayed,'First save creates a single immutable draft revision');same(saved.current.content,full,'Complete multilingual text and captions persist without silent rewriting');
 const frozen=await api(revision(p,1));check(frozen.read_only===true&&frozen.status==='DRAFT'&&frozen.actor_code==='P6','Historical content is immutable and tied to real bound editor');
 same(frozen.photos,[],'Text captions do not fabricate uploaded photo references');
 for(const[op,status]of[['submit',409],['review',403],['export',403]]){const body={...command(p,1,'FORMAL-DENIED-'+op),expected_workflow_version:0,...(op==='review'?{review_role:'BRANCH',decision:'APPROVE',note:'SYNTHETIC'}:{})};await denied(base+'/'+op,body,'worker',status,'A complete '+op+' command cannot bypass missing review configuration or explicit authority');}same(await api(current(p)),saved,'Rejected formal operations change neither saved content nor workflow state');
 phase='full replacement and explicit sources';
 const project=(await api('/projects',undefined,'team')).find(row=>row.id===p);await api('/projects',{...project,start_date:'2025-01-01',end_date:'2025-01-31',participant_count:7},'team');
 let view=await api(current(p));check(view.source_changed===true,'Changed business dates or participant count are detected');same(view.sources,frozen.sources,'A read never refreshes the frozen source automatically');
 saved=await api(base+'/save',save(p,1,'REPLACE-TWO',{achievements:'SYNTHETIC REPLACEMENT'}));same(saved.current.content,{achievements:'SYNTHETIC REPLACEMENT',issues:'',nextSteps:''},'Save is full replacement: omitted text empties and publicity disappears');same(saved.sources,frozen.sources,'Ordinary save retains prior sources even after business changes');check(saved.source_changed,'Source change remains explicit after content save');
 const refreshed=await api(base+'/refresh',command(p,2,'REFRESH-THREE'));check(refreshed.version===3&&!refreshed.source_changed&&refreshed.current.operation==='REFRESH_SOURCES','Explicit refresh appends a new source revision');same(refreshed.current.content,saved.current.content,'Refresh copies saved content exactly');check(refreshed.sources.project.value.participant_count===7&&refreshed.sources.project.value.start_date==='2025-01-01','Refresh uses current server-owned facts');same(await api(revision(p,1)),frozen,'All previous content and source remain unchanged after refresh');
 phase='concurrent version and idempotency';
 const contest=await Promise.all([request(base+'/save',save(p,3,'RACE-A',{achievements:'SYNTHETIC A'})),request(base+'/save',save(p,3,'RACE-B',{achievements:'SYNTHETIC B'}))]);same(contest.map(r=>r.status).sort(),[200,409],'Two writes for the same version have one winner');check((await api(current(p))).version===4,'A rejected concurrent writer adds no revision');
 const idem=save(p,4,'RETRY-FIVE',{achievements:'SYNTHETIC IDEMPOTENT'}),retry=await Promise.all([api(base+'/save',idem),api(base+'/save',idem)]);check(retry.every(r=>r.version===5)&&retry.filter(r=>r.replayed).length===1,'Concurrent same-key retries append only one revision');
 await denied(base+'/save',{...idem,content:{achievements:'SYNTHETIC DIFFERENT'}},'worker',409,'Same request key cannot carry new content');await denied(base+'/refresh',command(p,4,'RETRY-FIVE'),'worker',409,'Same request key cannot change operation');
 const peer=await api(base+'/save',save(p,5,'CREATE-ONE',{achievements:'SYNTHETIC PEER'}),'peer');check(peer.version===6&&peer.current.actor_code==='P7','Different accounts may independently use the same request id');
 const replay=await api(base+'/save',first);check(replay.version===1&&replay.latest_version===6&&replay.replayed===true&&replay.reload_required===true,'Old request replay returns original result with current head and reload requirement');same(replay.current.content,full,'Replay never substitutes latest content for original operation');
 const head=await api(current(p));check(head.version===6&&head.current.content.achievements==='SYNTHETIC PEER','Current head remains the latest saved text after old replay');
 const page=await api(history(p)+'&offset=1&limit=2');same(page.items.map(r=>r.revision),[5,4],'History pages remain descending and precise');check(page.total===6&&page.items.every(r=>r.status==='DRAFT'&&!('content'in r)&&!('account_id'in r)),'History list returns metadata only');
 await denied(revision(p,99),undefined,'worker',404,'Missing future historical version is rejected');
 const archiveRequest=save(archived,0,'ARCHIVE-ONE',{achievements:'SYNTHETIC ARCHIVE BODY'});await api(base+'/save',archiveRequest);const archivedRevision=await api(revision(archived,1));await api(base+'/save',save(damaged,0,'DAMAGED-ONE',{achievements:'SYNTHETIC INTACT'}));await api(base+'/save',save(drift,0,'DRIFT-ONE',{achievements:'SYNTHETIC FIXED ORGANIZATION'}));
 phase='legacy reference protections and restart';
 await denied('/projects/delete',{id:p},'team',409,'Referenced project cannot delete its summary history');await denied('/projects',{...project,demand_id:(await api('/projects',undefined,'team')).find(r=>r.id===other).demand_id},'team',409,'Legacy project update cannot rebind summary ownership');
 const beforeArchive=await api(current(p)),archiveDenied=await denied('/projects/archive',{id:p},'team',400,'Summary draft does not bypass current project delivery prerequisites');check(archiveDenied.envelope?.msg.includes('项目尚未完成交付')&&archiveDenied.envelope.msg.includes('尚未安排任何课程')&&archiveDenied.envelope.msg.includes('已核对实际课时不足'),'Archive rejection retains each actual incomplete-delivery blocker');same(await api(current(p)),beforeArchive,'Rejected archive preserves project status and summary state');
 await stop();const persisted=await offline('inspect');check(persisted.M08_SUMMARY_HEADS.count===4&&persisted.M08_SUMMARY_REVISIONS.count===9&&persisted.M08_SUMMARY_REQUESTS.count===9,'Only successful drafts, revisions and idempotency receipts persisted');
 await boot();await relogin();same(await api(current(p)),head,'Real Main restart preserves exact current content, sources and capabilities');same(await api(revision(p,1)),frozen,'Real Main restart preserves original revision exactly');
 const afterRestart=await api(base+'/save',first);check(afterRestart.replayed&&afterRestart.version===1&&afterRestart.latest_version===6,'Persistent idempotency survives a real process restart');
 phase='revocation, rebinding and account state';
 const grants=structuredClone(configuration.grants),bindings=structuredClone(configuration.accountBindings);
 await publish({grants:grants.filter(g=>!(g.roleCode==='EDITOR'&&g.resource==='summary.edit'))},'read-only');capabilities(await api(current(p)),false,'Live edit revocation');await denied(base+'/save',first,'worker',403,'Revoked edit grant blocks historical replay');
 await publish({grants:grants.filter(g=>!(g.roleCode==='EDITOR'&&g.resource==='summary.read'))},'no-read');await denied(current(p),undefined,'worker',403,'Revoked read grant blocks current content');await denied(revision(p,1),undefined,'worker',403,'Revoked read grant blocks stored history');await denied(base+'/save',first,'worker',403,'Edit alone cannot recover old result');
 await publish({grants},'restore');await publish({accountBindings:bindings.map(b=>b.accountId===workerId?{...b,personCode:'P11'}:b)},'rebound',['worker']);await denied(base+'/save',first,'worker',409,'Same account rebound to a new person cannot replay original actor receipt');
 await publish({accountBindings:bindings},'binding-restored',['worker']);check((await api(base+'/save',first)).version===1,'Original binding can recover its own original receipt');
 const user=(await api('/users',undefined,'admin')).find(u=>u.id===workerId);await api('/users',{...user,status:0},'admin');await denied(current(p),undefined,'worker',401,'Disabled account loses existing session immediately');await denied(base+'/save',first,'worker',401,'Disabled account cannot use cached receipt');await api('/users',{...user,status:1},'admin');await denied(current(p),undefined,'worker',401,'Re-enabling account does not resurrect revoked session');await login('worker');check((await api(current(p))).version===6,'Fresh login after re-enable sees intact latest draft');
 await stop();const protectedCounts=await offline('inspect');same(m08(protectedCounts),m08(persisted),'Authorization failures and old replays never modify the stored summary history');
 await offline('historical-states');await boot();await relogin();const beforeDenied=await api(revision(archived,1));same(beforeDenied,archivedRevision,'Historical archive preserves prior body and source');
 phase='archived and damaged history';
 const archivedView=await api(current(archived));capabilities(archivedView,false,'Historically archived project');check(archivedView.version===1&&archivedView.project_status==='已归档','Archived draft remains readable');
 for(const[op,b]of[['save',archiveRequest],['save',save(archived,1,'ARCHIVE-NEW',{achievements:'SYNTHETIC CHANGE'})],['refresh',command(archived,1,'ARCHIVE-REFRESH')]])await denied(base+'/'+op,b,'worker',409,'Archived status blocks '+op+' including prior idempotency replay');
 const ar=(await api('/projects',undefined,'team')).find(r=>r.id===archived);await denied('/projects',{...ar,status:'进行中'},'team',409,'Legacy update cannot unarchive a summary');await denied('/projects',{...ar,remark:'SYNTHETIC CHANGE'},'team',409,'Legacy archived metadata cannot silently unlock history');await denied('/projects/delete',{id:archived},'team',409,'Archived history cannot be deleted through old project CRUD');
 for(const route of[current(damaged),history(damaged),revision(damaged,1)])await denied(route,undefined,'worker',409,'Tampered historical content fails closed on every read form');await denied(base+'/save',save(damaged,1,'DAMAGED-APPEND',{achievements:'SYNTHETIC'}),'worker',409,'Cannot append to a damaged chain');
 await denied(current(drift),undefined,'worker',403,'Changed source institution re-evaluates current scope');await denied(current(drift),undefined,'outsider',409,'A newly authorized source institution cannot inherit the old summary head');
 const intact=await api(current(p));check(intact.version===6,'Faults in another project never alter an intact draft');await api('/logout',{},'worker');await denied(current(p),undefined,'worker',401,'Logged-out token cannot retrieve saved body');
 return{main_project_id:p,current_version:6,persisted_heads:4,persisted_revisions:9,persisted_requests:9,inspected_tables:Object.keys(persisted).length,archive_boundary:'Archived read-only is a dedicated offline synthetic historical state; current real archive remains blocked by M05'};
}
let evidence;runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{await stop();const summary={suite:'summaries-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;});
