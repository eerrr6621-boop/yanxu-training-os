#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_MODULE = path.join(__dirname, '../web/scene/book-binding.js');
const EPS = 2e-5;
const AREA_EPS_SQ = 1e-16;

let assertions = 0;
const groups = [];

function assert(condition, message, details) {
  assertions += 1;
  if (!condition) {
    const error = new Error(message);
    if (details !== undefined) error.details = details;
    throw error;
  }
}

function approx(actual, expected, epsilon = EPS) {
  return Number.isFinite(actual) && Math.abs(actual - expected) <= epsilon;
}

function parseModulePath(argv) {
  let candidate = null;
  for (let i = 2; i < argv.length; i += 1) {
    if (argv[i] === '--module') candidate = argv[++i];
    else if (argv[i].startsWith('--module=')) candidate = argv[i].slice('--module='.length);
    else if (!argv[i].startsWith('-') && candidate === null) candidate = argv[i];
    else throw new Error(`Unknown argument: ${argv[i]}`);
  }
  return path.resolve(candidate || DEFAULT_MODULE);
}

async function importSourceAsDataUrl(modulePath) {
  const source = fs.readFileSync(modulePath, 'utf8');
  const tagged = `${source}\n//# sourceURL=${modulePath.replace(/\s/g, '%20')}\n`;
  return import(`data:text/javascript;base64,${Buffer.from(tagged).toString('base64')}`);
}

function vertexAt(positions, index) {
  const offset = index * 3;
  return [positions[offset], positions[offset + 1], positions[offset + 2]];
}

function boundsOf(positions) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < positions.length; i += 3) {
    for (let axis = 0; axis < 3; axis += 1) {
      min[axis] = Math.min(min[axis], positions[i + axis]);
      max[axis] = Math.max(max[axis], positions[i + axis]);
    }
  }
  return { min, max };
}

function clusteredLevels(positions, epsilon = EPS) {
  const zs = [];
  for (let i = 2; i < positions.length; i += 3) zs.push(positions[i]);
  zs.sort((a, b) => a - b);
  const levels = [];
  for (const z of zs) {
    if (!levels.length || Math.abs(z - levels[levels.length - 1]) > epsilon) levels.push(z);
  }
  return levels;
}

function nearestLevel(levels, z) {
  let best = 0;
  let distance = Infinity;
  for (let i = 0; i < levels.length; i += 1) {
    const next = Math.abs(z - levels[i]);
    if (next < distance) {
      best = i;
      distance = next;
    }
  }
  assert(distance <= EPS, 'vertex z does not belong to a contiguous ring level', { z, distance });
  return best;
}

function quantizedPoint(point, epsilon = EPS) {
  return point.map((value) => Math.round(value / epsilon)).join(',');
}

function mirroredPointMultiset(positions, mirrorX) {
  const keys = [];
  for (let i = 0; i < positions.length; i += 3) {
    keys.push(quantizedPoint([
      mirrorX ? -positions[i] : positions[i],
      positions[i + 1],
      positions[i + 2],
    ]));
  }
  return keys.sort();
}

function validateArrayContract(mesh, label) {
  assert(mesh && typeof mesh === 'object', `${label}: result must be an object`);
  assert(mesh.positions instanceof Float32Array, `${label}: positions must be Float32Array`);
  assert(mesh.uvs instanceof Float32Array, `${label}: uvs must be Float32Array`);
  assert(mesh.indices instanceof Uint16Array, `${label}: indices must be Uint16Array`);
  assert(mesh.positions.length > 0 && mesh.positions.length % 3 === 0, `${label}: positions length must be positive and divisible by 3`);
  const vertexCount = mesh.positions.length / 3;
  assert(mesh.uvs.length === vertexCount * 2, `${label}: every vertex must have one uv pair`, { vertexCount, uvLength: mesh.uvs.length });
  assert(mesh.indices.length > 0 && mesh.indices.length % 3 === 0, `${label}: indices length must be positive and divisible by 3`);
  assert([...mesh.positions].every(Number.isFinite), `${label}: positions must be finite`);
  assert([...mesh.uvs].every(Number.isFinite), `${label}: uvs must be finite`);
  assert([...mesh.uvs].every((value) => value >= -EPS && value <= 1 + EPS), `${label}: uvs must remain normalized`);
  assert([...mesh.indices].every((index) => index < vertexCount), `${label}: every triangle index must address a vertex`);
  return { vertexCount, triangleCount: mesh.indices.length / 3 };
}

function validateShape(mesh, spec, label) {
  const { width, height, depth, layers, segments, side } = spec;
  const { vertexCount, triangleCount } = validateArrayContract(mesh, label);
  const ringSize = segments * 4;
  const levelCount = layers * 2 + 1;
  const expectedVertices = ringSize * levelCount + 2;
  const expectedTriangles = (levelCount - 1) * ringSize * 2 + ringSize * 2;
  assert(vertexCount === expectedVertices, `${label}: ring levels and two cap centers must determine vertex count`, { vertexCount, expectedVertices });
  assert(triangleCount === expectedTriangles, `${label}: sides and two cap fans must determine triangle count`, { triangleCount, expectedTriangles });

  const bounds = boundsOf(mesh.positions);
  const expectedX = side > 0 ? [0, width] : [-width, 0];
  assert(bounds.min[0] >= expectedX[0] - EPS && bounds.max[0] <= expectedX[1] + EPS, `${label}: x coordinates escaped the selected half`, { bounds: bounds.min[0] + '..' + bounds.max[0], expectedX });
  assert(approx(bounds.min[0], expectedX[0]) && approx(bounds.max[0], expectedX[1]), `${label}: x bounds must reach both half-book edges`, { bounds, expectedX });
  assert(approx(bounds.min[1], -height / 2) && approx(bounds.max[1], height / 2), `${label}: y bounds must reach +/- height/2`, bounds);
  assert(approx(bounds.min[2], -depth - 0.001) && approx(bounds.max[2], -0.001), `${label}: z bounds must cover the compressed depth`, bounds);

  const levels = clusteredLevels(mesh.positions);
  assert(levels.length === levelCount, `${label}: expected exactly 2*layers+1 z ring levels`, { actual: levels.length, expected: levelCount, levels });
  const expectedStep = depth / (levelCount - 1);
  for (let i = 0; i < levels.length; i += 1) {
    assert(approx(levels[i], -depth - 0.001 + expectedStep * i), `${label}: ring z levels must be contiguous and evenly ordered`, { i, actual: levels[i] });
  }

  let sawInsetRing = false;
  let sawFullRing = false;
  for (let levelIndex = 0; levelIndex < levels.length; levelIndex += 1) {
    const points = [];
    for (let vertex = 0; vertex < vertexCount; vertex += 1) {
      const point = vertexAt(mesh.positions, vertex);
      if (Math.abs(point[2] - levels[levelIndex]) <= EPS) points.push(point);
    }
    const ringBounds = boundsOf(new Float32Array(points.flat()));
    const edgeDistance = [
      Math.abs(ringBounds.min[0] - expectedX[0]),
      Math.abs(ringBounds.max[0] - expectedX[1]),
      Math.abs(ringBounds.min[1] + height / 2),
      Math.abs(ringBounds.max[1] - height / 2),
    ];
    assert(edgeDistance.every((distance) => distance <= 0.002 + EPS), `${label}: signature seam inset exceeded 0.002`, { levelIndex, edgeDistance });
    if (edgeDistance.some((distance) => distance > EPS)) sawInsetRing = true;
    if (edgeDistance.every((distance) => distance <= EPS)) sawFullRing = true;

    const perimeterKeys = new Set();
    for (const point of points) {
      if (
        Math.abs(point[0] - ringBounds.min[0]) <= EPS ||
        Math.abs(point[0] - ringBounds.max[0]) <= EPS ||
        Math.abs(point[1] - ringBounds.min[1]) <= EPS ||
        Math.abs(point[1] - ringBounds.max[1]) <= EPS
      ) perimeterKeys.add(quantizedPoint(point));
    }
    assert(perimeterKeys.size === ringSize, `${label}: every z level must expose one complete segmented perimeter ring`, { levelIndex, actual: perimeterKeys.size, expected: ringSize });
  }
  assert(sawInsetRing && sawFullRing, `${label}: signature relief must include both subtly inset and full perimeter rings`);

  const bridgeCounts = new Array(levelCount - 1).fill(0);
  for (let offset = 0; offset < mesh.indices.length; offset += 3) {
    const triangleLevels = [
      nearestLevel(levels, mesh.positions[mesh.indices[offset] * 3 + 2]),
      nearestLevel(levels, mesh.positions[mesh.indices[offset + 1] * 3 + 2]),
      nearestLevel(levels, mesh.positions[mesh.indices[offset + 2] * 3 + 2]),
    ];
    const lo = Math.min(...triangleLevels);
    const hi = Math.max(...triangleLevels);
    assert(hi - lo <= 1, `${label}: a side triangle skipped a ring level`, { triangleLevels });
    if (hi === lo + 1) bridgeCounts[lo] += 1;
  }
  assert(bridgeCounts.every((count) => count > 0), `${label}: every adjacent ring pair must be connected`, bridgeCounts);
  return { bounds, levels, vertexCount, triangleCount };
}

function validateTrianglesAndTopology(mesh, label) {
  const bounds = boundsOf(mesh.positions);
  const center = [
    (bounds.min[0] + bounds.max[0]) / 2,
    (bounds.min[1] + bounds.max[1]) / 2,
    (bounds.min[2] + bounds.max[2]) / 2,
  ];
  const canonicalByKey = new Map();
  const canonicalForVertex = [];
  const canonicalPoints = [];
  for (let vertex = 0; vertex < mesh.positions.length / 3; vertex += 1) {
    const point = vertexAt(mesh.positions, vertex);
    const key = quantizedPoint(point);
    if (!canonicalByKey.has(key)) {
      canonicalByKey.set(key, canonicalPoints.length);
      canonicalPoints.push(point);
    }
    canonicalForVertex[vertex] = canonicalByKey.get(key);
  }

  const edges = new Map();
  const adjacency = canonicalPoints.map(() => new Set());
  let signedVolumeTimesSix = 0;
  let minimumOutwardDot = Infinity;
  const triangleCount = mesh.indices.length / 3;
  for (let offset = 0; offset < mesh.indices.length; offset += 3) {
    const raw = [mesh.indices[offset], mesh.indices[offset + 1], mesh.indices[offset + 2]];
    const ids = raw.map((index) => canonicalForVertex[index]);
    assert(new Set(ids).size === 3, `${label}: triangle collapsed after welding`, { triangle: offset / 3, ids });
    const [a, b, c] = raw.map((index) => vertexAt(mesh.positions, index));
    const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    const normal = [
      ab[1] * ac[2] - ab[2] * ac[1],
      ab[2] * ac[0] - ab[0] * ac[2],
      ab[0] * ac[1] - ab[1] * ac[0],
    ];
    const areaSqTimesFour = normal[0] ** 2 + normal[1] ** 2 + normal[2] ** 2;
    assert(areaSqTimesFour > AREA_EPS_SQ, `${label}: degenerate triangle`, { triangle: offset / 3, raw, areaSqTimesFour });
    const centroid = [(a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3];
    const outward = [centroid[0] - center[0], centroid[1] - center[1], centroid[2] - center[2]];
    const outwardDot = normal[0] * outward[0] + normal[1] * outward[1] + normal[2] * outward[2];
    minimumOutwardDot = Math.min(minimumOutwardDot, outwardDot);
    assert(outwardDot > 1e-12, `${label}: triangle winding is not outward`, { triangle: offset / 3, outwardDot });
    signedVolumeTimesSix += a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);

    for (const [from, to] of [[ids[0], ids[1]], [ids[1], ids[2]], [ids[2], ids[0]]]) {
      const key = from < to ? `${from}:${to}` : `${to}:${from}`;
      edges.set(key, (edges.get(key) || 0) + 1);
      adjacency[from].add(to);
      adjacency[to].add(from);
    }
  }
  const badEdges = [...edges].filter(([, incidence]) => incidence !== 2);
  assert(badEdges.length === 0, `${label}: closed mesh must have edge incidence exactly two after positional weld`, badEdges.slice(0, 12));
  assert(signedVolumeTimesSix > 0, `${label}: closed mesh must have positive signed volume for outward winding`, { signedVolumeTimesSix });

  const seen = new Set([0]);
  const queue = [0];
  while (queue.length) {
    const current = queue.pop();
    for (const next of adjacency[current]) if (!seen.has(next)) { seen.add(next); queue.push(next); }
  }
  assert(seen.size === canonicalPoints.length, `${label}: welded mesh must be one connected component`, { visited: seen.size, vertices: canonicalPoints.length });
  const eulerCharacteristic = canonicalPoints.length - edges.size + triangleCount;
  assert(eulerCharacteristic === 2, `${label}: closed paper block must have sphere-like Euler characteristic 2`, { vertices: canonicalPoints.length, edges: edges.size, faces: triangleCount, eulerCharacteristic });
  return { weldedVertices: canonicalPoints.length, edges: edges.size, minimumOutwardDot, signedVolume: signedVolumeTimesSix / 6 };
}

function validateMirror(right, left) {
  assert(right.positions.length === left.positions.length, 'mirrored halves must have equal position counts');
  assert(right.uvs.length === left.uvs.length, 'mirrored halves must have equal uv counts');
  assert(right.indices.length === left.indices.length, 'mirrored halves must have equal index counts');
  const mirroredRight = mirroredPointMultiset(right.positions, true);
  const actualLeft = mirroredPointMultiset(left.positions, false);
  assert(mirroredRight.length === actualLeft.length && mirroredRight.every((key, index) => key === actualLeft[index]), 'left vertex multiset must be the x-mirror of right vertex multiset');
  const rightBounds = boundsOf(right.positions);
  const leftBounds = boundsOf(left.positions);
  assert(approx(leftBounds.min[0], -rightBounds.max[0]) && approx(leftBounds.max[0], -rightBounds.min[0]), 'left/right x bounds must mirror exactly', { rightBounds, leftBounds });
  assert(approx(leftBounds.min[1], rightBounds.min[1]) && approx(leftBounds.max[1], rightBounds.max[1]) && approx(leftBounds.min[2], rightBounds.min[2]) && approx(leftBounds.max[2], rightBounds.max[2]), 'mirrored halves must preserve y/z bounds', { rightBounds, leftBounds });
}

function validateRelief(pageRestRelief) {
  assert(typeof pageRestRelief === 'function', 'pageRestRelief export must be a function');
  const us = [0, 0.03, 0.15, 0.35, 0.6, 0.82, 1];
  const vs = [0, 0.2, 0.5, 0.8, 1];
  const progresses = [0, 0.125, 0.33, 0.5, 0.67, 0.875, 1];
  const hovers = [0, 0.25, 0.6, 1];
  let maxAbs = 0;
  for (const u of us) for (const v of vs) for (const progress of progresses) for (const hover of hovers) {
    const value = pageRestRelief(u, v, progress, hover);
    assert(typeof value === 'number' && Number.isFinite(value), 'pageRestRelief must return a finite scalar across its domain', { u, v, progress, hover, value });
    maxAbs = Math.max(maxAbs, Math.abs(value));
  }
  assert(maxAbs < 0.06, 'pageRestRelief must remain below 0.06 absolute displacement', { maxAbs });

  for (const v of vs) for (const progress of progresses) for (const hover of hovers) {
    assert(pageRestRelief(0, v, progress, hover) === 0, 'pageRestRelief must anchor u=0 at exact zero', { v, progress, hover, value: pageRestRelief(0, v, progress, hover) });
  }
  for (const u of us) for (const v of vs) {
    assert(Math.abs(pageRestRelief(u, v, 0.5, 0)) <= 1e-8, 'pageRestRelief must fade to approximately zero at upright progress 0.5 without hover', { u, v, value: pageRestRelief(u, v, 0.5, 0) });
    const start = pageRestRelief(u, v, 0, 0);
    const end = pageRestRelief(u, v, 1, 0);
    assert(Math.abs(Math.abs(start) - Math.abs(end)) <= 1e-8, 'pageRestRelief endpoint magnitudes must be symmetric', { u, v, start, end });
    assert(approx(pageRestRelief(u, v, 0.37, -5), pageRestRelief(u, v, 0.37, 0), 1e-12), 'hover values below zero must clamp to zero', { u, v });
    assert(approx(pageRestRelief(u, v, 0.37, 5), pageRestRelief(u, v, 0.37, 1), 1e-12), 'hover values above one must clamp to one', { u, v });
  }
  const endpointPeak = Math.max(...us.flatMap((u) => vs.map((v) => Math.abs(pageRestRelief(u, v, 0, 0)))));
  assert(endpointPeak > 1e-6, 'pageRestRelief should provide nonzero resting relief away from the gutter', { endpointPeak });
  const hoverDelta = Math.max(...us.flatMap((u) => vs.map((v) => Math.abs(pageRestRelief(u, v, 0.5, 1) - pageRestRelief(u, v, 0.5, 0)))));
  assert(hoverDelta > 1e-6 && hoverDelta < 0.06, 'bounded hover should have a finite, nontrivial effect', { hoverDelta });
  return { maxAbs, endpointPeak, hoverDelta };
}

async function runGroup(name, fn) {
  const before = assertions;
  const started = Date.now();
  try {
    const details = await fn();
    groups.push({ name, ok: true, assertions: assertions - before, ms: Date.now() - started, details });
  } catch (error) {
    groups.push({ name, ok: false, assertions: assertions - before, ms: Date.now() - started, error: error.message, details: error.details });
    throw error;
  }
}

(async () => {
  const modulePath = parseModulePath(process.argv);
  let api;
  try {
    api = await importSourceAsDataUrl(modulePath);
    await runGroup('exports', () => {
      assert(typeof api.createPaperBlock === 'function', 'createPaperBlock export must be a function');
      assert(typeof api.pageRestRelief === 'function', 'pageRestRelief export must be a function');
    });

    const defaults = { width: 1.55, height: 2.1, depth: 0.072, layers: 8, segments: 12 };
    const right = api.createPaperBlock({ ...defaults, side: 1 });
    const left = api.createPaperBlock({ ...defaults, side: -1 });
    await runGroup('default right geometry', () => ({
      shape: validateShape(right, { ...defaults, side: 1 }, 'right'),
      topology: validateTrianglesAndTopology(right, 'right'),
    }));
    await runGroup('default left geometry', () => ({
      shape: validateShape(left, { ...defaults, side: -1 }, 'left'),
      topology: validateTrianglesAndTopology(left, 'left'),
    }));
    await runGroup('mirror and budget', () => {
      validateMirror(right, left);
      const rightTriangles = right.indices.length / 3;
      const leftTriangles = left.indices.length / 3;
      assert(rightTriangles + leftTriangles < 4000, 'both default paper halves must stay below the 4000-triangle budget', { rightTriangles, leftTriangles, combined: rightTriangles + leftTriangles });
      return { rightTriangles, leftTriangles, combined: rightTriangles + leftTriangles };
    });

    const custom = { width: 1.2, height: 1.8, depth: 0.09, layers: 3, segments: 5 };
    const customRight = api.createPaperBlock({ ...custom, side: 1 });
    const customLeft = api.createPaperBlock({ ...custom, side: -1 });
    await runGroup('custom parameter geometry', () => {
      const rightShape = validateShape(customRight, { ...custom, side: 1 }, 'custom right');
      const leftShape = validateShape(customLeft, { ...custom, side: -1 }, 'custom left');
      validateMirror(customRight, customLeft);
      return { rightShape, leftShape };
    });
    await runGroup('page rest relief', () => validateRelief(api.pageRestRelief));
  } catch (error) {
    process.stdout.write(`${JSON.stringify({ ok: false, module: modulePath, assertions, groups, error: error.message, details: error.details }, null, 2)}\n`);
    process.exitCode = 1;
    return;
  }
  process.stdout.write(`${JSON.stringify({ ok: true, module: modulePath, assertions, groups }, null, 2)}\n`);
})();

