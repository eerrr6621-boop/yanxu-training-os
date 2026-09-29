/** Local, dependency-free XLSX export for synthetic M06 results. Live exports use the Java service. */
const labels = {
  TEACHING: '授课日期', CONFIRMATION: '确认日期', PAYMENT: '支付日期',
  ESTIMATED: '预计课时', PLANNED: '计划课时', ACTUAL: '实际课时', PAYABLE: '计酬课时',
  HOURS: '课时', FEE: '课酬', COMPETITION: '并列同名次，后续跳位（1、2、2、4）',
  DENSE: '并列同名次，后续连续（1、2、2、3）', ORDINAL: '依次排名，同值按编码排序',
  CONFIRMED: '已确认', CANCELLED: '已取消', SUPERSEDED: '已被替代', ADJUSTMENT: '增减调整',
};
const s = value => ({ value: String(value ?? ''), type: 's' });
const n = value => {
  const text = String(value);
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) throw new Error('无效报表数值');
  const precision = text.replace(/[-.]/g, '').replace(/^0+/, '').replace(/0+$/, '').length;
  return { value: text, type: precision <= 15 ? 'n' : 's', number: true, integer: !text.includes('.') };
};
const xml = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));
const column = index => { let text = ''; for (let n = index + 1; n; n = Math.floor((n - 1) / 26)) text = String.fromCharCode(65 + (n - 1) % 26) + text; return text; };

export function reportPeriod(filter) {
  const m = /^(\d{4})-(\d{2})-01$/.exec(filter.start);
  if (m) {
    const year = Number(m[1]), month = Number(m[2]);
    const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    if (year >= 1 && month >= 1 && month <= 12 && filter.end === `${m[1]}-${m[2]}-${days[month - 1]}`) return `${m[1]}年${m[2]}月`;
  }
  return `${filter.start} 至 ${filter.end}`;
}

/** Shared table definitions also support artifact QA without recalculating any historical facts. */
export function monthlySheets(result, model) {
  if (!result || result.errors?.length || !result.rule?.version?.startsWith('DEMO-')) throw new Error('只能导出有效的合成演示报表');
  const { rule, filter, totals, details, rankings, organizations } = result;
  const d = value => n(model.decimal(value));
  const hour = value => value === null ? s('待补齐') : d(value);
  const period = reportPeriod(filter);
  const scope = filter.orgCodes.length ? [...filter.orgCodes].sort().join('、') : '全部演示机构';
  const intro = `合成演示 · ${period} · 按${labels[rule.dateBasis]} · ${labels[rule.hourBasis]} · ${result.currency}`;
  const table = (name, headers, widths, data, filterable = true) => ({ name, widths, filterable, rows: [[], [s(name === '月度概览' ? `研序培训月报｜${period}` : name)], [s(intro)], [], headers.map(s), ...data] });
  const overview = [
    [s('入选记录数'), n(totals.records), s('条'), s('包括本次计入的增减调整')],
    [s('课程数'), n(totals.courses.size), s('门'), s('相同课程只统计一次')],
    [s('讲师数'), n(rankings.length), s('位'), s('本次入选的不同讲师')],
    [s('机构数'), n(organizations.length), s('家'), s('本次入选的不同机构')],
    [s('历史课酬合计'), d(totals.fee), s(result.currency), s('直接汇总已保存金额，不重新套费率')],
    ...['ESTIMATED', 'PLANNED', 'ACTUAL', 'PAYABLE'].map(basis => [s(labels[basis]), hour(result.hourTotals[basis]), s('课时'), s('四类分别统计，不相加；1课时45分钟')]),
    [],
    [s('统计月份 / 范围'), s(period), s(''), s('起止日均计入')],
    [s('日期基准'), s(labels[rule.dateBasis]), s(''), s('')],
    [s('排名指标'), s(rule.rankMetric === 'HOURS' ? labels[rule.hourBasis] : '历史课酬'), s(''), s('降序排列')],
    [s('并列规则'), s(labels[rule.tieRule]), s(''), s('同指标时以讲师编码稳定展示')],
    [s('计入状态'), s(rule.includedStatuses.map(value => labels[value]).join('、')), s(''), s('取消及替换历史按当前所选口径处理')],
    [s('机构范围'), s(scope), s(''), s('全部范围仅指演示机构')],
    [s('统计规则版本'), s(rule.version), s(''), s('合成演示，不能作为正式结算依据')],
    [],
    [s('对账项目'), s('课时差额'), s('课酬差额'), s('记录数差额')],
    ...result.reconcile.map(row => [s({ DETAIL: '明细与概览', RANKING: '讲师汇总与明细', ORG_SUMMARY: '机构汇总与明细' }[row.type]), d(row.hourDifference), d(row.feeDifference), n(row.recordDifference)]),
    [],
    [s('文件用途'), s('统计结果快照'), s(''), s('切换月份后重新导出；本文件不重算费用')],
    [s('数据来源'), s('M06 合成演示明细'), s(''), s('不含真实讲师、机构或课程')],
  ];
  return [
    table('月度概览', ['项目', '数值 / 口径', '单位 / 差额', '说明'], [28, 48, 18, 55], overview, false),
    table('讲师排名', ['排名', '讲师编码', '记录数', '课程数', labels[rule.hourBasis], `历史课酬 / ${result.currency}`], [10, 26, 14, 14, 20, 24],
      rankings.map(row => [n(row.rank), s(row.code), n(row.records), n(row.courses.size), d(row.hours), d(row.fee)])),
    table('机构汇总', ['机构编码', '记录数', '课程数', labels[rule.hourBasis], `历史课酬 / ${result.currency}`], [28, 14, 14, 20, 24],
      organizations.map(row => [s(row.code), n(row.records), n(row.courses.size), d(row.hours), d(row.fee)])),
    table('明细对账', ['明细编码', '课程编码', '讲师编码', '机构编码', labels[rule.dateBasis], '状态', labels[rule.hourBasis], `历史课酬 / ${result.currency}`, '原费用版本'], [27, 25, 25, 28, 18, 17, 20, 25, 27],
      details.map(({ source: row, date, hours, fee }) => [s(row.recordCode), s(row.courseCode), s(row.teacherCode), s(row.orgCode), s(date), s(labels[row.status]), d(hours), d(fee), s(row.feeVersion)])),
  ];
}

function worksheet(sheet) {
  if (sheet.rows.length > 1048576) throw new Error('明细超过 Excel 单表行数上限');
  const lastCol = column(sheet.widths.length - 1), lastRow = Math.max(5, sheet.rows.length);
  const rows = sheet.rows.map((row, i) => {
    const rowNo = i + 1;
    const height = rowNo === 2 ? 30 : rowNo === 3 ? 30 : rowNo === 5 ? 28 : 26;
    const cells = row.map((cell, col) => {
      let style = rowNo === 2 ? 1 : rowNo === 3 ? 3 : rowNo === 5 ? 2 : cell.number ? cell.type === 's' ? 6 : cell.integer ? 5 : 4 : 0;
      const attrs = `r="${column(col)}${rowNo}" s="${style}"`;
      return cell.type === 'n' ? `<c ${attrs} t="n"><v>${xml(cell.value)}</v></c>` : `<c ${attrs} t="inlineStr"><is><t xml:space="preserve">${xml(cell.value)}</t></is></c>`;
    }).join('');
    return `<row r="${rowNo}" ht="${height}" customHeight="1">${cells}</row>`;
  }).join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" showGridLines="0">${sheet.filterable ? '<pane ySplit="5" topLeftCell="A6" activePane="bottomLeft" state="frozen"/>' : ''}</sheetView></sheetViews><cols>${sheet.widths.map((width, i) => `<col min="${i + 1}" max="${i + 1}" width="${width}" customWidth="1"/>`).join('')}</cols><sheetData>${rows}</sheetData>${sheet.filterable ? `<autoFilter ref="A5:${lastCol}${lastRow}"/>` : ''}<mergeCells count="2"><mergeCell ref="A2:${lastCol}2"/><mergeCell ref="A3:${lastCol}3"/></mergeCells><pageMargins left="0.3" right="0.3" top="0.5" bottom="0.5" header="0.2" footer="0.2"/></worksheet>`;
}
const styles = `<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00######"/></numFmts><fonts count="4"><font><sz val="11"/><name val="Arial"/><color rgb="FF253442"/></font><font><b/><sz val="16"/><name val="Arial"/><color rgb="FF253442"/></font><font><b/><sz val="11"/><name val="Arial"/><color rgb="FF235C55"/></font><font><sz val="10"/><name val="Arial"/><color rgb="FF62717F"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8F3F0"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" horizontal="center"/></xf><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf><xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;
const encoder = new TextEncoder();
function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0); } return (crc ^ 0xffffffff) >>> 0; }
function zip(files) {
  const chunks = [], central = []; let offset = 0;
  for (const [path, text] of files) {
    const name = encoder.encode(path), data = encoder.encode(text), crc = crc32(data);
    const local = new Uint8Array(30 + name.length), l = new DataView(local.buffer);
    l.setUint32(0, 0x04034b50, true); l.setUint16(4, 20, true); l.setUint16(6, 0x0800, true); l.setUint16(12, 33, true);
    l.setUint32(14, crc, true); l.setUint32(18, data.length, true); l.setUint32(22, data.length, true); l.setUint16(26, name.length, true); local.set(name, 30);
    chunks.push(local, data);
    const entry = new Uint8Array(46 + name.length), c = new DataView(entry.buffer);
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true); c.setUint16(14, 33, true);
    c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, offset, true); entry.set(name, 46);
    central.push(entry); offset += local.length + data.length;
  }
  const size = central.reduce((sum, part) => sum + part.length, 0), end = new Uint8Array(22), e = new DataView(end.buffer);
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true); e.setUint32(12, size, true); e.setUint32(16, offset, true);
  const output = new Uint8Array(offset + size + end.length); let cursor = 0;
  for (const part of [...chunks, ...central, end]) { output.set(part, cursor); cursor += part.length; }
  return output;
}

export async function exportMonthlyWorkbook(result, model) {
  const sheets = monthlySheets(result, model), rel = 'http://schemas.openxmlformats.org/package/2006/relationships', office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  return zip([
    ['[Content_Types].xml', `<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`],
    ['_rels/.rels', `<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="${rel}"><Relationship Id="rId1" Type="${office}/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
    ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="${office}"><sheets>${sheets.map((sheet, i) => `<sheet name="${xml(sheet.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`],
    ['xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="${rel}">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${office}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId5" Type="${office}/styles" Target="styles.xml"/></Relationships>`],
    ['xl/styles.xml', styles],
    ...sheets.map((sheet, i) => [`xl/worksheets/sheet${i + 1}.xml`, worksheet(sheet)]),
  ]);
}
