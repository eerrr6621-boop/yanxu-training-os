// Internal synthetic test UI only; formal UI uses the original project workspace and host controls.
import { buildDemoDocx, validateDemoExport } from './docx.js';

const LABELS = Object.freeze({ DRAFT: '草稿', IN_REVIEW: '复核中', RETURNED: '已退回', APPROVED: '复核通过' });
const FIELDS = Object.freeze(['achievements', 'issues', 'nextSteps']);
const SECTION_HEADINGS = Object.freeze(['理论筑基 靶向破题', '干货满满 方法落地', '互动共创 知行合一', '精心组织 全程保障', '学以致用 未来可期']);
const LEGACY_LABELS = Object.freeze({ achievements: '培训成效', issues: '问题与改进', nextSteps: '后续计划' });
function emptyPublicity() {
  return { title: '', introduction: '', sections: SECTION_HEADINGS.map((heading) => ({ heading, body: '' })), photoCaptions: ['课堂授课', '互动研讨', '培训合影'] };
}
function hasNarrative(content) {
  return FIELDS.some((field) => content[field]?.trim()) || !!content.publicity?.introduction.trim() || !!content.publicity?.sections.some((section) => section.body.trim());
}
const copy = (value) => JSON.parse(JSON.stringify(value));
function freeze(value) {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}
export function createDemoSummary() {
  return {
    project: { projectId: 8001, projectCode: 'DEMO-PRJ-008', branchCode: 'DEMO-BR-01', courseCodes: ['DEMO-COURSE-01'], startDate: '2026-09-14', endDate: '2026-09-15', participantCount: 24, sourceVersion: 'demo-project-v1' },
    feedback: { projectId: 8001, summaryId: 7001, revision: 1, sourceLabel: '合成演示已汇总结果', importedAt: '2026-09-16T02:00:00Z', responseCount: 22, metrics: [{ label: '整体满意度', displayValue: '96%' }, { label: '内容适用性', displayValue: '来源汇总为良好' }], highlights: '课程安排清晰，案例讨论便于理解（合成演示）。' },
    revision: 1, status: 'DRAFT',
    content: { achievements: '', issues: '', nextSteps: '', publicity: {
      title: '时间管理专题培训总结',
      introduction: '为帮助参训人员合理安排工作节奏、提升任务执行效率，项目 DEMO-PRJ-008 于 2026 年 9 月 14 日至 15 日开展时间管理专题培训，24 名学员参加。课程围绕目标拆解、优先级判断与计划执行展开，通过方法讲解、案例练习和小组交流，引导学员把所学方法转化为可执行的行动安排。',
      sections: [
        { heading: SECTION_HEADINGS[0], body: '培训从常见的时间分配问题切入，引导学员回顾日常任务安排，识别临时事项打断、任务优先级不清等情况。围绕目标与行动的关系，课程逐步梳理时间管理的基本思路，为后续练习建立共同基础。' },
        { heading: SECTION_HEADINGS[1], body: '课程结合合成工作情境，讲解任务清单、优先级排序与时间分块等方法。学员按照目标拆解任务，明确关键步骤和完成时点，并在练习中调整计划，使工具的使用与实际工作安排相衔接。' },
        { heading: SECTION_HEADINGS[2], body: '小组围绕多项任务同时到来的场景开展研讨，比较不同安排的取舍。通过同伴分享与集中交流，学员进一步理解计划需要兼顾重点任务和必要弹性，并形成各自的改进清单。' },
        { heading: SECTION_HEADINGS[3], body: '培训组织围绕课程节奏做好课前通知、材料准备与现场衔接，为授课、练习和交流预留相应时间。过程中及时收集共性疑问，配合课程安排进行回应，保障学习活动有序开展。' },
        { heading: SECTION_HEADINGS[4], body: '培训结束后，学员将结合自身工作选择可落实的改进事项，尝试建立每周计划与定期回顾习惯。项目后续拟通过内部交流分享应用情况，持续完善任务安排，让课堂所学在实践中得到检验。' }
      ],
      photoCaptions: ['课堂授课', '互动研讨', '培训合影']
    } },
    reviewNote: '', reviewDecisions: [], synthetic: true
  };
}

/** Local-only demo model. Saved snapshots and review events are immutable. No persistence or permissions. */
export function createDemoController(initial = createDemoSummary()) {
  validateDemoExport(initial);
  const base = freeze(copy(initial));
  let working = copy(base.content), current = null, history = Object.freeze([]), revision = 0;
  const editable = () => !current || current.status === 'DRAFT';
  const dirty = () => !current || JSON.stringify(current.content) !== JSON.stringify(working);
  function editArticle(change) {
    if (!editable()) throw new Error('当前版本已冻结，请先新建版本。');
    const next = copy(working), article = next.publicity ?? emptyPublicity();
    next.publicity = article;
    change(article);
    validateDemoExport({ ...copy(base), content: next });
    working = next;
  }
  function itemIndex(index, list) {
    if (!Number.isInteger(index) || index < 0 || index >= list.length) throw new Error('正文或照片位置不存在。');
    return index;
  }
  function record(dto, event) {
    current = freeze(copy(dto));
    history = Object.freeze([...history, Object.freeze({ event, snapshot: current })]);
    return current;
  }
  function assertSaved() {
    if (!current || dirty()) throw new Error('请先保存当前修改，再继续此操作。');
  }
  return {
    get current() { return current; },
    get history() { return history; },
    get working() { return freeze(copy(working)); },
    get facts() { return base.project; },
    get feedback() { return base.feedback; },
    get editable() { return editable(); },
    get dirty() { return dirty(); },
    edit(field, value) {
      if (!editable()) throw new Error('当前版本已冻结，请先新建版本。');
      if (!FIELDS.includes(field) || typeof value !== 'string' || value.length > 12000) throw new Error('总结字段无效或超过 12000 字。');
      const next = { ...copy(working), [field]: value };
      validateDemoExport({ ...copy(base), content: next });
      working = next;
    },
    editPublicity(field, value, index) {
      editArticle((article) => {
        if (typeof value !== 'string') throw new Error('宣传稿内容必须为文字。');
        if (field === 'title' || field === 'introduction') article[field] = value;
        else if (field === 'heading' || field === 'body') article.sections[itemIndex(index, article.sections)][field] = value;
        else if (field === 'photoCaption') article.photoCaptions[itemIndex(index, article.photoCaptions)] = value;
        else throw new Error('宣传稿字段不存在。');
      });
    },
    addPublicitySection() { editArticle((article) => { article.sections.push({ heading: '', body: '' }); }); },
    removePublicitySection(index) { editArticle((article) => { article.sections.splice(itemIndex(index, article.sections), 1); }); },
    addPhotoCaption() { editArticle((article) => { article.photoCaptions.push(''); }); },
    removePhotoCaption(index) { editArticle((article) => { article.photoCaptions.splice(itemIndex(index, article.photoCaptions), 1); }); },
    save() {
      if (!editable()) throw new Error('当前版本已冻结，请先新建版本。');
      if (!dirty()) throw new Error('没有需要保存的修改。');
      const next = { ...copy(base), revision: revision + 1, status: 'DRAFT', content: copy(working), reviewNote: '', reviewDecisions: [] };
      validateDemoExport(next);
      revision++;
      return record(next, '保存草稿');
    },
    submit() {
      assertSaved();
      if (current.status !== 'DRAFT') throw new Error('仅已保存草稿可提交复核。');
      if (!hasNarrative(current.content)) throw new Error('请填写导语、章节正文或原有总结内容，再提交复核。');
      return record({ ...copy(current), status: 'IN_REVIEW', reviewNote: '', reviewDecisions: [] }, '提交复核');
    },
    review(role, approved, note = '') {
      assertSaved();
      if (current.status !== 'IN_REVIEW') throw new Error('当前版本未处于复核中。');
      if (!['BRANCH', 'BP'].includes(role) || typeof approved !== 'boolean') throw new Error('复核角色或操作无效。');
      if (current.reviewDecisions.some((decision) => decision.role === role)) throw new Error('此角色已复核当前版本，不能重复处理。');
      if (typeof note !== 'string' || note.length > 4000) throw new Error('复核意见不能超过 4000 字。');
      if (!approved && !note.trim()) throw new Error('退回时必须填写说明。');
      const reviewDecisions = [...copy(current.reviewDecisions), { role, approved, note: note.trim() }];
      const status = !approved ? 'RETURNED' : reviewDecisions.length === 2 ? 'APPROVED' : 'IN_REVIEW';
      const next = { ...copy(current), status, reviewNote: note.trim(), reviewDecisions };
      validateDemoExport(next);
      return record(next, `${role === 'BRANCH' ? '负责人' : 'BP'}${approved ? '通过' : '退回修改'}`);
    },
    newRevision() {
      assertSaved();
      if (!['RETURNED', 'APPROVED'].includes(current.status)) throw new Error('仅退回或通过后的版本可由此新建草稿。');
      working = copy(current.content);
      revision++;
      return record({ ...copy(current), revision, status: 'DRAFT', reviewNote: '', reviewDecisions: [] }, '新建草稿版本');
    },
    exportCurrent() { assertSaved(); return buildDemoDocx(current); }
  };
}

function element(tag, className, value) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (value !== undefined) node.textContent = value;
  return node;
}
function button(action, value, kind = '') {
  const node = element('button', `yx-summary-button ${kind}`, value);
  node.type = 'button'; node.dataset.action = action;
  return node;
}
function section(title, detail) {
  const node = element('section', 'yx-summary-card');
  const heading = element('div', 'yx-summary-section-heading');
  heading.append(element('h2', '', title));
  if (detail) heading.append(element('p', 'yx-summary-muted', detail));
  node.append(heading);
  return node;
}

export async function mount(root, context = {}) {
  if (!root || typeof root.replaceChildren !== 'function') throw new Error('缺少模块容器');
  if (context.signal?.aborted) return () => {};
  const host = element('div', 'yx-summaries');
  const styles = document.createElement('link');
  styles.rel = 'stylesheet'; styles.href = new URL('./styles.css', import.meta.url).href;
  const content = element('div', 'yx-summary-content');
  host.append(styles, content); root.replaceChildren(host);
  let disposed = false, controller = null;
  const urls = new Set(), timers = new Set();
  const scope = new AbortController();
  const cleanup = () => {
    if (disposed) return;
    disposed = true; scope.abort();
    context.signal?.removeEventListener('abort', cleanup);
    for (const timer of timers) clearTimeout(timer);
    for (const url of urls) URL.revokeObjectURL(url);
    timers.clear(); urls.clear();
    if (host.parentNode === root) host.remove();
  };
  context.signal?.addEventListener('abort', cleanup, { once: true });
  const notify = (message, error = false) => {
    if (disposed) return;
    const area = host.querySelector('[data-notice]');
    if (area) { area.textContent = message; area.classList.toggle('yx-summary-error', error); }
    try { context.notify?.(message); } catch { /* Host notifications must not affect local state. */ }
  };
  if (context.mode !== 'demo') {
    const card = section('培训总结与 Word', 'M08 · 内部培训总结');
    card.append(element('p', 'yx-summary-live-state', '正式模式尚未接入'), element('p', 'yx-summary-muted', '项目事实、M07 已汇总结果、保存与复核权限需由统一宿主接口接入后启用。当前页面不加载合成项目。'));
    content.append(card);
    return cleanup;
  }
  controller = createDemoController();

  function updateEditingState() {
    if (disposed) return;
    const saved = controller.current, draft = controller.editable;
    const pendingReview = !!host.querySelector('[data-review-note]')?.value;
    const state = host.querySelector('[data-save-state]');
    if (state) state.textContent = !saved ? '尚未保存 · 先保存首个版本' : controller.dirty ? `有未保存修改 · 已保存版本 V${saved.revision}` : `当前已保存 V${saved.revision} · ${LABELS[saved.status]}`;
    const save = host.querySelector('[data-action="save"]');
    if (save) save.disabled = !draft || !controller.dirty;
    const submit = host.querySelector('[data-action="submit"]');
    if (submit) submit.disabled = !saved || controller.dirty || saved.status !== 'DRAFT';
    const download = host.querySelector('[data-action="export"]');
    if (download) download.disabled = !saved || controller.dirty || pendingReview;
    const guard = host.querySelector('[data-export-hint]');
    if (guard) guard.textContent = pendingReview ? '复核意见尚未提交，请先完成本次复核操作再导出。' : !saved || controller.dirty ? '请先保存当前内容，才能导出对应版本。' : `将导出 V${saved.revision}（${LABELS[saved.status]}）的已保存内容。`;
  }
  function render() {
    if (disposed) return;
    const current = controller.current, state = current?.status ?? 'DRAFT';
    content.replaceChildren();
    const header = element('header', 'yx-summary-header');
    const title = element('div');
    title.append(element('p', 'yx-summary-eyebrow', '研序 · M08'), element('h1', '', '培训宣传总结'), element('p', 'yx-summary-subtitle', '按样例组织导语、五个章节与照片说明，保存后统一导出 Word。'));
    header.append(title, element('span', 'yx-summary-demo-tag', '内部合成测试'));
    const banner = element('div', 'yx-summary-banner', '此页仅供内部逻辑测试，正式功能沿用原系统项目页面。仅使用合成样例。修改与版本只保留在本页内存，离开或刷新后清空；不向服务端提交，不代表正式权限规则。');
    const grid = element('div', 'yx-summary-grid');
    const main = element('div', 'yx-summary-main'), aside = element('aside', 'yx-summary-aside');
    const facts = section('项目事实', '自动带入 · 只读');
    const dl = element('dl', 'yx-summary-facts');
    const p = controller.facts;
    for (const [key, value] of [['项目编码', p.projectCode], ['分公司编码', p.branchCode ?? '未分配'], ['课程编码', p.courseCodes.length ? p.courseCodes.join('、') : '未提供'], ['培训日期', `${p.startDate ?? '来源未提供'} 至 ${p.endDate ?? '来源未提供'}`], ['参训人数', p.participantCount == null ? '来源未提供' : `${p.participantCount} 人`], ['来源版本', p.sourceVersion]]) {
      const pair = element('div'); pair.append(element('dt', '', key), element('dd', '', value)); dl.append(pair);
    }
    facts.append(dl);
    const editor = section('宣传稿正文', controller.editable ? '分公司对接人填写 · 标题、章节和照片说明均可调整' : '当前版本已冻结 · 复核内容只读');
    const working = controller.working, article = working.publicity ?? emptyPublicity();
    function articleField(labelText, field, value, maxLength, rows = 3, index) {
      const label = element('label', 'yx-summary-field');
      label.append(element('span', '', labelText));
      const input = element(rows === 1 ? 'input' : 'textarea');
      if (rows === 1) input.type = 'text'; else input.rows = rows;
      input.dataset.publicityField = field;
      if (index !== undefined) input.dataset.index = String(index);
      input.value = value; input.maxLength = maxLength; input.readOnly = !controller.editable;
      label.append(input); return label;
    }
    editor.append(articleField('宣传稿标题', 'title', article.title, 300, 1), articleField('导语 · 项目背景与培训概况', 'introduction', article.introduction, 12000, 4));
    article.sections.forEach((part, index) => {
      const block = element('div', 'yx-summary-article-section');
      const top = element('div', 'yx-summary-block-heading');
      top.append(element('strong', '', `章节 ${index + 1}`));
      if (controller.editable) { const remove = button('remove-section', '删除章节', 'yx-summary-small'); remove.dataset.index = String(index); top.append(remove); }
      block.append(top, articleField('章节标题', 'heading', part.heading, 100, 1, index), articleField('章节正文', 'body', part.body, 12000, 4, index));
      editor.append(block);
    });
    if (controller.editable) { const add = button('add-section', '添加章节'); add.disabled = article.sections.length >= 12; editor.append(add); }
    const photos = section('文末照片位置', '先保留照片位置和说明文字，后续按确认后的规则加入照片。');
    const photoList = element('div', 'yx-summary-photo-list');
    article.photoCaptions.forEach((caption, index) => {
      const slot = element('div', 'yx-summary-photo-placeholder');
      const visual = element('div', 'yx-summary-photo-slot', `照片位置 ${index + 1}`);
      slot.append(visual, articleField('照片说明', 'photoCaption', caption, 300, 1, index));
      if (controller.editable) { const remove = button('remove-photo', '删除位置', 'yx-summary-small'); remove.dataset.index = String(index); slot.append(remove); }
      photoList.append(slot);
    });
    photos.append(photoList);
    if (!article.photoCaptions.length) photos.append(element('p', 'yx-summary-muted', '尚未设置照片位置。'));
    if (controller.editable) { const add = button('add-photo', '添加照片位置'); add.disabled = article.photoCaptions.length >= 6; photos.append(add); }
    photos.append(element('p', 'yx-summary-muted yx-summary-photo-note', '当前默认三处，可按文章需要增减；此处仅填写说明文字，不接收照片文件。'));
    const legacy = element('details', 'yx-summary-legacy');
    legacy.append(element('summary', '', '原有总结内容'));
    legacy.append(element('p', 'yx-summary-muted', '已有的培训成效、问题与改进、后续计划继续保留；使用宣传稿结构时随附在 Word 附录中。'));
    for (const key of FIELDS) {
      const label = element('label', 'yx-summary-field'); label.append(element('span', '', LEGACY_LABELS[key]));
      const input = element('textarea'); input.dataset.field = key; input.value = working[key]; input.rows = 3; input.maxLength = 12000; input.readOnly = !controller.editable;
      label.append(input); legacy.append(label);
    }
    editor.append(legacy);
    const preview = section('成稿预览', '显示当前填写内容 · 导出以已保存版本为准');
    const previewBody = element('div', 'yx-summary-article-preview'); previewBody.dataset.articlePreview = ''; preview.append(previewBody);
    const feedback = section('M07 已汇总结果', '直接引用来源展示值 · 不发问卷，不重新计算评分');
    const f = controller.feedback;
    if (f && f.projectId === p.projectId) {
      const metrics = element('div', 'yx-summary-metrics');
      for (const metric of f.metrics) { const block = element('div'); block.append(element('strong', '', metric.displayValue), element('span', '', metric.label)); metrics.append(block); }
      feedback.append(metrics, element('p', 'yx-summary-result-text', f.highlights || '来源未提供汇总说明。'), element('p', 'yx-summary-source', `来源：${f.sourceLabel} · 记录 ${f.summaryId} · 版本 ${f.revision}\n导入：${f.importedAt} · 有效汇总份数：${f.responseCount ?? '来源未提供'}`));
    } else feedback.append(element('p', 'yx-summary-empty', f ? '结果与项目不一致，禁止引用。' : '尚未导入已汇总结果；暂无数据，不以零值代替。'));
    main.append(editor, photos, preview);

    const review = section('版本与复核', '分公司负责人和 BP 复核 · 顺序待确认，演示不限制先后');
    const status = element('div', `yx-summary-status yx-summary-status-${state.toLowerCase()}`);
    status.append(element('span', '', LABELS[state]), element('strong', '', current ? `V${current.revision}` : '未保存'));
    review.append(status);
    const saveState = element('p', 'yx-summary-save-state'); saveState.dataset.saveState = ''; review.append(saveState);
    const actions = element('div', 'yx-summary-actions');
    if (controller.editable) actions.append(button('save', '保存为新版本', 'yx-summary-primary'), button('submit', '演示提交复核'));
    if (state === 'IN_REVIEW') {
      const noteLabel = element('label', 'yx-summary-field'); noteLabel.append(element('span', '', '复核意见（退回必填）'));
      const input = element('textarea'); input.dataset.reviewNote = ''; input.rows = 3; input.maxLength = 4000; input.placeholder = '填写具体修改说明'; noteLabel.append(input); review.append(noteLabel);
      const roles = element('div', 'yx-summary-role-decisions');
      for (const role of ['BRANCH', 'BP']) {
        const decision = current.reviewDecisions.find((item) => item.role === role);
        const label = role === 'BRANCH' ? '负责人' : 'BP';
        const row = element('div', 'yx-summary-role-row');
        row.append(element('span', '', `${label}：${decision ? '已通过' : '待复核'}`));
        const pass = button('approve', `演示${label}通过`, 'yx-summary-primary'); pass.dataset.role = role; pass.disabled = !!decision;
        const back = button('return', `演示${label}退回`); back.dataset.role = role; back.disabled = !!decision;
        row.append(pass, back); roles.append(row);
      }
      review.append(roles);
    }
    if (['RETURNED', 'APPROVED'].includes(state)) actions.append(button('new', '新建草稿版本', 'yx-summary-primary'));
    if (current?.reviewNote) review.append(element('p', 'yx-summary-review-note', `复核意见：${current.reviewNote}`));
    if (state === 'IN_REVIEW') review.append(element('p', 'yx-summary-muted', '提交后已冻结内容。负责人和 BP 均通过后才完成复核；任何一方退回均需修改后重新复核。'));
    if (state === 'APPROVED') review.append(element('p', 'yx-summary-muted', '通过版本已锁定。后续修改需新建草稿，复核状态将重置。'));
    review.append(actions);
    const exporting = section('Word 导出', '管理员账号导出 · 教学研发团队成员和领导');
    const exportHint = element('p', 'yx-summary-muted'); exportHint.dataset.exportHint = '';
    exporting.append(exportHint, button('export', '管理员导出（演示）', 'yx-summary-export'), element('p', 'yx-summary-export-warning', '导出当前已保存版本。此处只演示管理员操作，正式账号权限尚未接入。正文仍需人工检查；请勿输入真实身份或敏感信息。内容结构已按提供样例调整，正式版式待确认。'));
    const history = section('版本记录', '仅本次演示会话 · 已保存内容不可覆盖');
    if (!controller.history.length) history.append(element('p', 'yx-summary-empty', '保存后将显示版本与复核记录。'));
    else {
      const list = element('ol', 'yx-summary-history');
      for (const item of [...controller.history].reverse()) {
        const row = element('li'), snap = item.snapshot;
        const label = element('div'); label.append(element('strong', '', `V${snap.revision} · ${item.event}`), element('span', '', LABELS[snap.status]));
        const detail = element('details'); detail.append(element('summary', '', '查看已保存内容'));
        if (snap.content.publicity) {
          const article = snap.content.publicity;
          detail.append(element('p', '', `标题：${article.title || '未填写'}`), element('p', '', `导语：${article.introduction || '未填写'}`));
          article.sections.forEach((part, index) => detail.append(element('p', '', `${part.heading || `章节 ${index + 1}`}：${part.body || '未填写'}`)));
          article.photoCaptions.forEach((caption, index) => detail.append(element('p', '', `照片 ${index + 1}：${caption || '未填写说明'}`)));
        }
        for (const key of FIELDS) if (snap.content[key]) detail.append(element('p', '', `${LEGACY_LABELS[key]}：${snap.content[key]}`));
        if (snap.reviewNote) detail.append(element('p', '', `复核意见：${snap.reviewNote}`));
        row.append(label, detail); list.append(row);
      }
      history.append(list);
    }
    aside.append(review, exporting, facts, feedback, history);
    grid.append(main, aside);
    const footer = element('footer', 'yx-summary-footer');
    const notice = element('p', 'yx-summary-notice'); notice.dataset.notice = ''; notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite');
    footer.append(notice, button('reset', '重置合成演示', 'yx-summary-reset'));
    content.append(header, banner, grid, footer);
    updateEditingState();
    updateArticlePreview();
  }
  function updateArticlePreview() {
    const preview = host.querySelector('[data-article-preview]');
    if (!preview) return;
    const article = controller.working.publicity ?? emptyPublicity();
    preview.replaceChildren(element('h2', '', article.title || '宣传稿标题'));
    if (article.introduction) preview.append(element('p', '', article.introduction));
    for (const part of article.sections) {
      if (part.heading) preview.append(element('h3', '', part.heading));
      if (part.body) preview.append(element('p', '', part.body));
    }
    if (article.photoCaptions.length) {
      preview.append(element('h3', '', '照片记录'));
      article.photoCaptions.forEach((caption, index) => preview.append(element('p', 'yx-summary-preview-photo', `照片位置 ${index + 1} · ${caption || '说明未填写'}`)));
    }
  }
  host.addEventListener('input', (event) => {
    const input = event.target;
    if (disposed) return;
    if (input.hasAttribute('data-review-note')) { updateEditingState(); return; }
    if (!input.dataset?.field && !input.dataset?.publicityField) return;
    try {
      if (input.dataset.publicityField) controller.editPublicity(input.dataset.publicityField, input.value, input.dataset.index === undefined ? undefined : Number(input.dataset.index));
      else controller.edit(input.dataset.field, input.value);
      updateEditingState(); updateArticlePreview();
    } catch (error) { notify(error.message, true); }
  }, { signal: scope.signal });
  host.addEventListener('click', (event) => {
    const target = event.target.closest('[data-action]');
    if (!target || !host.contains(target) || target.disabled || disposed) return;
    try {
      const action = target.dataset.action;
      if (action === 'export') {
        const bytes = controller.exportCurrent(), current = controller.current;
        const url = URL.createObjectURL(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }));
        urls.add(url);
        const link = element('a'); link.href = url; link.download = `合成演示_培训总结_${current.project.projectCode}_V${current.revision}_${current.status}.docx`; host.append(link); link.click(); link.remove();
        const timer = setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url); timers.delete(timer); }, 1000); timers.add(timer);
        notify(`已导出 V${current.revision}（${LABELS[current.status]}）的已保存内容。`); return;
      }
      let message;
      if (action === 'add-section') { controller.addPublicitySection(); message = '已添加章节，保存后计入当前版本。'; }
      else if (action === 'remove-section') { controller.removePublicitySection(Number(target.dataset.index)); message = '已删除当前章节，已保存历史保持不变。'; }
      else if (action === 'add-photo') { controller.addPhotoCaption(); message = '已添加照片位置，保存后计入当前版本。'; }
      else if (action === 'remove-photo') { controller.removePhotoCaption(Number(target.dataset.index)); message = '已删除当前照片位置，已保存历史保持不变。'; }
      else if (action === 'save') { const saved = controller.save(); message = `已保存草稿 V${saved.revision}，历史版本保持原内容。`; }
      else if (action === 'submit') { controller.submit(); message = '已演示提交复核，当前版本已冻结。'; }
      else if (action === 'approve' || action === 'return') {
        const saved = controller.review(target.dataset.role, action === 'approve', host.querySelector('[data-review-note]')?.value ?? '');
        message = action !== 'approve' ? '已演示退回；新建草稿版本后可继续修改。' : saved.status === 'APPROVED' ? '负责人和 BP 均已演示通过，当前版本已锁定。' : '已记录本方通过，等待另一方复核。';
      } else if (action === 'new') { const saved = controller.newRevision(); message = `已创建草稿 V${saved.revision}，复核状态已重置。`; }
      else if (action === 'reset') { controller = createDemoController(); message = '已重置合成演示，当前会话的版本记录已清空。'; }
      else return;
      render(); notify(message);
    } catch (error) { notify(error.message || '操作未完成，请检查当前内容。', true); }
  }, { signal: scope.signal });
  render();
  return cleanup;
}
