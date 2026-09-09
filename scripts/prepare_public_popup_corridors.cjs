#!/usr/bin/env node
'use strict';

// Offline research only. Never called by the server and never grants a route.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const COLUMNS = ['站序', '站名', '到站时间', '出发时间', '停留时间'];
const need = (ok, why) => { if (!ok) throw new Error(why); };
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function clock(s) {
  need(typeof s === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(s), 'invalid_clock');
  return Number(s.slice(0, 2)) * 60 + Number(s.slice(3));
}
function day(s) {
  need(typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s), 'invalid_day');
  const t = new Date(s + 'T00:00:00Z');
  need(Number.isFinite(t.getTime()) && t.toISOString().slice(0, 10) === s, 'invalid_day');
  return t.getTime();
}
function text(s) { need(typeof s === 'string' && s.trim() === s && s.length > 0, 'text_required'); return s; }
function sourcePage(s) {
  const u = new URL(s);
  need(u.origin === 'https://kyfw.12306.cn' && u.pathname === '/otn/leftTicket/init' && !u.username && !u.password && !u.hash, 'ordinary_public_page_required');
}
function parseTable(table, observedOn) {
  text(table.id); text(table.query_from); text(table.query_to);
  const service = text(table.displayed_train);
  need(/^[GDC]\d{1,5}$/.test(service), 'high_speed_service_required');
  need(table.queried_train === service, 'query_display_train_mismatch');
  need(day(table.service_date) >= day(observedOn) && day(table.service_date) - day(observedOn) <= 14 * 86400000, 'unsupported_query_day_window');
  const header = table.heading.match(/^([GDC]\d{1,5})次\n([^\n>]+)-->([^\n>]+)\n(?:高速|动车|城际)\n有空调$/);
  need(header && header[1] === service, 'headed_service_required');
  const rows = table.rows;
  need(Array.isArray(rows) && rows.length >= 2 && rows.length <= 64, 'row_count');
  const names = new Set(); let prevSequence = 0, prevDeparture = -1;
  const stops = rows.map((cells, index) => {
    need(Array.isArray(cells) && cells.length === 5 && cells.every(s => typeof s === 'string'), 'five_raw_cells_required');
    const [seq, station, arrival, departure, dwell] = cells;
    need(/^\d{2}$/.test(seq) && Number(seq) > prevSequence, 'strict_sequence_order');
    need(!names.has(text(station)), 'duplicate_station'); names.add(station);
    const first = index === 0, last = index === rows.length - 1;
    need(!first || Number(seq) === 1, 'origin_row_required');
    const a = first ? null : clock(arrival), d = clock(departure);
    if (first) need(arrival === '----' && dwell === '----', 'origin_roles');
    else {
      need(a > prevDeparture && d >= a, 'overnight_or_nonmonotonic_unsupported');
      if (last) need(a === d && dwell === '----', 'terminal_roles');
      else need(/^\d+分钟$/.test(dwell) && Number(dwell.slice(0, -2)) === d - a, 'dwell_clock_mismatch');
    }
    prevSequence = Number(seq); prevDeparture = d;
    return {index, sequence: Number(seq), station, arrival, departure, a, d,
      raw_cells_sha256: hash(JSON.stringify(cells))};
  });
  need(header[2] === stops[0].station && header[3] === stops.at(-1).station, 'heading_endpoint_mismatch');
  const q = table.query_segment;
  need(q && q.arrival_day === '当日到达', 'same_day_query_anchor_required');
  const start = stops.findIndex(s => s.station === q.from_station), end = stops.findIndex(s => s.station === q.to_station);
  need(start >= 0 && end > start, 'query_anchor_order');
  need(stops[start].departure === q.departure && stops[end].arrival === q.arrival, 'query_anchor_clock_mismatch');
  need(clock(q.arrival) - clock(q.departure) === clock(q.elapsed), 'query_anchor_elapsed_mismatch');
  const segments = [], unanchored = [];
  for (let i = 0; i < stops.length - 1; i++) for (let j = i + 1; j < stops.length; j++) {
    const from = stops[i], to = stops[j];
    const sample = {table_id: table.id, service_id: service, observed_on: observedOn,
      query_service_date: table.service_date, from_station: from.station, to_station: to.station,
      from_row_index: i, to_row_index: j, from_sequence: from.sequence, to_sequence: to.sequence,
      departure_clock: from.departure, arrival_clock: to.arrival, minutes: to.a - from.d,
      from_row_sha256: from.raw_cells_sha256, to_row_sha256: to.raw_cells_sha256,
      source_heading: table.heading, calendar_basis: 'inside_explicit_same_day_query_segment',
      review_status: 'first_read_only', city_admitted: false, air_fallback_proven: false};
    if (i >= start && j <= end) segments.push(sample);
    else unanchored.push({...sample, calendar_basis: 'outside_query_segment_date_unproven'});
  }
  return {id: table.id, rows: rows.length, segments, unanchored,
    sequence_gaps: stops.slice(1).filter((s, i) => s.sequence !== stops[i].sequence + 1).map(s => s.sequence),
    query_start_row_index: start, query_end_row_index: end};
}
function build(input) {
  need(input.schema === 'public_rail_popup_observation_v1' && input.review_status === 'first_read_only' && input.production_adopted === false, 'research_only_input');
  need(input.actual_original_page_read === true && input.published_on === null, 'observed_unpublished_source_required');
  sourcePage(input.source_url); sourcePage(input.source_identity);
  need(input.observation_timezone === 'Asia/Shanghai', 'timezone_required');
  text(input.reader); day(input.observed_on); assert.deepEqual(input.columns, COLUMNS, 'column_order');
  need(Array.isArray(input.tables) && input.tables.length > 0 && input.tables.length <= 64, 'table_count');
  const ids = new Set(), serviceDays = new Set();
  const tables = input.tables.map(t => {
    need(!ids.has(t.id), 'duplicate_table'); ids.add(t.id);
    const key = JSON.stringify([t.displayed_train, t.service_date]);
    need(!serviceDays.has(key), 'duplicate_service_day'); serviceDays.add(key);
    return parseTable(t, input.observed_on);
  });
  const groups = new Map(), stations = new Set();
  for (const sample of tables.flatMap(t => t.segments)) {
    const endpoints = [sample.from_station, sample.to_station].sort();
    endpoints.forEach(s => stations.add(s)); const key = JSON.stringify(endpoints);
    if (!groups.has(key)) groups.set(key, {stations: endpoints, forward_samples: [], reverse_samples: [],
      city_admitted: false, air_fallback_proven: false});
    const pair = groups.get(key);
    pair[sample.from_station === endpoints[0] ? 'forward_samples' : 'reverse_samples'].push(sample);
  }
  const pairs = Array.from(groups.values()).sort((a, b) => JSON.stringify(a.stations).localeCompare(JSON.stringify(b.stations), 'en'));
  for (const pair of pairs) {
    pair.observed_both_directions = pair.forward_samples.length > 0 && pair.reverse_samples.length > 0;
    pair.both_directions_have_under240_sample = pair.forward_samples.some(s => s.minutes < 240) && pair.reverse_samples.some(s => s.minutes < 240);
  }
  return {schema: 'whole_corridor_pending_research_v1', source_format: 'ordinary_public_popup_five_columns_v1',
    source_url: input.source_url, observed_on: input.observed_on,
    displayed_table_count: tables.length, displayed_row_count: tables.reduce((n, t) => n + t.rows, 0),
    directed_samples: tables.reduce((n, t) => n + t.segments.length, 0),
    outside_query_segment_excluded: tables.reduce((n, t) => n + t.unanchored.length, 0),
    unanchored_clock_samples: tables.flatMap(t => t.unanchored),
    table_summary: tables.map(({segments, unanchored, ...t}) => ({...t, directed_samples: segments.length, unanchored: unanchored.length})),
    distinct_station_names: stations.size, unordered_station_pairs: pairs.length,
    bidirectional_station_pairs: pairs.filter(p => p.observed_both_directions).length,
    both_direction_under240_station_pairs: pairs.filter(p => p.both_directions_have_under240_sample).length,
    station_pairs: pairs, new_city_admissions: 0, production_changed: false,
    warning: 'First-reader research only. Future query service date is not observation date or origin date. No source licence, second-reader, city mapping, transfer, fastest/typical, exhaustive coverage, rail-exclusion or flight claim. Outside-anchor clock samples are excluded from all pair counts.'};
}
function privateOutput(requested) {
  const spelled = path.resolve(requested), actual = path.join(fs.realpathSync(path.dirname(spelled)), path.basename(spelled));
  for (const p of [spelled, actual]) need(!p.split(path.sep).some(part => ['web', 'public', 'dist', 'static', 'config'].includes(part)), 'private_output_only');
  need(!fs.existsSync(actual), 'new_output_required'); return actual;
}
if (require.main === module) {
  need(process.argv.length === 5, 'usage: input.json expected_sha256 NEW-private-output.json');
  const [inputPath, expectedHash, outputPath] = process.argv.slice(2);
  need(fs.statSync(inputPath).size <= 16 * 1024 * 1024, 'input_too_large');
  const bytes = fs.readFileSync(inputPath); need(hash(bytes) === expectedHash, 'input_hash_mismatch');
  const result = build(JSON.parse(bytes)); result.input_pin = {file: path.resolve(inputPath), sha256: expectedHash};
  const output = privateOutput(outputPath);
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n', {flag: 'wx', mode: 0o600});
  console.log(JSON.stringify({tables: result.displayed_table_count, rows: result.displayed_row_count,
    directed_samples: result.directed_samples, excluded_outside_query: result.outside_query_segment_excluded,
    stations: result.distinct_station_names, bidirectional_pairs: result.bidirectional_station_pairs,
    both_under240: result.both_direction_under240_station_pairs, new_city_admissions: 0,
    output_sha256: hash(fs.readFileSync(output))}));
}
module.exports = {build, parseTable, clock, day, privateOutput};
