#!/usr/bin/env node
'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
let checks = 0;
const check = (condition, label) => { assert.ok(condition, label); checks++; };
const close = (a, b, epsilon = 1e-6) => Math.abs(a - b) < epsilon;
async function moduleAt(relative) {
  return import('data:text/javascript;base64,' + fs.readFileSync(path.join(root, relative)).toString('base64'));
}
(async () => {
  const { HEADLINES, fitHeadlinePrint } = await moduleAt('web/scene/book-headlines.js');
  const { createPaperBlock, pageRestRelief } = await moduleAt('web/scene/book-binding.js');
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'docs/design-prompts/headlines-v13r9.json'), 'utf8'));
  check(HEADLINES.map(x => x.title).join('/') === '培训运营/师资推荐/课程交付/项目管理', 'four correct titles in order');
  check(Object.isFrozen(HEADLINES) && HEADLINES.every(x => Object.isFrozen(x.bounds)), 'placement metadata is immutable');
  let bytes = 0;
  for (const entry of HEADLINES) {
    const p = fitHeadlinePrint(entry.bounds), b = entry.bounds;
    const left = p.x + b.x * p.scale, right = left + b.width * p.scale;
    check(close((left + right) / 2, 384), entry.title + ': shared visible-ink center');
    check(close(p.y + (b.y + b.height) * p.scale, 492), entry.title + ': shared baseline');
    check(b.height * p.scale >= 127 && b.height * p.scale <= 128, entry.title + ': consistent readable ink height');
    check(left >= 72 && right <= 696, entry.title + ': entire ink inside symmetric clipping window');
    check(close(p.width / 960, p.height / 240), entry.title + ': no stretching');
    const file = fs.readFileSync(path.join(root, 'web', entry.image)); bytes += file.length;
    check(file.toString('ascii', 0, 4) === 'RIFF' && file.toString('ascii', 8, 12) === 'WEBP', entry.title + ': valid WebP container');
    check(file.toString('ascii', 12, 16) === 'VP8L' && file[20] === 0x2f, entry.title + ': lossless image');
    const info = file.readUInt32LE(21);
    check((info & 0x3fff) + 1 === 960 && ((info >>> 14) & 0x3fff) + 1 === 240 && !!(info & 0x10000000), entry.title + ': 960×240 with alpha');
    check(file.length < 100000, entry.title + ': browser asset <100KB');
    const source = manifest.entries.find(x => x.title === entry.title);
    check(source?.sha256 === crypto.createHash('sha256').update(file).digest('hex'), entry.title + ': verified transparent asset unchanged');
    const original = fs.readFileSync(path.join(root, source.original));
    check(original.readUInt32BE(16) === source.originalSize[0] && original.readUInt32BE(20) === source.originalSize[1], entry.title + ': full original retained');
  }
  check(bytes < 400000, 'four title assets <400KB total');
  // R8 endorsement visible bounds after its original 2172px canvas is placed at width551.
  check(close(105 + (313 + 1889) / 2 * 551 / 2172, 384, .5), 'fixed endorsement uses the same optical center');
  for (const bad of [null, {}, {x:-1,y:0,width:10,height:10}, {x:0,y:0,width:0,height:10}, {x:0,y:0,width:961,height:20}, {x:0,y:240,width:10,height:10}, {x:NaN,y:0,width:10,height:10}]) {
    assert.throws(() => fitHeadlinePrint(bad), RangeError); checks++;
  }
  assert.throws(() => fitHeadlinePrint(HEADLINES[0].bounds, 100, 100), RangeError); checks++;
  const renderer = fs.readFileSync(path.join(root, 'web/scene/login-book-three.js'), 'utf8');
  check(renderer.includes('ctx.rect(72, 310, 624, 215)'), 'renderer consumes the symmetric clip contract');
  check(renderer.includes('fitHeadlinePrint(entry.bounds, art.naturalWidth, art.naturalHeight)'), 'renderer consumes shared optical-placement helper');
  check(renderer.includes('restBend: REST_BEND') && renderer.includes('* REST_BEND'), 'block and top sheet share bend setting');
  // Sample every upper cap triangle of the actual bowed core, not a separate mock mesh.
  let minimumGap = Infinity;
  for (const side of [-1, 1]) {
    const depth = .11, bend = 2.8, sheetZ = side < 0 ? .0003 : .0001;
    const mesh = createPaperBlock({width:1.55, height:2.1, depth, side, layers:1, restBend:bend});
    check([...mesh.positions].every(Number.isFinite), 'bowed core has finite vertices');
    for (let vertex = 0; vertex < 48; vertex++) check(close(mesh.positions[vertex * 3 + 2], -.001 - depth), 'bottom stays seated on cover');
    const capCenter = mesh.positions.length / 3 - 1;
    for (let index = 0; index < mesh.indices.length; index += 3) {
      const ids = [...mesh.indices.subarray(index, index + 3)];
      if (!ids.includes(capCenter)) continue;
      const vertices = ids.map(v => [...mesh.positions.subarray(v * 3, v * 3 + 3)]);
      for (let a = 0; a <= 10; a++) for (let b = 0; b <= 10 - a; b++) {
        const weights = [a / 10, b / 10, 1 - (a + b) / 10];
        const point = [0,1,2].map(axis => vertices.reduce((sum,v,i) => sum + v[axis] * weights[i], 0));
        const sheet = sheetZ + pageRestRelief(Math.abs(point[0]) / 1.55, point[1] / 2.1 + .5, 0) * bend;
        const gap = sheet - point[2]; minimumGap = Math.min(minimumGap, gap);
        check(gap > .0008, 'core cap stays beneath visible paper without z fighting');
      }
    }
  }
  for (const restBend of [-1, 4.1, NaN, Infinity]) { assert.throws(() => createPaperBlock({restBend}), RangeError); checks++; }
  console.log(JSON.stringify({ok:true,suite:'R9 optical title layout, image integrity and bowed core',checks,assetBytes:bytes,minimumGap}));
})().catch(error => { console.error(error); process.exitCode = 1; });
