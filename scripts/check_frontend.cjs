#!/usr/bin/env node
'use strict';

/**
 * Yanxu frontend static regression gate (no third-party dependencies).
 *
 * Default: static-only, no network.
 *   node scripts/check_frontend.cjs
 *   node scripts/check_frontend.cjs --project-root /path/to/yanxu-training
 * Optional read-only deployment/preview verification:
 *   node scripts/check_frontend.cjs --base-url http://127.0.0.1:18087
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

function parseArgs(argv) {
  const result = { projectRoot: '', baseUrl: '', help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '--help' || item === '-h') result.help = true;
    else if (item === '--project-root' || item === '--root') {
      if (!argv[index + 1] || argv[index + 1].startsWith('-')) throw new Error(item + ' requires a directory');
      result.projectRoot = argv[++index];
    } else if (item === '--base-url') {
      if (!argv[index + 1] || argv[index + 1].startsWith('-')) throw new Error('--base-url requires a URL');
      result.baseUrl = argv[++index];
    }
    else if (!item.startsWith('-') && !result.projectRoot) result.projectRoot = item;
    else throw new Error('Unknown or incomplete argument: ' + item);
  }
  return result;
}

function usage() {
  return [
    'Usage: node check_frontend.cjs [project-root] [--base-url URL]',
    '       node check_frontend.cjs --project-root DIR [--base-url URL]',
    '',
    'Without --base-url this command performs no network requests.',
  ].join('\n');
}

function resolveRoots(input) {
  const candidate = path.resolve(input || process.cwd());
  const choices = [
    { projectRoot: candidate, webRoot: path.join(candidate, 'web') },
    { projectRoot: path.join(candidate, 'yanxu-training'), webRoot: path.join(candidate, 'yanxu-training', 'web') },
    { projectRoot: path.dirname(candidate), webRoot: candidate },
  ];
  const found = choices.find(({ webRoot }) =>
    fs.existsSync(path.join(webRoot, 'index.html')) &&
    fs.existsSync(path.join(webRoot, 'lucide.min.js')));
  if (!found) throw new Error('Could not locate web/index.html and web/lucide.min.js from ' + candidate);
  return found;
}

function walkTextFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) return ['vendor', 'node_modules', '.git'].includes(entry.name) ? [] : walkTextFiles(file);
    return ['.html', '.js', '.css'].includes(path.extname(entry.name).toLowerCase()) ? [file] : [];
  });
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function stringLiterals(expression) {
  const values = [];
  const regex = /(['"])([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\1/g;
  let match;
  while ((match = regex.exec(expression))) values.push(match[2]);
  return values;
}

function ternaryBranchLiterals(expression) {
  const values = [];
  const regex = /[?:]\s*(['"])([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\1/g;
  let match;
  while ((match = regex.exec(expression))) values.push(match[2]);
  return values;
}

function firstArgument(source, openIndex) {
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let index = openIndex + 1; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quote = char; continue; }
    if (char === '(' || char === '[' || char === '{') { depth += 1; continue; }
    if (char === ')' && depth === 0) return source.slice(openIndex + 1, index);
    if (char === ')' || char === ']' || char === '}') { depth -= 1; continue; }
    if (char === ',' && depth === 0) return source.slice(openIndex + 1, index);
  }
  return '';
}

function functionBody(source, name) {
  const marker = new RegExp('function\\s+' + name + '\\s*\\([^)]*\\)\\s*\\{').exec(source);
  if (!marker) return '';
  const start = marker.index + marker[0].length;
  let depth = 1;
  let quote = '';
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quote = char; continue; }
    if (char === '{') depth += 1;
    else if (char === '}' && --depth === 0) return source.slice(start, index);
  }
  return '';
}

function collectIcons(sourceByPath, webRoot) {
  const iconSources = new Map();
  const dynamicExpressions = new Set();
  function addIcon(name, file, origin) {
    if (!/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(name)) return;
    if (!iconSources.has(name)) iconSources.set(name, []);
    const label = path.relative(webRoot, file) + ':' + origin;
    if (!iconSources.get(name).includes(label)) iconSources.get(name).push(label);
  }

  for (const [file, source] of sourceByPath) {
    if (!['.html', '.js'].includes(path.extname(file).toLowerCase())) continue;
    for (const match of source.matchAll(/data-lucide\s*=\s*(['"])([a-z][a-z0-9-]*)\1/g)) {
      addIcon(match[2], file, 'data-lucide');
    }

    const callRegex = /\bicon\s*\(/g;
    let call;
    while ((call = callRegex.exec(source))) {
      const expression = firstArgument(source, call.index + call[0].length - 1).trim();
      const names = expression.includes('?') ? ternaryBranchLiterals(expression) : stringLiterals(expression);
      names.forEach((name) => addIcon(name, file, 'icon-call'));
      if (!names.length) dynamicExpressions.add(expression.replace(/\s+/g, ' ').slice(0, 120));
    }

    for (const match of source.matchAll(/\b(?:ico|icon|okIcon)\s*:\s*([^,\n}]+)/g)) {
      stringLiterals(match[1]).forEach((name) => addIcon(name, file, 'icon-property'));
    }

    for (const helper of ['actionIcon', 'fileIcon']) {
      const body = functionBody(source, helper);
      stringLiterals(body).forEach((name) => {
        if (helper === 'actionIcon') {
          const escapedName = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
          if (new RegExp("[:?]\\s*['\"]" + escapedName + "['\"]").test(body) || name === 'arrow-right') {
            addIcon(name, file, helper);
          }
        } else if (/^file-|^(presentation|sheet)$/.test(name)) addIcon(name, file, helper);
      });
    }

    for (const match of source.matchAll(/\b(?:const|let)\s+statusIcon\s*=\s*([^;]+);/g)) {
      ternaryBranchLiterals(match[1]).forEach((name) => addIcon(name, file, 'statusIcon'));
    }
  }

  return { iconSources, dynamicExpressions };
}

function inspectLucide(webRoot, sourceByPath) {
  const sandbox = {};
  vm.runInNewContext(fs.readFileSync(path.join(webRoot, 'lucide.min.js'), 'utf8'), sandbox, { timeout: 5000 });
  const library = sandbox.lucide && (sandbox.lucide.icons || sandbox.lucide);
  if (!library || typeof library !== 'object') throw new Error('Local Lucide bundle did not expose an icon dictionary');
  const toPascal = (name) => name.split('-').map((part) => part ? part[0].toUpperCase() + part.slice(1) : '').join('');
  const { iconSources, dynamicExpressions } = collectIcons(sourceByPath, webRoot);
  const names = [...iconSources.keys()].sort();
  const invalid = names.filter((name) => !library[toPascal(name)]).map((name) => ({ name, sources: iconSources.get(name) }));
  const expectedDynamic = new Set([
    'a.icon || actionIcon(a.l)',
    'fileIcon(item.file.name)',
    'fileIcon(item.file_name)',
    'item.ico',
    'n.ico',
    'statusIcon',
    'step.ico',
    'tabItem.icon',
  ]);
  const unexpectedDynamic = [...dynamicExpressions].filter((value) => !expectedDynamic.has(value)).sort();
  return {
    explicit_count: names.length,
    library_count: Object.keys(library).length,
    invalid,
    dynamic_expressions: [...dynamicExpressions].sort(),
    unexpected_dynamic: unexpectedDynamic,
  };
}

function inspectScripts(files, sourceByPath, webRoot) {
  const errors = [];
  let externalCount = 0;
  let inlineCount = 0;
  for (const file of files.filter((item) => path.extname(item).toLowerCase() === '.js')) {
    if (['lucide.min.js', 'echarts.min.js'].includes(path.basename(file))) continue;
    externalCount += 1;
    const checked = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
    if (checked.status !== 0) errors.push({ file: path.relative(webRoot, file), detail: (checked.stderr || checked.stdout || 'syntax error').trim().slice(0, 500) });
  }
  for (const file of files.filter((item) => path.extname(item).toLowerCase() === '.html')) {
    const source = sourceByPath.get(file);
    const blocks = [...source.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)];
    blocks.forEach((match, index) => {
      if (!match[1].trim()) return;
      inlineCount += 1;
      try { new vm.Script(match[1], { filename: path.relative(webRoot, file) + ':inline-' + (index + 1) }); }
      catch (error) { errors.push({ file: path.relative(webRoot, file), detail: String(error.message || error).slice(0, 500) }); }
    });
  }
  return { checked: externalCount + inlineCount, external_count: externalCount, inline_count: inlineCount, errors };
}

function htmlMounts(source) {
  const values = [];
  for (const tag of source.matchAll(/<(?:link|script|img)\b[^>]*>/gi)) {
    const attr = /\b(?:href|src)="([^"]+)"/i.exec(tag[0]);
    if (attr && attr[1].startsWith('/')) values.push(attr[1]);
  }
  return values;
}

function inspectHtml(files, sourceByPath, webRoot) {
  const htmlFiles = files.filter((file) => path.dirname(file) === webRoot && path.extname(file).toLowerCase() === '.html').sort();
  const pages = {};
  const issues = [];
  const versionsByPath = new Map();
  const requiredCommon = ['/assets/yx-mark-v13.png', '/v13.css', '/lucide.min.js'];
  for (const file of htmlFiles) {
    const name = path.basename(file);
    const source = sourceByPath.get(file);
    const build = /<html[^>]*\bdata-build="([^"]+)"/i.exec(source)?.[1] || '';
    const bodyClass = /<body[^>]*\bclass="([^"]+)"/i.exec(source)?.[1] || '';
    const mounts = htmlMounts(source);
    pages[name] = { build, body_class: bodyClass, mounts };
    if (!build) issues.push(name + ': missing html[data-build]');
    if (!bodyClass.split(/\s+/).includes('yx-v13')) issues.push(name + ': missing body.yx-v13');
    for (const required of requiredCommon) {
      if (!mounts.some((value) => value.split('?')[0] === required)) issues.push(name + ': missing ' + required);
    }
    for (const value of mounts) {
      const resourcePath = value.split('?')[0];
      if (!versionsByPath.has(resourcePath)) versionsByPath.set(resourcePath, new Map());
      if (!versionsByPath.get(resourcePath).has(name)) versionsByPath.get(resourcePath).set(name, new Set());
      versionsByPath.get(resourcePath).get(name).add(value);
    }
  }
  const builds = new Set(Object.values(pages).map((page) => page.build).filter(Boolean));
  if (builds.size !== 1) issues.push('top-level HTML data-build values differ');
  for (const [resourcePath, perPage] of versionsByPath) {
    if (perPage.size < 2) continue;
    const variants = new Set([...perPage.values()].flatMap((set) => [...set]));
    if (variants.size > 1) issues.push('shared mount has inconsistent cache versions: ' + resourcePath + ' => ' + [...variants].join(', '));
  }
  return { pages, issues };
}

function collectResources(sourceByPath) {
  const references = new Set();
  const add = (value) => {
    if (!value || value === '/' || value.startsWith('/api/')) return;
    if (/\.(?:html|css|js|png|jpe?g|gif|webp|svg|ico|woff2?)(?:\?|$)/i.test(value)) references.add(value);
  };
  for (const source of sourceByPath.values()) {
    for (const match of source.matchAll(/(?:href|src)\s*=\s*['"](\/[^'"#]+)['"]/g)) add(match[1]);
    // Generated business pictograms are held in a fixed source map, not literal img tags.
    for (const match of source.matchAll(/['"](\/assets\/icons\/[a-z0-9-]+\.png)['"]/g)) add(match[1]);
    for (const match of source.matchAll(/(?:url\(|import\(|script\.src\s*=\s*)\s*['"](\/[^'")]+)['"]/g)) add(match[1]);
  }
  return [...references].sort();
}

function localResourceFile(webRoot, reference) {
  const pathname = decodeURIComponent(new URL(reference, 'http://static.invalid/').pathname);
  const resolved = path.resolve(webRoot, '.' + pathname);
  if (resolved !== webRoot && !resolved.startsWith(webRoot + path.sep)) return '';
  return resolved;
}

function inspectLocalResources(webRoot, references) {
  const missing = [];
  const items = [];
  for (const reference of references) {
    const file = localResourceFile(webRoot, reference);
    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      missing.push(reference);
      continue;
    }
    const bytes = fs.readFileSync(file);
    items.push({ reference, file, bytes: bytes.length, sha256: sha256(bytes) });
  }
  return { items, missing };
}

function expectedMimes(reference) {
  const extension = path.extname(reference.split('?')[0]).toLowerCase();
  return ({
    '.html': ['text/html'], '.css': ['text/css'],
    '.js': ['text/javascript', 'application/javascript'],
    '.png': ['image/png'], '.jpg': ['image/jpeg'], '.jpeg': ['image/jpeg'],
    '.gif': ['image/gif'], '.webp': ['image/webp'], '.svg': ['image/svg+xml'],
    '.ico': ['image/'], '.woff': ['font/'], '.woff2': ['font/'],
  })[extension] || [];
}

function normalizeBaseUrl(raw) {
  if (!raw) return '';
  const value = new URL(raw);
  if (!['http:', 'https:'].includes(value.protocol) || value.username || value.password || value.search || value.hash) {
    throw new Error('--base-url must be a plain HTTP(S) URL without credentials, query, or fragment');
  }
  value.pathname = value.pathname.replace(/\/+$/, '') || '/';
  return value.href.replace(/\/+$/, '');
}

async function inspectHttp(baseUrl, localItems) {
  if (!baseUrl) return { enabled: false, checked: 0, failed: [], mime_mismatches: [], content_mismatches: [] };
  const failed = [];
  const mimeMismatches = [];
  const contentMismatches = [];
  for (const item of localItems) {
    const url = new URL(item.reference, baseUrl + '/');
    let response;
    let bytes;
    try {
      response = await fetch(url, { method: 'GET', headers: { Accept: '*/*' }, signal: AbortSignal.timeout(15000) });
      bytes = Buffer.from(await response.arrayBuffer());
    } catch (error) {
      failed.push({ reference: item.reference, status: 0, detail: String(error.message || error).slice(0, 200) });
      continue;
    }
    if (response.status !== 200 || bytes.length === 0) {
      failed.push({ reference: item.reference, status: response.status, bytes: bytes.length });
      continue;
    }
    const expected = expectedMimes(item.reference);
    const actual = String(response.headers.get('content-type') || '').toLowerCase();
    if (expected.length && !expected.some((value) => actual.startsWith(value))) {
      mimeMismatches.push({ reference: item.reference, expected, actual });
    }
    const servedHash = sha256(bytes);
    if (servedHash !== item.sha256) contentMismatches.push({ reference: item.reference, local_sha256: item.sha256, served_sha256: servedHash });
  }
  return { enabled: true, base_url: baseUrl, checked: localItems.length, failed, mime_mismatches: mimeMismatches, content_mismatches: contentMismatches };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) { console.log(usage()); return 0; }
  const { projectRoot, webRoot } = resolveRoots(args.projectRoot);
  const baseUrl = normalizeBaseUrl(args.baseUrl);
  const files = walkTextFiles(webRoot);
  const sourceByPath = new Map(files.map((file) => [file, fs.readFileSync(file, 'utf8')]));
  const lucide = inspectLucide(webRoot, sourceByPath);
  const scripts = inspectScripts(files, sourceByPath, webRoot);
  const html = inspectHtml(files, sourceByPath, webRoot);
  const references = collectResources(sourceByPath);
  for (const name of Object.keys(html.pages)) {
    const reference = '/' + name;
    if (!references.includes(reference)) references.push(reference);
  }
  references.sort();
  const local = inspectLocalResources(webRoot, references);
  const http = await inspectHttp(baseUrl, local.items);
  const ok = lucide.invalid.length === 0 && lucide.unexpected_dynamic.length === 0 &&
    scripts.errors.length === 0 && html.issues.length === 0 && local.missing.length === 0 &&
    http.failed.length === 0 && http.mime_mismatches.length === 0 && http.content_mismatches.length === 0;
  const report = {
    ok,
    mode: baseUrl ? 'static+read-only-http' : 'static-only',
    project_root: projectRoot,
    web_root: webRoot,
    lucide,
    scripts,
    html,
    resources: { referenced: references.length, local_missing: local.missing },
    http,
  };
  console.log(JSON.stringify(report, null, 2));
  return ok ? 0 : 1;
}

main().then((code) => { process.exitCode = code; }).catch((error) => {
  console.error(JSON.stringify({ ok: false, error: String(error.message || error).slice(0, 800) }, null, 2));
  process.exitCode = 2;
});
