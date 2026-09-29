import { preview, qualify } from './model.js';
import { createDemoCatalog, createDemoRequest } from './demo.js';

const LABELS = { certified: '已记录认证', not_certified: '未认证', unknown: '认证未知', revoked: '已撤销' };
const TABLES = { catalog: '目录', courses: '课程表', teachers: '教师表', certifications: '认证表', request: '判定条件' };
const PAGE_SIZE = 20;

/** Mount owns only its wrapper; no API calls, storage, session or global event mutation. */
export async function mount(root, context = {}) {
  if (!root?.ownerDocument) throw new TypeError('mount requires a DOM root');
  if (context.signal?.aborted) return () => {};
  const doc = root.ownerDocument;
  let disposed = false, revision = 0, current = null;
  const listeners = [], handlersByType = new Map();
  const node = (tag, text, cls) => {
    const el = doc.createElement(tag);
    if (text !== undefined && text !== null) el.textContent = String(text);
    if (cls) el.className = cls;
    return el;
  };
  const on = (el, type, fn) => {
    if (!handlersByType.has(type)) {
      const handlers = new WeakMap();
      const delegate = event => {
        if (disposed || context.signal?.aborted) return;
        const pending = [];
        for (let target = event.target; target; target = target.parentNode) {
          const handler = handlers.get(target);
          if (handler) { const value = handler(event); if (value?.then) pending.push(value); }
          if (target === wrap) break;
        }
        if (pending.length) return Promise.all(pending);
      };
      handlersByType.set(type, handlers);
      wrap.addEventListener(type, delegate);
      listeners.push(() => wrap.removeEventListener(type, delegate));
    }
    handlersByType.get(type).set(el, fn);
  };
  const wrap = node('section', null, 'yx-catalog');
  wrap.setAttribute('aria-label', '课程目录与认证资格');
  const stylesheet = node('link'); stylesheet.rel = 'stylesheet'; stylesheet.href = new URL('./style.css', import.meta.url).href;
  wrap.append(stylesheet);
  const heading = node('header', null, 'yx-catalog__header');
  const title = node('div'); title.append(node('p', 'M04 · 课程目录', 'yx-catalog__eyebrow'), node('h1', '每一门课，都有明确的认证依据'));
  heading.append(title, node('span', context.mode === 'demo' ? '合成样例演示' : '正式模式 · 未接入', 'yx-catalog__badge'));
  wrap.append(heading);
  const cleanup = () => {
    if (disposed) return;
    disposed = true; revision++; current = null;
    listeners.splice(0).forEach(remove => remove());
    handlersByType.clear();
    context.signal?.removeEventListener('abort', cleanup);
    wrap.remove();
  };
  context.signal?.addEventListener('abort', cleanup, { once: true });
  root.append(wrap);
  if (context.mode !== 'demo') {
    const notice = node('div', null, 'yx-catalog__notice'); notice.setAttribute('role', 'status');
    notice.append(node('h2', '课程目录与资格接口未接入'), node('p', '正式模式暂未连接已批准的数据接口，因此当前没有课程、教师或资格结果可展示。'), node('p', '需由宿主完成接口与权限接入后启用。本模块目前仅提供独立的合成样例演示。'));
    wrap.append(notice);
    return cleanup;
  }
  const disclosure = node('div', null, 'yx-catalog__notice');
  disclosure.append(node('strong', '当前为合成数据演示 · 不代表正式派课审批'), node('p', '这是材料结构预览，尚未核验认定来源的真实性。只按具体课程认证、判定日期及明确勾选的等级／城市核验。整体准入与业务排名规则待配置；结果保留输入顺序，不构成推荐排名。'), node('p', '全部内容仅在当前页面内存中处理。请仅载入合成编码样例，不输入姓名、电话、履历；source_ref 应填写编码来源引用。'));
  wrap.append(disclosure);

  const editorSection = node('section', null, 'yx-catalog__card');
  const editorHeading = node('div', null, 'yx-catalog__section-heading');
  editorHeading.append(node('h2', '1. 预览编码目录'), node('span', '不产生正式保存或提交', 'yx-catalog__muted'));
  editorSection.append(editorHeading);
  const editorLabel = node('label', null, 'yx-catalog__field');
  editorLabel.append(node('span', '粘贴或编辑目录 JSON'));
  const editor = node('textarea'); editor.rows = 11; editor.spellcheck = false; editor.setAttribute('aria-label', '目录 JSON'); editor.dataset.role = 'editor';
  editorLabel.append(editor); editorSection.append(editorLabel);
  const actions = node('div', null, 'yx-catalog__actions');
  const previewButton = node('button', '预览并校验', 'yx-catalog__primary'); previewButton.type = 'button'; previewButton.dataset.action = 'preview';
  const resetButton = node('button', '恢复合成样例'); resetButton.type = 'button';
  const uploadLabel = node('label', null, 'yx-catalog__upload'); uploadLabel.append(node('span', '或载入合成 JSON 文件'));
  const upload = node('input'); upload.type = 'file'; upload.accept = '.json,application/json'; upload.setAttribute('aria-label', '载入合成 JSON 文件'); upload.dataset.role = 'upload'; uploadLabel.append(upload);
  actions.append(previewButton, resetButton, uploadLabel); editorSection.append(actions);
  const status = node('p', '', 'yx-catalog__status'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite'); editorSection.append(status);
  const previewArea = node('div'); previewArea.dataset.role = 'preview'; editorSection.append(previewArea); wrap.append(editorSection);

  const matrixSection = node('section', null, 'yx-catalog__card'); matrixSection.append(node('h2', '2. 教师 × 具体课程目录'));
  matrixSection.append(node('p', '目录展示认证记录；是否生效须按下方日期核验。未提供日期边界不代表永久有效。', 'yx-catalog__muted'));
  const matrixArea = node('div'); matrixArea.dataset.role = 'matrix'; matrixSection.append(matrixArea); wrap.append(matrixSection);
  const requestSection = node('section', null, 'yx-catalog__card'); requestSection.append(node('h2', '3. 核验课程认证资格'));
  const requestArea = node('div'); requestArea.dataset.role = 'request'; requestSection.append(requestArea);
  const results = node('div'); results.dataset.role = 'results'; results.setAttribute('aria-live', 'polite'); requestSection.append(results); wrap.append(requestSection);

  function table(headers, rows) {
    const scroller = node('div', null, 'yx-catalog__table-scroll'); scroller.tabIndex = 0;
    const tbl = node('table'), thead = node('thead'), tr = node('tr');
    headers.forEach(value => { const th = node('th', value); th.scope = 'col'; tr.append(th); }); thead.append(tr); tbl.append(thead);
    const tbody = node('tbody');
    rows.forEach(values => { const row = node('tr'); values.forEach(value => { const td = node('td'); if (value?.nodeType) td.append(value); else td.textContent = String(value ?? ''); row.append(td); }); tbody.append(row); });
    tbl.append(tbody); scroller.append(tbl); return scroller;
  }
  function issuesView(issues) {
    const area = node('div');
    if (!issues.length) { area.append(node('p', '校验通过，没有错误或警告。', 'yx-catalog__success')); return area; }
    let page = 0;
    const render = () => {
      if (disposed) return;
      const shown = issues.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
      area.replaceChildren(table(['级别', '表 / 行', '字段', '说明'], shown.map(item => [
        node('span', item.severity === 'error' ? '错误' : '警告', item.severity === 'error' ? 'yx-catalog__error' : 'yx-catalog__warning'),
        `${TABLES[item.table] || item.table} / ${item.row === 0 ? '整体' : `第 ${item.row} 行`}`,
        item.field || '整行', `${item.message} [${item.code}]`
      ])));
      const nav = node('div', null, 'yx-catalog__pagination');
      nav.append(node('span', `校验问题 ${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + shown.length} / ${issues.length} 条 · 全部问题均已计入校验`));
      for (const [label, delta, disabled] of [['上一页问题', -1, page === 0], ['下一页问题', 1, (page + 1) * PAGE_SIZE >= issues.length]]) {
        const button = node('button', label); button.type = 'button'; button.disabled = disabled;
        on(button, 'click', () => { page += delta; render(); }); nav.append(button);
      }
      area.append(nav);
    };
    render();
    return area;
  }
  function invalidate(message) {
    current = null; revision++;
    status.textContent = message;
    previewArea.replaceChildren();
    matrixArea.replaceChildren(node('p', '请先通过目录预览。', 'yx-catalog__muted'));
    requestArea.replaceChildren(node('p', '目录校验通过后可选择具体课程与条件。', 'yx-catalog__muted'));
    results.replaceChildren();
  }
  function matrix(data) {
    let teacherPage = 0, coursePage = 0;
    const render = () => {
      if (disposed || !current) return;
      matrixArea.replaceChildren();
      const teachers = data.teachers.slice(teacherPage * PAGE_SIZE, (teacherPage + 1) * PAGE_SIZE);
      const courses = data.courses.slice(coursePage * 5, (coursePage + 1) * 5);
      if (!data.teachers.length || !data.courses.length) { matrixArea.append(node('p', '本批目录没有可组成矩阵的教师或课程。')); return; }
      const certs = new Map(data.certifications.map(row => [JSON.stringify([row.teacher_code, row.course_code]), row]));
      const rows = teachers.map(teacher => [teacher.teacher_code, teacher.teacher_level || '未知', teacher.city || '未知', ...courses.map(course => {
        const cert = certs.get(JSON.stringify([teacher.teacher_code, course.course_code]));
        const cell = node('div', null, 'yx-catalog__matrix-cell');
        if (!cert) cell.append(node('span', '缺少该课认证', 'yx-catalog__muted'));
        else {
          cell.append(node('strong', LABELS[cert.status], cert.status === 'certified' ? 'yx-catalog__success' : 'yx-catalog__warning'));
          cell.append(node('small', `${cert.valid_from || '未提供起日'} → ${cert.valid_to || '未提供止日'}`));
          cell.append(node('small', `来源：${cert.source_ref || '未提供'}`));
        }
        return cell;
      })]);
      matrixArea.append(table(['教师编码', '等级', '城市', ...courses.map(course => `${course.course_name}\n${course.course_code}${course.active ? '' : '（停用）'}`)], rows));
      const nav = node('div', null, 'yx-catalog__pagination');
      nav.append(node('span', `教师 ${teacherPage * PAGE_SIZE + 1}–${teacherPage * PAGE_SIZE + teachers.length} / ${data.teachers.length}；课程 ${coursePage * 5 + 1}–${coursePage * 5 + courses.length} / ${data.courses.length}`));
      const button = (label, disabled, change) => { const b = node('button', label); b.type = 'button'; b.disabled = disabled; on(b, 'click', () => { change(); render(); }); nav.append(b); };
      button('上一组教师', teacherPage === 0, () => teacherPage--); button('下一组教师', (teacherPage + 1) * PAGE_SIZE >= data.teachers.length, () => teacherPage++);
      button('上一组课程', coursePage === 0, () => coursePage--); button('下一组课程', (coursePage + 1) * 5 >= data.courses.length, () => coursePage++);
      matrixArea.append(nav);
    };
    render();
  }
  function resultGroup(titleText, rows) {
    const section = node('section', null, 'yx-catalog__result-group'); let page = 0;
    const render = () => {
      if (disposed) return;
      section.replaceChildren(node('h3', `${titleText} · ${rows.length} 人`));
      if (!rows.length) { section.append(node('p', '本次没有符合该分类的记录。', 'yx-catalog__muted')); return; }
      const shown = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
      section.append(table(['教师编码', '等级', '城市', '依据 / 缺口', '来源与有效期'], shown.map(row => [
        row.teacher_code, row.teacher_level || '未知', row.city || '未知', row.eligible ? '符合本次具体课程认证及已选限制' : row.reasons.map(r => `${r.message} [${r.code}]`).join('\n'),
        row.evidence ? `${row.evidence.source_ref || '未提供来源'}\n${row.evidence.valid_from || '未提供起日'} → ${row.evidence.valid_to || '未提供止日'}` : '无该课认证证据'
      ])));
      const nav = node('div', null, 'yx-catalog__pagination'); nav.append(node('span', `第 ${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + shown.length} 条，共 ${rows.length} 条 · 按输入顺序展示`));
      for (const [label, delta, disabled] of [['上一页', -1, page === 0], ['下一页', 1, (page + 1) * PAGE_SIZE >= rows.length]]) {
        const b = node('button', label); b.type = 'button'; b.disabled = disabled; on(b, 'click', () => { page += delta; render(); }); nav.append(b);
      }
      section.append(nav);
    };
    render(); return section;
  }
  function requestForm(data) {
    requestArea.replaceChildren(); results.replaceChildren();
    const form = node('form'), row = node('div', null, 'yx-catalog__form-row');
    const courseLabel = node('label', null, 'yx-catalog__field'); courseLabel.append(node('span', '具体课程'));
    const course = node('select'); course.setAttribute('aria-label', '具体课程'); course.required = true;
    data.courses.forEach(item => { const option = node('option', `${item.course_name} · ${item.course_code}${item.active ? '' : '（停用）'}`); option.value = item.course_code; course.append(option); });
    courseLabel.append(course); row.append(courseLabel);
    const dateLabel = node('label', null, 'yx-catalog__field'); dateLabel.append(node('span', '判定日期'));
    const date = node('input'); date.type = 'date'; date.required = true; date.value = createDemoRequest().as_of; date.setAttribute('aria-label', '判定日期'); dateLabel.append(date); row.append(dateLabel); form.append(row);
    form.append(node('p', '初始日期 2026-09-21 是明确指定的合成演示日期，可自行修改；不会读取机器当天日期。', 'yx-catalog__muted'));
    const chosen = { accepted_levels: new Set(), allowed_cities: new Set() };
    for (const [field, key, legend] of [['accepted_levels', 'teacher_level', '等级精确允许列表'], ['allowed_cities', 'city', '城市精确允许列表']]) {
      const fieldset = node('fieldset'); fieldset.append(node('legend', legend), node('p', '不勾选＝不添加此限制；勾选后按原值精确匹配。未知值不能通过对应限制。', 'yx-catalog__muted'));
      const options = node('div', null, 'yx-catalog__checks');
      [...new Set(data.teachers.map(t => t[key]).filter(Boolean))].forEach(value => {
        const label = node('label', null, 'yx-catalog__check'); const checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.value = value;
        on(checkbox, 'change', () => { checkbox.checked ? chosen[field].add(value) : chosen[field].delete(value); results.replaceChildren(node('p', '筛选已更改，请重新核验。', 'yx-catalog__muted')); });
        label.append(checkbox, node('span', value)); options.append(label);
      });
      if (!options.childNodes.length) options.append(node('p', '本批目录未提供可选择的已知值。', 'yx-catalog__muted'));
      fieldset.append(options); form.append(fieldset);
    }
    const run = node('button', '核验课程认证资格', 'yx-catalog__primary'); run.type = 'submit'; run.disabled = !data.courses.length; form.append(run);
    const evaluate = () => {
      if (!current || disposed) return;
      const request = { course_code: course.value, as_of: date.value, accepted_levels: [...chosen.accepted_levels], allowed_cities: [...chosen.allowed_cities] };
      const output = qualify(current, request); results.replaceChildren();
      if (!output.ready) { results.append(issuesView(output.issues)); return; }
      results.append(node('p', `目录 ${output.catalog_version} · 课程 ${output.course_code} · 判定日期 ${output.as_of}`, 'yx-catalog__result-context'));
      results.append(resultGroup('课程认证符合', output.eligible), resultGroup('资格缺口', output.gaps));
      results.append(node('p', '认定来源真实性尚未验证，整体准入规则与业务排名尚未配置；本次材料结构核验不是最终派课批准。', 'yx-catalog__muted'));
    };
    on(form, 'submit', event => { event.preventDefault(); evaluate(); });
    for (const el of [course, date]) on(el, 'change', () => results.replaceChildren(node('p', '判定条件已更改，请重新核验。', 'yx-catalog__muted')));
    requestArea.append(form); if (data.courses.length) evaluate();
  }
  function runPreview() {
    if (disposed) return;
    invalidate('正在校验本地输入……');
    let payload;
    try { payload = JSON.parse(editor.value); }
    catch (error) { status.textContent = `JSON 解析失败：${error.message}`; return; }
    const output = preview(payload);
    const errors = output.issues.filter(i => i.severity === 'error').length;
    const warnings = output.issues.length - errors;
    status.textContent = `${output.ready ? '目录校验通过' : '整批未通过，不接受部分记录'} · ${errors} 个错误 · ${warnings} 个警告`;
    const summary = node('div', null, 'yx-catalog__summary');
    for (const [label, count] of [['课程', output.counts.courses], ['教师', output.counts.teachers], ['认证记录', output.counts.certifications]]) {
      const item = node('div'); item.append(node('strong', count), node('span', label)); summary.append(item);
    }
    previewArea.append(summary, node('p', `目录版本：${output.catalog_version || '未提供有效版本'}`, 'yx-catalog__muted'), issuesView(output.issues));
    if (!output.ready) return;
    current = output.data; matrix(current); requestForm(current);
  }
  on(editor, 'input', () => invalidate('内容已更改，请重新预览；此前资格结果已失效。'));
  on(previewButton, 'click', runPreview);
  on(resetButton, 'click', () => { editor.value = JSON.stringify(createDemoCatalog(), null, 2); upload.value = ''; runPreview(); });
  on(upload, 'change', async () => {
    const file = upload.files?.[0]; if (!file) return;
    invalidate('正在读取本地合成 JSON 文件……'); const token = revision;
    if (file.size > 10 * 1024 * 1024) { status.textContent = '文件超过本地演示的 10 MB 限制，请缩小样例。'; return; }
    try {
      const content = await file.text();
      if (disposed || context.signal?.aborted || token !== revision) return;
      editor.value = content; runPreview();
    } catch (error) { if (!disposed && token === revision) status.textContent = `无法读取文件：${error.message}`; }
  });
  editor.value = JSON.stringify(createDemoCatalog(), null, 2); runPreview();
  return cleanup;
}
