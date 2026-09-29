'use strict';
// Actual Main/Api HTTP, synthetic identities and fresh H2 only. No app/data, production or notification channel.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process'), {promisify} = require('node:util');
const run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes required');
const repo = process.env.YANXU_INTEGRATION_REPO ? path.resolve(process.env.YANXU_INTEGRATION_REPO) : path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(!classes.startsWith(path.join(repo, 'out')), 'Use isolated compiled classes');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationRecommendationHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-recommendation-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.recommendation.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'manager', options = {}) {
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(options.headers || {})};
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: options.method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : options.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(10000)});
  const text = await response.text(); const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text};
}
async function api(route, body, actor = 'manager') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, options = {}) {
  const r = await request(route, body, actor, options);
  check(r.status === status && (options.method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  return r;
}
async function login(actor, username = 'synthetic-recommendation-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationRecommendationHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated recommendation service did not become ready');
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM');
  });
}
const route='/teacher-recommendations';
const ids=[],actors=['manager','peer','other','viewer'];
const requestBody={requirement:'培训主题：文档编号复核\n培训对象：见习文控员',training_mode:'线上',max_results:3};
let scope,otherScope,catalogVersion=0;
const selected=(changes={})=>({...requestBody,standard_course:{scope_id:scope,expected_version:catalogVersion,course_code:'001',as_of:'2026-09-22',accepted_levels:['讲师'],allowed_cities:['杭州'],...changes}});
const jobPath=id=>route+'/jobs?id='+encodeURIComponent(id);
async function complete(body=selected(),actor='manager'){
 const submitted=await api(route+'/jobs',body,actor);check(submitted.job_id&&['queued','running','completed'].includes(submitted.status),'Original jobs POST returns bounded existing job protocol');
 for(let n=0;n<150;n++){const state=await api(jobPath(submitted.job_id),undefined,actor);if(state.terminal)return state;await pause(20);}throw new Error('Synthetic recommendation did not terminate');
}
function catalog(version){
 return {schema_version:'m04_catalog_v1',catalog_version:'SYNTHETIC-HTTP-'+version,courses:[{course_code:'001',course_name:'文档编号复核',active:true},{course_code:'002',course_name:'无认证课程',active:true},{course_code:'003',course_name:'停用课程',active:false}],
 teachers:ids.slice(0,7).map((_,i)=>({teacher_code:String(i+1).padStart(4,'0'),teacher_level:'讲师',city:'杭州'})),
 certifications:ids.slice(1,7).map((_,i)=>({teacher_code:String(i+2).padStart(4,'0'),course_code:'001',status:i===0?'unknown':i===1?'revoked':'certified',source_ref:i<2?'':'SYNTHETIC-EVIDENCE-'+i,valid_from:'2026-01-01',valid_to:'2026-12-31'}))};
}
async function publishCatalog(){const body={expected_version:catalogVersion,change_comment:'SYNTHETIC explicit qualification fixture',catalog:catalog(catalogVersion+1),bindings:ids.slice(0,7).map((teacher_id,i)=>({teacher_id,teacher_code:String(i+1).padStart(4,'0')}))};const p=await api('/course-catalog/scopes/'+scope+'/preview',body);check(p.ready===true&&p.confirmation_required===true,'Actual catalog preview awaits explicit confirmation');await api('/course-catalog/scopes/'+scope+'/confirm',{batch_id:p.batch_id,expected_version:catalogVersion,confirm:true});catalogVersion++;}
async function courseEmpty(changes,status,actor='manager'){
 const value=await api(route,selected(changes),actor);check(value.course_qualification.status===status&&value.course_qualification.scoring_pool_count===0&&value.total_in_library===8,'Unusable course '+status+' retains total library size and filters before scoring');same(value.recommendations,[],'Unusable course does not fall back to uncertified recommendations');same(value.results,[],'Empty canonical results match recommendations');same(value.review_candidates,[],'Unusable course cannot leak candidates through review list');return value;
}
async function runChecks(){
 await boot(true);for(const actor of actors)await login(actor);
 scope=(await api('/course-catalog/scopes')).scopes[0].scope_id;otherScope=(await api('/course-catalog/scopes',undefined,'other')).scopes[0].scope_id;
 configuration=(await api('/organization/config',undefined,'admin')).configuration;
 for(let i=0;i<8;i++)ids.push(await api('/teachers',{name:'SYNTHETIC QUALIFICATION '+(i+1),status:'在库',teacher_level:'讲师',base_province:'浙江',base_city:'杭州',field:'文档编号复核',intro:'本人主讲文档编号复核课程，培训对象：见习文控员。',fee_rate:500},'admin'));
 await publishCatalog();
 phase='full pool qualification before original scoring';
 const baseline=await api(route,requestBody);check(baseline.total_in_library===8&&baseline.course_qualification.status==='NOT_SELECTED'&&baseline.course_qualification.eligible_count===null,'Absent standard_course retains legacy path and explicitly marks course certification unchecked');
 check(baseline.recommendations.length>=3,'Synthetic legacy pool genuinely contains recommendable first three teachers');
 check(baseline.recommendations.slice(0,3).every(r=>ids.slice(0,3).includes(r.teacher_id)),'Original stable order would recommend the first three unqualified records');
 const picked=await api(route,selected());same(picked.recommendations.map(r=>r.teacher_id),ids.slice(3,7),'Full-pool qualification admits later four eligible teachers and keeps third-place ties');
 check(picked.total_in_library===8&&picked.course_qualification.scoring_pool_count===4&&picked.course_qualification.eligible_count===4,'Total library count stays eight while the scoring pool is exactly four');
 same(picked.results,picked.recommendations,'Existing canonical results protocol remains intact');check(picked.course_qualification.pool_exclusions.length===4,'Missing, unknown, revoked and unbound teachers remain explicit qualification gaps');
 check(!('_access_version'in picked)&&!JSON.stringify(picked).includes('server-only'),'Internal capture, Result and M01 marker are not serialized into response');
 check(picked.minimum_required===3&&picked.returned_count===4&&picked.shortfall===0,'Original top-three plus ties and shortfall fields remain unchanged');
 const budget=await api(route,{...selected(),hard_budget:true,max_fee_rate:100});check(budget.recommendations.length===0&&budget.course_qualification.scoring_pool_count===4&&budget.excluded.length===4,'Qualification never bypasses the original hard budget exclusion');
 await courseEmpty({course_code:'002'},'NO_ELIGIBLE');await courseEmpty({course_code:'003'},'INVALID_SELECTION');await courseEmpty({course_code:'999'},'INVALID_SELECTION');await courseEmpty({allowed_cities:['宁波']},'NO_ELIGIBLE');
 await courseEmpty({scope_id:otherScope,expected_version:0},'NOT_CONFIGURED','other');
 phase='explicit permissions and strict request boundaries';
 await denied(route,selected(),'anonymous',401,'No anonymous recommendation');await denied(route,selected(),'viewer',403,'Explicit M04 grants do not replace existing business write role');await denied(route,selected(),'other',403,'Cross-organization standard course cannot be selected');await denied(route,selected(),'admin',403,'Old admin role never implies catalog qualification access');
 for(const standard_course of[null,false,{},[],{scope_id:scope}])for(const path of[route,route+'/jobs'])await denied(path,{...requestBody,standard_course},'manager',400,'Invalid explicit selection must not become the legacy no-selection path');
 for(const field of['eligible_teacher_ids','candidates','teacher_ids','qualification','course_qualification','qualification_context','context_id','_access_version','_m04_capture','recommendations','results','review_candidates'])await denied(route,{...selected(),[field]:[]},'manager',400,'Client cannot provide trusted '+field);
 for(const changes of[{scope_id:String(scope)},{scope_id:1.5},{expected_version:'1'},{expected_version:-1},{actor:'P1'},{as_of:'2026-02-30'}])await denied(route,selected(changes),'manager',400,'Selection rejects unsafe types, invalid date and unknown fields');
 await denied(route,selected({expected_version:0}),'manager',409,'Stale directory version retains its original conflict status');
 const draft=(await api('/demands/draft',{request_id:'SYNTHETIC-RECOMMENDATION-DRAFT',title:'SYNTHETIC PRIVATE DEMAND',unit:'SYNTHETIC',business_path:'direct',organization_code:'001',internal_contact_code:'P1',category_text:'业务技能',delivery_mode_text:'线上',period_text:'全天',duration_minutes:'90',participant_count:'1',budget_amount:'0',objectives:'SYNTHETIC',content:'文档编号复核'},'manager')).workflow;
 for(const path of[route,route+'/jobs'])await denied(path,{...requestBody,demand_id:draft.id},'other',403,'Original demand ownership is checked before synchronous scoring or asynchronous submission');
 const demandScoped=await api(route,{...selected(),demand_id:draft.id});check(demandScoped.demand_id===draft.id,'Authorized original demand source remains usable');
 phase='opaque async storage and same-session delivery';
 const done=await complete();check(done.status==='completed'&&done.result.course_qualification.status==='READY','Real worker publishes a qualified completed result');same(done.result.recommendations.map(r=>r.teacher_id),ids.slice(3,7),'Async recommendation matches synchronous full-pool result');
 const repeat=await api(jobPath(done.job_id));same(repeat,done,'Each same-session cached task read revalidates and retains the original result');
 await denied(jobPath(done.job_id),undefined,'peer',404,'Another account cannot read a cached job');await denied(route+'/jobs/cancel',{job_id:done.job_id},'peer',404,'Another account cannot cancel a cached job');
 tokens.original=tokens.manager;await login('manager');
 await denied(jobPath(done.job_id),undefined,'manager',403,'Same UID with a new real login cannot inherit completed job');await denied(route+'/jobs/cancel',{job_id:done.job_id},'manager',403,'Same UID new login cannot cancel or change old job');same(await api(jobPath(done.job_id),undefined,'original'),done,'Rejected cross-session cancellation preserves original owner result');
 await api('/logout',{},'original');await denied(jobPath(done.job_id),undefined,'original',401,'Logged-out original session loses the job');
 const cityJob=await complete();const teacher=(await api('/teachers',undefined,'admin')).find(t=>t.id===ids[3]);await api('/teachers',{...teacher,base_city:'宁波'},'admin');
 await denied(jobPath(cityJob.job_id),undefined,'manager',409,'Current teacher facts invalidate stored qualified result');await denied(route+'/jobs/cancel',{job_id:cityJob.job_id},'manager',409,'Cancellation revalidates current qualification before state mutation');
 await api('/teachers',teacher,'admin');const invalidated=await api(jobPath(cityJob.job_id));check(invalidated.status==='failed'&&!invalidated.result&&invalidated.error_code===409,'Detected invalid cache stays discarded even after teacher fact restoration');
 const catalogJob=await complete();await publishCatalog();await denied(jobPath(catalogJob.job_id),undefined,'manager',409,'New confirmed directory revision invalidates stored result');
 const noCourse=await complete(requestBody);check(noCourse.result.course_qualification.status==='NOT_SELECTED','Original no-course async path also carries an explicit unverified qualification summary');
 phase='M01 version and grant changes';
 const versionJob=await complete();const before=configuration.version;configuration={...configuration,version:'synthetic-recommendation-v2'};await api('/organization/config',{expectedVersion:before,configuration},'admin');check((await api('/me')).uid>0,'Version-only M01 update keeps login active');
 for(const id of[versionJob.job_id,noCourse.job_id])await denied(jobPath(id),undefined,'manager',409,'Both selected and unselected stored tasks revalidate M01 version');
 const current=await complete();const previous=configuration.version;configuration={...configuration,version:'synthetic-recommendation-v3',grants:configuration.grants.filter(g=>g.resource!=='catalog.read')};await api('/organization/config',{expectedVersion:previous,configuration},'admin');
 await denied(jobPath(current.job_id),undefined,'manager',401,'Catalog permission revocation invalidates original login');await login('manager');await denied(route,selected(),'manager',403,'Fresh login cannot recover revoked catalog permission');await denied(jobPath(current.job_id),undefined,'manager',403,'Fresh login cannot transfer old stored result');
 const plain=await api(route,requestBody);check(plain.course_qualification.status==='NOT_SELECTED','Removing catalog access does not invent a new grant requirement for absent standard_course');
 return {teacher_ids:ids,qualified_teacher_ids:ids.slice(3,7),scope_id:scope,catalog_version:catalogVersion,standard_course_optional:true,server_cache:'Existing bounded job results only; no new recommendation cache'};
}
let evidence;runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{await stop();const summary={suite:'recommendation-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;});
