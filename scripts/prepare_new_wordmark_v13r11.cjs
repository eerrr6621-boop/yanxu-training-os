// Offline maintenance only. No application code or runtime dependency is changed.
// User-approved: remove the generated low-alpha halo, trim, and compress the asset.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const preserved = path.join(root, 'design/login/v13r11-originals/new-wordmark.png');
const source = process.argv[2] ? path.resolve(process.argv[2]) : preserved;
const hash = data => crypto.createHash('sha256').update(data).digest('hex');

async function writeNewOrIdentical(file, bytes) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  try {
    const current = await fs.readFile(file);
    if (hash(current) !== hash(bytes)) throw new Error(`Refusing to overwrite differing file: ${file}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await fs.writeFile(file, bytes, { flag: 'wx' });
  }
}

(async () => {
  const original = await fs.readFile(source);
  await writeNewOrIdentical(preserved, original);
  const { data, info } = await sharp(original).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width, height } = info;
  if (info.channels !== 4) throw new Error('Expected RGBA input');
  const alphaFloor = 8;
  const sourceCoreAlpha = 254;
  let left = width, top = height, right = -1, bottom = -1, removedHaloPixels = 0;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const alpha = data[i + 3];
      // Smoothly map existing edge coverage, not a binary threshold of the glyph.
      const cleanAlpha = Math.max(0, Math.min(255, Math.round((alpha - alphaFloor) * 255 / (sourceCoreAlpha - alphaFloor))));
      data[i + 3] = cleanAlpha;
      if (cleanAlpha === 0) {
        if (alpha > 0) removedHaloPixels++;
        // Remove hidden glow RGB, too, so transparent pixels carry no color contamination.
        data[i] = data[i + 1] = data[i + 2] = 0;
      } else {
        // Preserve all retained RGB values: no recoloring, redraw, or shape replacement.
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x);
        bottom = Math.max(bottom, y);
      }
    }
  }
  if (right < left) throw new Error('No foreground survived alpha cleaning');
  const crop = { left, top, width: right - left + 1, height: bottom - top + 1 };
  const clean = await sharp(data, { raw: { width, height, channels: 4 } })
    .extract(crop)
    .resize({ width: 156, kernel: 'lanczos3', withoutEnlargement: true })
    .extend({ top: 2, bottom: 2, left: 2, right: 2, background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
  const [webp, png] = await Promise.all([
    sharp(clean).webp({ lossless: true, effort: 6 }).toBuffer(),
    sharp(clean).png({ compressionLevel: 9, adaptiveFiltering: true }).toBuffer()
  ]);
  const selectedFormat = webp.length <= png.length ? 'webp' : 'png';
  const selected = selectedFormat === 'webp' ? webp : png;
  const output = path.join(root, `web/assets/new-wordmark-v13r11.${selectedFormat}`);
  await writeNewOrIdentical(output, selected);

  const metadata = await sharp(selected).metadata();
  const decoded = await sharp(selected).ensureAlpha().raw().toBuffer();
  const histogram = new Array(256).fill(0);
  for (let i = 3; i < decoded.length; i += 4) histogram[decoded[i]]++;
  const alphaValues = histogram.flatMap((count, alpha) => count ? [alpha] : []);

  // QA composite: source at left, cleaned asset at right; white row then dark row.
  const sourcePreview = await sharp(original).resize({ width: 230 }).png().toBuffer();
  const cssPreview = await sharp(selected).resize({ width: 38 }).png().toBuffer();
  const dark = await sharp({ create: { width: 560, height: 140, channels: 4, background: '#192130' } }).png().toBuffer();
  const qa = await sharp({ create: { width: 560, height: 280, channels: 4, background: '#ffffff' } })
    .composite([
      { input: dark, left: 0, top: 140 },
      { input: sourcePreview, left: 20, top: 20 },
      { input: sourcePreview, left: 20, top: 160 },
      { input: selected, left: 300, top: 30 },
      { input: selected, left: 300, top: 170 },
      { input: cssPreview, left: 485, top: 48 },
      { input: cssPreview, left: 485, top: 188 }
    ])
    .png().toBuffer();
  const qaPath = path.join(root, '.codex-tmp/new-wordmark-v13r11-qa.png');
  await writeNewOrIdentical(qaPath, qa);
  console.log(JSON.stringify({
    source, preserved, sourceSha256: hash(original), sourceBytes: original.length,
    output, outputSha256: hash(selected), outputBytes: selected.length,
    outputWidth: metadata.width, outputHeight: metadata.height,
    sourceCrop: crop, alphaFloor, sourceCoreAlpha, removedHaloPixels,
    outputAlphaRange: [alphaValues[0], alphaValues.at(-1)],
    outputTransparentPixels: histogram[0], outputOpaquePixels: histogram[255],
    outputSemitransparentPixels: histogram.slice(1, 255).reduce((a, b) => a + b, 0),
    losslessWebpBytes: webp.length, losslessPngBytes: png.length, qaPath
  }, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
