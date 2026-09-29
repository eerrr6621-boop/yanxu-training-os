import {
  createDraft, validateDraft, lessonUnits, fieldLimit, FIELD_LABELS, STATUS_LABELS, INITIAL_DRAFT, SUGGESTED_VALUES,
  createSyntheticQueue, createSyntheticSubmission, canAccept, canRegisterBidResult,
  acceptSynthetic, registerSyntheticBidResult,
} from './model.js';

let nextInstance = 0;
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

/** Internal synthetic logic harness only. Formal UI stays in the original app.js pages. No network or storage. */
export async function mount(root, context = {}) {
  if (!root || typeof root.replaceChildren !== 'function') throw new TypeError('M02 mount requires a root element');
  let disposed = false;
  let form, draft = createDraft(INITIAL_DRAFT), savedDraft = null;
  let queue = [], sequence = 0;
  const submitted = new Set();
  const instance = `yx-intake-${++nextInstance}`;
  const active = () => !disposed && !context.signal?.aborted;
  const cleanup = () => {
    if (disposed) return;
    disposed = true;
    root.removeEventListener('input', onInput);
    root.removeEventListener('change', onInput);
    root.removeEventListener('click', onClick);
    root.removeEventListener('submit', onSubmit);
    context.signal?.removeEventListener('abort', cleanup);
    // Leave DOM disposal to the host: an older cleanup cannot clear a new mount.
  };
  if (!active()) { disposed = true; return cleanup; }
  context.signal?.addEventListener('abort', cleanup, { once: true });
  if (context.mode !== 'demo') {
    root.innerHTML = `<section class="yx-intake" aria-label="培训需求与项目投标"><div class="yx-intake-empty"><span class="yx-intake-eyebrow">M02 · 培训需求与项目投标</span><h2>正式环境尚未接入</h2><p>本页仅用于内部逻辑测试。正式功能接入原系统的培训需求、投标与立项和项目总览页面，本页不提供正式操作。</p><p class="yx-intake-muted">已按暂定业务规则完成页面；正式使用前由管理员配置账号、组织范围及接单、登记权限。</p></div></section>`;
    return cleanup;
  }

  const syntheticUser = context.user?.synthetic === true;
  const filler = syntheticUser ? String(context.user.display_name || context.user.user_code || '合成会话填报人') : '未提供合成会话';
  const fillerCode = syntheticUser ? String(context.user.user_code || '合成会话 · 未分配演示编码') : '不使用真实会话身份';
  queue = createSyntheticQueue();

  const field = (key, { optional = false, area = false, hint = '', inputmode = 'text', placeholder = '', wide = false } = {}) => {
    const id = `${instance}-${key}`;
    const choices = SUGGESTED_VALUES[key] || [];
    return `<div class="yx-intake-field${wide ? ' yx-intake-wide' : ''}"><label for="${id}">${esc(FIELD_LABELS[key])}<span>${optional ? '选填' : '提交必填'}</span></label>${area ? `<textarea id="${id}" name="${key}" rows="3"` : `<input id="${id}" name="${key}" type="text" value="${esc(draft[key])}" inputmode="${inputmode}" ${choices.length ? `list="${id}-choices"` : ''}` } maxlength="${fieldLimit(key)}" autocomplete="off" placeholder="${esc(placeholder)}" aria-describedby="${id}-hint ${id}-error">${area ? '</textarea>' : ''}${choices.length ? `<datalist id="${id}-choices">${choices.map(value => `<option value="${esc(value)}"></option>`).join('')}</datalist>` : ''}<small id="${id}-hint">${esc(hint)}</small><p class="yx-intake-field-error" id="${id}-error" data-error="${key}" hidden></p></div>`;
  };
  root.innerHTML = `<section class="yx-intake" aria-label="培训需求与项目投标">
    <header class="yx-intake-header"><div><span class="yx-intake-eyebrow">研序 / M02</span><h1>需求与受理逻辑测试</h1><p>内部合成场景，不代表正式产品界面。</p></div><span class="yx-intake-demo-tag">内部逻辑测试</span></header>
    <div class="yx-intake-notice" role="note"><strong>仅限内部验证</strong><span>正式功能沿用原系统页面和操作方式。本页仅使用合成数据；所有修改保留在本次页面内，刷新即清除。不会发起正式申请或审批，不会写入生产系统。请勿填入真实个人资料。</span></div>
    <div class="yx-intake-status" role="status" aria-live="polite" data-status hidden></div>
    <div class="yx-intake-layout">
      <form class="yx-intake-form" data-draft-form novalidate>
        <section class="yx-intake-card"><div class="yx-intake-section-heading"><div><span class="yx-intake-step">01</span><h2>需求与责任人</h2></div><button class="yx-intake-button yx-intake-quiet" type="button" data-action="sample">填入合成样例</button></div>
          <div class="yx-intake-session"><span>实际填报人 · 会话带入，不可编辑</span><strong>${esc(filler)}</strong><small>${esc(fillerCode)}</small></div>
          <div class="yx-intake-fields">${field('title', { wide: true, placeholder: '描述这次培训的主题' })}
            <fieldset class="yx-intake-path-choice yx-intake-wide"><legend>承接路径 <span>提交必填</span></legend><label><input type="radio" name="business_path" value="direct" checked><span><strong>直接承接</strong><small>负责人先审，BP 后审</small></span></label><label><input type="radio" name="business_path" value="bid"><span><strong>投标项目</strong><small>沿用原办公签报，登记结果</small></span></label><p class="yx-intake-field-error" data-error="business_path" hidden></p></fieldset>
            ${field('organization_code', { placeholder: '使用已分配编码', hint: '合成示例编码均以 DEMO 开头，不代表正式编码规则。' })}
            ${field('demand_code', { optional: true, placeholder: '尚未分配可留空', hint: '需求编码与系统数字记录 ID 分开。' })}
            ${field('internal_contact_code', { placeholder: '内部对接人编码', hint: '内部负责沟通的人，可与填报人不同。' })}
            ${field('customer_contact_code', { optional: true, placeholder: '暂不清楚可后补', hint: '客户对接人可以后补，不影响内部提交；本期不设客户登录入口。' })}
          </div>
        </section>
        <section class="yx-intake-card"><div class="yx-intake-section-heading"><div><span class="yx-intake-step">02</span><h2>培训安排</h2></div><span class="yx-intake-muted">45 分钟 = 1 课时</span></div>
          <p class="yx-intake-help">可从常用建议中选择，也可直接输入。时间未定先填“待协调”，后续再补具体安排。</p>
          <div class="yx-intake-fields">${field('category_text', { placeholder: '按现有需求填写分类' })}${field('delivery_mode_text', { placeholder: '按现有需求填写授课方式' })}${field('period_text', { placeholder: '例如：某日下午（自由文字）' })}${field('duration_minutes', { inputmode: 'decimal', placeholder: '填写分钟，如 90 或 22.5', hint: '保留原始分钟；基本课时按分钟÷45保留两位、四舍五入。' })}${field('participant_count', { inputmode: 'numeric', placeholder: '大于 0 的整数' })}${field('budget_amount', { optional: true, inputmode: 'decimal', placeholder: '非负十进制数', hint: '仅记录需求预算，不据此计费或结算。' })}${field('expected_start_date', { optional: true, placeholder: 'YYYY-MM-DD' })}${field('expected_end_date', { optional: true, placeholder: 'YYYY-MM-DD', hint: '结束日期可与开始日期相同，不能更早。' })}${field('objectives', { wide: true, area: true, placeholder: '希望参与者通过培训达成什么目标？' })}</div>
        </section>
        <section class="yx-intake-card" data-bid-section hidden><div class="yx-intake-section-heading"><div><span class="yx-intake-step">03</span><h2>原办公签报</h2></div></div><p class="yx-intake-help">投标仍在原办公系统完成签报。研序记录引用和结果，不替代原签报审批。</p><div class="yx-intake-fields">${field('external_approval_ref', { wide: true, placeholder: '签报单号或可追溯引用', hint: '仅投标路径提交必填；不需要其他系统密码。' })}</div></section>
        <div class="yx-intake-form-actions"><div><button type="button" class="yx-intake-button yx-intake-secondary" data-action="save">保存草稿（演示）</button><button type="button" class="yx-intake-button yx-intake-secondary" data-action="restore" hidden>恢复上次草稿</button><button type="submit" class="yx-intake-button yx-intake-primary" ${syntheticUser ? '' : 'disabled'}>提交演示需求</button></div><small data-draft-note>草稿可缺字段；已填写的数字和日期仍需有效。</small></div>
      </form>
      <aside class="yx-intake-aside" aria-label="路径与课时说明"><section class="yx-intake-card yx-intake-route"><span class="yx-intake-eyebrow">这张需求如何流转</span><h2 data-path-title>先选择承接路径</h2><div data-path></div><p class="yx-intake-help">负责人和 BP 的审批结果由审批模块提供。本页没有审批通过按钮。</p></section><section class="yx-intake-card yx-intake-units"><span class="yx-intake-eyebrow">课时换算</span><output data-units aria-live="polite">填写分钟后自动换算</output><p>授课分钟 ÷ 45</p><small>基本课时保留两位，采用四舍五入（HALF_UP），同时保留原始分钟。基本课时不等于计酬课时，不据此自动计费。</small></section><section class="yx-intake-material"><h3>当前办理规则</h3><p>草稿可边填边存，客户对接人可后补。直接承接先由负责人、再由 BP 审批。</p><p>培训团队中获授权的人员接单；分公司内部对接人获授权后，依据原办公签报登记投标结果。具体账号由管理员配置。</p><p>以上为当前试用安排，可以根据实际使用情况调整。</p></section></aside>
    </div>
    <section class="yx-intake-card yx-intake-queue"><div class="yx-intake-section-heading"><div><span class="yx-intake-step">队列</span><h2>培训团队受理</h2></div><span class="yx-intake-demo-tag">全部为合成记录</span></div><p class="yx-intake-help">负责人待审、BP 待审与投标待结果均不可接单。只有合成“审批已通过”或“已中标”记录可演示受理；未中标留档。</p><div data-queue></div></section>
  </section>`;
  form = root.querySelector('[data-draft-form]');
  root.addEventListener('input', onInput);
  root.addEventListener('change', onInput);
  root.addEventListener('click', onClick);
  root.addEventListener('submit', onSubmit);
  updatePath();
  renderQueue();
  return cleanup;

  function readDraft() {
    const values = {};
    for (const [key, value] of new FormData(form)) values[key] = String(value);
    draft = createDraft(values);
    return draft;
  }
  function announce(message, error = false) {
    if (!active()) return;
    const el = root.querySelector('[data-status]');
    el.hidden = false;
    el.textContent = message;
    el.classList.toggle('yx-intake-status-error', error);
  }
  function showErrors(errors = {}) {
    if (!active()) return;
    root.querySelectorAll('[data-error]').forEach(el => {
      const key = el.dataset.error;
      el.textContent = errors[key] || '';
      el.hidden = !errors[key];
      form.querySelectorAll(`[name="${key}"]`).forEach(input => {
        if (errors[key]) input.setAttribute('aria-invalid', 'true');
        else input.removeAttribute('aria-invalid');
      });
    });
    if (Object.keys(errors).length) form.querySelector(`[name="${Object.keys(errors)[0]}"]`)?.focus();
  }
  function updatePath() {
    if (!active()) return;
    const path = draft.business_path;
    root.querySelector('[data-bid-section]').hidden = path !== 'bid';
    root.querySelector('[name="external_approval_ref"]').disabled = path !== 'bid';
    root.querySelector('[data-path-title]').textContent = path === 'direct' ? '直接承接' : path === 'bid' ? '投标项目' : '先选择承接路径';
    const steps = path === 'direct' ? ['内部填报', '分公司负责人先审', 'BP 后审', '培训团队承接'] : path === 'bid' ? ['原办公系统签报', '研序登记投标结果', '中标 → 待团队受理', '未中标 → 留档'] : ['直接承接：负责人 → BP → 团队', '投标：原签报 → 结果登记 → 分流'];
    root.querySelector('[data-path]').innerHTML = `<ol>${steps.map(step => `<li>${esc(step)}</li>`).join('')}</ol>`;
    root.querySelector('[data-units]').textContent = lessonUnits(draft.duration_minutes).text;
  }
  function onInput(event) {
    if (!active() || !form?.contains(event.target)) return;
    readDraft();
    updatePath();
    const error = root.querySelector(`[data-error="${event.target.name}"]`);
    if (error) { error.hidden = true; error.textContent = ''; form.querySelectorAll(`[name="${event.target.name}"]`).forEach(input => input.removeAttribute('aria-invalid')); }
    if (event.target.name === 'business_path') {
      root.querySelector('[data-error="external_approval_ref"]').hidden = true;
      root.querySelector('[name="external_approval_ref"]').removeAttribute('aria-invalid');
    }
  }
  function renderQueue() {
    if (!active()) return;
    root.querySelector('[data-queue]').innerHTML = `<div class="yx-intake-queue-summary"><span>${queue.length} 条合成记录</span><span>${queue.filter(canAccept).length} 条可演示受理</span></div><div class="yx-intake-table-wrap"><table><caption>合成需求受理队列，状态均为演示样例</caption><thead><tr><th scope="col">需求 / 样例标识</th><th scope="col">承接路径</th><th scope="col">当前状态</th><th scope="col">演示操作</th></tr></thead><tbody>${queue.map(record => `<tr data-record="${esc(record.id)}"><td><strong>${esc(record.title)}</strong><small>${esc(record.id)} · ${esc(record.organization_code)}</small>${record.business_path === 'bid' ? `<small>签报引用：${esc(record.external_approval_ref)}</small>` : ''}</td><td>${record.business_path === 'direct' ? '直接承接' : '投标项目'}</td><td><span class="yx-intake-state yx-intake-state-${esc(record.status)}">${esc(STATUS_LABELS[record.status])}</span></td><td>${canRegisterBidResult(record) ? `<button type="button" class="yx-intake-button yx-intake-secondary" data-action="open-bid" data-id="${esc(record.id)}" aria-expanded="false">登记结果（演示）</button><div class="yx-intake-bid-result" data-bid-editor="${esc(record.id)}" hidden><label>投标结果<select data-result aria-label="${esc(record.title)}投标结果"><option value="">请选择</option><option value="won">中标</option><option value="lost">未中标</option></select></label><label>结果依据引用<input data-result-ref type="text" placeholder="合成结果引用" autocomplete="off"></label><small>仅登记演示结果，不执行原签报审批。</small><button type="button" class="yx-intake-button yx-intake-primary" data-action="save-bid" data-id="${esc(record.id)}">确认登记（演示）</button></div>` : `<button type="button" class="yx-intake-button ${canAccept(record) ? 'yx-intake-primary' : 'yx-intake-secondary'}" data-action="accept" data-id="${esc(record.id)}" ${canAccept(record) ? '' : 'disabled'}>${record.status === 'accepted' ? '已受理' : record.status === 'lost' ? '已留档' : '受理（演示）'}</button>`}</td></tr>`).join('')}</tbody></table></div>`;
  }
  function onSubmit(event) {
    if (!active() || event.target !== form) return;
    event.preventDefault();
    if (!syntheticUser) { announce('请由演示宿主提供合成会话后再提交。', true); return; }
    const current = readDraft();
    const result = createSyntheticSubmission(current, `DEMO-NEW-${sequence + 1}`, filler);
    if (!result.ok) { showErrors(result.errors); announce('需求尚未提交，请检查标出的字段。', true); return; }
    const signature = JSON.stringify(current);
    if (submitted.has(signature)) { announce('这份合成需求已提交，未重复创建。'); return; }
    submitted.add(signature);
    sequence += 1;
    queue.unshift(result.record);
    showErrors();
    renderQueue();
    announce(current.business_path === 'direct' ? '合成需求已进入“负责人待审”。仍需负责人、BP 依次审批，当前不可接单。' : '合成需求已进入“投标待结果”。原办公签报仍在原系统完成。');
  }
  function onClick(event) {
    if (!active()) return;
    const button = event.target.closest('[data-action]');
    if (!button || !root.contains(button) || button.disabled) return;
    const action = button.dataset.action;
    if (action === 'sample' || (action === 'restore' && savedDraft)) {
      draft = action === 'restore' ? { ...savedDraft } : createDraft({ title: '合成需求 · 培训表达练习', business_path: 'direct', organization_code: 'DEMO-ORG-A', internal_contact_code: 'DEMO-INTERNAL-A', customer_contact_code: 'DEMO-CUSTOMER-A', category_text: '通用能力', delivery_mode_text: '线下', period_text: '待协调', duration_minutes: '50', participant_count: '24', objectives: '合成目标：练习清晰表达与小组交流。' });
      for (const [key, value] of Object.entries(draft)) {
        const inputs = form.querySelectorAll(`[name="${key}"]`);
        inputs.forEach(input => { if (input.type === 'radio') input.checked = input.value === value; else input.value = value; });
      }
      updatePath();
      showErrors();
      announce(action === 'restore' ? '已恢复本次页面中保存的草稿。' : '已填入合成样例。可修改字段、切换路径，或演示提交。');
      return;
    }
    if (action === 'save') {
      const result = validateDraft(readDraft(), 'draft');
      showErrors(result.errors);
      if (!result.valid) { announce('草稿未保存，请检查数字和日期。', true); return; }
      savedDraft = { ...result.draft };
      root.querySelector('[data-action="restore"]').hidden = false;
      root.querySelector('[data-draft-note]').textContent = `草稿已保留在本次页面中${savedDraft.title ? `：${savedDraft.title}` : '（尚未填写主题）'}。刷新即清除。`;
      announce('合成草稿已保存到本次页面。未发起申请或审批。');
      return;
    }
    const index = queue.findIndex(record => record.id === button.dataset.id);
    if (index < 0) return;
    const record = queue[index];
    if (action === 'open-bid' && canRegisterBidResult(record)) {
      const editor = button.closest('td').querySelector('[data-bid-editor]');
      editor.hidden = !editor.hidden;
      button.setAttribute('aria-expanded', String(!editor.hidden));
      if (!editor.hidden) editor.querySelector('select').focus();
      return;
    }
    let result;
    if (action === 'accept') result = acceptSynthetic(record);
    if (action === 'save-bid') {
      const editor = button.closest('[data-bid-editor]');
      result = registerSyntheticBidResult(record, editor.querySelector('[data-result]').value, editor.querySelector('[data-result-ref]').value);
    }
    if (!result) return;
    if (!result.ok) { announce(result.error, true); return; }
    queue[index] = result.record;
    renderQueue();
    announce(action === 'accept' ? `“${record.title}”已演示受理，不可重复接单。` : result.record.status === 'won' ? '已登记合成中标结果，现在可演示团队受理。' : '已登记合成未中标结果，记录留档，不进入受理。');
  }
}
