'use strict';
// Real original pages, API, modal and table; synthetic DOM/transport and module mount spies only.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=path.resolve(__dirname,'..');
const sourcePath=path.join(app,'web/app.js');
const source=fs.readFileSync(sourcePath,'utf8');
let suite=fs.readFileSync(app+'/scripts/IntegrationDeliveryCatalogUI.test.cjs','utf8').split('(async () => {')[0];
suite=suite.replace("const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');",`const source = candidate.replaceAll("await import('/modules/", "await loadHostModule('/modules/");`);
const lib=vm.runInNewContext(suite+'\n({harness,project,dispatch,NodeStub});',{require,__dirname:app+'/scripts',candidate:source,console,structuredClone,URL,URLSearchParams,AbortController,DOMException,setImmediate});
const between=(a,b)=>{const start=source.indexOf(a),end=source.indexOf(b,start);assert.ok(start>=0&&end>start,a);return source.slice(start,end);};
let checks=0;
const check=(truth,label)=>{assert.ok(truth,label);checks++;};
const equal=(value,expected,label)=>{assert.deepEqual(JSON.parse(JSON.stringify(value)),expected,label);checks++;};
const limited=(flags={},id=5)=>({id,title:'SYNTHETIC 最小项目',status:'进行中',delivery_controlled:true,read_access:{limited:true,delivery:false,summary:false,feedback:false,...flags}});
const deferred=()=>{let resolve;return{promise:new Promise(r=>resolve=r),resolve:v=>resolve(v)}};
const urls=h=>h.calls.map(c=>c.url);
function harness(options={}){
 const h=lib.harness({page:'project_detail',projectRows:[limited({delivery:true})],...options});
 const s=h.sandbox;h.summary=[];h.feedback=[];h.gotos=[];
 s.mountProjectSummary=(c,p,e)=>h.summary.push({id:p.id,epoch:e,root:c.querySelector('#project-summary-content')});
 s.mountSurveyFormalEntry=(c,entry,o)=>{h.feedback.push({entry,...o});return{};};
 s.navigateTo=(page,options)=>h.gotos.push({page,options});
 s.showProjectOverview=row=>s.navigateTo('project_detail',{projectId:row.id});
 s.REGION_PROVINCES=[];s.showProjectStart=()=>{};s.showProjectTransition=()=>{};s.quickCreateDemand=()=>{};
 vm.runInContext(between('  const CRUD = {','  async function fillOptions(')+between('  function renderModuleSummary(','  function deliveryHoursValue(')+'\nthis.projectConfig=CRUD.projects;',s);
 return h;
}
(async()=>{
 for(let bits=0;bits<8;bits++){
  const flags={delivery:!!(bits&1),summary:!!(bits&2),feedback:!!(bits&4)},h=harness({projectRows:[limited(flags)],writer:true});
  await h.mount();
  equal(urls(h),['/api/projects'],'Limited workspace does not request legacy project dependencies '+bits);
  equal(h.summary.length,flags.summary?1:0,'Summary VIEW gates mount '+bits);
  equal(h.feedback.length,flags.feedback?1:0,'Feedback VIEW gates formal mount '+bits);
  equal(!!h.content.querySelector('[data-limited-dispatches]'),flags.delivery,'Delivery VIEW gates dispatch link '+bits);
  equal(!!h.content.querySelector('[data-limited-cases]'),flags.delivery,'Delivery VIEW gates Cases link '+bits);
  check(!/¥|委托单位待补充|负责人待补充|0 课时|人数待定|编辑资料|完成交付|归档项目|合同金额/.test(h.content.textContent),'No synthetic missing financial/personnel values or old writes '+bits);
  equal(h.imports,[],'No optional module loaded before intent '+bits);
  if(flags.summary)check(h.summary[0].root,'Summary uses existing slot '+bits);
  if(flags.feedback)check(h.feedback[0].entry&&h.feedback[0].projectId===5&&h.feedback[0].projects.length===1,'Formal survey gets exact candidate without raw questionnaire config '+bits);
  h.sandbox.beginRouteEpoch();
 }
 const malformed=harness({projectRows:[limited({delivery:'true',summary:1,feedback:{}})]});await malformed.mount();
 check(malformed.content.textContent.includes('暂无可查看模块'),'Nonboolean capability does not become allowed');equal(malformed.summary.length+malformed.feedback.length,0,'No module mount for malformed flags');
 const escaped=harness({projectRows:[{...limited({delivery:true}),title:'<img src=x onerror=bad()>',status:'<svg>'}]});await escaped.mount();
 check(!escaped.content.querySelector('img')&&!escaped.content.querySelector('svg')&&escaped.content.innerHTML.includes('&lt;img'),'Project title/status remain escaped');
 await escaped.content.querySelector('[data-limited-dispatches]').onclick();equal(escaped.gotos,[{page:'dispatches',options:{projectId:5}}],'Dispatch link keeps project context');
 escaped.sandbox.state.user={uid:99};await escaped.content.querySelector('[data-limited-dispatches]').onclick();equal(escaped.gotos.length,1,'Old identity cannot reuse context button');
 for(const mode of ['route','identity']){
  const pending=deferred(),h=harness({fetchOverride:async(url,opts,normal)=>{if(url==='/api/projects')await pending.promise;return normal(url,opts);}}),run=h.mount();
  if(mode==='route')h.leave();else h.sandbox.state.user={uid:999};pending.resolve();await run;
  check(!h.content.querySelector('h1'),'Late project read cannot enter old workspace after '+mode);equal(h.summary.length+h.feedback.length,0,'No late module mount '+mode);
 }
 for(const status of ['待启动','进行中','已完成','已归档']){
  const h=harness({page:'projects',writer:true,projectRows:[{...limited({delivery:true,summary:true}),status}]});await h.sandbox.pageCrud(h.content,h.sandbox.projectConfig);
  equal(h.buttons(5),['打开项目'],'System admin cannot obtain legacy mutation on limited '+status);
  equal(h.content.querySelectorAll('th').map(x=>x.textContent),['培训项目','状态','可查看模块','操作'],'Limited table contains no missing columns '+status);
  check(!/合同总额|计划课时|¥|委托单位待补充|人数待定/.test(h.content.textContent),'Limited summary only counts known records '+status);
  await h.content.querySelector('[data-act]').onclick();equal(h.gotos,[{page:'project_detail',options:{projectId:5}}],'Limited table action opens correct record '+status);
 }
 const mixed=harness({page:'projects',writer:true,projectRows:[limited({feedback:true}),{...lib.project(6,false),amount:123,hours:2}]});await mixed.sandbox.pageCrud(mixed.content,mixed.sandbox.projectConfig);
 equal(mixed.content.querySelectorAll('table').length,2,'Mixed list separates complete columns from module-only rows');
 check(mixed.buttons(6).includes('编辑')&&mixed.buttons(6).includes('完成交付'),'Complete legacy project actions preserved');
 equal(mixed.buttons(5),['打开项目'],'Mixed limited row cannot inherit neighboring write rights');
 check(mixed.content.querySelector('[data-row-id="6"]').textContent.includes('123.00'),'Visible full row preserves its actual financial value');
 check(!/¥|0 课时|待补充/.test(mixed.content.querySelector('[data-row-id="5"]').textContent),'Limited row does not borrow full-row defaults');
 const full=harness({page:'projects',writer:true,projectRows:[lib.project(6,false)]});await full.sandbox.pageCrud(full.content,full.sandbox.projectConfig);check(full.content.textContent.includes('合同总额')&&full.content.textContent.includes('计划课时'),'All-complete project summary keeps old behavior');
 for(const writer of [false,true]){
  const p=limited({delivery:true}),d={...lib.dispatch(7),read_access:p.read_access},h=harness({page:'dispatches',writer,projectRows:[p],dispatchRows:[d],contextProjectId:5});await h.mount();
  equal(urls(h),['/api/dispatches?project_id=5','/api/projects'],'Limited dispatch initial read avoids teachers '+writer);
  equal(h.buttons(7),['授课记录'],'Limited dispatch relies only on component capabilities '+writer);
  check(!h.content.querySelector('#add-btn'),'Limited project cannot create legacy dispatch '+writer);
  check(h.content.textContent.includes('合成讲师'),'Whitelist teacher_name is sufficient for existing row');
 }
 const readOnly=harness({page:'dispatches',writer:false,projectRows:[lib.project(5)],dispatchRows:[lib.dispatch(7)]});await readOnly.mount();check(!urls(readOnly).includes('/api/teachers'),'Read-only full dispatch does not fetch teacher directory');
 const editor=harness({page:'dispatches',writer:true,projectRows:[lib.project(5)],dispatchRows:[{...lib.dispatch(7),status:'待发送'}]});await editor.mount();
 check(!urls(editor).includes('/api/teachers'),'Legacy scheduling directory is deferred until editor opens');
 await editor.content.querySelector('#add-btn').onclick();equal(urls(editor).filter(x=>x==='/api/teachers').length,1,'New original dispatch loads directory on intent');
 check(editor.modal().textContent.includes('合成讲师'),'Teacher choices populated after explicit create');editor.sandbox.closeModal(true);
 await editor.act(7,'编辑');equal(urls(editor).filter(x=>x==='/api/teachers').length,1,'Same original page reuses its loaded teacher directory');check(editor.modal(),'Original row editor still opens');
 const material=harness({page:'dispatches',writer:true,projectRows:[lib.project(5)],dispatchRows:[lib.dispatch(7)]});await material.mount();await material.act(7,'编辑');check(!urls(material).includes('/api/teachers'),'Materials-only editor does not require full teacher directory');
 const mixedDispatch=harness({page:'dispatches',writer:true,projectRows:[limited({delivery:true}),lib.project(6,false)],dispatchRows:[{...lib.dispatch(7),read_access:limited({delivery:true}).read_access},lib.dispatch(9,false)]});await mixedDispatch.mount();
 equal(mixedDispatch.buttons(7),['授课记录'],'Mixed dispatch cannot inherit legacy edit rights');check(mixedDispatch.buttons(9).includes('编辑'),'Mixed original dispatch retains edit');await mixedDispatch.content.querySelector('#add-btn').onclick();
 const options=mixedDispatch.field('project_id').querySelectorAll('option');equal(options.map(x=>x.value),['','6'],'New dispatch excludes limited project choices');
 for(const mode of ['route','identity','target']){
  const h=harness(),wait=deferred();await h.mount();let mounted=0;
  h.sandbox.loadHostModule=async()=>{await wait.promise;return{mountCasesWorkspace(){mounted++;return{refresh:async()=>{},cleanup(){},beforeClose:()=>true}}};};
  const pending=h.content.querySelector('[data-limited-cases]').onclick();
  if(mode==='route')h.leave();else if(mode==='identity')h.sandbox.state.user={uid:333};else h.sandbox.state.projectId=6;
  wait.resolve();await pending;equal(mounted,0,'Lazy Cases module cannot mount after '+mode+' change');
 }
 const cases=harness();await cases.mount();let reads=0,disposed=0;let host;cases.sandbox.loadHostModule=async()=>({mountCasesWorkspace(root,context){host=context;return{refresh:async(id,title)=>{reads++;equal(id,5,'Cases receives trusted project ID');},cleanup(){disposed++;},beforeClose:()=>true};}});
 await cases.content.querySelector('[data-limited-cases]').onclick();equal(reads,1,'Explicit Cases entry triggers one module read');equal(urls(cases),['/api/projects'],'Cases entry never falls through to old /fees');check(host.signal&&!host.signal.aborted&&host.isCurrent(),'Cases receives original identity/route/abort contract');cases.leave();check(host.signal.aborted&&disposed===1&&!host.isCurrent(),'Route cleanup disposes Cases and aborts pending work');
 const busy=harness();await busy.mount();const slow=deferred();let mounts=0;busy.sandbox.loadHostModule=async()=>{mounts++;await slow.promise;return{mountCasesWorkspace(){return{refresh:async()=>{},cleanup(){},beforeClose:()=>true}}}};const first=busy.content.querySelector('[data-limited-cases]').onclick();await busy.content.querySelector('[data-limited-cases]').onclick();equal(mounts,1,'Double Cases click does not start duplicate loads');slow.resolve();await first;
 const failed=harness();await failed.mount();failed.sandbox.loadHostModule=async()=>{throw Error('synthetic module failure')};await failed.content.querySelector('[data-limited-cases]').onclick();check(failed.content.textContent.includes('暂时无法读取')&&!failed.content.querySelector('[data-limited-cases]').disabled,'Failed module load permits explicit retry without old finance fallback');
 const guard=harness();await guard.mount();let calls=0;guard.sandbox.loadHostModule=async()=>({mountCasesWorkspace(){return{refresh:async()=>{calls++},cleanup(){},beforeClose:()=>false}}});await guard.content.querySelector('[data-limited-cases]').onclick();await guard.content.querySelector('[data-limited-cases]').onclick();equal(calls,1,'Existing Cases dirty guard retains unsaved modal instead of refresh');
 check(!source.slice(source.indexOf('function renderLimitedProjectWorkspace'),source.indexOf('async function pageProjectDetail')).includes('confirm('),'Limited workspace has no native confirm');
 console.log('IntegrationLimitedProjectUI: '+checks+' checks passed');
})().catch(error=>{console.error(error);process.exitCode=1});
