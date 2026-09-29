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
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationSurveyHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-survey-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration, serial = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.survey.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
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
  check(!r.text.includes('SYNTHETIC_PRIVATE'), label + ' error does not echo upload contents');
  return r;
}
async function login(actor, username = 'synthetic-survey-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationSurveyHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated survey service did not become ready');
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
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationSurveyHttpFixture', operation], {cwd: repo, env, timeout: 20000, maxBuffer: 65536});
  return operation === 'inspect' ? JSON.parse(result.stdout.trim()) : null;
}
const configRoute = '/survey-response-imports/config', previewRoute = '/survey-response-imports/preview';
// Configuration publication is an explicit authentication boundary, never an API retry policy.
async function renewPublishedSessions(label, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'worker', 'peer', 'readonly', 'empty', 'filler']) {
  for (const actor of actors) {
    if (!tokens[actor]) continue; // Some identities have not logged in since a process restart.
    await denied('/me', undefined, actor, 401, label + ' invalidates the old ' + actor + ' token');
    await login(actor, actor === 'admin' ? 'admin' : undefined);
  }
}
async function publish(changes, suffix) {
  const expectedVersion = configuration.version;
  configuration = {...structuredClone(configuration), ...changes, version: 'synthetic-survey-http-' + suffix};
  await api('/organization/config', {expectedVersion, configuration}, 'admin');
}
async function identities() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  const accounts = [['leader','viewer'],['bp','viewer'],['team','manager'],['outsider','viewer'],['worker','viewer'],['peer','viewer'],['readonly','viewer'],['empty','viewer'],['filler','manager'],['unbound','manager']];
  for (const [actor, role] of accounts) {
    ids.push(await api('/users', {username:'synthetic-survey-'+actor,name:'SYNTHETIC '+actor,role,status:1,password},'admin')); await login(actor);
  }
  configuration=workflowConfiguration(ids.slice(0,5));configuration.version='synthetic-survey-http-v1';
  for(const person of configuration.people)if(['P2','P3','P4'].includes(person.personCode))person.responsibleOrganizationCodes.push('999');
  for(const grant of configuration.grants)if(grant.scope==='NAMED_ORGS'&&grant.organizationCodes.includes('001'))grant.organizationCodes.push('999');
  const optional={required:false,allowedTargetRoles:['SURVEY','OBSERVER'],targetMustCoverOrganization:false,allowSelf:false};
  for(const [personCode,role,organizationCode,index] of [['P6','SURVEY','001',5],['P7','SURVEY','001',6],['P8','OBSERVER','001',7],['P9','SURVEY','002',8],['P10','FILLER','999',9]]){
    configuration.people.push({personCode,roleCodes:[role],organizationCode,responsibleOrganizationCodes:[],leaderPersonCode:role==='FILLER'?'P2':null,bpPersonCode:role==='FILLER'?'P3':null,enabled:true});
    configuration.accountBindings.push({accountId:ids[index],personCode,enabled:true});
  }
  for(const roleCode of ['SURVEY','OBSERVER']){configuration.roleCodes.push(roleCode);configuration.relations.push({roleCode,leader:optional,bp:optional});}
  for(const [roleCode,resource,action] of [['SURVEY','survey.preview','HANDLE'],['OUT','survey.preview','HANDLE'],['OBSERVER','survey.preview','VIEW'],['OBSERVER','demand.read','VIEW']])
    configuration.grants.push({ruleId:roleCode+'-'+resource,roleCode,resource,action,effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]});
  await api('/organization/config',{expectedVersion:null,configuration},'admin');
  await renewPublishedSessions('Initial binding');
}
async function acceptedProject(actor,org,person){
  const tag='SURVEY-'+(++serial);
  let d=(await api('/demands/draft',{request_id:tag+'-DRAFT',title:'SYNTHETIC SURVEY PROJECT '+org,unit:'SYNTHETIC CUSTOMER',business_path:'direct',organization_code:org,internal_contact_code:person,category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'90',participant_count:'1',budget_amount:'0',objectives:'SYNTHETIC',content:'SYNTHETIC',teacher_req:'',remark:'SYNTHETIC'},actor)).workflow;
  d=(await api('/demands/submit',{id:d.id,expected_version:d.version,request_id:tag+'-SUBMIT'},actor)).workflow;
  for(const approver of ['leader','bp'])d=(await api('/approvals/tasks/'+d.id+'/actions',{action:'APPROVE',expectedVersion:d.approval.version,expectedStage:d.approval.stage,requestId:tag+'-'+approver,comment:'SYNTHETIC'},approver)).workflow;
  return (await api('/demands/accept',{id:d.id,expected_version:d.version,request_id:tag+'-ACCEPT',team_code:'900'},'team')).workflow.project_id;
}
function body(projectId,bytes=fs.readFileSync(path.join(own,'clean.xlsx'))){return{fileName:'SYNTHETIC_PRIVATE_FILE.xlsx',xlsxBase64:bytes.toString('base64'),projectId};}
function privacy(value,label){const raw=JSON.stringify(value);check(!raw.includes('SYNTHETIC_PRIVATE')&&!raw.includes('fileFingerprint')&&!raw.includes('scoreFingerprint')&&!raw.includes('xlsxBase64'),label+' returns no personal markers, filename, fingerprints or upload content');}
function draft(value,label){check(value.mode==='RESPONSE_SUMMARY_PREVIEW'&&value.state==='PREVIEW',label+' generates the real draft preview');check(value.canCommit===false&&value.policyConfirmed===false&&value.historyAvailable===false&&value.synthetic===false&&value.rulesDraft.confirmed===false,label+' cannot claim commitment, confirmed rules, history or demo mode');same(value.duplicateCheck.status,'NOT_CHECKED',label+' does not invent duplicate history');privacy(value,label);}
async function rawUpload(actor,text,totalLength=text.length){
  const socket=net.createConnection({host:'127.0.0.1',port}),chunks=[];let closedAt=null,error=null;const started=Date.now();
  await new Promise((resolve,reject)=>{socket.once('connect',resolve);socket.once('error',reject);});
  socket.on('data',chunk=>{chunks.push(chunk);});socket.on('error',e=>{error=e.code||'SOCKET_ERROR';});socket.on('close',()=>{closedAt=Date.now()-started;});
  socket.write(`POST /api${previewRoute} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nX-Token: ${tokens[actor]}\r\nContent-Type: application/json\r\nContent-Length: ${totalLength}\r\nConnection: close\r\n\r\n${text}`);
  return{socket,started,get response(){return Buffer.concat(chunks).toString('utf8');},get responseBytes(){return Buffer.concat(chunks);},get closedAt(){return closedAt;},get error(){return error;}};
}
async function completeRawResponse(upload,millis){
  const until=Date.now()+millis;
  while(Date.now()<until){
    const bytes=upload.responseBytes,split=bytes.indexOf('\r\n\r\n');
    if(split>=0){
      const headers=bytes.subarray(0,split).toString('ascii'),status=/^HTTP\/1\.1 (\d{3}) /.exec(headers),length=/\r\nContent-Length:\s*(\d+)(?:\r\n|$)/i.exec(headers);
      assert.ok(status&&length,'Early rejection must provide an HTTP status and Content-Length');
      const expected=Number(length[1]);assert.ok(Number.isSafeInteger(expected),'Early rejection length must be a safe integer');
      if(bytes.length>=split+4+expected){
        assert.equal(bytes.length,split+4+expected,'Early rejection response body must exactly match Content-Length');
        const text=bytes.subarray(split+4).toString('utf8');return{status:Number(status[1]),text,envelope:JSON.parse(text)};
      }
    }
    if(upload.closedAt!==null)break;
    await pause(10);
  }
  throw new Error('Early rejection did not return a complete HTTP response: '+(upload.error||'incomplete response'));
}
async function closeWithin(upload,millis){const until=Date.now()+millis;while(upload.closedAt===null&&Date.now()<until)await pause(100);return upload.closedAt;}
async function runChecks(){
  await boot(true);check(fs.statSync(path.join(own,'maximum.xlsx')).size===5*1024*1024,'Synthetic exact 5 MiB boundary file was generated locally');
  await denied(configRoute,undefined,'anonymous',401,'Configuration requires a real authenticated session');
  await denied(configRoute,undefined,'admin',403,'Unconfigured administrator gets no implicit preview grant');
  await identities();const ownProject=await acceptedProject('admin','001','P1'),otherProject=await acceptedProject('filler','999','P10');
  const historical=(await api('/projects',undefined,'team')).filter(p=>p.title.startsWith('SYNTHETIC ')&&!p.title.startsWith('SYNTHETIC SURVEY PROJECT '));
  const payload=body(ownProject);await stop();const before=await offline('inspect');const fileNames=fs.readdirSync(own).sort();
  await boot();for(const actor of ['worker','peer','readonly','outsider','empty','team','unbound'])await login(actor);
  phase='real session and trusted scope';
  const cfg=await api(configRoute);check(cfg.ready===true&&cfg.maxBytes===5*1024*1024,'Real config advertises the decoded file limit');
  same(cfg.projects.map(p=>p.id),[ownProject],'Dedicated viewer preview role sees only its unique trusted accepted project');
  check(cfg.projects[0].key===String(ownProject)&&cfg.canCommit===false&&cfg.policyConfirmed===false&&cfg.historyAvailable===false&&cfg.synthetic===false,'Config selection key is technical and all formal flags stay closed');privacy(cfg,'Configuration');
  const empty=await api(configRoute,undefined,'empty');check(empty.ready===true&&empty.projects.length===0,'Explicit preview role with no accepted projects gets an honest empty catalog');
  same((await api(configRoute,undefined,'outsider')).projects.map(p=>p.id),[otherProject],'Other institution gets its own isolated project catalog');
  check(!(await api('/projects',undefined,'worker')).some(p=>[ownProject,otherProject].includes(p.id)),'Preview viewer does not acquire demand/project browsing permission');
  for(const actor of ['admin','team','readonly','unbound']){await denied(configRoute,undefined,actor,403,actor+' cannot replace HANDLE grant with old role or VIEW grant');await denied(previewRoute,'SYNTHETIC_PRIVATE_BAD_JSON',actor,403,actor+' is rejected before upload parsing',{raw:true});}
  await denied(previewRoute,'SYNTHETIC_PRIVATE_BAD_JSON','anonymous',401,'Unauthenticated upload is rejected before parsing',{raw:true});
  await denied(previewRoute,{...payload,projectId:otherProject,xlsxBase64:'!!'},'worker',403,'Unauthorized project is rejected before Base64 decoding');
  await denied(previewRoute,{...payload,projectId:ownProject},'outsider',403,'Other institution cannot preview local project results');
  for(const project of historical)await denied(previewRoute,{...payload,projectId:project.id,xlsxBase64:'!!'},'worker',403,'Unmigrated, mismatched, stale or draft source is not trusted: '+project.title);
  phase='strict protocol';
  for(const [route,body,method] of [[configRoute,{},'POST'],[configRoute,undefined,'HEAD'],[previewRoute,undefined,'GET'],[previewRoute,undefined,'PUT']])await denied(route,body,'worker',405,'Known route rejects '+method,{method});
  await denied(configRoute+'?projectId='+ownProject,undefined,'worker',400,'Config rejects user-supplied scope query');
  await denied(previewRoute+'?organization=001',payload,'worker',400,'Preview rejects query injection');
  await denied('/survey-response-imports/save',payload,'worker',404,'No persistence route exists');
  await denied(previewRoute,payload,'worker',415,'Upload requires JSON media type',{headers:{'Content-Type':'text/plain'}});
  await denied(previewRoute,payload,'worker',415,'Compressed request body is not accepted',{headers:{'Content-Encoding':'gzip'}});
  for(const raw of ['{','[]','{"fileName":{},"xlsxBase64":"AAAA","projectId":1}','{"fileName":"a.xlsx","xlsxBase64":[],"projectId":1}','{"fileName":"a.xlsx","fileName":"b.xlsx","xlsxBase64":"AAAA"}'])await denied(previewRoute,raw,'worker',400,'Malformed or nonflat upload is rejected',{raw:true});
  await denied(previewRoute,Buffer.from([0x7b,0x22,0xff,0x22,0x7d]),'worker',400,'Invalid UTF-8 bytes are rejected',{raw:true});
  for(const field of ['policy','history','permission','rules','organizationCode','actor','synthetic','policyConfirmed','canCommit','historyAvailable'])await denied(previewRoute,{...payload,[field]:true},'worker',400,'Client cannot inject '+field);
  for(const projectId of [String(ownProject),null,0,-1,1.5,true,9007199254740992])await denied(previewRoute,{...payload,projectId},'worker',400,'Project id must be a positive safe JSON integer');
  await denied(previewRoute,{projectId:ownProject,xlsxBase64:payload.xlsxBase64},'worker',400,'All three scalar fields are required');
  for(const xlsxBase64 of ['', 'AAAA\n','A===','@@@@','a'])await denied(previewRoute,{...payload,xlsxBase64},'worker',400,'Malformed Base64 is rejected');
  for(const fileName of ['SYNTHETIC_PRIVATE.xls','bad\n.xlsx','a'.repeat(256)+'.xlsx'])await denied(previewRoute,{...payload,fileName},'worker',400,'Filename is format-checked without becoming an identifier');
  phase='draft statistics and privacy';
  const normal=await request(previewRoute,payload);check(normal.status===200&&normal.envelope.code===0,'Host flushes independent preview response successfully');check(normal.headers.get('cache-control')==='no-store','Preview responses are never cacheable');
  const result=normal.envelope.data;draft(result,'Synthetic workbook');
  check(result.responseRowCount===4&&result.questions.length===10,'Four synthetic records produce ten question summaries');
  const first=result.questions[0];same({valid:first.validCount,blank:first.blankCount,invalid:first.invalidCount,sum:first.sumText,average:first.averageText},{valid:2,blank:1,invalid:1,sum:'10',average:'5.00'},'Question one preserves zero, independent denominator, blank and invalid counts');
  check(result.questions.slice(1).every(q=>q.validCount===4&&q.blankCount===0&&q.invalidCount===0&&q.sumText==='32'&&q.averageText==='8.00'),'Other nine questions each retain their own valid denominator');
  check(result.issues.some(i=>i.code==='INVALID_SCORE')&&result.overallQuestionKey==='q10','Invalid cell is generically explained and overall satisfaction remains question ten');
  same(await api(previewRoute,payload),result,'Repeating same file does not create a history entry or claim duplicate detection');
  for(const bytes of [Buffer.from('SYNTHETIC_PRIVATE_NOT_XLSX'),...['traversal.xlsx','external.xlsx','expanded-limit.xlsx'].map(name=>fs.readFileSync(path.join(own,name)))]){
    const invalid=await api(previewRoute,body(ownProject,bytes));check(invalid.state==='ERROR'&&invalid.questions.length===0&&invalid.canCommit===false,'Invalid, traversal, external or excessive ZIP produces only generic file error');privacy(invalid,'Bad workbook result');
  }
  phase='independent request and decoded limits';
  const maximum=body(ownProject,fs.readFileSync(path.join(own,'maximum.xlsx')));check(Buffer.byteLength(JSON.stringify(maximum))>1024*1024,'Valid synthetic upload really exceeds the old host body limit');
  draft(await api(previewRoute,maximum),'Exact 5 MiB XLSX');
  const tooLarge=Buffer.concat([fs.readFileSync(path.join(own,'maximum.xlsx')),Buffer.alloc(1)]);await denied(previewRoute,body(ownProject,tooLarge),'worker',413,'Decoded 5 MiB plus one byte is rejected even at legal Base64 character length');
  await denied(previewRoute,{...payload,xlsxBase64:'A'.repeat(6990508+4)},'worker',413,'Base64 text above its separate limit is rejected');
  const json=JSON.stringify(payload),exact=json+' '.repeat(7*1024*1024-Buffer.byteLength(json));
  const boundary=await request(previewRoute,exact,'worker',{raw:true});check(boundary.status===200&&boundary.envelope.data.state==='PREVIEW','Exactly 7 MiB JSON body is accepted only at the preview route');
  // The declared length is rejected before the body is consumed. A short prefix avoids racing
  // a complete 7 MiB upload against the server closing an already rejected request.
  const oversized=await rawUpload('worker',json,Buffer.byteLength(exact)+1);
  try{
    const rejection=await completeRawResponse(oversized,3000);
    check(rejection.status===413&&rejection.envelope?.code===413,'Preview declared body above 7 MiB is rejected before the remaining body is sent');
    check(!rejection.text.includes('SYNTHETIC_PRIVATE'),'Oversized preview rejection does not echo upload contents');
  }finally{oversized.socket.destroy();}
  await denied('/users',' '.repeat(1024*1024+1),'admin',413,'Other host routes retain the original 1 MiB request limit',{raw:true});
  phase='real slow upload under Main defaults';
  const slow=await rawUpload('worker','{',1000);let slowMillis;
  try{
    await pause(300);const begin=Date.now();await api('/me',undefined,'admin');check(Date.now()-begin<2000,'Blocked upload holds no global business lock');
    await denied(previewRoute,payload,'worker',429,'Same account cannot start a second active upload');
    draft(await api(previewRoute,payload,'peer'),'Another authorized account while upload is blocked');
    slowMillis=await closeWithin(slow,20500);check(slowMillis!==null&&slowMillis>=10000&&slowMillis<=21000,'Actual unfinished TCP upload closes under Main request-time defaults');
    if(slowMillis===null)slow.socket.destroy();await pause(250);
    const recovered=await request(previewRoute,payload);check(recovered.status===200&&recovered.envelope.data.state==='PREVIEW','Timed-out upload ultimately releases its account and global processing slot');
  }finally{slow.socket.destroy();}
  phase='no persistence and existing pages';
  for(const route of ['/questionnaires','/teacher_evals','/stats/overview','/stats/report'])check((await request(route,undefined,'admin')).status===200,'Existing effects or report API remains callable: '+route);
  await stop();const after=await offline('inspect');same(after,before,'All business tables retain identical counts and contents after previews, malformed uploads and timeout');
  same(fs.readdirSync(own).sort(),fileNames,'Preview adds no original-file copies, extracted files or persistent result artifacts');
  phase='in-flight authorization recheck';
  await boot();for(const actor of ['worker','peer'])await login(actor);const allGrants=structuredClone(configuration.grants);
  const waiting=await rawUpload('worker','{',Buffer.byteLength(json));
  try{await pause(250);await publish({grants:configuration.grants.filter(g=>!(g.roleCode==='SURVEY'&&g.resource==='survey.preview'))},'revoke');waiting.socket.write(json.slice(1));await closeWithin(waiting,5000);check(waiting.response.startsWith('HTTP/1.1 401')&&!waiting.response.includes('RESPONSE_SUMMARY_PREVIEW'),'Revocation during upload discards the result before decoding or response generation');}finally{waiting.socket.destroy();}
  await denied(configRoute,undefined,'worker',401,'In-flight grant revocation also invalidates the old preview session');
  await renewPublishedSessions('Preview grant revoked');
  await denied(configRoute,undefined,'worker',403,'Fresh login still lacks the removed preview grant');
  await publish({grants:allGrants},'restore');await renewPublishedSessions('Preview grant restored');
  const changed=await rawUpload('worker','{',Buffer.byteLength(json));try{await pause(250);await publish({},'version-change');changed.socket.write(json.slice(1));await closeWithin(changed,5000);check(changed.response.startsWith('HTTP/1.1 409')&&!changed.response.includes('RESPONSE_SUMMARY_PREVIEW'),'Configuration version change during upload rejects a stale source snapshot');}finally{changed.socket.destroy();}
  draft(await api(previewRoute,payload),'After explicit authorization refresh');
  await api('/logout',{},'worker');await denied(previewRoute,payload,'worker',401,'Logged-out token cannot reuse old preview capability');
  return{source_project_ids:[ownProject,otherProject],readonly_tables:Object.keys(before).length,slow_upload_closed_ms:slowMillis,slow_upload_boundary:'Main default maxReqTime plus integration watchdog; not a watchdog-only proof',synthetic_workbook:path.join(own,'clean.xlsx'),expected_preview:{response_rows:4,questions:10,q1:{valid:2,blank:1,invalid:1,sum:'10',average:'5.00'},q2_to_q10:{valid:4,sum:'32',average:'8.00'}}};
}
let evidence;
runChecks().then(result=>{evidence=result;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{
  await stop();const summary={suite:'survey-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),production_touched:false,notifications_sent:false,artifacts:own};
  fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;
});
