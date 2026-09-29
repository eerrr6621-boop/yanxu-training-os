/** Actual class-hour reference only. This helper never calculates money. */
export function convertMinutesToClassHours(text, minutesPerUnit = '45', scale = 2) {
  if (minutesPerUnit !== '45' || scale !== 2) throw new RangeError('当前仅支持45分钟/课时，课时保留两位小数。');
  if (typeof text !== 'string' || text === '') throw new TypeError('请填写授课分钟数。');
  if (text.length > 40) throw new RangeError('分钟数最多输入40个字符。');
  if (!/^[0-9]+(?:\.[0-9]+)?$/.test(text)) throw new TypeError('请输入非负分钟数，例如60或67.5；不要包含空格或其他符号。');
  const [whole, fraction = ''] = text.split('.');
  if (fraction.length > 8) throw new RangeError('分钟数最多保留8位小数。');
  const digits = (whole + fraction).replace(/^0+/, '') || '0';
  if (digits.length > 24) throw new RangeError('分钟数最多包含24位有效数字。');
  const numerator = BigInt(digits) * 100n;
  const denominator = 45n * (10n ** BigInt(fraction.length));
  const rounded = numerator / denominator + (numerator % denominator * 2n >= denominator ? 1n : 0n);
  if (rounded.toString().length > 24) throw new RangeError('换算后的课时超出可保存范围，请核对分钟数。');
  const fixed = rounded.toString().padStart(3, '0');
  return `${fixed.slice(0, -2)}.${fixed.slice(-2)}`;
}
