'use strict';
// Synthetic form interaction only: reuse the established upload DOM stand-in.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
const tests=fs.readFileSync(path.join(__dirname,'test_teacher_upload.cjs'),'utf8');
const slice=(start,end)=>app.slice(app.indexOf(start),app.indexOf(end,app.indexOf(start)));
const harness=vm.runInNewContext(tests.slice(tests.indexOf('function harness('),tests.indexOf('(async () =>'))+'\nharness;', {vm,slice,app});
let checks=0;const check=(ok,label)=>{assert.ok(ok,label);checks++;};
(async()=>{
  const h=harness();h.context.openTeacherCreate();
  h.context.formData={name:'【灰度测试】未完成档案',base_province:'浙江',base_city:'',fee_rate:null,teacher_level:''};
  await h.modal.options.onOk();
  const body=h.calls.find(call=>call.url==='/teachers').body;
  check(body.status==='待完善','Province-only creation is explicit draft');
  check(body.fee_rate===null && body.teacher_level==='','Unknown price/grade not fabricated');
  check(h.messages.at(-1).includes('待完善'),'User sees draft status after save');
  const fields=vm.runInContext('teacherFormFields(true)',h.context);
  check(!fields.find(f=>f.k==='base_city').required && !fields.find(f=>f.k==='base_province').required,'Creation does not require guessed location');
  check(fields.find(f=>f.k==='fee_rate').nullable && !fields.find(f=>f.k==='fee_rate').required,'Unknown price explicitly nullable');
  const complete=vm.runInContext('teacherFormFields()',h.context);
  check(complete.find(f=>f.k==='base_city').required,'Ready record editing still requires real city');

  const draft=harness();draft.context.openTeacherResumeUpload([{id:7,name:'【灰度测试】待完善',status:'待完善',base_province:'浙江',base_city:''}],7);
  draft.context.formData={base_province:'浙江',base_city:''};
  const file={name:'synthetic.pdf',size:10,slice:()=>({arrayBuffer:async()=>Uint8Array.from(Buffer.from('%PDF-1.7')).buffer})};
  const input=draft.modal.nodes.get('#teacher-resume-file');input.files=[file];input.onchange();
  await draft.modal.options.onOk();
  check(draft.uploads.length===1 && draft.uploads[0].id==='7','Draft uploads to existing owner before city completion');
  check(!draft.calls.some(call=>call.url==='/teachers/checkin'),'Upload never silently activates draft');
  check(draft.modal.body.includes('完善并入库') && draft.modal.body.includes('先上传简历'),'Upload has explicit completion guidance');
  check(app.includes("show: (row) => row.status === '待完善'") && app.includes("okText: '保存并确认入库'"),'Draft has all-field completion action');

  // Exercise the real collector, not its test harness stub, for nullable money.
  const inputNode={value:'',closest:()=>null,removeAttribute(){}};
  const context={$:()=>inputNode,toast(){}};vm.createContext(context);
  vm.runInContext(slice('  function collectForm(', '  // ============ 表格'),context);
  check(context.collectForm({},[{k:'fee_rate',type:'number',nullable:true}]).fee_rate===null,'Blank price serializes to null, not 0');
  inputNode.value='2500';check(context.collectForm({},[{k:'fee_rate',type:'number',nullable:true}]).fee_rate===2500,'Confirmed price stays numeric');
  inputNode.value='';check(context.collectForm({},[{k:'hours',type:'number'}]).hours===0,'Other numeric fields preserve existing behavior');
  console.log(JSON.stringify({ok:true,suite:'teacher-draft-ui',checks,network:false,real_uploads:false}));
})().catch(error=>{console.error(error);process.exitCode=1;});
