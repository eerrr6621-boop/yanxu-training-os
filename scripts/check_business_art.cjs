#!/usr/bin/env node
'use strict';

/**
 * Offline integrity gate for Yanxu V13 business pictograms.
 *
 * - Reads only web/app.js and the mapped local PNG files.
 * - Evaluates only BUSINESS_ART and businessArt in an isolated VM.
 * - Does not load the SPA, launch a browser, or access the network.
 *
 * Usage:
 *   node check_business_art.cjs
 *   node check_business_art.cjs --project-root /path/to/yanxu-training
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const zlib = require('node:zlib');

const EXPECTED_KEYS = Object.freeze([
  'dashboard',
  'projects',
  'documents',
  'contract',
  'calendar',
  'faculty',
  'evaluation',
  'collection',
  'fees',
  'costs',
  'report',
  'access',
  'materials',
  'recommend',
]);
const REQUIRED_WIDTH = 160;
const REQUIRED_HEIGHT = 160;
const REQUIRED_BIT_DEPTH = 8;
const REQUIRED_COLOR_TYPE = 6; // PNG truecolour with alpha (RGBA).
const MAX_FILE_BYTES = 80 * 1024;
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

function parseArgs(argv) {
  let projectRoot = '';
  let help = false;
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '--help' || item === '-h') help = true;
    else if (item === '--project-root' || item === '--root') {
      if (!argv[index + 1] || argv[index + 1].startsWith('-')) throw new Error(item + ' requires a directory');
      projectRoot = argv[++index];
    } else if (!item.startsWith('-') && !projectRoot) projectRoot = item;
    else throw new Error('Unknown or incomplete argument: ' + item);
  }
  return { projectRoot, help };
}

function usage() {
  return [
    'Usage: node check_business_art.cjs [project-root]',
    '       node check_business_art.cjs --project-root DIR',
    '',
    'Offline only: no SPA, browser, or network access.',
  ].join('\n');
}

function resolveProjectRoot(input) {
  const candidate = path.resolve(input || process.cwd());
  const choices = [candidate, path.join(candidate, 'yanxu-training')];
  const found = choices.find((root) => fs.existsSync(path.join(root, 'web', 'app.js')));
  if (!found) throw new Error('Could not locate web/app.js from ' + candidate);
  return found;
}

function extractConstInitializer(source, name) {
  const marker = new RegExp('\\bconst\\s+' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*=').exec(source);
  if (!marker) throw new Error('Missing const initializer: ' + name);
  const start = marker.index + marker[0].length;
  let parens = 0;
  let brackets = 0;
  let braces = 0;
  let quote = '';
  let escaped = false;
  let lineComment = false;
  let blockComment = false;
  for (let index = start; index < source.length; index += 1) {
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
    if (char === '(') parens += 1;
    else if (char === ')') parens -= 1;
    else if (char === '[') brackets += 1;
    else if (char === ']') brackets -= 1;
    else if (char === '{') braces += 1;
    else if (char === '}') braces -= 1;
    else if (char === ';' && parens === 0 && brackets === 0 && braces === 0) {
      return source.slice(start, index).trim();
    }
    if (parens < 0 || brackets < 0 || braces < 0) throw new Error('Unbalanced initializer: ' + name);
  }
  throw new Error('Unterminated const initializer: ' + name);
}

function loadBusinessDefinitions(appPath) {
  const source = fs.readFileSync(appPath, 'utf8');
  const mapInitializer = extractConstInitializer(source, 'BUSINESS_ART');
  const rendererInitializer = extractConstInitializer(source, 'businessArt');
  if (!/^Object\.freeze\s*\(/.test(mapInitializer)) throw new Error('BUSINESS_ART must use Object.freeze({...})');
  if (!/^\([^)]*\)\s*=>/.test(rendererInitializer)) throw new Error('businessArt must remain an arrow function');
  const sandbox = Object.create(null);
  const evaluation = [
    '"use strict";',
    'const BUSINESS_ART = ' + mapInitializer + ';',
    'const businessArt = ' + rendererInitializer + ';',
    'globalThis.__businessGate = { BUSINESS_ART, businessArt, frozen: Object.isFrozen(BUSINESS_ART) };',
  ].join('\n');
  vm.runInNewContext(evaluation, sandbox, {
    filename: 'app.js#BUSINESS_ART',
    timeout: 1000,
    codeGeneration: { strings: false, wasm: false },
  });
  const loaded = sandbox.__businessGate;
  if (!loaded || typeof loaded.businessArt !== 'function' || !loaded.BUSINESS_ART) {
    throw new Error('Could not load BUSINESS_ART and businessArt from isolated VM');
  }
  return loaded;
}

function parseImgAttributes(html) {
  const text = String(html);
  const issues = [];
  if (!/^<img\b[^<>]*>$/i.test(text)) issues.push('renderer must return exactly one img element');
  const attrs = Object.create(null);
  for (const match of text.matchAll(/\b([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)"/g)) {
    const key = match[1].toLowerCase();
    if (Object.hasOwn(attrs, key)) issues.push('duplicate attribute: ' + key);
    attrs[key] = match[2];
  }
  return { attrs, issues };
}

function inside(root, candidate) {
  return candidate === root || candidate.startsWith(root + path.sep);
}

function buildCrcTable() {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    table[index] = value >>> 0;
  }
  return table;
}

const CRC_TABLE = buildCrcTable();

function crc32(buffers) {
  let value = 0xffffffff;
  for (const buffer of buffers) {
    for (const byte of buffer) value = CRC_TABLE[(value ^ byte) & 0xff] ^ (value >>> 8);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function paeth(left, up, upperLeft) {
  const estimate = left + up - upperLeft;
  const leftDistance = Math.abs(estimate - left);
  const upDistance = Math.abs(estimate - up);
  const upperLeftDistance = Math.abs(estimate - upperLeft);
  if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) return left;
  if (upDistance <= upperLeftDistance) return up;
  return upperLeft;
}

function decodePng(bytes) {
  const issues = [];
  const chunks = [];
  if (bytes.length < PNG_SIGNATURE.length || !bytes.subarray(0, 8).equals(PNG_SIGNATURE)) {
    return { issues: ['invalid PNG signature'] };
  }

  let offset = 8;
  let sawIend = false;
  let idatClosed = false;
  const idatParts = [];
  while (offset < bytes.length) {
    if (bytes.length - offset < 12) { issues.push('truncated PNG chunk header'); break; }
    const length = bytes.readUInt32BE(offset);
    if (length > MAX_FILE_BYTES) { issues.push('PNG chunk exceeds safe decode bound'); break; }
    const typeStart = offset + 4;
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    const chunkEnd = dataEnd + 4;
    if (chunkEnd > bytes.length) { issues.push('truncated PNG chunk payload'); break; }
    const typeBytes = bytes.subarray(typeStart, dataStart);
    const type = typeBytes.toString('ascii');
    if (!/^[A-Za-z]{4}$/.test(type)) issues.push('invalid PNG chunk type at byte ' + offset);
    const data = bytes.subarray(dataStart, dataEnd);
    const storedCrc = bytes.readUInt32BE(dataEnd);
    const calculatedCrc = crc32([typeBytes, data]);
    if (storedCrc !== calculatedCrc) issues.push(type + ' CRC mismatch');
    chunks.push({ type, length });

    if (type === 'IDAT') {
      if (idatClosed) issues.push('IDAT chunks must be consecutive');
      idatParts.push(data);
    } else if (idatParts.length) idatClosed = true;

    if (type === 'IEND') {
      if (length !== 0) issues.push('IEND must be empty');
      sawIend = true;
      offset = chunkEnd;
      if (offset !== bytes.length) issues.push('trailing bytes after IEND');
      break;
    }
    if (type[0] === type[0].toUpperCase() && !['IHDR', 'PLTE', 'IDAT', 'IEND'].includes(type)) {
      issues.push('unsupported critical PNG chunk: ' + type);
    }
    offset = chunkEnd;
  }

  if (!sawIend) issues.push('missing IEND');
  if (!chunks.length || chunks[0].type !== 'IHDR') issues.push('IHDR must be the first chunk');
  const ihdrChunks = chunks.filter((chunk) => chunk.type === 'IHDR');
  if (ihdrChunks.length !== 1 || ihdrChunks[0].length !== 13) issues.push('PNG must contain one 13-byte IHDR');
  if (!idatParts.length) issues.push('missing IDAT');
  if (issues.length) return { issues, chunks };

  const ihdrOffset = 8 + 8;
  const width = bytes.readUInt32BE(ihdrOffset);
  const height = bytes.readUInt32BE(ihdrOffset + 4);
  const bitDepth = bytes[ihdrOffset + 8];
  const colorType = bytes[ihdrOffset + 9];
  const compression = bytes[ihdrOffset + 10];
  const filterMethod = bytes[ihdrOffset + 11];
  const interlace = bytes[ihdrOffset + 12];
  if (width !== REQUIRED_WIDTH || height !== REQUIRED_HEIGHT) issues.push(`IHDR dimensions must be ${REQUIRED_WIDTH}x${REQUIRED_HEIGHT}`);
  if (bitDepth !== REQUIRED_BIT_DEPTH) issues.push('IHDR bit depth must be 8');
  if (colorType !== REQUIRED_COLOR_TYPE) issues.push('IHDR color type must be 6 (RGBA)');
  if (compression !== 0) issues.push('unsupported PNG compression method');
  if (filterMethod !== 0) issues.push('unsupported PNG filter method');
  if (interlace !== 0) issues.push('PNG must be non-interlaced for deterministic decode');
  if (issues.length) return { issues, chunks, width, height, bitDepth, colorType, interlace };

  const bytesPerPixel = 4;
  const rowBytes = width * bytesPerPixel;
  const expectedInflatedBytes = height * (rowBytes + 1);
  let filtered;
  try {
    filtered = zlib.inflateSync(Buffer.concat(idatParts), { maxOutputLength: expectedInflatedBytes });
  } catch (error) {
    issues.push('IDAT zlib decode failed: ' + String(error.message || error).slice(0, 160));
    return { issues, chunks, width, height, bitDepth, colorType, interlace };
  }
  if (filtered.length !== expectedInflatedBytes) {
    issues.push(`decoded scanlines must be ${expectedInflatedBytes} bytes, received ${filtered.length}`);
    return { issues, chunks, width, height, bitDepth, colorType, interlace };
  }

  const pixels = Buffer.allocUnsafe(width * height * bytesPerPixel);
  let inputOffset = 0;
  for (let row = 0; row < height; row += 1) {
    const filter = filtered[inputOffset++];
    if (filter > 4) {
      issues.push('unsupported PNG row filter ' + filter + ' at row ' + row);
      break;
    }
    const rowOffset = row * rowBytes;
    const previousOffset = rowOffset - rowBytes;
    for (let column = 0; column < rowBytes; column += 1) {
      const raw = filtered[inputOffset++];
      const left = column >= bytesPerPixel ? pixels[rowOffset + column - bytesPerPixel] : 0;
      const up = row > 0 ? pixels[previousOffset + column] : 0;
      const upperLeft = row > 0 && column >= bytesPerPixel ? pixels[previousOffset + column - bytesPerPixel] : 0;
      let predictor = 0;
      if (filter === 1) predictor = left;
      else if (filter === 2) predictor = up;
      else if (filter === 3) predictor = Math.floor((left + up) / 2);
      else if (filter === 4) predictor = paeth(left, up, upperLeft);
      pixels[rowOffset + column] = (raw + predictor) & 0xff;
    }
  }

  let transparentPixels = 0;
  let nonTransparentPixels = 0;
  let fullyOpaquePixels = 0;
  let partialAlphaPixels = 0;
  for (let offset = 3; offset < pixels.length; offset += 4) {
    const alpha = pixels[offset];
    if (alpha === 0) transparentPixels += 1;
    else {
      nonTransparentPixels += 1;
      if (alpha === 255) fullyOpaquePixels += 1;
      else partialAlphaPixels += 1;
    }
  }
  if (transparentPixels === 0) issues.push('alpha channel has no fully transparent pixels');
  if (nonTransparentPixels === 0) issues.push('alpha channel has no visible pixels');
  return {
    issues,
    chunks,
    width,
    height,
    bitDepth,
    colorType,
    interlace,
    idatBytes: idatParts.reduce((sum, part) => sum + part.length, 0),
    decodedBytes: filtered.length,
    transparentPixels,
    nonTransparentPixels,
    fullyOpaquePixels,
    partialAlphaPixels,
  };
}

function inspectPng(webRoot, key, reference) {
  const result = { key, reference, ok: false, issues: [] };
  const pathname = new URL(reference, 'http://static.invalid/').pathname;
  const file = path.resolve(webRoot, '.' + pathname);
  result.file = path.relative(webRoot, file);
  if (!inside(webRoot, file)) {
    result.issues.push('resolved path escapes web root');
    return result;
  }
  if (!fs.existsSync(file)) {
    result.issues.push('file is missing');
    return result;
  }
  const stat = fs.statSync(file);
  if (!stat.isFile()) {
    result.issues.push('path is not a regular file');
    return result;
  }
  const realFile = fs.realpathSync(file);
  const realWebRoot = fs.realpathSync(webRoot);
  if (!inside(realWebRoot, realFile)) {
    result.issues.push('file symlink escapes web root');
    return result;
  }
  result.bytes = stat.size;
  if (stat.size <= 0) result.issues.push('file is empty');
  if (stat.size > MAX_FILE_BYTES) result.issues.push(`file exceeds ${MAX_FILE_BYTES} bytes`);
  if (result.issues.length) return result;
  const decoded = decodePng(fs.readFileSync(file));
  result.png = decoded;
  result.issues.push(...decoded.issues);
  result.ok = result.issues.length === 0;
  return result;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) { console.log(usage()); return 0; }
  const projectRoot = resolveProjectRoot(args.projectRoot);
  const webRoot = path.join(projectRoot, 'web');
  const appPath = path.join(webRoot, 'app.js');
  const loaded = loadBusinessDefinitions(appPath);
  const map = loaded.BUSINESS_ART;
  const actualKeys = Object.keys(map).sort();
  const expectedKeys = [...EXPECTED_KEYS].sort();
  const failures = [];
  const addFailure = (scope, message) => failures.push({ scope, message });

  if (!loaded.frozen) addFailure('mapping', 'BUSINESS_ART is not frozen');
  const missingKeys = expectedKeys.filter((key) => !Object.hasOwn(map, key));
  const unexpectedKeys = actualKeys.filter((key) => !EXPECTED_KEYS.includes(key));
  if (missingKeys.length) addFailure('mapping', 'missing keys: ' + missingKeys.join(', '));
  if (unexpectedKeys.length) addFailure('mapping', 'unexpected keys: ' + unexpectedKeys.join(', '));

  const seenPaths = new Map();
  const mappingItems = actualKeys.map((key) => {
    const reference = map[key];
    const issues = [];
    if (typeof reference !== 'string') issues.push('path must be a string');
    else {
      if (!/^\/assets\/icons\/[a-z0-9-]+-v13\.png$/.test(reference)) issues.push('path must stay under /assets/icons/ and end in -v13.png');
      if (reference !== `/assets/icons/${key}-v13.png`) issues.push('path basename must match its mapping key');
      if (reference.includes('..') || reference.includes('\\') || /[%?#]/.test(reference)) issues.push('path contains traversal, encoding, query, or fragment syntax');
      if (!seenPaths.has(reference)) seenPaths.set(reference, []);
      seenPaths.get(reference).push(key);
    }
    issues.forEach((message) => addFailure('mapping.' + key, message));
    return { key, reference, ok: issues.length === 0, issues };
  });
  for (const [reference, keys] of seenPaths) {
    if (keys.length > 1) addFailure('mapping', `duplicate path ${reference}: ${keys.join(', ')}`);
  }
  const uniquePaths = seenPaths.size === actualKeys.length;

  const rendererItems = [];
  for (const key of EXPECTED_KEYS) {
    const expectedPath = map[key];
    const issues = [];
    let html = '';
    try { html = loaded.businessArt(key); }
    catch (error) { issues.push('renderer threw: ' + String(error.message || error).slice(0, 160)); }
    const parsed = parseImgAttributes(html);
    issues.push(...parsed.issues);
    const attrs = parsed.attrs;
    if (!(attrs.class || '').split(/\s+/).includes('business-art')) issues.push('class must include business-art');
    if (attrs.alt !== '') issues.push('alt must be empty');
    if (attrs['aria-hidden'] !== 'true') issues.push('aria-hidden must equal true');
    if (attrs.width !== '48' || attrs.height !== '48') issues.push('width and height must both equal 48');
    if (attrs.decoding !== 'async') issues.push('decoding must equal async');
    let renderedPath = '';
    try {
      const renderedUrl = new URL(attrs.src || '', 'http://static.invalid/');
      renderedPath = renderedUrl.pathname;
      if (renderedPath !== expectedPath) issues.push(`src path must equal ${expectedPath}`);
      if (!/^\?v=[A-Za-z0-9._-]+$/.test(renderedUrl.search)) issues.push('src must include one safe cache-version query');
    } catch (error) {
      issues.push('src is not a valid URL path');
    }
    issues.forEach((message) => addFailure('renderer.' + key, message));
    rendererItems.push({ key, expected_path: expectedPath, rendered_path: renderedPath, ok: issues.length === 0, issues });
  }

  const fallbackIssues = [];
  let fallbackPath = '';
  for (const unknown of ['unknown-kind', '__proto__', 'constructor', null]) {
    try {
      const rendered = parseImgAttributes(loaded.businessArt(unknown));
      const renderedUrl = new URL(rendered.attrs.src || '', 'http://static.invalid/');
      fallbackPath = renderedUrl.pathname;
      if (renderedUrl.pathname !== map.documents) fallbackIssues.push(String(unknown) + ' did not fall back to documents');
    } catch (error) {
      fallbackIssues.push(String(unknown) + ' fallback threw: ' + String(error.message || error).slice(0, 120));
    }
  }
  fallbackIssues.forEach((message) => addFailure('renderer.fallback', message));

  const pngItems = EXPECTED_KEYS.map((key) => inspectPng(webRoot, key, map[key] || ''));
  for (const item of pngItems) item.issues.forEach((message) => addFailure('png.' + item.key, message));
  const totalBytes = pngItems.reduce((sum, item) => sum + (Number(item.bytes) || 0), 0);
  const presentFiles = pngItems.filter((item) => Number.isFinite(item.bytes)).length;
  const passingFiles = pngItems.filter((item) => item.ok).length;

  const report = {
    ok: failures.length === 0,
    suite: 'V13 business-art offline integrity gate',
    mode: 'offline-static-vm-png-decode',
    project_root: projectRoot,
    app_source: path.relative(projectRoot, appPath),
    mapping: {
      expected_keys: expectedKeys,
      actual_keys: actualKeys,
      frozen: Boolean(loaded.frozen),
      missing_keys: missingKeys,
      unexpected_keys: unexpectedKeys,
      unique_paths: uniquePaths,
      items: mappingItems,
    },
    renderer: {
      checked: rendererItems.length,
      items: rendererItems,
      fallback_checked: 4,
      fallback_path: fallbackPath,
      fallback_ok: fallbackIssues.length === 0,
      fallback_issues: fallbackIssues,
    },
    png: {
      required: { width: REQUIRED_WIDTH, height: REQUIRED_HEIGHT, bit_depth: REQUIRED_BIT_DEPTH, color_type: REQUIRED_COLOR_TYPE, max_file_bytes: MAX_FILE_BYTES },
      expected_files: EXPECTED_KEYS.length,
      present_files: presentFiles,
      passing_files: passingFiles,
      total_bytes: totalBytes,
      total_kib: Number((totalBytes / 1024).toFixed(2)),
      items: pngItems,
    },
    failures,
  };
  console.log(JSON.stringify(report, null, 2));
  return report.ok ? 0 : 1;
}

try { process.exitCode = main(); }
catch (error) {
  console.log(JSON.stringify({
    ok: false,
    suite: 'V13 business-art offline integrity gate',
    mode: 'offline-static-vm-png-decode',
    failures: [{ scope: 'fatal', message: String(error.message || error).slice(0, 800) }],
  }, null, 2));
  process.exitCode = 1;
}
