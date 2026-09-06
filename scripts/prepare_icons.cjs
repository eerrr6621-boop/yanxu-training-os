#!/usr/bin/env node
'use strict';

// Optional asset-maintenance step, not a runtime dependency. Requires sharp.
// Only uniformly downscales and PNG-compresses approved ImageGen originals.
// Originals are never overwritten; no crop, recoloring or design edits.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const names = ['dashboard', 'projects', 'documents', 'contract', 'calendar',
  'evaluation', 'faculty', 'collection', 'fees', 'costs', 'report', 'access',
  'materials', 'recommend'];

(async () => {
  let originalBytes = 0, webBytes = 0;
  const outputs = [];
  for (const name of names) {
    const input = path.join(root, 'design/icons/v13-originals', name + '-v13.png');
    const output = path.join(root, 'web/assets/icons', name + '-v13.png');
    const metadata = await sharp(input).metadata();
    if (!metadata.hasAlpha || metadata.width !== metadata.height) throw new Error(name + ': expected a square transparent original');
    fs.mkdirSync(path.dirname(output), { recursive: true });
    await sharp(input).resize({ width: 160, height: 160, fit: 'inside', withoutEnlargement: true })
      .png({ compressionLevel: 9, adaptiveFiltering: true }).toFile(output);
    const originalSize = fs.statSync(input).size, webSize = fs.statSync(output).size;
    originalBytes += originalSize; webBytes += webSize;
    outputs.push({ name, original_bytes: originalSize, web_bytes: webSize });
  }
  console.log(JSON.stringify({ icons: outputs.length, original_bytes: originalBytes,
    web_bytes: webBytes, reduction_percent: +(100 * (1 - webBytes / originalBytes)).toFixed(1), outputs }, null, 2));
})().catch((error) => { console.error(error.message); process.exitCode = 1; });
