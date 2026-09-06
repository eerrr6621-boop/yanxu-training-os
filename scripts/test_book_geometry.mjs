import assert from 'node:assert/strict';
import { bookPageAngle, createBookPageGeometry, easeBookProgress } from '../web/scene/book-surface.js';

const page = createBookPageGeometry();
const { width: W, height: H, cols: C, rows: R, positions, indices, uvs } = page;
const stride = C + 1;
const dx = W / C;
const dy = H / R;
const initialUvs = new Float32Array(uvs);
const initialIndices = new Uint16Array(indices);
const point = (i, j) => Array.from(positions.subarray((j * stride + i) * 3, (j * stride + i) * 3 + 3));
const sub = (a, b) => a.map((value, i) => value - b[i]);
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const length = (a) => Math.hypot(...a);
const distance = (a, b) => length(sub(a, b));
const close = (actual, expected, tolerance, message) => assert.ok(Math.abs(actual - expected) <= tolerance, `${message}: ${actual} vs ${expected}`);

assert.equal(positions.length, 49 * 13 * 3);
assert.equal(uvs.length, 49 * 13 * 2);
assert.equal(indices.length, 48 * 12 * 6);
assert.ok(indices instanceof Uint16Array);

for (const progress of [0, 1]) {
  page.update(progress);
  for (let j = 0; j <= R; j++) {
    for (let i = 0; i <= C; i++) {
      const [x, y, z] = point(i, j);
      close(x, (progress ? -1 : 1) * i * dx, 1e-7, 'endpoint x');
      close(y, j * dy - H / 2, 1e-7, 'endpoint y');
      assert.equal(z, 0, 'endpoint must be exactly flat');
    }
  }
  const normal = cross(sub(point(1, 0), point(0, 0)), sub(point(0, 1), point(0, 0)));
  assert.ok(progress === 0 ? normal[2] > 0 : normal[2] < 0, 'correct physical front-face winding');
}

const worst = { horizontalStrain: 0, verticalStrain: 0, diagonalStrain: 0, areaStrain: 0 };
for (let step = 0; step <= 100; step++) {
  const progress = step / 100;
  assert.equal(page.update(progress), positions, 'update reuses the position array');
  for (let j = 0; j <= R; j++) {
    const spine = point(0, j);
    assert.ok(spine[0] === 0 && spine[2] === 0, 'spine cannot move');
    close(spine[1], j * dy - H / 2, 1e-7, 'spine y');
    for (let i = 0; i <= C; i++) {
      const a = point(i, j);
      assert.ok(a.every(Number.isFinite), 'finite positions');
      close(a[1], j * dy - H / 2, 1e-7, 'rows remain at distinct y values');
      assert.ok(a[2] >= 0, 'paper stays above the book plane');
      assert.ok(length(a) <= page.boundingSphere.radius + 1e-6, 'conservative sphere encloses the full animation');
      if (i < C) {
        const b = point(i + 1, j);
        const strain = Math.abs(distance(a, b) / dx - 1);
        worst.horizontalStrain = Math.max(worst.horizontalStrain, strain);
        assert.ok(strain < 1e-5, 'horizontal arc length is preserved');
        if (progress > 0 && progress < 1) assert.ok(b[2] > a[2], 'monotone z rules out row self-intersection');
        if (progress === 0) assert.ok(b[0] > a[0]);
        if (progress === 1) assert.ok(b[0] < a[0]);
      }
      if (j < R) {
        const c = point(i, j + 1);
        const strain = Math.abs(distance(a, c) / dy - 1);
        worst.verticalStrain = Math.max(worst.verticalStrain, strain);
        assert.ok(strain < 0.006, 'y-dependent corner curl causes only small vertical strain');
      }
      if (i < C && j < R) {
        const b = point(i + 1, j);
        const c = point(i, j + 1);
        const d = point(i + 1, j + 1);
        for (const [start, end] of [[a, d], [b, c]]) {
          const strain = Math.abs(distance(start, end) / Math.hypot(dx, dy) - 1);
          worst.diagonalStrain = Math.max(worst.diagonalStrain, strain);
          assert.ok(strain < 0.012, 'small diagonal/shear distortion');
        }
        for (const [origin, first, second] of [[a, b, c], [b, d, c]]) {
          const doubledArea = length(cross(sub(first, origin), sub(second, origin)));
          const strain = Math.abs(doubledArea / (dx * dy) - 1);
          worst.areaStrain = Math.max(worst.areaStrain, strain);
          assert.ok(doubledArea > 1e-8, 'no degenerate triangles');
          assert.ok(strain < 0.012, 'small triangle area distortion');
        }
        // Every intermediate-y section meets this quad along a -> diagonal ->
        // right-edge path. Its z stays monotone, including the actual triangles.
        if (progress > 0 && progress < 1) {
          for (const q of [0.25, 0.5, 0.75]) {
            const leftZ = a[2] * (1 - q) + c[2] * q;
            const diagonalZ = b[2] * (1 - q) + c[2] * q;
            const rightZ = b[2] * (1 - q) + d[2] * q;
            assert.ok(leftZ < diagonalZ && diagonalZ < rightZ, 'no triangle-sheet self-intersection');
          }
        }
      }
    }
  }
  assert.deepEqual(uvs, initialUvs, 'UVs never move or mirror during deformation');
  assert.deepEqual(indices, initialIndices, 'topology never changes');
}

for (let k = 0; k < indices.length; k += 3) {
  const [a, b, c] = indices.subarray(k, k + 3);
  const uvArea = (uvs[b * 2] - uvs[a * 2]) * (uvs[c * 2 + 1] - uvs[a * 2 + 1])
    - (uvs[b * 2 + 1] - uvs[a * 2 + 1]) * (uvs[c * 2] - uvs[a * 2]);
  assert.ok(uvArea > 0, 'UV winding stays positive');
}

for (const u of [0, 0.25, 0.5, 0.75, 1]) {
  for (const v of [0, 0.5, 1]) {
    let previous = -1;
    for (let step = 0; step <= 100; step++) {
      const theta = bookPageAngle(u, v, step / 100);
      assert.ok(theta >= 0 && theta <= Math.PI);
      assert.ok(theta >= previous, 'no backward angular motion');
      previous = theta;
    }
  }
}

// The corner curl must exist, but must disappear precisely at both endpoints.
page.update(0.5);
assert.ok(distance(point(C, R), [point(C, 0)[0], H / 2, point(C, 0)[2]]) > 0.01, 'visible but mild upper-corner curl');
assert.equal(easeBookProgress(-1), 0);
assert.equal(easeBookProgress(2), 1);
for (const exponent of [4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]) {
  for (const t of [10 ** -exponent, 1 - 10 ** -exponent]) {
    const eased = easeBookProgress(t);
    assert.ok(eased >= 0 && eased <= 1, 'easing must not numerically overshoot');
    page.update(t);
    for (let i = 2; i < positions.length; i += 3) assert.ok(positions[i] >= 0, 'no tiny negative-Z endpoint curl');
  }
}
for (const t of [0.01, 0.1, 0.3, 0.5, 0.8, 0.99]) {
  page.update(t);
  const before = new Float32Array(positions);
  page.update(t + 1e-5);
  for (let i = 0; i < positions.length; i += 3) {
    assert.ok(Math.hypot(positions[i] - before[i], positions[i + 1] - before[i + 1], positions[i + 2] - before[i + 2]) < 0.0002, 'temporal continuity');
  }
}
assert.throws(() => page.update(NaN), RangeError);
assert.throws(() => page.update(Infinity), RangeError);
assert.throws(() => createBookPageGeometry({ width: 0 }), RangeError);
assert.throws(() => createBookPageGeometry({ rows: 2.5 }), RangeError);
assert.throws(() => createBookPageGeometry({ bend: 0.9, cornerCurl: 0.2 }), RangeError);

console.log('PASS: endpoints, finite values, fixed spine, shared-edge continuity, no self-intersection, stable UVs, bounded strain, monotone angles, bounds and validation.');
console.log(JSON.stringify({ vertices: positions.length / 3, triangles: indices.length / 3, samples: 101, worstStrainPercent: Object.fromEntries(Object.entries(worst).map(([key, value]) => [key, Number((value * 100).toFixed(5))])) }, null, 2));
