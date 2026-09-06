// Optical print placement is independent of each generated image's transparent margins.
export const HEADLINES = Object.freeze([
  { key: 'headlineOperations', title: '培训运营', image: '/assets/book-headline-operations-v13r9.webp', bounds: { x: 18, y: 22, width: 925, height: 197 } },
  { key: 'headlineFaculty', title: '师资推荐', image: '/assets/book-headline-faculty-v13r9.webp', bounds: { x: 64, y: 20, width: 832, height: 200 } },
  { key: 'headlineDelivery', title: '课程交付', image: '/assets/book-headline-delivery-v13r9.webp', bounds: { x: 41, y: 21, width: 880, height: 198 } },
  { key: 'headlineProjects', title: '项目管理', image: '/assets/book-headline-projects-v13r9.webp', bounds: { x: 53, y: 20, width: 853, height: 200 } },
].map(item => Object.freeze({ ...item, bounds: Object.freeze(item.bounds) })));

export function fitHeadlinePrint(bounds, imageWidth = 960, imageHeight = 240) {
  const { x, y, width, height } = bounds || {};
  if (![x, y, width, height, imageWidth, imageHeight].every(Number.isFinite) ||
      x < 0 || y < 0 || width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0 ||
      x + width > imageWidth || y + height > imageHeight) throw new RangeError('Invalid headline ink bounds');
  const scale = Math.min(600 / width, 128 / height);
  return Object.freeze({ x: (768 - width * scale) / 2 - x * scale, y: 492 - (y + height) * scale,
    width: imageWidth * scale, height: imageHeight * scale, scale });
}
