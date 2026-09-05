#!/usr/bin/env node
'use strict';

// Run with: node --experimental-vm-modules check-local-esm-closure.cjs WEB_ROOT [ENTRY_URL...]
// Syntax-parses and links modules only; never evaluates application/vendor code.
// Intended as a child-process helper for scripts/check_frontend.cjs.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

async function inspect(webRoot, entries = ['/login-motion.js']) {
  if (typeof vm.SourceTextModule !== 'function') throw new Error('Launch with --experimental-vm-modules');
  webRoot = fs.realpathSync(webRoot);
  const origin = 'https://local-static.invalid';
  const context = vm.createContext(Object.create(null));
  const modules = new Map();
  const items = new Map();
  const edges = [];
  const errors = [];
  const entryQueue = [...entries];
  const queued = new Set(entryQueue);

  function resolve(specifier, importer = '/') {
    if (!specifier.startsWith('/') && !specifier.startsWith('./') && !specifier.startsWith('../')) {
      throw new Error(`Non-local or bare module is not covered: ${specifier} imported by ${importer}`);
    }
    const url = new URL(specifier, new URL(importer, origin));
    if (url.origin !== origin) throw new Error(`External module: ${url.href}`);
    const pathname = decodeURIComponent(url.pathname);
    if (!/\.(?:m?js)$/i.test(pathname)) throw new Error(`Expected a JavaScript module: ${url.pathname}`);
    const file = path.resolve(webRoot, '.' + pathname);
    if (!file.startsWith(webRoot + path.sep)) throw new Error(`Module escapes web root: ${specifier}`);
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new Error(`Missing module: ${url.pathname} imported by ${importer}`);
    const realFile = fs.realpathSync(file);
    if (!realFile.startsWith(webRoot + path.sep)) throw new Error(`Module symlink escapes web root: ${specifier}`);
    return { reference: url.pathname + url.search, file: realFile };
  }

  function load(specifier, importer = '/') {
    const { reference, file } = resolve(specifier, importer);
    // The browser treats query variants as separate module identities.
    if (modules.has(reference)) return modules.get(reference);
    const bytes = fs.readFileSync(file);
    const source = bytes.toString('utf8');
    const module = new vm.SourceTextModule(source, { context, identifier: reference });
    modules.set(reference, module);
    items.set(reference, {
      reference, file, bytes: bytes.length,
      sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    });
    // Static imports/re-exports are handled by the real Node module linker below.
    // Literal dynamic imports are separate entry points (current app's pattern).
    // Computed import expressions need an explicit checked manifest entry.
    for (const match of source.matchAll(/\bimport\s*\(\s*(['"])((?:\\.|[^\\])*?)\1\s*(?=[,)])/g)) {
      const raw = match[2];
      if (/\\/.test(raw)) throw new Error(`Escaped dynamic import needs an explicit manifest: ${reference}`);
      const target = resolve(raw, reference).reference;
      edges.push({ importer: reference, specifier: raw, target, kind: 'dynamic-literal' });
      if (!queued.has(target)) { queued.add(target); entryQueue.push(target); }
    }
    return module;
  }

  for (let index = 0; index < entryQueue.length; index++) {
    const entry = entryQueue[index];
    try {
      const module = load(entry);
      if (module.status === 'unlinked') {
        await module.link((specifier, importer) => {
          const dependency = load(specifier, importer.identifier);
          edges.push({ importer: importer.identifier, specifier, target: dependency.identifier, kind: 'static' });
          return dependency;
        });
      }
      if (module.status === 'errored') throw module.error;
    } catch (error) {
      errors.push({ entry, message: String(error.message || error) });
    }
  }
  return {
    ok: errors.length === 0,
    mode: 'parse-and-link-only-no-evaluation-no-network',
    entries,
    module_count: modules.size,
    resources: [...items.values()].sort((a, b) => a.reference.localeCompare(b.reference)),
    edges,
    errors,
  };
}

if (require.main === module) {
  const [webRoot, ...entries] = process.argv.slice(2);
  inspect(webRoot, entries.length ? entries : undefined).then((report) => {
    console.log(JSON.stringify(report, null, 2));
    process.exitCode = report.ok ? 0 : 1;
  }).catch((error) => {
    console.log(JSON.stringify({ ok: false, error: String(error.message || error) }));
    process.exitCode = 2;
  });
}

module.exports = { inspect };
