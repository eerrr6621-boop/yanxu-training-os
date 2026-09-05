/**
 * A dependency-free deforming book leaf for THREE.BufferGeometry.
 * XY is the flat book plane; x=0 is the fixed spine; +Z faces the reader.
 * No texture/UV coordinates are changed during deformation.
 */

const DEFAULTS = Object.freeze({
  width: 1.55,
  height: 2.1,
  cols: 48,
  rows: 12,
  bend: 0.58,
  cornerCurl: 0.12,
});

function finite(value, name) {
  if (!Number.isFinite(value)) throw new RangeError(`${name} must be finite`);
  return value;
}

function configuration(options = {}) {
  const config = { ...DEFAULTS, ...options };
  for (const key of ['width', 'height']) {
    if (finite(config[key], key) <= 0) throw new RangeError(`${key} must be positive`);
  }
  for (const key of ['cols', 'rows']) {
    if (!Number.isInteger(config[key]) || config[key] < 1) {
      throw new RangeError(`${key} must be a positive integer`);
    }
  }
  for (const key of ['bend', 'cornerCurl']) {
    if (finite(config[key], key) < 0) throw new RangeError(`${key} must be nonnegative`);
  }
  // The strict upper bound proves 0 < theta < PI at every interior progress,
  // and d(theta)/d(easedProgress) > 0. Do not remove this safety constraint.
  if (config.bend + config.cornerCurl >= 1) {
    throw new RangeError('bend + cornerCurl must be less than 1 radian');
  }
  return config;
}

export function easeBookProgress(progress) {
  const t = Math.max(0, Math.min(1, finite(progress, 'progress')));
  // Symmetry avoids cancellation near t=1, where direct polynomial evaluation
  // can overshoot 1 by a few ulps and incorrectly bend the page below Z=0.
  const q = t <= 0.5 ? t : 1 - t;
  const low = q * q * q * (10 + q * (-15 + 6 * q));
  return t <= 0.5 ? low : 1 - low;
}

/** Tangent angle at normalized page position (u,v), in radians. */
export function bookPageAngle(u, v, progress, options = {}) {
  if (!Number.isFinite(u) || u < 0 || u > 1 || !Number.isFinite(v) || v < 0 || v > 1) {
    throw new RangeError('u and v must be in [0, 1]');
  }
  const { bend, cornerCurl } = configuration(options);
  const p = easeBookProgress(progress);
  if (p === 0) return 0;
  if (p === 1) return Math.PI;
  const g = 2 * u * u * (3 - 2 * u) - 1;
  const angleOffset = bend * g + cornerCurl * v ** 4 * u ** 3;
  return Math.PI * p + Math.sin(Math.PI * p) * angleOffset;
}

/**
 * Returns stable typed arrays and an allocation-free update(progress) method.
 * Front faces have +Z normals when progress=0 and -Z when progress=1.
 * Integration uses a tangent at each cell midpoint, preserving each horizontal
 * mesh-edge length exactly before Float32 rounding. Tiny y-dependent curl
 * introduces only sub-percent stretch with the default dimensions/settings.
 *
 * THREE adapter:
 *   const leaf = createPageSurface();
 *   geometry.setAttribute('position', new THREE.BufferAttribute(leaf.positions, 3));
 *   geometry.setAttribute('uv', new THREE.BufferAttribute(leaf.uvs, 2));
 *   geometry.setIndex(new THREE.BufferAttribute(leaf.indices, 1));
 *   // On each animated frame:
 *   leaf.update(progress);
 *   geometry.attributes.position.needsUpdate = true;
 *   geometry.computeVertexNormals();
 *
 * Assign the conservative bounding sphere below once; a sphere computed only
 * from the initial right-hand page can wrongly cull the later left-hand page.
 */
export function createPageSurface(options = {}) {
  const config = configuration(options);
  const { width, height, cols, rows, bend, cornerCurl } = config;
  const stride = cols + 1;
  const vertexCount = stride * (rows + 1);
  const positions = new Float32Array(vertexCount * 3);
  const uvs = new Float32Array(vertexCount * 2);
  const IndexArray = vertexCount > 65536 ? Uint32Array : Uint16Array;
  const indices = new IndexArray(cols * rows * 6);
  const widthStep = width / cols;
  const baseAngles = new Float64Array(cols);
  const cornerWeights = new Float64Array(cols);
  for (let i = 0; i < cols; i++) {
    const u = (i + 0.5) / cols;
    baseAngles[i] = bend * (2 * u * u * (3 - 2 * u) - 1);
    cornerWeights[i] = cornerCurl * u ** 3;
  }
  for (let j = 0; j <= rows; j++) {
    for (let i = 0; i <= cols; i++) {
      const vertex = j * stride + i;
      uvs[vertex * 2] = i / cols;
      uvs[vertex * 2 + 1] = j / rows;
    }
  }
  let offset = 0;
  for (let j = 0; j < rows; j++) {
    for (let i = 0; i < cols; i++) {
      const a = j * stride + i;
      const b = a + 1;
      const c = a + stride;
      const d = c + 1;
      indices[offset++] = a;
      indices[offset++] = b;
      indices[offset++] = c;
      indices[offset++] = b;
      indices[offset++] = d;
      indices[offset++] = c;
    }
  }

  function update(progress) {
    const p = easeBookProgress(progress);
    const phase = Math.PI * p;
    const envelope = Math.sin(phase);
    const flat = p === 0 || p === 1;
    for (let j = 0; j <= rows; j++) {
      const v = j / rows;
      const y = (v - 0.5) * height;
      const cornerWeight = v ** 4;
      let x = 0;
      let z = 0;
      for (let i = 0; i <= cols; i++) {
        const vertex = (j * stride + i) * 3;
        // Explicit endpoint branch avoids residual sin(PI) lift and drift.
        positions[vertex] = flat ? (p === 0 ? 1 : -1) * i * widthStep : x;
        positions[vertex + 1] = y;
        positions[vertex + 2] = flat ? 0 : z;
        if (i < cols && !flat) {
          const theta = phase + envelope * (baseAngles[i] + cornerWeights[i] * cornerWeight);
          x += widthStep * Math.cos(theta);
          z += widthStep * Math.sin(theta);
        }
      }
    }
    return positions;
  }

  update(0);
  return {
    ...config,
    positions,
    indices,
    uvs,
    update,
    boundingSphere: { center: [0, 0, 0], radius: Math.hypot(width, height / 2) },
    boundingBox: { min: [-width, -height / 2, 0], max: [width, height / 2, width] },
  };
}

// Descriptive compatibility alias for standalone geometry consumers.
export const createBookPageGeometry = createPageSurface;
