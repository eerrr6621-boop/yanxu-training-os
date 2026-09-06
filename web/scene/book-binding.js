// Static, compressed paper signatures. One closed mesh per half, not loose sheets.
export function createPaperBlock({ width = 1.55, height = 2.1, depth = .072, layers = 8, segments = 12, side = 1 } = {}) {
  if (![width, height, depth].every((n) => Number.isFinite(n) && n > 0) || !Number.isInteger(layers) || layers < 1 || layers > 32 || !Number.isInteger(segments) || segments < 1 || segments > 32 || ![-1, 1].includes(side)) throw new RangeError('Invalid paper block dimensions');
  const positions = [], uvs = [], indices = [], ring = segments * 4;
  for (let level = 0; level <= layers * 2; level += 1) {
    const t = level / (layers * 2), inset = level % 2 ? 0 : .0012;
    for (let edge = 0; edge < 4; edge += 1) for (let segment = 0; segment < segments; segment += 1) {
      const f = segment / segments;
      const corners = [[0, -height / 2 + inset], [width - inset, -height / 2 + inset], [width - inset, height / 2 - inset], [0, height / 2 - inset]];
      const a = corners[edge], b = corners[(edge + 1) % 4];
      positions.push(side * (a[0] + (b[0] - a[0]) * f), a[1] + (b[1] - a[1]) * f, -.001 - depth + t * depth);
      uvs.push((edge + f) / 4, t);
      if (level < layers * 2) {
        const i = level * ring + edge * segments + segment, j = level * ring + (edge * segments + segment + 1) % ring;
        indices.push(i, j, i + ring, j, j + ring, i + ring);
      }
    }
  }
  for (const top of [false, true]) {
    const center = positions.length / 3, start = top ? layers * 2 * ring : 0;
    positions.push(side * width / 2, 0, top ? -.001 : -.001 - depth); uvs.push(.5, top ? 1 : 0);
    for (let i = 0; i < ring; i += 1) {
      const a = start + i, b = start + (i + 1) % ring;
      indices.push(center, top ? a : b, top ? b : a);
    }
  }
  if (side < 0) for (let i = 0; i < indices.length; i += 3) [indices[i + 1], indices[i + 2]] = [indices[i + 2], indices[i + 1]];
  return { positions: new Float32Array(positions), uvs: new Float32Array(uvs), indices: new Uint16Array(indices) };
}

// Resting shoulder near the gutter fades away as the page stands upright.
export function pageRestRelief(u, v, progress, hover = 0) {
  const p = Math.min(1, Math.max(0, progress));
  const eased = p * p * p * (p * (p * 6 - 15) + 10);
  const settle = Math.cos(Math.PI * eased) ** 4;
  const broad = .023 * Math.sin(Math.PI * u);
  const gutter = .008 * (1 - Math.exp(-u / .06)) * Math.exp(-u / .22) * (1 - u);
  const longitudinal = .0025 * Math.sin(Math.PI * u) * (1 - (2 * v - 1) ** 2);
  return settle * (broad + gutter + longitudinal) + Math.min(1, Math.max(0, hover)) * .02 * u ** 5 * (1 - v) ** 4;
}
