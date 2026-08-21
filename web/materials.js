(function () {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const icon = (name) => `<i data-lucide="${esc(name)}" aria-hidden="true"></i>`;
  const state = { items: [], admin: false, category: '全部资料', query: '', maxBytes: 100 * 1024 * 1024, extensions: ['pdf', 'doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx', 'zip'] };
  let toastTimer = null;

  function refreshIcons(root = document) {
    if (!window.lucide) return;
    try { window.lucide.createIcons({ root, attrs: { 'stroke-width': 1.75 } }); } catch (error) {}
  }

  async function request(path, options = {}) {
    let response;
    try {
      response = await fetch('/api/materials' + path, { credentials: 'same-origin', ...options });
    } catch (error) {
      throw new Error(navigator.onLine ? '资料服务暂时不可用，请稍后重试' : '网络已断开，请恢复连接后重试');
    }
    const raw = await response.text();
    let payload;
    try { payload = JSON.parse(raw); } catch (error) { throw new Error('资料服务返回了无法识别的数据'); }
    if (!response.ok || payload.code !== 0) throw new Error(payload.msg || '操作失败');
    return payload.data;
  }

  function toast(message, error = false) {
    const box = $('#material-toast');
    clearTimeout(toastTimer);
    box.hidden = false;
    box.className = 'material-toast' + (error ? ' error' : '');
    box.innerHTML = `${icon(error ? 'circle-alert' : 'circle-check')}<span>${esc(message)}</span>`;
    refreshIcons(box);
    toastTimer = setTimeout(() => { box.hidden = true; }, 3600);
  }

  function sizeLabel(bytes) {
    const value = Number(bytes || 0);
    if (value >= 1024 * 1024 * 1024) return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
    if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(value >= 10 * 1024 * 1024 ? 0 : 1)} MB`;
    if (value >= 1024) return `${Math.ceil(value / 1024)} KB`;
    return `${value} B`;
  }

  function dateLabel(value) {
    if (!value) return '刚刚更新';
    const date = new Date(String(value).replace(' ', 'T'));
    if (Number.isNaN(date.getTime())) return String(value).slice(0, 10);
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(date);
  }

  function extensionOf(fileName) {
    const match = String(fileName || '').toLowerCase().match(/\.([a-z0-9]+)$/);
    return match ? match[1] : '';
  }

  function fileIcon(fileName) {
    const extension = extensionOf(fileName);
    if (extension === 'pdf') return 'file-text';
    if (['ppt', 'pptx'].includes(extension)) return 'presentation';
    if (['xls', 'xlsx'].includes(extension)) return 'sheet';
    if (extension === 'zip') return 'file-archive';
    return 'file-type-2';
  }

  function visibleItems() {
    const query = state.query.trim().toLowerCase();
    return state.items.filter((item) => {
      const inCategory = state.category === '全部资料' || item.category === state.category;
      const content = `${item.title || ''} ${item.category || ''} ${item.summary || ''} ${item.file_name || ''}`.toLowerCase();
      return inCategory && (!query || content.includes(query));
    });
  }

  function renderCategories() {
    const categories = ['全部资料', ...new Set(state.items.map((item) => item.category || '综合学习包'))];
    if (!categories.includes(state.category)) state.category = '全部资料';
    const root = $('#material-categories');
    root.innerHTML = categories.map((category) => `<button type="button" class="${category === state.category ? 'active' : ''}" data-category="${esc(category)}" aria-pressed="${category === state.category}">${esc(category)}</button>`).join('');
    $$('[data-category]', root).forEach((button) => {
      button.onclick = () => { state.category = button.dataset.category; renderCategories(); renderGrid(); };
    });
  }

  function renderGrid() {
    const root = $('#material-grid');
    const items = visibleItems();
    root.setAttribute('aria-busy', 'false');
    $('#library-summary').textContent = state.admin ? `显示 ${items.length} / ${state.items.length} 份资料（含下架内容）` : `找到 ${items.length} 份可下载资料`;
    $('#material-count').textContent = state.items.filter((item) => item.status === undefined || item.status === '上架').length;
    $('#category-count').textContent = new Set(state.items.filter((item) => item.status === undefined || item.status === '上架').map((item) => item.category || '综合学习包')).size;
    if (!items.length) {
      root.innerHTML = `<div class="material-empty">${icon(state.query ? 'search-x' : 'library')}<b>${state.query ? '没有匹配的资料' : '资料正在整理中'}</b><p>${state.query ? '换个关键词或分类再试试。' : '管理员上架后会第一时间出现在这里。'}</p></div>`;
      refreshIcons(root);
      return;
    }
    root.innerHTML = items.map((item) => {
      const online = item.status === undefined || item.status === '上架';
      const extension = extensionOf(item.file_name).toUpperCase() || 'FILE';
      const actions = state.admin ? `
        <button type="button" data-edit="${item.id}" title="编辑资料" aria-label="编辑 ${esc(item.title)}">${icon('pencil')}</button>
        <button type="button" data-toggle="${item.id}" title="${online ? '下架' : '上架'}" aria-label="${online ? '下架' : '上架'} ${esc(item.title)}">${icon(online ? 'archive' : 'cloud-upload')}</button>
        <button type="button" class="danger" data-delete="${item.id}" title="删除资料" aria-label="删除 ${esc(item.title)}">${icon('trash-2')}</button>` : '';
      return `<article class="material-card ${online ? '' : 'is-offline'}" data-material-id="${item.id}">
        <div class="material-card-top"><span class="file-icon">${icon(fileIcon(item.file_name))}</span><div class="file-badges"><span>${esc(extension)}</span>${item.version ? `<span>${esc(item.version)}</span>` : ''}${state.admin ? `<span class="status-${online ? 'online' : 'offline'}">${online ? '已上架' : '已下架'}</span>` : ''}</div></div>
        <h3>${esc(item.title)}</h3>
        <p>${esc(item.summary || '该学习包暂未填写简介，可直接下载查看完整内容。')}</p>
        <div class="file-meta"><span>${icon('layers-3')}${esc(item.category || '综合学习包')}</span><span>${icon('hard-drive-download')}${sizeLabel(item.file_size)}</span><span>${icon('calendar-days')}${dateLabel(item.updated_at || item.created_at)}</span><span>${icon('download')}${Number(item.download_count || 0).toLocaleString('zh-CN')} 次</span></div>
        <div class="material-card-actions">
          ${online ? `<a class="download-button" href="/api/materials/download?id=${encodeURIComponent(item.id)}" data-download="${item.id}">${icon('download')}下载学习包</a>` : `<span class="download-disabled">当前已下架</span>`}
          ${actions}
        </div>
      </article>`;
    }).join('');
    $$('[data-download]', root).forEach((link) => {
      link.onclick = () => {
        const item = state.items.find((row) => String(row.id) === link.dataset.download);
        if (item) { item.download_count = Number(item.download_count || 0) + 1; setTimeout(renderGrid, 500); }
      };
    });
    if (state.admin) bindAdminActions(root);
    refreshIcons(root);
  }

  function bindAdminActions(root) {
    $$('[data-edit]', root).forEach((button) => button.onclick = () => openEdit(state.items.find((item) => String(item.id) === button.dataset.edit)));
    $$('[data-toggle]', root).forEach((button) => button.onclick = async () => {
      const item = state.items.find((row) => String(row.id) === button.dataset.toggle);
      if (!item) return;
      const next = item.status === '上架' ? '下架' : '上架';
      button.disabled = true;
      try {
        await request('/update', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: item.id, title: item.title, category: item.category, summary: item.summary, version: item.version, status: next }) });
        toast(`资料已${next}`);
        await loadItems();
      } catch (error) { toast(error.message, true); button.disabled = false; }
    });
    $$('[data-delete]', root).forEach((button) => button.onclick = () => {
      const item = state.items.find((row) => String(row.id) === button.dataset.delete);
      if (item) openConfirm(item);
    });
  }

  function modalShell(title, kicker, body, extraClass = '') {
    const root = $('#material-modal-root');
    root.innerHTML = `<div class="material-mask"><section class="material-dialog ${extraClass}" role="dialog" aria-modal="true" aria-labelledby="material-dialog-title" tabindex="-1">
      <header class="material-dialog-head"><div><span>${esc(kicker)}</span><h2 id="material-dialog-title">${esc(title)}</h2></div><button type="button" data-close aria-label="关闭">${icon('x')}</button></header>${body}</section></div>`;
    const mask = $('.material-mask', root);
    const previous = document.activeElement;
    const close = () => { root.innerHTML = ''; document.body.style.overflow = ''; if (previous && document.contains(previous)) previous.focus(); };
    document.body.style.overflow = 'hidden';
    $$('[data-close]', mask).forEach((button) => button.onclick = close);
    mask.onclick = (event) => { if (event.target === mask) $('.material-dialog', mask).focus(); };
    mask._close = close;
    mask.onkeydown = (event) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); return; }
      if (event.key !== 'Tab') return;
      const focusable = $$('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]', mask);
      if (!focusable.length) return;
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    refreshIcons(mask);
    requestAnimationFrame(() => ($('input, select, textarea, button', mask) || mask).focus());
    return mask;
  }

  function formHtml(item, withFile) {
    return `<form class="material-form" id="material-form">
      <div class="material-field wide"><label for="material-name">学习包名称 <em>*</em></label><input id="material-name" name="title" maxlength="120" required value="${esc(item?.title || '')}" placeholder="例如：客户服务沟通技巧工具包"></div>
      <div class="material-field"><label for="material-category">资料分类 <em>*</em></label><input id="material-category" name="category" maxlength="40" required value="${esc(item?.category || '综合学习包')}" placeholder="课程讲义 / 工具模板"></div>
      <div class="material-field"><label for="material-version">版本信息</label><input id="material-version" name="version" maxlength="32" value="${esc(item?.version || '')}" placeholder="例如：2026 版 / V2.1"></div>
      <div class="material-field wide"><label for="material-summary">资料简介</label><textarea id="material-summary" name="summary" maxlength="500" placeholder="简要说明内容、适用对象和使用方式">${esc(item?.summary || '')}</textarea><small>最多 500 个字符，访客会在资料卡片中看到前两行。</small></div>
      <div class="material-field"><label for="material-status">发布状态</label><select id="material-status" name="status"><option value="上架" ${!item || item.status === '上架' ? 'selected' : ''}>立即上架</option><option value="下架" ${item?.status === '下架' ? 'selected' : ''}>暂存为下架</option></select></div>
      ${withFile ? `<div class="material-field wide"><label>学习包文件 <em>*</em></label><label class="file-drop" id="file-drop">${icon('cloud-upload')}<b id="file-name">点击选择，或将文件拖到这里</b><span>支持 PDF、Word、PPT、Excel、ZIP · 单个不超过 ${sizeLabel(state.maxBytes)}</span><input id="material-file" type="file" required accept="${state.extensions.map((extension) => '.' + extension).join(',')}"></label></div>` : ''}
      <div class="material-dialog-foot"><button type="button" class="dialog-cancel" data-close>取消</button><button type="submit" class="dialog-submit">${icon(withFile ? 'cloud-upload' : 'save')}<span>${withFile ? '上传并保存' : '保存修改'}</span></button></div>
    </form>`;
  }

  function formValues(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    Object.keys(data).forEach((key) => { if (typeof data[key] === 'string') data[key] = data[key].trim(); });
    return data;
  }

  function base64Url(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = '';
    for (let index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function openUpload() {
    const mask = modalShell('添加学习包', '公开资料管理', formHtml(null, true));
    const form = $('#material-form', mask);
    const fileInput = $('#material-file', mask);
    const drop = $('#file-drop', mask);
    const fileName = $('#file-name', mask);
    const showFile = () => {
      const file = fileInput.files[0];
      fileName.textContent = file ? `${file.name} · ${sizeLabel(file.size)}` : '点击选择，或将文件拖到这里';
    };
    fileInput.onchange = showFile;
    ['dragenter', 'dragover'].forEach((type) => drop.addEventListener(type, (event) => { event.preventDefault(); drop.classList.add('dragging'); }));
    ['dragleave', 'drop'].forEach((type) => drop.addEventListener(type, (event) => { event.preventDefault(); drop.classList.remove('dragging'); }));
    drop.addEventListener('drop', (event) => {
      if (!event.dataTransfer?.files?.length) return;
      const transfer = new DataTransfer();
      transfer.items.add(event.dataTransfer.files[0]);
      fileInput.files = transfer.files;
      showFile();
    });
    form.onsubmit = async (event) => {
      event.preventDefault();
      const file = fileInput.files[0];
      if (!file) { toast('请选择需要上传的学习包文件', true); return; }
      if (file.size > state.maxBytes) { toast(`单个学习包不能超过 ${sizeLabel(state.maxBytes)}`, true); return; }
      const extension = extensionOf(file.name);
      if (!state.extensions.includes(extension)) { toast('文件格式不受支持', true); return; }
      const submit = $('.dialog-submit', form);
      submit.disabled = true;
      submit.innerHTML = `${icon('loader-circle')}<span>正在上传…</span>`;
      refreshIcons(submit);
      try {
        const metadata = { ...formValues(form), file_name: file.name };
        await request('/upload', { method: 'POST', headers: { 'Content-Type': file.type || 'application/octet-stream', 'X-Material-Meta': base64Url(JSON.stringify(metadata)) }, body: file });
        mask._close();
        toast('学习包已添加');
        await loadItems();
      } catch (error) {
        toast(error.message, true);
        submit.disabled = false;
        submit.innerHTML = `${icon('cloud-upload')}<span>上传并保存</span>`;
        refreshIcons(submit);
      }
    };
  }

  function openEdit(item) {
    if (!item) return;
    const mask = modalShell('编辑资料信息', '公开资料管理', formHtml(item, false));
    const form = $('#material-form', mask);
    form.onsubmit = async (event) => {
      event.preventDefault();
      const submit = $('.dialog-submit', form);
      submit.disabled = true;
      try {
        await request('/update', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: item.id, ...formValues(form) }) });
        mask._close();
        toast('资料信息已更新');
        await loadItems();
      } catch (error) { toast(error.message, true); submit.disabled = false; }
    };
  }

  function openConfirm(item) {
    const mask = modalShell('删除这份资料？', '不可撤销操作', `<div class="confirm-body"><span>${icon('trash-2')}</span><p>删除“${esc(item.title)}”后，外部下载链接会立即失效，服务器中的文件也会一并移除。</p></div><div class="material-dialog-foot"><button type="button" class="dialog-cancel" data-close>保留资料</button><button type="button" class="confirm-danger" id="confirm-delete">确认删除</button></div>`, 'confirm-dialog');
    $('#confirm-delete', mask).onclick = async () => {
      const button = $('#confirm-delete', mask);
      button.disabled = true;
      try {
        await request('/delete', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: item.id }) });
        mask._close();
        toast('资料已删除');
        await loadItems();
      } catch (error) { toast(error.message, true); button.disabled = false; }
    };
  }

  async function detectAdmin() {
    try {
      const response = await fetch('/api/me', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      const payload = await response.json();
      state.admin = response.ok && payload.code === 0 && payload.data?.role === 'admin';
    } catch (error) { state.admin = false; }
    $('#admin-actions').hidden = !state.admin;
  }

  async function loadItems() {
    try {
      if (state.admin) {
        const result = await request('/manage');
        state.items = result.items || [];
        state.maxBytes = Number(result.max_upload_bytes || state.maxBytes);
        state.extensions = Array.isArray(result.allowed_extensions) ? result.allowed_extensions : state.extensions;
      } else {
        state.items = await request('/public');
      }
      renderCategories();
      renderGrid();
    } catch (error) {
      $('#material-grid').setAttribute('aria-busy', 'false');
      $('#material-grid').innerHTML = `<div class="material-empty">${icon('cloud-alert')}<b>资料暂时无法加载</b><p>${esc(error.message)}</p></div>`;
      $('#library-summary').textContent = '同步失败';
      refreshIcons($('#material-grid'));
    }
  }

  async function boot() {
    refreshIcons();
    $('#material-search').oninput = (event) => { state.query = event.target.value; renderGrid(); };
    document.addEventListener('keydown', (event) => {
      if (event.key === '/' && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || '')) {
        event.preventDefault(); $('#material-search').focus();
      }
    });
    $('#add-material').onclick = openUpload;
    await detectAdmin();
    await loadItems();
  }

  boot();
})();
