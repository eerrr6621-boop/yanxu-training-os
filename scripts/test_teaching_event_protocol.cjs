'use strict';
// Synthetic documents through the real profile preparation/coverage/selection
// adapter. This test never invokes a model, HTTP, a database, or a real resume.
const assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const [java,classpath]=process.argv.slice(2);assert(java&&classpath,'Explicit Java and compiled classpath required');
const block=(topic,audience)=>`实际授课记录\n课程名称：${topic}\n授课人：本人\n授课角色：主讲\n完成状态：已完成\n培训对象：${audience}\n授课形式：线下`;
const cases=[['窑炉巡检','车间班组长'],['图像标注','数据审核员']].flatMap(([topic,audience],i)=>[false,true].map(sameAudience=>({
  id:`synthetic-structured-event-${i+1}-${sameAudience?'same-history':'future-context'}`,
  query:`培训主题：${topic}\n培训对象：${audience}\n${sameAudience?'请找以前给这类学员讲完这门课的老师':'必须有实际授课记录'}`,
  documents:[{id:1,text:block(topic,audience)},{id:2,text:block(topic,'其他岗位学员')},
    {id:3,text:block(topic,audience).replace('授课人：本人','授课人：同事')}]
})));
const input={phase:'prepare',expected_model:'no-model-invoked',expected_revision:'synthetic-protocol-v1',cases};
const run=spawnSync(java,['-cp',classpath,'com.training.PosttrainingV2Probe'],{input:JSON.stringify(input),encoding:'utf8',maxBuffer:8*1024*1024,timeout:30000});
assert.equal(run.status,0,run.stderr);const output=JSON.parse(run.stdout);assert.equal(output.phase,'prepare');assert.equal(output.production_path,false);
let checks=2;
for(let index=0;index<cases.length;index++) {
  const original=cases[index],result=output.cases[index];
  const sameAudience=original.id.endsWith('same-history'),expected=sameAudience?[1]:[1,2];
  assert.equal(result.id,original.id);assert.deepEqual(result.admitted_ids,expected);assert.deepEqual(result.selected.map(r=>r.teacher_id),expected);
  assert.deepEqual(result.review.map(r=>r.teacher_id),sameAudience?[2]:[]);assert.deepEqual(result.excluded_ids,[3]);checks+=5;
  for(let doc=0;doc<original.documents.length;doc++) {
    assert.equal(result.prepared_documents[doc].text,original.documents[doc].text.normalize('NFKC'));checks++;
  }
  const selected=result.selected[0];assert.equal(selected.model_score,null);assert.equal(selected.teaching_events.length,1);checks+=2;
  const source=Array.from(result.prepared_documents[0].text);
  for(const event of selected.teaching_events) {
    assert.equal(event.text,source.slice(event.source_start,event.source_end).join(''));assert.equal(event.verified,false);checks+=2;
    for(const field of Object.values(event.fields)) {
      assert.equal(field.text,source.slice(field.source_start,field.source_end).join(''));assert.equal(field.text,field.value);checks+=2;
    }
  }
}
console.log(`Teaching event profile protocol: ${checks} checks passed; no model, real resume or database used`);
