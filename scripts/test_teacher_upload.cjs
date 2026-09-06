#!/usr/bin/env node
'use strict';
// Exercise actual app form functions with deterministic DOM/API stand-ins, no browser/network/data writes.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const app = fs.readFileSync(path.join(root, 'web/app.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'web/v13.css'), 'utf8');
let checks = 0;
const check = (condition, label) => {assert.ok(condition, label); checks++;};
const slice = (start, end) => app.slice(app.indexOf(start), app.indexOf(end, app.indexOf(start)));
function harness(writable = true) {
  const calls = [], modals = [], uploads = [], messages = [];
  let current;
  const context = {
    formSeq: 0, state: {teacherTab: 'library'},
    YX_REGION_CITIES: {浙江: ['杭州', '宁波'], 江苏: ['南京']},
    esc: value => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'),
    icon: name => `<i data-icon="${name}"></i>`,
    canWrite: () => writable, resumeLimitMb: ext => ext === 'pdf' ? 15 : 80,
    resumeLimitBytes: ext => (ext === 'pdf' ? 15 : 80) * 1024 * 1024,
    formatResumeBytes: bytes => String(bytes), refreshIcons() {},
    toast: text => messages.push(text), invalidateTeacherRecommendations() {}, renderPage() {calls.push('render');},
    collectForm: () => context.formData, formData: {base_province: '浙江', base_city: '杭州'},
    api: async (url, options) => {calls.push({url, body: options.body}); return 42;},
    uploadTeacherResume: async (file, id) => {uploads.push({file, id}); return {status:'ready'};},
    openModal(title, body, options) {
      if (current) current.isConnected = false;
      const nodes = new Map();
      function node(id) {return {id, value:'', textContent:'', innerHTML:'', disabled:false, hidden:false, attributes:{},
        classList:{add(){},remove(){}}, addEventListener(type, fn) {this[type]=fn;}, focus() {this.focused=true;},
        setAttribute(k,v) {this.attributes[k]=v;}, removeAttribute(k) {delete this.attributes[k];}};}
      for (const match of body.matchAll(/\bid="([^"]+)"/g)) nodes.set('#'+match[1], node(match[1]));
      for (const id of ['modal-x','modal-cancel','modal-ok']) nodes.set('#'+id,node(id));
      const owner = nodes.get('#teacher-resume-owner');
      if (owner) owner.value = /<option value="([^"<>]+)" selected>/.exec(body)?.[1] || '';
      current = {title, body, options, nodes, dataset:{}, isConnected:true};
      modals.push(current); return current;
    },
    $: (selector, mask) => selector === '#modal-mask' ? current : (mask || current)?.nodes.get(selector),
    $$: () => [],
  };
  vm.createContext(context);
  vm.runInContext(slice('  const REGION_PROVINCES =', '  function refreshRegionSuggestions'), context);
  vm.runInContext(slice('  const teacherFormFields =', '  let teacherResumeUploadConfig'), context);
  vm.runInContext(slice('  function renderForm(', '  function collectForm('), context);
  vm.runInContext(slice('  function teacherEntryGuide(', '  function resumeClaimFacts('), context);
  return {context, calls, modals, uploads, messages, get modal() {return current;}};
}
(async () => {
  const denied = harness(false);
  denied.context.openTeacherResumeUpload([]); denied.context.openTeacherCreate();
  check(!denied.modals.length && !denied.calls.length, 'viewer cannot open write flows');
  const empty = harness(); empty.context.openTeacherResumeUpload([]);
  check(empty.modal.title === '先建立讲师档案' && empty.modal.body.includes('上传简历不会自动新建档案'), 'empty library explains record prerequisite');
  check(empty.modal.options.okText === '新建讲师档案' && !empty.calls.length, 'empty prompt offers explicit action, never auto-creates');
  check(empty.modal.options.onOk() === false && empty.modal.title === '新建讲师档案', 'create action opens form without dismissing the new dialog');
  check(empty.modal.body.includes('保存讲师的基本信息与常驻地区') && empty.modal.body.includes('已有档案请勿重复创建'), 'create form explains next step and duplicate avoidance');
  empty.context.formData = null;
  check(await empty.modal.options.onOk() === false && !empty.calls.length, 'invalid creation never writes');
  empty.context.formData = {name:'测试讲师',base_province:'浙江',base_city:'杭州',fee_rate:0};
  await empty.modal.options.onOk();
  check(empty.calls[0].url === '/teachers' && empty.calls[0].body.status === '在库' && !empty.uploads.length, 'explicit save keeps existing record API, no automatic upload');
  check(empty.messages.at(-1).includes('下一步可关联并上传简历'), 'successful creation explains the next action');

  const teachers = [
    {id:1,name:'甲<老师>',base_province:'浙江',base_city:'杭州',org:'研发 & 培训中心'},
    {id:2,name:'乙老师',base_province:'江苏',base_city:'南京',org:'培训中心'},
  ];
  const h = harness(); h.context.openTeacherResumeUpload(teachers);
  const modal = h.modal, owner = modal.nodes.get('#teacher-resume-owner');
  check(modal.title === '上传讲师简历' && modal.options.okText === '上传并解析', 'general upload has accurate title and action');
  check(modal.body.includes('关联已建档讲师') && modal.body.includes('请选择已建立档案的讲师'), 'owner field names existing-record requirement');
  check(modal.body.includes('先取消上传，在「师资档案」点击「新建讲师档案」'), 'missing teacher has exact navigation guidance');
  check(modal.body.includes('aria-describedby="teacher-resume-owner-help teacher-resume-owner-error"'), 'help and validation are associated with select');
  check(modal.body.includes('甲&lt;老师&gt;') && modal.body.includes('研发 &amp; 培训中心'), 'teacher data stays escaped');
  check(modal.body.includes('再次上传会替换旧文件') && modal.body.includes('仅管理员和业务管理员'), 'replacement and privacy warnings preserved');
  check(await modal.options.onOk() === false && owner.focused && owner.attributes['aria-invalid'] === 'true' && !h.calls.length, 'empty owner reports error and never writes');
  owner.value = '2'; owner.onchange();
  check(!owner.attributes['aria-invalid'] && !modal.nodes.get('#teacher-resume-owner-error').textContent, 'choosing owner clears old validation');
  const residence = modal.nodes.get('#resume-residence-fields').innerHTML;
  check(residence.includes('value="江苏" selected') && residence.includes('value="南京"'), 'changing owner fills correct province and city');
  check(residence.includes('data-region-province="base_province"') && residence.includes('<datalist'), 'province-city linking remains wired');
  h.context.formData = null;
  check(await modal.options.onOk() === false && !h.calls.length, 'invalid residence never writes or uploads');
  h.context.formData = {base_province:'江苏',base_city:'南京'};
  check(await modal.options.onOk() === false && h.messages.at(-1).includes('请选择 PDF') && !h.uploads.length, 'file still required');
  const fileInput = modal.nodes.get('#teacher-resume-file');
  fileInput.files = [{name:'wrong.txt',size:12}]; fileInput.onchange();
  check(h.messages.at(-1).includes('有效的 PDF 或 PPTX'), 'file type validation retained');
  fileInput.files = [{name:'large.pdf',size:16*1024*1024}]; fileInput.onchange();
  check(h.messages.at(-1).includes('不能超过'), 'file size limit retained');
  const pdf = {name:'test.pdf',size:10,slice:()=>({arrayBuffer:async()=>Uint8Array.from(Buffer.from('%PDF-1.7')).buffer})};
  fileInput.files = [pdf]; fileInput.onchange();
  await modal.options.onOk();
  check(h.uploads.length === 1 && h.uploads[0].id === '2' && h.uploads[0].file === pdf, 'upload stays associated with selected existing teacher');
  check(!h.calls.some(x=>x.url === '/teachers') && !h.calls.some(x=>x.url === '/teachers/residence'), 'existing unchanged residence does not create or rewrite record');
  check(h.context.state.teacherTab === 'resumes' && !owner.disabled && modal.dataset.locked === 'false', 'successful upload returns to resume management and unlocks controls');
  const prefilled = harness(); prefilled.context.openTeacherResumeUpload(teachers, 1);
  check(prefilled.modal.nodes.get('#teacher-resume-owner').value === '1' && prefilled.modal.title === '上传讲师简历', 'prefilled first upload is not falsely labelled replacement');
  const guide = prefilled.context.teacherEntryGuide();
  check(guide.indexOf('1. 新建讲师档案') < guide.indexOf('2. 关联并上传简历') && guide.indexOf('2. 关联并上传简历') < guide.indexOf('3. 核对解析结果'), 'guide follows real workflow order');
  check(guide.includes('已有档案可直接上传简历，无需重复建档'), 'existing teacher shortcut is explicit');
  check(slice('  function renderTeacherLibrary(', '  function renderResumeManagement(').includes('teacherEntryGuide()') && slice('  function renderResumeManagement(', '  function demandRequirementText(').includes('teacherEntryGuide()'), 'both teacher tabs expose preparation flow');
  check(!app.includes('手工入库') && app.includes("onclick = openTeacherCreate"), 'entry label and handler use clear create-record language');
  check(/\.resume-upload-fields \{[^}]*grid-template-columns: minmax\(0,1fr\);[^}]*gap: 22px/.test(css), 'owner and residence use a single-column group with persistent spacing');
  check(modal.body.includes('<div class="resume-upload-fields">') && !modal.body.includes('class="form-item span2"'), 'owner cannot create accidental outer grid columns');
  check(/\.resume-upload-fields \.form-grid \{[^}]*align-items: start/.test(css), 'province/city labels align at top');
  check(/@media \(max-width: 700px\) \{\s*html body.yx-v13 \.resume-upload-fields \.form-grid \{ grid-template-columns: minmax\(0,1fr\);/.test(css), 'small screens stack residence fields');
  check(/\.resume-upload-fields select \{[^}]*min-width: 0; max-width: 100%/.test(css), 'long owner names cannot widen the modal');
  console.log(JSON.stringify({ok:true,suite:'teacher upload prerequisites, form spacing and existing-record association',checks}));
})().catch(error=>{console.error(error);process.exitCode=1;});
