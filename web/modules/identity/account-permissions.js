// Read-only inspection of the server's organization gate, never a configuration editor.
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
const operations = new Map([
  ['demand.read/VIEW','查看需求'],['demand.write/HANDLE','填写需求'],['approval.review/HANDLE','审批审核'],['demand.accept/HANDLE','承接需求'],['bid.result/HANDLE','登记投标结果'],
  ['catalog.read/VIEW','查看课程目录'],['catalog.manage/HANDLE','维护课程目录'],['delivery.read/VIEW','查看授课'],['delivery.write/HANDLE','填写授课'],['delivery.verify/HANDLE','核对授课'],
  ['reports.read/VIEW','查看报表'],['reports.export/EXPORT','导出报表'],['survey.preview/HANDLE','预览评价统计'],['summary.read/VIEW','查看总结'],['summary.edit/HANDLE','填写总结'],
]);
const positive = n => Number.isSafeInteger(n) && n > 0;
const text = v => typeof v === 'string' && v.length > 0;
const codes = v => Array.isArray(v) && v.every(text) && new Set(v).size === v.length;
const bindingNames = {CONFIG_NOT_PUBLISHED:'组织权限尚未发布',UNBOUND:'尚未绑定业务身份',BOUND_DISABLED:'业务身份绑定已停用',BOUND_ENABLED:'业务身份已绑定'};
const statusNames = {ALLOWED:'范围允许',DENIED:'范围不允许',NOT_CONFIGURED:'尚未配置',ACCOUNT_DISABLED:'账号已停用'};
function validated(value, accountId, organization) {
  const fail = () => { throw Error('账号业务权限暂时无法核实，请重新查询。'); };
  if (!value || value.read_only !== true || value.organization_gate_only !== true || !['NOT_CONFIGURED','CONFIGURED'].includes(value.configuration_status)) fail();
  const subject = value.subject;
  if (!subject || subject.account_id !== accountId || !text(subject.username) || !(subject.name === null || typeof subject.name === 'string') || typeof subject.account_enabled !== 'boolean' || !Object.hasOwn(bindingNames,subject.binding_status) || !codes(subject.role_codes)) fail();
  const bound = ['BOUND_DISABLED','BOUND_ENABLED'].includes(subject.binding_status);
  if (bound ? !text(subject.person_code) || !text(subject.organization_code) || typeof subject.person_enabled !== 'boolean' || typeof subject.home_organization_enabled !== 'boolean' : subject.person_code !== null || subject.organization_code !== null || subject.person_enabled !== null || subject.home_organization_enabled !== null || subject.role_codes.length !== 0) fail();
  if (!Array.isArray(value.organizations) || !value.organizations.every(row => row && text(row.organization_code) && typeof row.enabled === 'boolean') || new Set(value.organizations.map(row=>row.organization_code)).size !== value.organizations.length || !Array.isArray(value.decisions)) fail();
  if (value.configuration_status === 'NOT_CONFIGURED') {
    if (organization || value.configuration_version !== null || value.organizations.length || value.selected_organization !== null || value.decisions.length || subject.binding_status !== 'CONFIG_NOT_PUBLISHED') fail();
    return value;
  }
  if (!text(value.configuration_version) || subject.binding_status === 'CONFIG_NOT_PUBLISHED' || !value.organizations.length || value.selected_organization !== (organization || null)) fail();
  if (bound && (!subject.role_codes.length || !value.organizations.some(row=>row.organization_code===subject.organization_code && row.enabled===subject.home_organization_enabled))) fail();
  if (!organization) { if (value.decisions.length) fail(); return value; }
  const selected = value.organizations.find(row=>row.organization_code===organization);
  if (!selected || value.decisions.length !== operations.size) fail();
  const seen = new Set();
  for (const row of value.decisions) {
    const key = row?.resource+'/'+row?.action;
    if (!operations.has(key) || seen.has(key) || !text(row.label) || !text(row.reason) || !codes(row.matched_rule_ids) || !Object.hasOwn(statusNames,row.status) || typeof row.allowed !== 'boolean' || row.allowed !== (row.status === 'ALLOWED')) fail();
    if (!subject.account_enabled && row.status !== 'ACCOUNT_DISABLED' || subject.account_enabled && row.status === 'ACCOUNT_DISABLED') fail();
    if (row.allowed && (subject.binding_status !== 'BOUND_ENABLED' || !subject.person_enabled || !subject.home_organization_enabled || !selected.enabled)) fail();
    seen.add(key);
  }
  return value;
}
export function createAccountPermissions(host) {
  for (const key of ['api','getUser','getModal','openModal','closeModal','renderTable','toast']) if (typeof host?.[key] !== 'function') throw TypeError('Missing account permissions host: '+key);
  const identity = host.getUser(); let alive = true, owned = null;
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true) && host.getUser() === identity && identity?.role === 'admin';
  const current = scope => active() && owned === scope && !scope.closed && scope.mask?.isConnected !== false && host.getModal() === scope.mask;
  const node = (scope,key) => scope.mask.querySelector('[data-permissions-'+key+']');
  function clear(scope, all = false) {
    scope.snapshot = null; node(scope,'subject').innerHTML = ''; node(scope,'results').innerHTML = '';
    if (all) { scope.organizations = []; node(scope,'organization').innerHTML = '<option value="">请选择机构</option>'; node(scope,'organization').value = ''; node(scope,'organization').disabled = true; }
  }
  function dispose(scope) { if (!scope || scope.closed) return; scope.closed = true; scope.ticket++; scope.controller?.abort(); clear(scope,true); if (owned===scope) owned=null; }
  function closeOwned() { const scope=owned; if (!scope) return; const mask=scope.mask; dispose(scope); if (host.getModal()===mask) host.closeModal(true); }
  function ensure(scope) { if (current(scope)) return true; if (owned===scope && !active()) closeOwned(); return false; }
  function render(scope, value, changed) {
    scope.snapshot=value;scope.organizations=value.organizations;
    const person=value.subject, configured=value.configuration_status==='CONFIGURED';
    node(scope,'subject').innerHTML=`<div class="inline-note"><span style="overflow-wrap:anywhere"><b>${escape(person.name || person.username)}</b><br>账号：${escape(person.username)} · 账号 ID ${person.account_id}<br>账号${person.account_enabled?'启用':'停用'} · ${bindingNames[person.binding_status]}${person.person_code===null?'':`<br>系统人员标识：${escape(person.person_code)} · 所属机构：${escape(person.organization_code)}<br>人员${person.person_enabled?'启用':'停用'} · 所属机构${person.home_organization_enabled?'启用':'停用'}<br>岗位编码：${person.role_codes.map(escape).join('、') || '未提供'}`}</span></div>`;
    const select=node(scope,'organization');select.innerHTML='<option value="">请选择机构</option>'+value.organizations.map(row=>`<option value="${escape(row.organization_code)}">${escape(row.organization_code)}${row.enabled?'':'（停用）'}</option>`).join('');select.value=value.selected_organization || '';select.disabled=!configured;
    node(scope,'notice').textContent=!configured?'组织权限尚未发布。':changed?'组织权限已更新，以下为本次查询结果。':value.selected_organization?'':'请选择机构，核对该账号的业务权限。';
    if (value.decisions.length) node(scope,'results').innerHTML=host.renderTable([
      {k:'label',l:'许可项',render:row=>escape(operations.get(row.resource+'/'+row.action))},
      {k:'status',l:'机构范围许可',render:row=>`<span class="${row.allowed?'status-tag':''}">${statusNames[row.status]}</span>`},
      {k:'reason',l:'原因',render:row=>`<span style="overflow-wrap:anywhere;word-break:break-word">${escape(row.reason)}</span>`},
    ],value.decisions.map((row,index)=>({...row,id:index+1})),[]);
  }
  async function read(scope, organization = '') {
    if (!ensure(scope)) return false;
    if (organization && !scope.organizations.some(row=>row.organization_code===organization)) return false;
    const priorVersion=scope.snapshot?.configuration_version ?? scope.version;
    const ticket=++scope.ticket;scope.controller?.abort();scope.controller=new AbortController();clear(scope);
    node(scope,'read').disabled=true;node(scope,'notice').textContent='正在查询账号业务权限…';
    try {
      const value=await host.api('/organization/account-access?account_id='+scope.accountId+(organization?'&organization_code='+encodeURIComponent(organization):''),{quiet:true,signal:scope.controller.signal});
      if (!ensure(scope) || ticket!==scope.ticket) return false;
      const result=validated(value,scope.accountId,organization);scope.version=result.configuration_version;render(scope,result,priorVersion!=null&&priorVersion!==result.configuration_version);return true;
    } catch(error) {
      if (!ensure(scope) || ticket!==scope.ticket || error?.name==='AbortError') return false;
      clear(scope,true);scope.version=null;
      const status=Number(error?.status||error?.code);
      if ([401,403].includes(status)) { closeOwned();return false; }
      node(scope,'notice').textContent=status===404?'当前账号已不存在，请关闭后刷新账号列表。':status===400?'所选机构已无法查询，请重新读取机构列表。':status===503?'组织权限暂时无法读取，请稍后重新查询。':'账号业务权限暂时无法核实，请重新查询。';
      return false;
    } finally { if (current(scope)&&ticket===scope.ticket) node(scope,'read').disabled=false; }
  }
  async function open(accountId) {
    if (!active() || !positive(accountId)) return false;
    if (host.getModal()) { host.toast('请先完成或关闭当前窗口，再查看业务权限。',true);return false; }
    const scope={accountId,mask:null,closed:false,ticket:0,controller:null,snapshot:null,organizations:[],version:null};
    scope.mask=host.openModal('查看业务权限',`<p class="modal-intro">这里只核对机构范围权限；具体事项还须符合流程条件。</p><div data-permissions-subject></div><p class="modal-intro" data-permissions-notice role="status" aria-live="polite"></p><div class="form-grid"><div class="form-item span2"><label for="account-permissions-organization">机构</label><select id="account-permissions-organization" data-permissions-organization disabled><option value="">请选择机构</option></select></div></div><div class="toolbar" style="flex-wrap:wrap;margin:12px 0"><button type="button" class="btn gray" data-permissions-read>重新查询</button><button type="button" class="btn gray" data-permissions-close>关闭</button></div><div data-permissions-results></div>`,{noFoot:true,onClose:()=>dispose(scope)});
    if (!scope.mask) return false;owned=scope;
    node(scope,'organization').onchange=()=>read(scope,node(scope,'organization').value);
    node(scope,'read').onclick=()=>read(scope,node(scope,'organization').value);
    node(scope,'close').onclick=()=>{if(current(scope))closeOwned();};
    await read(scope);return current(scope);
  }
  function destroy() { if (!alive) return;closeOwned();alive=false;host.signal?.removeEventListener('abort',destroy); }
  if (host.signal?.aborted) alive=false;else host.signal?.addEventListener('abort',destroy,{once:true});
  return {open,destroy};
}
