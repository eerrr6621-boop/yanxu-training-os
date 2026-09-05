#!/usr/bin/env node
'use strict';

// Offline regression for check-local-esm-closure.cjs. No browser, server,
// production tree or vendored Three.js copies are needed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

if (typeof vm.SourceTextModule !== 'function') {
  const child = spawnSync(process.execPath, ['--experimental-vm-modules', '--no-warnings', __filename, ...process.argv.slice(2)], { stdio: 'inherit' });
  process.exit(child.status == null ? 2 : child.status);
}

const helperPath = process.argv[2] || path.join(__dirname, 'check_local_esm.cjs');
const { inspect } = require(helperPath);

async function main() {
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-esm-closure-test-'));
  const webRoot = path.join(fixtureRoot, 'web');
  fs.mkdirSync(webRoot);
  const checks = [];
  function write(relative, contents) {
    const file = path.join(webRoot, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, contents);
  }
  async function rejected(entry, pattern, label) {
    const report = await inspect(webRoot, [entry]);
    assert.equal(report.ok, false, label + ' must fail');
    assert.ok(report.errors.some((item) => pattern.test(item.message)), label + ': ' + JSON.stringify(report.errors));
    checks.push(label);
  }

  write('entry.js', "import { answer } from './nested.js'; import('./late.js'); export { answer }; throw new Error('MUST_NOT_EVALUATE_APPLICATION_CODE');\n");
  write('nested.js', "export const answer = 42; export * from './leaf.js';\n");
  write('leaf.js', 'export const leaf = true;\n');
  write('late.js', 'export const lazy = true;\n');
  const good = await inspect(webRoot, ['/entry.js']);
  assert.equal(good.ok, true, JSON.stringify(good.errors));
  assert.equal(good.module_count, 4);
  assert.deepEqual(good.resources.map((item) => item.reference).sort(), ['/entry.js', '/late.js', '/leaf.js', '/nested.js']);
  assert.ok(good.resources.every((item) => item.bytes > 0 && /^[a-f0-9]{64}$/.test(item.sha256)));
  assert.ok(good.edges.some((item) => item.kind === 'dynamic-literal' && item.target === '/late.js'));
  checks.push('transitive imports + re-exports + literal dynamic imports', 'resource bytes + SHA-256', 'never evaluates application/vendor code');

  write('scene/book.js', "import { Renderer } from '../vendor/three.module.js'; export { Renderer };\n");
  write('vendor/three.module.js', "export { Renderer } from './three.core.js';\n");
  await rejected('/scene/book.js', /Missing module: \/vendor\/three\.core\.js/, 'missing transitive three.core rejected');

  write('wrong-export.js', "import { absentExport } from './nested.js'; export { absentExport };\n");
  await rejected('/wrong-export.js', /absentExport/, 'missing export rejected');

  write('syntax.js', 'export const broken = ;\n');
  await rejected('/syntax.js', /Unexpected token/, 'syntax error rejected');

  write('external.js', "import 'https://cdn.invalid/three.js';\n");
  await rejected('/external.js', /Non-local|External module/, 'external static import rejected');

  write('bare.js', "import { Scene } from 'three'; export { Scene };\n");
  await rejected('/bare.js', /bare module|Non-local/, 'bare module rejected');

  write('external-dynamic.js', "import('https://cdn.invalid/three.js');\n");
  await rejected('/external-dynamic.js', /Non-local|External module/, 'external literal dynamic import rejected');

  // The symlink itself is inside the web root; its real target deliberately is
  // outside that root, but still within this test's own fresh temporary folder.
  const outside = path.join(fixtureRoot, 'outside.js');
  fs.writeFileSync(outside, 'export const forbidden = true;\n');
  fs.symlinkSync(outside, path.join(webRoot, 'escape.js'));
  write('symlink.js', "import './escape.js';\n");
  await rejected('/symlink.js', /symlink escapes web root/, 'symlink escape rejected');

  write('cycle-a.js', "import { b } from './cycle-b.js'; export const a = 'A'; export { b };\n");
  write('cycle-b.js', "import { a } from './cycle-a.js'; export const b = 'B'; export { a };\n");
  const cyclic = await inspect(webRoot, ['/cycle-a.js']);
  assert.equal(cyclic.ok, true, JSON.stringify(cyclic.errors));
  assert.equal(cyclic.module_count, 2);
  checks.push('valid cyclic modules accepted without evaluation');

  console.log(JSON.stringify({ ok: true, suite: 'local ESM closure offline gate', checks, fixture_root: fixtureRoot }, null, 2));
}

main().catch((error) => {
  console.error(JSON.stringify({ ok: false, suite: 'local ESM closure offline gate', error: String(error.stack || error) }, null, 2));
  process.exitCode = 1;
});
