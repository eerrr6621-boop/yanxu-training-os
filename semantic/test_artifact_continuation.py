import itertools,json,os,subprocess,unittest
import worker
from learner_artifact import artifact_context_spans

LEAD='我曾给资料管理员完整讲授《🌿标本标签核对》，授课方式是到场面授。'

def variants():
    for newline,indent,where in itertools.product(('\n','\r\n'),('','  ','\t'),('prefix','contrast','both')):
        join='，'+newline+indent
        prefix=join if where in ('prefix','both') else '，'
        contrast=join if where in ('contrast','both') else '，'
        yield LEAD+'整期结束后'+prefix+'学员带走的是空白笔记模板'+contrast+'不是客户信息。',True
        for tail in ('但本人没有讲授上述课程。','本人仅担任助教。','实际由同事讲授。'):
            yield LEAD+'学员带走的是空白笔记模板'+join+'不是客户信息。'+tail,False

class ArtifactContinuationTests(unittest.TestCase):
    def test_raw_end_comma_is_not_a_known_nonproof_cut(self):
        text=worker._normalized(LEAD+'学员带走的是空白笔记模板，不是客户信息。后续说明，')
        spans=worker._affirmative_spans(text)
        self.assertGreater(len(spans),1)
        self.assertEqual(spans[-1][1],len(text))
        self.assertTrue(text[spans[-1][0]:spans[-1][1]].endswith(','))
    def test_known_nonproof_cut_has_no_dangling_separator(self):
        text=worker._normalized('本人曾主讲《标签核对》。方法先说明操作顺序，再指出需要调整的步骤，而不是只评价“可以”。')
        value=worker.evidence_units(text)
        self.assertTrue(value['complete']);self.assertTrue(value['units'])
        for unit in value['units']:
            self.assertFalse(unit['text'].endswith(','))
            self.assertEqual(unit['text'],text[unit['source_start']:unit['source_end']])
    def test_original_layout_and_context(self):
        for raw,positive in variants():
            text=worker._normalized(raw);value=worker.evidence_units(text)
            self.assertEqual(value['complete'],positive,raw)
            if positive:
                self.assertEqual(len(artifact_context_spans(text,worker._completed_personal_event)),1)
                self.assertTrue(value['units'])
                for unit in value['units']:
                    self.assertEqual(unit['text'],text[unit['source_start']:unit['source_end']])
                    self.assertEqual(text,text[unit['paragraph_start']:unit['paragraph_end']])
                    self.assertNotIn('笔记模板',unit['text'])
            else:self.assertEqual(value['units'],[])
    def test_java_parity(self):
        java=os.environ.get('ACTOR_TEST_JAVA');cp=os.environ.get('ACTOR_TEST_CLASSPATH')
        if not java or not cp:self.skipTest('requires compiled Java')
        values=list(variants())
        rows=json.loads(subprocess.run([java,'-cp',cp,'com.training.LearnerArtifactTest','--inspect'],
            input=json.dumps([s for s,_ in values]),capture_output=True,text=True,check=True).stdout)
        for (raw,positive),row in zip(values,rows):
            text=worker._normalized(raw)
            self.assertEqual(row['omitted'],[list(s) for s in artifact_context_spans(text,worker._completed_personal_event)],raw)
            self.assertEqual(row['allowed'],positive,raw)
            self.assertTrue(all(e['verified'] is False for e in row['events']))
            if positive:
                self.assertTrue(row['positive']);self.assertEqual(row['scoring_sections'],[])
                self.assertTrue(all(r.get('context_text')==text for r in row['records']))
            else:self.assertEqual(row['positive'],[])

if __name__=='__main__':unittest.main()
