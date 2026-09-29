import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';

const [java, classes] = process.argv.slice(2);
assert.ok(java && classes, 'Pass the Java executable and isolated compiled classes directory.');
// Load as a data module without depending on the host's package.json type.
const source = await readFile(new URL('../web/modules/settlement/conversion.js', import.meta.url), 'utf8');
const { convertMinutesToClassHours } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const vectors = JSON.parse(execFileSync(java, ['-cp', classes, 'com.training.M05DeliverySettlementHoursTest', '--vectors'], { encoding: 'utf8' }));
let passed = 0;
for (const { minutes, classHours } of vectors) {
  assert.equal(convertMinutesToClassHours(minutes), classHours, `${minutes} minutes must agree with Java`);
  passed++;
}
for (const bad of ['', ' 60', '60 ', '+60', '-1', '.5', '1.', '1e2', 'NaN', '0.123456789', '1'.repeat(25), '0'.repeat(41), '999999999999999999999999']) {
  assert.throws(() => convertMinutesToClassHours(bad), `${bad} must be rejected, not silently rounded or zeroed`);
  passed++;
}
console.log(`M05 Java/JavaScript class-hour parity: ${passed} assertions passed`);
