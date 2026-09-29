'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {build, parseTable, clock, day, privateOutput} = require('./prepare_public_popup_corridors.cjs');
let checks = 0;
const eq = (a,b) => {assert.deepEqual(a,b); checks++;};
function table() {return {id:'toy-g101', displayed_train:'G101', queried_train:'G101',
  service_date:'2026-09-10', query_from:'甲城', query_to:'丙城',
  heading:'G101次\n甲城东-->丁城北\n高速\n有空调',
  query_segment:{from_station:'甲城东',to_station:'丙城南',departure:'08:00',arrival:'09:10',elapsed:'01:10',arrival_day:'当日到达'},
  rows:[['01','甲城东','----','08:00','----'],['02','乙城','08:30','08:40','10分钟'],
    ['04','丙城南','09:10','09:15','5分钟'],['05','丁城北','09:50','09:50','----']]};}
function input() {return {schema:'public_rail_popup_observation_v1', observed_on:'2026-09-09',
  observation_timezone:'Asia/Shanghai',reader:'synthetic',review_status:'first_read_only',production_adopted:false,
  actual_original_page_read:true,published_on:null,
  source_url:'https://kyfw.12306.cn/otn/leftTicket/init?fs=old-city&date=2026-01-01',
  source_identity:'https://kyfw.12306.cn/otn/leftTicket/init',
  columns:['站序','站名','到站时间','出发时间','停留时间'],tables:[table()]};}
function reject(change, pattern) {const x=input(); change(x); assert.throws(()=>build(x),pattern);checks++;}
const r = build(input());
eq(r.directed_samples,3); eq(r.outside_query_segment_excluded,3);eq(r.displayed_row_count,4);
eq(r.distinct_station_names,3);eq(r.bidirectional_station_pairs,0);eq(r.both_direction_under240_station_pairs,0);
eq(r.table_summary[0].sequence_gaps,[4]);
eq(r.station_pairs.flatMap(p=>p.forward_samples.concat(p.reverse_samples)).map(x=>x.minutes).sort((a,b)=>a-b),[30,30,70]);
eq(r.new_city_admissions,0);eq(r.production_changed,false);
eq(r.unanchored_clock_samples.every(s=>s.calendar_basis==='outside_query_segment_date_unproven'),true);
eq(r.station_pairs.every(p=>!p.city_admitted&&!p.air_fallback_proven),true);
eq(r.station_pairs.flatMap(p=>p.forward_samples.concat(p.reverse_samples)).every(s=>s.observed_on==='2026-09-09'&&s.query_service_date==='2026-09-10'),true);
// Sparse stops are not silently reconstructed; reverse is never invented.
eq(r.station_pairs.some(p=>p.stations.includes('丁城北')),false);
eq(r.source_url,input().source_url);
reject(x=>x.columns.reverse(),/column_order/);
reject(x=>x.production_adopted=true,/research_only/);
reject(x=>x.review_status='approved',/research_only/);
reject(x=>x.actual_original_page_read=false,/observed_unpublished/);
reject(x=>x.published_on='2026-09-09',/observed_unpublished/);
reject(x=>x.observation_timezone='UTC',/timezone/);
reject(x=>x.source_url='https://example.org/otn/leftTicket/init',/ordinary_public/);
reject(x=>x.source_url='https://kyfw.12306.cn/otn/leftTicket/query',/ordinary_public/);
reject(x=>x.source_url='https://user:secret@kyfw.12306.cn/otn/leftTicket/init',/ordinary_public/);
reject(x=>x.source_url+='\u0023fragment',/ordinary_public/);
reject(x=>x.tables[0].rows[1].push('another train'),/five_raw/);
reject(x=>x.tables[0].rows[1][0]='01',/sequence/);
reject(x=>x.tables[0].rows[0][0]='02',/origin_row/);
reject(x=>x.tables[0].rows[1][1]='甲城东',/duplicate_station/);
reject(x=>x.tables[0].rows[1][2]='25:30',/invalid_clock/);
reject(x=>x.tables[0].rows[1][3]='08:29',/nonmonotonic/);
reject(x=>x.tables[0].rows[1][4]='11分钟',/dwell/);
reject(x=>x.tables[0].rows[0][2]='08:00',/origin_roles/);
reject(x=>x.tables[0].rows.at(-1)[3]='09:51',/terminal_roles/);
reject(x=>x.tables[0].heading=x.tables[0].heading.replace('丁城北','戊城'),/heading_endpoint/);
reject(x=>x.tables[0].queried_train='G102',/query_display/);
reject(x=>x.tables[0].displayed_train='Z101',/high_speed/);
reject(x=>x.tables[0].service_date='2026-09-08',/query_day_window/);
reject(x=>x.tables[0].service_date='2026-09-24',/query_day_window/);
reject(x=>x.observed_on='2026-02-30',/invalid_day/);
reject(x=>x.tables[0].query_segment.arrival_day='次日到达',/same_day/);
reject(x=>x.tables[0].query_segment.from_station='不存在',/query_anchor_order/);
reject(x=>x.tables[0].query_segment.to_station='甲城东',/query_anchor_order/);
reject(x=>x.tables[0].query_segment.departure='08:01',/query_anchor_clock/);
reject(x=>x.tables[0].query_segment.elapsed='01:11',/elapsed/);
reject(x=>x.tables.push(structuredClone(x.tables[0])),/duplicate_table/);
reject(x=>{const t=structuredClone(x.tables[0]);t.id='different-id';x.tables.push(t);},/duplicate_service_day/);
// A visible 24h wrap cannot be silently treated as next day.
reject(x=>x.tables[0].rows[2][2]='00:10',/nonmonotonic/);
eq(clock('00:00'),0);eq(clock('23:59'),1439);eq(day('2024-02-29'),Date.UTC(2024,1,29));
// Two independent directions with asymmetric times, strict 240-minute boundary.
function paired(forward, reverse) {
  const x=input();
  x.tables=[['G201','甲城','乙城',forward],['G202','乙城','甲城',reverse]].map(([id,a,b,m])=>{
    const end=`${String(8+Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
    return {id,queried_train:id,displayed_train:id,service_date:'2026-09-10',query_from:a,query_to:b,
      heading:`${id}次\n${a}-->${b}\n高速\n有空调`,
      rows:[['01',a,'----','08:00','----'],['02',b,end,end,'----']],
      query_segment:{from_station:a,to_station:b,departure:'08:00',arrival:end,elapsed:`${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`,arrival_day:'当日到达'}};
  });return build(x);
}
eq(paired(239,239).both_direction_under240_station_pairs,1);
eq(paired(239,240).both_direction_under240_station_pairs,0);
eq(paired(240,239).both_direction_under240_station_pairs,0);
eq(paired(240,240).bidirectional_station_pairs,1);
eq(paired(91,77).station_pairs[0].forward_samples[0].minutes===91 || paired(91,77).station_pairs[0].reverse_samples[0].minutes===91,true);
const root = fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-popup-test-'));
eq(privateOutput(path.join(root,'new.json')),path.join(fs.realpathSync(root),'new.json'));
const existing=path.join(root,'existing.json');fs.writeFileSync(existing,'{}',{flag:'wx'});
assert.throws(()=>privateOutput(existing),/new_output/);checks++;
const publicPath=path.join(root,'public');fs.mkdirSync(publicPath);
assert.throws(()=>privateOutput(path.join(publicPath,'new.json')),/private_output/);checks++;
const alias=path.join(root,'alias');fs.symlinkSync(publicPath,alias,'dir');
assert.throws(()=>privateOutput(path.join(alias,'new.json')),/private_output/);checks++;
eq(parseTable(table(),'2026-09-09').segments[2].minutes,30);
console.log(`PASS ${checks} synthetic public-popup checks (not real route accuracy)`);
