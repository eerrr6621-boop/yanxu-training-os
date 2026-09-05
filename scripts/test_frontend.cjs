#!/usr/bin/env node
'use strict';

/**
 * Pure-function regression tests extracted from web/app.js.
 * This file does not execute the SPA, access the network, or launch a browser.
 *
 * Usage:
 *   node scripts/test_frontend.cjs
 *   node scripts/test_frontend.cjs --project-root /path/to/yanxu-training
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function parseProjectRoot(argv) {
  let input = '';
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '--help' || item === '-h') {
      console.log('Usage: node test_frontend.cjs [project-root] [--project-root DIR]');
      process.exit(0);
    }
    if (item === '--project-root' || item === '--root') {
      if (!argv[index + 1] || argv[index + 1].startsWith('-')) throw new Error(item + ' requires a directory');
      input = argv[++index];
    } else if (!item.startsWith('-') && !input) input = item;
    else throw new Error('Unknown argument: ' + item);
  }
  const candidate = path.resolve(input || process.cwd());
  const choices = [candidate, path.join(candidate, 'yanxu-training'), path.dirname(candidate)];
  const found = choices.find((root) => fs.existsSync(path.join(root, 'web', 'app.js')));
  if (!found) throw new Error('Could not locate web/app.js from ' + candidate);
  return found;
}

function extractNamedFunction(source, name) {
  const marker = new RegExp('function\\s+' + name + '\\s*\\([^)]*\\)\\s*\\{').exec(source);
  if (!marker) throw new Error('Function not found in app.js: ' + name);
  const start = marker.index;
  const braceStart = source.indexOf('{', marker.index);
  let depth = 1;
  let quote = '';
  let escaped = false;
  let lineComment = false;
  let blockComment = false;
  for (let index = braceStart + 1; index < source.length; index += 1) {
    const char = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (char === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (char === '*' && next === '/') { blockComment = false; index += 1; }
      continue;
    }
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '/' && next === '/') { lineComment = true; index += 1; continue; }
    if (char === '/' && next === '*') { blockComment = true; index += 1; continue; }
    if (char === "'" || char === '"' || char === '`') { quote = char; continue; }
    if (char === '{') depth += 1;
    else if (char === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error('Function body is incomplete in app.js: ' + name);
}

function loadCollectionProgress(appPath) {
  const source = fs.readFileSync(appPath, 'utf8');
  const functionSource = extractNamedFunction(source, 'collectionProgress');
  const sandbox = Object.create(null);
  vm.runInNewContext(functionSource + '\nglobalThis.__tested = collectionProgress;', sandbox, {
    filename: 'app.js#collectionProgress',
    timeout: 1000,
  });
  if (typeof sandbox.__tested !== 'function') throw new Error('Extracted collectionProgress is not callable');
  return sandbox.__tested;
}

let passed = 0;
const failures = [];
function check(label, condition, detail = '') {
  if (condition) passed += 1;
  else failures.push({ label, detail });
}
function closeTo(actual, expected, tolerance = 1e-9) {
  return Number.isFinite(actual) && Math.abs(actual - expected) <= tolerance;
}
function assertProgress(fn, label, input, expected) {
  const actual = fn(...input);
  check(label + ': returns an object', actual && typeof actual === 'object' && !Array.isArray(actual));
  check(label + ': target', closeTo(actual.target, expected.target), `expected ${expected.target}, received ${actual.target}`);
  check(label + ': outstanding', closeTo(actual.outstanding, expected.outstanding), `expected ${expected.outstanding}, received ${actual.outstanding}`);
  check(label + ': rate', closeTo(actual.rate, expected.rate, expected.rateTolerance || 1e-9), `expected ${expected.rate}, received ${actual.rate}`);
  check(label + ': mismatch', actual.mismatch === expected.mismatch, `expected ${expected.mismatch}, received ${actual.mismatch}`);
  check(label + ': exact result fields', Object.keys(actual).sort().join(',') === 'mismatch,outstanding,rate,target', Object.keys(actual).sort().join(','));
  return actual;
}

function main() {
  const projectRoot = parseProjectRoot(process.argv.slice(2));
  const appPath = path.join(projectRoot, 'web', 'app.js');
  const collectionProgress = loadCollectionProgress(appPath);

  // Contract is authoritative. A 50k registered/received tranche against a
  // 100k contract still leaves 50k outstanding and surfaces plan mismatch.
  assertProgress(collectionProgress, 'contract-first partial registered receivable',
    [100000, 50000, 50000],
    { target: 100000, outstanding: 50000, rate: 50, mismatch: true });

  assertProgress(collectionProgress, 'contract exists with no receivable rows',
    [100000, 0, 0],
    { target: 100000, outstanding: 100000, rate: 0, mismatch: true });

  assertProgress(collectionProgress, 'no contract falls back to registered receivable',
    [0, 80000, 20000],
    { target: 80000, outstanding: 60000, rate: 25, mismatch: false });

  assertProgress(collectionProgress, 'overpayment clamps progress and outstanding',
    [100000, 100000, 125000],
    { target: 100000, outstanding: 0, rate: 100, mismatch: false });

  assertProgress(collectionProgress, 'all zero values',
    [0, 0, 0],
    { target: 0, outstanding: 0, rate: 0, mismatch: false });

  const centRate = 50000.01 / 100000.01 * 100;
  assertProgress(collectionProgress, 'cent precision is preserved',
    [100000.01, 100000.00, 50000.01],
    { target: 100000.01, outstanding: 50000, rate: centRate, mismatch: true, rateTolerance: 1e-10 });

  assertProgress(collectionProgress, 'fully collected still reports receivable mismatch',
    [100000, 80000, 100000],
    { target: 100000, outstanding: 0, rate: 100, mismatch: true });

  // Decimal inputs must be settled at currency precision. In JavaScript,
  // 0.1 + 0.7 is not represented as exactly 0.8; that floating-point tail
  // must not leak into a visible \u00a50.00 outstanding task or a sub-100% rate.
  assertProgress(collectionProgress, 'decimal sum settles an 0.8 contract exactly',
    [0.8, 0.8, 0.1 + 0.7],
    { target: 0.8, outstanding: 0, rate: 100, mismatch: false });

  assertProgress(collectionProgress, 'one-cent decimal balance remains outstanding',
    [0.8, 0.8, 0.1 + 0.69],
    { target: 0.8, outstanding: 0.01, rate: 98.75, mismatch: false });

  assertProgress(collectionProgress, 'zero target remains zero despite received value',
    [0, 0, 0.1 + 0.7],
    { target: 0, outstanding: 0, rate: 0, mismatch: false });

  assertProgress(collectionProgress, 'aligned contract and receivable',
    [100000, 100000, 50000],
    { target: 100000, outstanding: 50000, rate: 50, mismatch: false });

  assertProgress(collectionProgress, 'numeric strings follow the same accounting rule',
    ['100000', '50000', '50000'],
    { target: 100000, outstanding: 50000, rate: 50, mismatch: true });

  const withinTolerance = collectionProgress(100, 100.005, 0);
  const outsideTolerance = collectionProgress(100, 100.006, 0);
  check('half-cent tolerance does not create a mismatch', withinTolerance.mismatch === false, String(withinTolerance.mismatch));
  check('difference beyond half a cent creates a mismatch', outsideTolerance.mismatch === true, String(outsideTolerance.mismatch));
  check('result objects are independent', collectionProgress(1, 1, 0) !== collectionProgress(1, 1, 0));

  const report = {
    ok: failures.length === 0,
    suite: 'collectionProgress pure-function regression',
    source: path.relative(projectRoot, appPath),
    assertions: passed + failures.length,
    passed,
    failed: failures.length,
    failures,
  };
  console.log(JSON.stringify(report, null, 2));
  return report.ok ? 0 : 1;
}

try { process.exitCode = main(); }
catch (error) {
  console.error(JSON.stringify({ ok: false, suite: 'collectionProgress pure-function regression', error: String(error.message || error).slice(0, 800) }, null, 2));
  process.exitCode = 2;
}
