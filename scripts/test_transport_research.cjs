'use strict';

// Offline synthetic fixtures only. Does not modify production config or contact a provider.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-transport-audit-test.'));
const java = process.env.JAVA || '/Users/tongyuqing/.local/bin/java';
const classes = path.join(work, 'classes');
let checks = 0;
const check = (value, message) => { assert.ok(value, message); checks++; };
const run = (args, ok = true) => {
  const proc = spawnSync(java, args, { cwd: root, encoding: 'utf8', timeout: 60000,
    env: { ...process.env, YANXU_NEARBY_MAX_MINUTES: '240' }, maxBuffer: 4 * 1024 * 1024 });
  if (ok) assert.equal(proc.status, 0, proc.stderr || proc.stdout || proc.error?.message);
  else assert.notEqual(proc.status, 0, 'Expected fail-closed invocation');
  return proc;
};
run(['-jar', path.join(root, 'lib/ecj.jar'), '-17', '-encoding', 'UTF-8', '-d', classes,
  '-sourcepath', path.join(root, 'src'), path.join(root, 'scripts/TransportCoverageAudit.java')]);

const day = '2026-09-07';
const NJ = ['江苏', '南京'], HF = ['安徽', '合肥'], SH = ['上海', '上海'], BJ = ['北京', '北京'];
const SZ = ['江苏', '苏州'], HZ = ['浙江', '杭州'], GZ = ['广东', '广州'], NB = ['浙江', '宁波'];
const WH = ['湖北', '武汉'], CS = ['湖南', '长沙'], CD = ['四川', '成都'], TJ = ['天津', '天津'];
const FZ = ['福建', '福州'], HK = ['海南', '海口'];
const time = minutes => `${String(Math.floor(minutes / 60) % 24).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
const source = (from, to, date = day) => ({ from_province: from[0], from_city: from[1], to_province: to[0], to_city: to[1],
  observed_on: date, source_service_date: date, source_url: 'https://example.test/synthetic-not-real-transport?api_key=DO_NOT_LEAK',
  source_provider: 'SYNTHETIC TEST ONLY', usage_authorized: true, synthetic_test_fixture: true });
const rail = (from, to, minutes, date = day) => ({ ...source(from, to, date), status: 'observed_timetable', samples: [{
  train_no: 'G1001', from_station: `合成${from[1]}站`, to_station: `合成${to[1]}站`, departure_time: '08:00',
  arrival_time: time(480 + minutes), arrival_day_offset: Math.floor((480 + minutes) / 1440),
  rail_minutes: minutes, station_city_mapping_checked: true }] });
const complete = (from, to, mode, minutes) => ({ ...source(from, to), coverage_complete: true,
  scope: mode === 'rail' ? 'all_city_stations_and_rail_itineraries' : 'all_city_airports_nonstop_services',
  minimum_minutes: minutes, no_service: false });
const flight = (from, to, minutes) => ({ ...source(from, to), flight_no: 'ZZ9999', from_airport_code: 'AAA', to_airport_code: 'BBB',
  departure_time: '08:00', arrival_time: time(480 + minutes), arrival_day_offset: 0, flight_minutes: minutes,
  airport_city_mapping_checked: true, nonstop: true, stops: 0, service_type: 'passenger', service_mode: 'air',
  cancelled: false, timezone: 'Asia/Shanghai' });
const rails = [], transport = { version: 'transport-matrix-v1', rail_checks: [], air_checks: [], flights: [] };
const pair = (other, outward, returning, date = day) => rails.push(rail(NJ, other, outward, date), rail(other, NJ, returning, date));
const railChecks = (other, outward, returning) => transport.rail_checks.push(complete(NJ, other, 'rail', outward), complete(other, NJ, 'rail', returning));
pair(HF, 100, 110);
rails.push(rail(NJ, SH, 90));
pair(BJ, 250, 260);
pair(SZ, 80, 80, '2026-07-01');
pair(HZ, 300, 310); railChecks(HZ, 300, 310); transport.flights.push(flight(NJ, HZ, 120), flight(HZ, NJ, 130));
pair(GZ, 300, 320); railChecks(GZ, 300, 320);
transport.air_checks.push(complete(NJ, GZ, 'air', 200), complete(GZ, NJ, 'air', 210));
const invalidA = rail(NJ, NB, 60), invalidB = rail(NB, NJ, 60);
invalidA.samples[0].station_city_mapping_checked = false; invalidB.samples[0].rail_minutes = 61;
rails.push(invalidA, invalidB);
transport.rail_checks.push({ ...complete(NJ, WH, 'rail', 300), coverage_complete: false });
pair(CS, 239, 240);
pair(CD, 300, 320); railChecks(CD, 300, 320); transport.flights.push(flight(NJ, CD, 180), flight(CD, NJ, 170));
pair(TJ, 120, 120, '2026-09-08');
pair(FZ, 300, 320);
transport.rail_checks.push({ ...complete(NJ, FZ, 'rail', 300), usage_authorized: false }, complete(FZ, NJ, 'rail', 320));
transport.flights.push(flight(NJ, FZ, 100), flight(FZ, NJ, 110));
rails.push({ ...source(NJ, HK), status: 'unresolved', samples: [] }, { ...source(HK, NJ), status: 'unresolved', samples: [] });
rails.push(rail(['不存在省份', '不存在城市'], NJ, 60));
const railFile = path.join(work, 'synthetic-rails.json'), transportFile = path.join(work, 'synthetic-transport.json');
fs.writeFileSync(railFile, JSON.stringify({ version: 'rail-timetable-v1', directions: rails }));
fs.writeFileSync(transportFile, JSON.stringify(transport));
const output = path.join(work, 'audit');
const args = ['-Ddispatch.nearby.max.minutes=240', '-cp', classes, 'com.training.TransportCoverageAudit', '--rail-file', railFile,
  '--transport-file', transportFile, '--today', day, '--output-dir', output, '--cross-check-all',
  '--verify-catalog-city', '江苏/南京', '--verify-catalog-city', '安徽/合肥', '--verify-catalog-city', '浙江/杭州'];
run(args);
const read = file => JSON.parse(fs.readFileSync(path.join(output, file), 'utf8'));
const summary = read('summary.json'), nanjing = read('江苏-南京.json');
const states = new Map(nanjing.routes.map(r => [r.origin.city, r.status]));
for (const [city, expected] of Object.entries({ 南京: 'same_city', 合肥: 'rail_reference_eligible', 上海: 'pending_oneway', 北京: 'pending_slow_sample',
  苏州: 'pending_stale', 杭州: 'air_reference_eligible', 广州: 'outside_verified', 宁波: 'pending_invalid_evidence', 武汉: 'pending_incomplete_exclusion',
  长沙: 'pending_slow_sample', 成都: 'pending_incomplete_exclusion', 天津: 'pending_stale', 拉萨: 'pending_missing',
  福州: 'pending_slow_sample', 海口: 'pending_missing' })) {
  check(states.get(city) === expected, `${city}: expected ${expected}, got ${states.get(city)}`);
}
check(summary.directory_city_count === 371, 'Frozen current product directory contains 371 cities');
check(summary.business_import_allowed === false && summary.commercial_permission_validated === false, 'Audit does not establish commercial permission or business import approval');
check(summary.directed_cross_city_universe === 137270, 'All directed cross-city combinations enumerated');
check(summary.unordered_cross_city_universe === 68635, 'All unordered cross-city combinations enumerated');
check(summary.enumerated_total_routes === 137641, 'Same-city entries counted separately');
check(summary.reference_resolved_unordered_pairs === 3, 'Only synthetic rail/air/outside pairs resolved');
check(summary.pending_unordered_pairs === 68632, 'Remaining city pairs retained as pending');
check(summary.reference_resolved_directed_routes === 6, 'Two directions counted for each resolved pair');
check(summary.ignored_unknown_city_rows === 1, 'Unknown directory mapping reported, not admitted');
check(summary.indexed_full_scan_cross_check === true, 'Indexed resolver equals unfiltered production resolver across all entries');
check(summary.dispatch_catalog_parity_cities.length === 3, 'Three production DispatchPriority catalogs agree');
check(!summary.enumeration_is_timetable_verification && !summary.human_research_performed_by_this_script && !summary.all_city_evidence_resolved,
  'Enumeration is not represented as source verification');
const files = fs.readdirSync(output).filter(name => name.endsWith('.json'));
check(files.length === 372, 'One file per city plus summary');
const originKeys = new Set();
for (const file of files.filter(f => f !== 'summary.json')) {
  const result = read(file);
  assert.equal(result.routes.length, 371); assert.equal(result.cross_city_route_count, 370);
  assert.equal(result.routes.filter(r => r.status === 'same_city').length, 1);
  assert.equal(new Set(result.routes.map(r => `${r.origin.province}/${r.origin.city}`)).size, 371);
  assert.equal(result.routes.filter(r => r.status.startsWith('pending_')).length, result.pending_cross_city_count);
  assert.equal(result.next_verification_queue.length, result.pending_cross_city_count);
  assert.ok(!fs.readFileSync(path.join(output, file), 'utf8').includes('DO_NOT_LEAK'));
  originKeys.add(`${result.destination.province}/${result.destination.city}`); checks += 7;
}
check(originKeys.size === 371, 'Each destination appears exactly once');
const duplicate = run(args, false);
check(duplicate.stderr.includes('Output must be new'), 'Existing result directory is protected');
const publicParent = path.join(work, 'public'); fs.mkdirSync(publicParent);
const publicArgs = args.slice(); publicArgs[publicArgs.indexOf('--output-dir') + 1] = path.join(publicParent, 'bad');
check(run(publicArgs, false).stderr.includes('Public output directory'), 'Web/public outputs are refused');

// Verify batches are accepted through directions only; pending nested observations are not admitted.
const batches = path.join(work, 'research'); fs.mkdirSync(batches);
fs.writeFileSync(path.join(batches, 'batch.json'), JSON.stringify({ version: 'transport-city-research-v1', directions: [rail(SH, NJ, 95)],
  pending_city_reviews: [{ city: '北京', outbound_observation: rail(NJ, BJ, 90), inbound_observation: rail(BJ, NJ, 90) }] }));
fs.writeFileSync(path.join(batches, 'nested.json'), JSON.stringify({ version: 'transport-city-research-v1', cities: [
  { city: '宁波', status: 'bidirectional_rail_under_240_observed', directions: [rail(NJ, NB, 80), rail(NB, NJ, 90)] },
  { city: '武汉', status: 'pending_verification', directions: [rail(NJ, WH, 100)] }
] }));
const batchOut = path.join(work, 'audit-batch');
run(['-Ddispatch.nearby.max.minutes=240', '-cp', classes, 'com.training.TransportCoverageAudit', '--rail-file', railFile,
  '--transport-file', transportFile, '--today', day, '--research-dir', batches, '--output-dir', batchOut]);
const batch = JSON.parse(fs.readFileSync(path.join(batchOut, '江苏-南京.json')));
check(batch.routes.find(r => r.origin.city === '上海').status === 'rail_reference_eligible', 'A valid return direction completes the batch pair');
check(batch.routes.find(r => r.origin.city === '北京').status === 'pending_slow_sample', 'Pending nested notes do not become valid direction evidence');
check(batch.routes.find(r => r.origin.city === '宁波').status === 'rail_reference_eligible', 'Nested cities[].directions uses production validation');
const partialWuhan = batch.routes.find(r => r.origin.city === '武汉');
check(partialWuhan.status.startsWith('pending_') && partialWuhan.rail_evidence.return.status === 'current_sample', 'Pending city one-way direction remains visible without fabricating the missing reverse');
const capOut = path.join(work, 'audit-cap-zero');
run(['-Ddispatch.nearby.max.minutes=0', '-cp', classes, 'com.training.TransportCoverageAudit', '--rail-file', railFile,
  '--transport-file', transportFile, '--today', day, '--output-dir', capOut]);
const cap = JSON.parse(fs.readFileSync(path.join(capOut, '江苏-南京.json')));
check(cap.routes.find(r => r.origin.city === '合肥').status === 'pending_policy_limit', 'Administrator cap blocks rail');
check(cap.routes.find(r => r.origin.city === '杭州').status === 'pending_policy_limit', 'Administrator cap blocks air');
check(cap.routes.find(r => r.origin.city === '南京').status === 'same_city', 'Administrator cap preserves same city');
console.log(JSON.stringify({ status: 'passed', checks, work_directory: work, synthetic_test_only: true,
  real_transport_verification_performed: false, full_directory_entries_cross_checked: 137641, catalog_destinations_cross_checked: 3 }, null, 2));
