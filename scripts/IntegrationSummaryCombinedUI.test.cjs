'use strict';
// Actual original summary editor, modal, transport, and lifecycle; only synthetic DTOs/DOM.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=path.resolve(__dirname,'..');
const sourcePath=path.join(app,'web/app.js');
const read=fs.readFileSync.bind(fs),source=read(sourcePath,'utf8');
const testFs=Object.create(fs);testFs.readFileSync=(file,...args)=>typeof file==='string'&&path.resolve(file)===app+'/web/app.js'?args[0]==='utf8'?source:Buffer.from(source):read(file,...args);
const requireTest=id=>id==='node:fs'||id==='fs'?testFs:require(id);
const old=read(app+'/scripts/IntegrationSummaryFormalUI.test.cjs','utf8');
const context={require:requireTest,process:{argv:[]},__dirname:app+'/scripts',console,structuredClone,AbortController,DOMException,URL,URLSearchParams,Buffer,setImmediate,Blob,Uint8Array,Response};
vm.runInNewContext(old.slice(0,old.indexOf('(async()=>{'))+'\nglobalThis.support={setup,formal,flowResponse,lib};\n}',context);
const {setup,formal,flowResponse,lib}=context.support;
let checks=0;const check=(v,label)=>{assert.ok(v,label);checks++;},equal=(a,b,label)=>{assert.deepEqual(JSON.parse(JSON.stringify(a)),b,label);checks++;};
const decision=(role='COMBINED')=>({review_role:role,responsibilities:role==='COMBINED'?['BRANCH','BP']:[role],actor_code:'SYNTHETIC-ONE-ACTOR',reviewed_at:'2026-09-23T01:00:00Z',workflow_version:3});
function dto(status='SUBMITTED',role='COMBINED',mode='SINGLE_EXPLICIT'){
 const d=formal(status,role,status!=='SUBMITTED');d.workflow.review_mode=mode;d.workflow.review_blocked_reason=null;d.workflow.review_order=mode==='SINGLE_EXPLICIT'?'COMBINED':'BRANCH_THEN_BP';
 if(mode==='SINGLE_EXPLICIT')d.workflow.bp_reviewer_code=d.workflow.branch_reviewer_code='SYNTHETIC-ONE-ACTOR';
 return d;
}
const returned=(status,mode='SINGLE_EXPLICIT')=>{const d=dto(status,null,mode);d.workflow.workflow_version=3;if(status==='APPROVED')d.workflow.decisions=mode==='SINGLE_EXPLICIT'?[decision()]:[decision('BRANCH'),decision('BP')];return d;};
const row=(action='APPROVE_COMBINED',role='COMBINED')=>({workflow_version:3,revision:1,action,actor_code:'SYNTHETIC-ONE-ACTOR',created_at:'2026-09-23T01:00:00Z',review_role:role,responsibilities:role==='COMBINED'?['BRANCH','BP']:[role],note:'<img src=x>实事求是的合成意见'});
function history(items){return{project_id:5,workflow_version:3,items,total:items.length,offset:0,limit:20,read_only:true};}
(async()=>{
 const h=setup({initial:dto(),fetchOverride:(url,opts,normal,respond,remote)=>{if(url.endsWith('/review')){const d=returned('APPROVED');remote.set(d);return flowResponse(d,respond)}return normal()}});await h.mount();await h.open();
 check(h.panel().textContent.includes('待负责人及BP复核（兼任）'),'Panel identifies explicit pending combined mode');
 equal(h.button('approve').textContent,'同时完成两岗位复核','One explicit combined approval label');
 check(!h.button('save')&&h.button('review-return'),'Review-only combined role uses original review controls');
 await h.click('approve');equal(h.requests('review').length,1,'Combined approval makes exactly one request');
 const body=JSON.parse(h.requests('review')[0].opts.body);equal(Object.keys(body).sort(),['decision','expected_version','expected_workflow_version','note','project_id','request_id','review_role'],'Combined review only uses original precise writable body');
 equal([body.review_role,body.decision,body.expected_version,body.expected_workflow_version],['COMBINED','APPROVE_COMBINED',1,2],'Combined action is never two ordinary approvals');
 equal(h.modal().querySelectorAll('[data-summary-decision]').length,1,'Combined approved result is one decision line');
 check(h.modal().textContent.includes('同一人员同时完成两岗位复核')&&!h.modal().querySelector('[data-summary-historical-review]'),'Current matching revision clearly shows one actor carrying both roles');
 const ret=setup({initial:dto(),fetchOverride:(url,opts,normal,respond,remote)=>{if(url.endsWith('/review')){const d=returned('RETURNED');remote.set(d);return flowResponse(d,respond)}return normal()}});await ret.mount();await ret.open();await ret.click('review-return');equal(ret.requests('review').length,0,'Combined return still requires a human note');
 const note=ret.modal().querySelector('[data-summary-review-note]');note.value='两项职责均需核实，请补充';await ret.emit(note,'input');await ret.click('review-return');equal(JSON.parse(ret.requests('review')[0].opts.body).decision,'RETURN','Combined return keeps RETURN');equal(JSON.parse(ret.requests('review')[0].opts.body).review_role,'COMBINED','Combined return does not invent separate reviewer');
 const blocked=dto('SUBMITTED',null);blocked.workflow.review_blocked_reason='兼任职责、人员关系或权限已变化，请核对配置';const pause=setup({initial:blocked});await pause.mount();await pause.open();
 check(!pause.button('approve')&&!pause.button('review-return')&&pause.modal().textContent.includes('兼任职责、人员关系或权限已变化'),'Paused server capability and reason do not permit a new action');
 for(const mutate of [d=>delete d.workflow.review_mode,d=>d.workflow.review_mode='DUAL_REVIEW',d=>d.workflow.review_order='BRANCH_THEN_BP',d=>d.workflow.review_blocked_reason='暂停',d=>d.workflow.decisions=[{...decision(),responsibilities:['BRANCH']}],d=>d.workflow.decisions=[decision(),decision('BP')],d=>d.workflow.decisions=[{...decision(),responsibilities:['BP','BRANCH']}],d=>d.workflow.capabilities.review_role='GUESSED']){
  const d=dto();mutate(d);const bad=setup({initial:d});await bad.mount();check(!bad.panel().querySelector('[data-summary-open]'),'Inconsistent combined metadata fails closed');
 }
 for(const role of ['BRANCH','BP',null]){
  const d=dto('SUBMITTED',role,'DUAL_REVIEW');d.workflow.bp_reviewer_code=d.workflow.branch_reviewer_code='SAME-ID';const old=setup({initial:d});await old.mount();await old.open();
  check(!old.modal().textContent.includes('同时完成两岗位复核'),'Same identity never infers combined permission for '+role);
  equal(old.button('approve')?.textContent||null,role?'复核通过':null,'Ordinary or no role remains server-controlled '+role);
 }
 for(const mode of ['SINGLE_EXPLICIT','DUAL_REVIEW'])for(const editField of ['issues','caption1']){
  const approved=returned('APPROVED',mode);const saved=setup({initial:approved,fetchOverride:(url,opts,normal,respond,remote)=>{if(url.endsWith('/save')){const next=returned('DRAFT',mode);next.workflow.decisions=approved.workflow.decisions;next.version=next.revision=next.latest_version=next.workflow.revision=2;next.workflow.submitted_revision=1;next.current=lib.revision(2,JSON.parse(opts.body).content);next.workflow.workflow_version=3;remote.set(next);return respond(next)}return normal()}});await saved.mount();await saved.open();await saved.edit(editField,'新一版合成'+editField);await saved.click('save');
  check(saved.modal().querySelector('[data-summary-historical-review]')?.textContent.includes('以下为第 1 版历史复核，本版须重新复核'),'Saved '+mode+'/'+editField+' distinguishes original approved revision');
  const decisions=saved.modal().querySelectorAll('[data-summary-decision]');equal(decisions.length,mode==='SINGLE_EXPLICIT'?1:2,'Keeps all original approval audit lines '+mode+'/'+editField);
  check(decisions.every(node=>node.textContent.startsWith('历史记录'))&&!saved.button('export'),'Historical approval cannot imply current approval or export '+mode+'/'+editField);
  check(saved.modal().querySelector('[data-summary-flow-status]').textContent.includes('待提交')&&saved.modal().querySelector('[data-summary-flow-status]').textContent.includes('正文第 2 版'),'New body/photo-caption revision remains draft '+mode+'/'+editField);
 }
 for(const action of ['APPROVE_COMBINED','RETURN']){
  const hist=setup({initial:dto(),fetchOverride:(url,opts,normal,respond)=>url.includes('/workflow-history?')?respond(history([row(action)])):normal()});await hist.mount();await hist.open();await hist.click('flow-history');
  const area=hist.modal().querySelector('#summary-flow-history');equal(area.querySelectorAll('article').length,1,'History represents one '+action+' event');
  check(area.textContent.includes('负责人及BP（兼任）')&&!area.querySelector('img'),'Both duties shown once and human note escaped');
  check(action==='RETURN'?!area.textContent.includes('通过')&&!area.textContent.includes('同时完成'):area.textContent.includes('同时完成两岗位复核'),'Combined return cannot be presented as approved');
 }
 const submitEvent={workflow_version:1,revision:1,action:'SUBMIT',actor_code:'AUTHOR',created_at:'2026-09-23T00:00:00Z',review_order:'COMBINED',policy_version:'SYNTHETIC-POLICY',branch_reviewer_code:'SAME-ID',bp_reviewer_code:'SAME-ID',review_mode:'SINGLE_EXPLICIT'};
 const sub=setup({initial:dto(),fetchOverride:(url,opts,normal,respond)=>url.includes('/workflow-history?')?respond(history([submitEvent])):normal()});await sub.mount();await sub.open();await sub.click('flow-history');check(sub.modal().querySelector('#summary-flow-history').textContent.includes('提交复核'),'New SUBMIT history mode is accepted without exposing policy references');check(!sub.modal().textContent.includes('SYNTHETIC-POLICY'),'Technical combined policy does not leak into business history');
 for(const event of [{...row(),responsibilities:['BRANCH']},{...row(),action:'APPROVE'},{...row(),review_role:'BP'}, {...row('RETURN'),responsibilities:null}, {...submitEvent,review_order:'UNORDERED'}]){
  const bad=setup({initial:dto(),fetchOverride:(url,opts,normal,respond)=>url.includes('/workflow-history?')?respond(history([event])):normal()});await bad.mount();await bad.open();await bad.click('flow-history');check(bad.modal().querySelector('#summary-flow-history').textContent.includes('暂不可读取')&&!bad.modal().querySelector('#summary-flow-history').querySelector('article'),'Wrong role/responsibility/action history rejected');
 }
 let first=true;const unknown=setup({initial:dto(),fetchOverride:(url,opts,normal,respond,remote)=>{if(url.endsWith('/review')){if(first){first=false;throw Error('lost reply')}const d=returned('APPROVED');remote.set(d);return flowResponse(d,respond)}return normal()}});await unknown.mount();await unknown.open();await unknown.click('approve');check(unknown.button('approve').disabled&&!unknown.button('retry').disabled,'Unknown combined mutation prevents a second independent action');await unknown.click('retry');equal(unknown.requests('review')[0].opts.body,unknown.requests('review')[1].opts.body,'Unknown combined result reuses exact request/body');
 const wait=lib.deferred();const rapid=setup({initial:dto(),fetchOverride:(url,opts,normal)=>url.endsWith('/review')?wait.promise:normal()});await rapid.mount();await rapid.open();const a=rapid.click('approve');await lib.tick();await rapid.emit(rapid.button('approve'),'click');equal(rapid.requests('review').length,1,'Double click cannot dispatch duplicate combined approval');rapid.leave();wait.resolve(new Response(JSON.stringify({code:0,data:{...returned('APPROVED').workflow,replayed:false,result_workflow_version:3}})));await a;check(!rapid.modal()&&rapid.content.textContent==='后续页面','Late combined response cannot revive closed route');
 const conflict=setup({initial:dto(),fetchOverride:(url,opts,normal,respond,remote)=>{if(url.endsWith('/review')){remote.set(blocked);return respond(null,409)}return normal()}});await conflict.mount();await conflict.open();await conflict.click('approve');equal(conflict.requests('review').length,1,'409 reads latest without a second review');check(conflict.modal().querySelector('#summary-conflict').textContent.includes('服务器'),'Combined conflict follows existing explicit reconciliation');
 console.log(JSON.stringify({suite:'original-summary-combined-ui',checks,status:'passed',browser:false,database:false}));
})().catch(error=>{console.error(error);process.exitCode=1});
