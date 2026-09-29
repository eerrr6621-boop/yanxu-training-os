import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const HOST_STYLESHEETS = ['style.css', 'studio.css', 'ledger.css', 'v10.css', 'v13.css'];
const escapeAttribute = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));

// 内部预览借用原系统弹窗外壳；正式宿主直接调用模块 mount。
export function createPreviewHtml(source, style, stylesheetUrls = HOST_STYLESHEETS.map(name => `../../${name}`)) {
  const links = stylesheetUrls.map(url => `<link rel="stylesheet" href="${escapeAttribute(url)}">`).join('');
  const safeSource = source.replace(/<\/script/gi, '<\\/script');
  const safeStyle = style.replace(/<\/style/gi, '<\\/style');
  return '<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>内部验证 · 原系统评分导入弹窗</title>' + links + '<style>' + safeStyle + '</style></head><body class="yx-v13 modal-open"><div class="mask"><div class="modal wide" role="dialog" aria-modal="true" aria-labelledby="preview-title"><div class="modal-head"><div><span class="modal-kicker">内部合成测试 · 沿用原系统控件</span><h3 id="preview-title">导入评分 Excel</h3></div></div><div class="modal-body"><main id="m07-preview"></main></div></div></div><script type="module">\n' + safeSource + '\nmount(document.getElementById("m07-preview"),{mode:"demo"});\n</script></body></html>\n';
}

export function buildPreview(moduleDir) {
  const source = fs.readFileSync(path.join(moduleDir, 'index.js'), 'utf8');
  const style = fs.readFileSync(path.join(moduleDir, 'styles.css'), 'utf8');
  fs.writeFileSync(path.join(moduleDir, 'preview.html'), createPreviewHtml(source, style));
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const moduleDir = path.resolve(process.env.M07_MODULE_DIR || path.join(scriptDir, '../web/modules/survey-results'));
  buildPreview(moduleDir);
  console.log('M07 internal preview rebuilt from the XLSX module; stylesheet links are relative to the existing host.');
}
