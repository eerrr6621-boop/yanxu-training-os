/** Browser-safe, dependency-free Word export for synthetic M08 demonstrations. */
const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const STATUS = Object.freeze({ DRAFT: '草稿', IN_REVIEW: '复核中', RETURNED: '已退回', APPROVED: '复核通过' });
const encoder = new TextEncoder();

function text(value, label, max = 12000, required = false) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) throw new Error(`${label}无效`);
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/u.test(value)) throw new Error(`${label}包含不支持的控制字符`);
  for (let at = 0; at < value.length; at++) {
    const unit = value.charCodeAt(at);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(++at);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error(`${label}包含无效 Unicode 字符`);
    } else if (unit >= 0xdc00 && unit <= 0xdfff) throw new Error(`${label}包含无效 Unicode 字符`);
  }
  return value;
}
function integer(value, label, min = 0) {
  if (!Number.isSafeInteger(value) || value < min) throw new Error(`${label}无效`);
  return value;
}
function code(value, label) {
  text(value, label, 64, true);
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value)) throw new Error(`${label}必须使用编码`);
  return value;
}
function date(value, label) {
  text(value, label, 10, true);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(`${value}T00:00:00Z`)) || new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) !== value) throw new Error(`${label}无效`);
  return value;
}
function esc(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}
function paragraph(value, style = '', pageBreakBefore = false) {
  const runs = String(value).split(/\r\n|\r|\n/).map((line, index) => `${index ? '<w:r><w:br/></w:r>' : ''}<w:r><w:t xml:space="preserve">${esc(line)}</w:t></w:r>`).join('');
  return `<w:p>${style || pageBreakBefore ? `<w:pPr>${style ? `<w:pStyle w:val="${style}"/>` : ''}${pageBreakBefore ? '<w:pageBreakBefore/>' : ''}</w:pPr>` : ''}${runs}</w:p>`;
}

/** Validate and allowlist exported data. Unknown fields (including identity maps) are never serialized. */
export function validateDemoExport(dto) {
  if (!dto || dto.synthetic !== true) throw new Error('此导出器仅用于明确标记的合成演示');
  if (!Object.hasOwn(STATUS, dto.status)) throw new Error('总结状态无效');
  const p = dto.project;
  if (!p || !dto.content) throw new Error('缺少项目或总结内容');
  integer(p.projectId, '项目记录 ID', 1);
  code(p.projectCode, '项目编码');
  if (p.branchCode != null) code(p.branchCode, '分公司编码');
  if (!Array.isArray(p.courseCodes) || p.courseCodes.length > 100) throw new Error('课程编码无效');
  p.courseCodes.forEach((c) => code(c, '课程编码'));
  if (p.startDate != null) date(p.startDate, '开始日期');
  if (p.endDate != null) date(p.endDate, '结束日期');
  if (p.startDate != null && p.endDate != null && p.endDate < p.startDate) throw new Error('项目结束日期不能早于开始日期');
  if (p.participantCount != null) integer(p.participantCount, '参训人数');
  text(p.sourceVersion, '项目来源版本', 100, true);
  integer(dto.revision, '总结版本', 1);
  ['achievements', 'issues', 'nextSteps'].forEach((key) => text(dto.content[key], '总结正文', 12000));
  const article = dto.content.publicity;
  if (article != null) {
    if (typeof article !== 'object' || Array.isArray(article)) throw new Error('宣传稿内容无效');
    text(article.title, '宣传稿标题', 300);
    text(article.introduction, '宣传稿导语', 12000);
    if (!Array.isArray(article.sections) || article.sections.length > 12) throw new Error('宣传稿章节最多 12 个');
    for (const section of article.sections) {
      if (!section || typeof section !== 'object' || Array.isArray(section)) throw new Error('宣传稿章节无效');
      text(section.heading, '章节标题', 100); text(section.body, '章节正文', 12000);
    }
    if (!Array.isArray(article.photoCaptions) || article.photoCaptions.length > 6) throw new Error('照片位置最多 6 处');
    article.photoCaptions.forEach((caption) => text(caption, '照片说明', 300));
  }
  text(dto.reviewNote ?? '', '复核意见', 4000);
  if (dto.status === 'RETURNED' && !dto.reviewNote?.trim()) throw new Error('退回版本必须保留退回说明');
  const decisions = dto.reviewDecisions ?? [];
  if (!Array.isArray(decisions) || decisions.length > 2) throw new Error('复核记录无效');
  const roles = new Set();
  for (const decision of decisions) {
    if (!decision || !['BRANCH', 'BP'].includes(decision.role) || roles.has(decision.role) || typeof decision.approved !== 'boolean') throw new Error('复核角色或记录无效');
    roles.add(decision.role);
    text(decision.note ?? '', '分角色复核意见', 4000);
    if (!decision.approved && !decision.note?.trim()) throw new Error('退回复核记录缺少说明');
  }
  if (dto.status === 'DRAFT' && decisions.length) throw new Error('草稿不可带入旧复核结论');
  if (dto.status === 'IN_REVIEW' && (decisions.some((d) => !d.approved) || decisions.length > 1)) throw new Error('复核中状态与复核记录不一致');
  if (dto.status === 'RETURNED' && (decisions.filter((d) => !d.approved).length !== 1 || decisions.at(-1)?.approved !== false)) throw new Error('退回状态与复核记录不一致');
  if (dto.status === 'APPROVED' && !(roles.has('BRANCH') && roles.has('BP') && decisions.every((d) => d.approved))) throw new Error('负责人和 BP 均通过后才可导出通过状态');
  if (dto.feedback != null) {
    const f = dto.feedback;
    if (f.projectId !== p.projectId) throw new Error('M07 汇总结果与项目不一致，禁止导出');
    integer(f.summaryId, 'M07 汇总记录 ID', 1);
    integer(f.revision, 'M07 汇总版本', 1);
    text(f.sourceLabel, 'M07 来源', 200, true);
    text(f.importedAt, '导入时间', 40, true);
    if (Number.isNaN(Date.parse(f.importedAt))) throw new Error('M07 导入时间无效');
    if (f.responseCount != null) integer(f.responseCount, '有效汇总份数');
    if (!Array.isArray(f.metrics) || f.metrics.length > 40) throw new Error('M07 指标无效');
    f.metrics.forEach((m) => { text(m.label, '指标名称', 100, true); text(m.displayValue, '指标展示值', 200, true); });
    text(f.highlights ?? '', 'M07 汇总说明', 6000);
  }
  return dto;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function header(length, entries) {
  const bytes = new Uint8Array(length), view = new DataView(bytes.buffer);
  for (const [offset, value, size] of entries) size === 2 ? view.setUint16(offset, value, true) : view.setUint32(offset, value, true);
  return bytes;
}
function concat(parts) {
  const output = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) { output.set(part, at); at += part.length; }
  return output;
}
/** ZIP STORE with a fixed DOS date for reproducible bytes; no compression dependencies. */
function zip(files) {
  const local = [], central = [];
  let offset = 0, centralSize = 0;
  for (const [path, value] of files) {
    const name = encoder.encode(path), data = encoder.encode(value), crc = crc32(data);
    const record = header(30, [[0, 0x04034b50, 4], [4, 20, 2], [6, 0x0800, 2], [12, 33, 2], [14, crc, 4], [18, data.length, 4], [22, data.length, 4], [26, name.length, 2]]);
    const directory = header(46, [[0, 0x02014b50, 4], [4, 20, 2], [6, 20, 2], [8, 0x0800, 2], [14, 33, 2], [16, crc, 4], [20, data.length, 4], [24, data.length, 4], [28, name.length, 2], [42, offset, 4]]);
    local.push(record, name, data); central.push(directory, name);
    offset += record.length + name.length + data.length;
    centralSize += directory.length + name.length;
  }
  return concat([...local, ...central, header(22, [[0, 0x06054b50, 4], [8, files.length, 2], [10, files.length, 2], [12, centralSize, 4], [16, offset, 4]])]);
}

function photoTable(captions) {
  const rows = captions.map((caption, index) => `<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val="2000" w:hRule="atLeast"/></w:trPr><w:tc><w:tcPr><w:tcW w:w="9746" w:type="dxa"/><w:vAlign w:val="center"/></w:tcPr>${paragraph(`照片位置 ${index + 1}`, 'PhotoPlaceholder')}${paragraph(caption || '照片说明未填写', 'PhotoCaption')}</w:tc></w:tr>`).join('');
  return `<w:tbl><w:tblPr><w:tblW w:w="9746" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders><w:top w:val="single" w:sz="4" w:color="B8B8B8"/><w:left w:val="single" w:sz="4" w:color="B8B8B8"/><w:bottom w:val="single" w:sz="4" w:color="B8B8B8"/><w:right w:val="single" w:sz="4" w:color="B8B8B8"/><w:insideH w:val="single" w:sz="4" w:color="B8B8B8"/></w:tblBorders><w:tblCellMar><w:top w:w="120" w:type="dxa"/><w:left w:w="180" w:type="dxa"/><w:bottom w:w="120" w:type="dxa"/><w:right w:w="180" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="9746"/></w:tblGrid>${rows}</w:tbl>`;
}

/** dto must describe a CURRENT SAVED revision. UI enforces saved-state selection. Returns real .docx bytes. */
export function buildDemoDocx(dto) {
  validateDemoExport(dto);
  const { project: p, feedback: f, content: c } = dto;
  const article = c.publicity;
  const body = [
    paragraph(article ? article.title || '培训宣传总结' : '培训项目总结', 'Title'),
    paragraph(`合成演示  ${p.projectCode}  V${dto.revision}  ${STATUS[dto.status]}`, 'Subtitle')
  ];
  if (article) {
    if (article.introduction) body.push(paragraph(article.introduction, 'ArticleBody'));
    article.sections.forEach((section, index) => {
      if (section.heading) body.push(paragraph(section.heading, 'Heading1'));
      else if (section.body) body.push(paragraph(`章节 ${index + 1}`, 'Heading1'));
      if (section.body) body.push(paragraph(section.body, 'ArticleBody'));
    });
    if (!article.introduction && !article.sections.some((section) => section.heading || section.body)) body.push(paragraph('本版本尚未填写宣传稿正文。'));
  } else {
    body.push(paragraph('培训成效', 'Heading1'), paragraph(c.achievements || '本版本未填写', 'ArticleBody'), paragraph('问题与改进', 'Heading1'), paragraph(c.issues || '本版本未填写', 'ArticleBody'), paragraph('后续计划', 'Heading1'), paragraph(c.nextSteps || '本版本未填写', 'ArticleBody'));
  }
  if (article?.photoCaptions.length) {
    body.push(paragraph('照片记录', 'Heading1', true), paragraph('以下为照片文字位置，按当前版本的照片说明排列。', 'AppendixBody'), photoTable(article.photoCaptions), paragraph('本演示仅保留位置，不接收或嵌入真实照片。', 'AppendixBody'));
  }
  body.push(paragraph('附录 来源与复核信息', 'Heading1', true), paragraph('项目事实', 'Heading2'), paragraph(`项目编码：${p.projectCode}    分公司编码：${p.branchCode ?? '未分配'}`, 'AppendixBody'), paragraph(`课程编码：${p.courseCodes.length ? p.courseCodes.join('、') : '未提供'}`, 'AppendixBody'), paragraph(`培训日期：${p.startDate ?? '来源未提供'} 至 ${p.endDate ?? '来源未提供'}    参训人数：${p.participantCount ?? '来源未提供'}`, 'AppendixBody'), paragraph(`项目事实来源版本：${p.sourceVersion}`, 'AppendixBody'));
  if (article && ['achievements', 'issues', 'nextSteps'].some((key) => c[key].trim())) {
    body.push(paragraph('原有总结内容', 'Heading2'));
    for (const [key, label] of [['achievements', '培训成效'], ['issues', '问题与改进'], ['nextSteps', '后续计划']]) if (c[key]) body.push(paragraph(`${label}：${c[key]}`, 'AppendixBody'));
  }
  body.push(paragraph('已汇总问卷结果', 'Heading2'));
  if (f) {
    body.push(paragraph(`来源：${f.sourceLabel}`, 'AppendixBody'), paragraph(`M07 汇总记录 ID：${f.summaryId}    来源版本：${f.revision}    导入时间：${f.importedAt}`, 'AppendixBody'), paragraph(`有效汇总份数：${f.responseCount == null ? '来源未提供' : f.responseCount}`, 'AppendixBody'));
    if (f.metrics.length) f.metrics.forEach((m) => body.push(paragraph(`${m.label}：${m.displayValue}`, 'AppendixBody')));
    else body.push(paragraph('来源未提供指标', 'AppendixBody'));
    if (f.highlights) body.push(paragraph(f.highlights, 'AppendixBody'));
    body.push(paragraph('上述结果直接引用已统计汇总，不采集个人答卷，不重新计算评分。', 'AppendixBody'));
  } else body.push(paragraph('尚未导入 M07 已汇总结果。本版本无相关数据，不以零值代替。', 'AppendixBody'));
  body.push(paragraph('复核记录', 'Heading2'), paragraph(`当前状态：${STATUS[dto.status]}    总结版本：${dto.revision}`, 'AppendixBody'), paragraph('分公司对接人填写，分公司负责人和 BP 复核。复核先后顺序待确认，演示不限制先后。', 'AppendixBody'));
  for (const role of ['BRANCH', 'BP']) {
    const decision = (dto.reviewDecisions ?? []).find((item) => item.role === role);
    body.push(paragraph(`${role === 'BRANCH' ? '分公司负责人' : 'BP'}：${decision ? decision.approved ? '已通过' : '已退回' : '尚未复核'}${decision?.note ? `；意见：${decision.note}` : ''}`, 'AppendixBody'));
  }
  if (dto.reviewNote) body.push(paragraph(`本次复核意见：${dto.reviewNote}`, 'AppendixBody'));
  body.push(paragraph('导出说明', 'Heading2'), paragraph('本文件全部项目及汇总数据为合成样例，仅演示当前已保存版本。正文结构已按提供样例调整，正式版式待确认。', 'AppendixBody'), paragraph('管理员账号导出，账号范围为教学研发团队成员和领导；本文件不代表正式账号权限验证。', 'AppendixBody'), paragraph('结构字段仅使用项目、分公司与课程编码，不包含实名映射或解码信息。自由填写正文仍需人工检查。', 'AppendixBody'));
  const document = `${XML}<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body.join('')}<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1080" w:right="1080" w:bottom="1080" w:left="1080" w:header="360" w:footer="360"/></w:sectPr></w:body></w:document>`;
  const font = '<w:rFonts w:ascii="Songti SC" w:hAnsi="Songti SC" w:eastAsia="Songti SC" w:cs="Songti SC"/>';
  const style = (id, name, paragraphProperties, runProperties = '') => `<w:style w:type="paragraph" w:styleId="${id}"><w:name w:val="${name}"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr>${paragraphProperties}</w:pPr><w:rPr>${font}<w:color w:val="000000"/>${runProperties}</w:rPr></w:style>`;
  const styles = `${XML}<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr>${font}<w:color w:val="000000"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="zh-CN" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="300" w:lineRule="auto"/><w:widowControl/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>${style('Title', 'Title', '<w:keepNext/><w:jc w:val="center"/><w:spacing w:before="0" w:after="180"/>', '<w:b/><w:sz w:val="38"/><w:szCs w:val="38"/>')}${style('Subtitle', 'Subtitle', '<w:keepNext/><w:jc w:val="center"/><w:spacing w:after="240"/>', '<w:sz w:val="18"/><w:szCs w:val="18"/>')}${style('ArticleBody', 'Article Body', '<w:jc w:val="both"/><w:ind w:firstLine="440"/><w:spacing w:after="130" w:line="330" w:lineRule="auto"/>')}${style('Heading1', 'heading 1', '<w:keepNext/><w:spacing w:before="200" w:after="100"/><w:outlineLvl w:val="0"/>', '<w:b/><w:sz w:val="26"/><w:szCs w:val="26"/>')}${style('Heading2', 'heading 2', '<w:keepNext/><w:spacing w:before="160" w:after="80"/><w:outlineLvl w:val="1"/>', '<w:b/><w:sz w:val="22"/><w:szCs w:val="22"/>')}${style('AppendixBody', 'Appendix Body', '<w:spacing w:after="75" w:line="270" w:lineRule="auto"/>', '<w:sz w:val="20"/><w:szCs w:val="20"/>')}${style('PhotoPlaceholder', 'Photo Placeholder', '<w:keepNext/><w:jc w:val="center"/><w:spacing w:after="100"/>', '<w:sz w:val="24"/><w:szCs w:val="24"/>')}${style('PhotoCaption', 'Photo Caption', '<w:jc w:val="center"/><w:spacing w:after="0"/>', '<w:sz w:val="22"/><w:szCs w:val="22"/>')}</w:styles>`;
  return zip([
    ['[Content_Types].xml', `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>`],
    ['_rels/.rels', `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`],
    ['word/document.xml', document],
    ['word/styles.xml', styles],
    ['word/_rels/document.xml.rels', `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`]
  ]);
}
