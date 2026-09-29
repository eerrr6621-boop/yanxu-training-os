import itertools
import json
import os
import subprocess
import unittest
import worker
from learner_artifact import artifact_context_spans


def spans(text):
    return artifact_context_spans(text, worker._completed_personal_event)


def cases():
    for topic in ('档案目录核对', '设备编号登记', '🌿标本标签核对'):
        lead='我曾给资料管理员完整讲授《'+topic+'》，授课方式是到场面授。'
        for subject,material,contrast,info in itertools.product(
                ('整期结束后，学员带走的是','学生拿到的是','课堂结束后留下的是'),
                ('空白笔记模板','教学步骤卡','操作检查表'),
                ('不是','而不是','而非','并非'),
                ('客户信息','真实业务数据','师徒业务考核记录')):
            yield lead+subject+material+'，'+contrast+info+'。', True
        artifact='整期结束后，学员带走的是空白笔记模板，不是客户信息。'
        for tail in ('本人没有讲授上述课程。','实际由同事主讲。','本人仅担任助教。','以上只是模板，不代表本人经历。'):
            yield lead+artifact+tail, False
        for prefix in ('', '同事曾给资料管理员完整讲授《'+topic+'》。',
                       '我计划给资料管理员完整讲授《'+topic+'》。',
                       '我参加《'+topic+'》培训。','以下是履历模板：'+lead):
            yield prefix+artifact, False
        for clause in (
                '学员带走的是空白笔记模板，不是本人授课记录',
                '学员带走的是本人主讲的模板，不是客户信息',
                '讲师带走的是空白笔记模板，不是客户信息',
                '学员带走的是空白笔记模板，不是未确认的数据',
                '学员带走的是空白笔记模板，不是客户信息但本人未主讲',
                '学员带走的是空白笔记模板，不是客户信息，实际由同事主讲',
                '学员带走的是空白笔记模板，不是老师具备资格的证明材料',
                '学员带走的是空白笔记模板，不是',
                '学员带走的是空白笔记，不是客户信息',
                '“学员带走的是空白笔记模板，不是客户信息”',
                '如果学员带走的是空白笔记模板，不是客户信息',
                '学员带走的是空白笔记模板；不是客户信息'):
            yield lead+clause+'。', False
        # The original pending physical-wrap input is unchanged. It must now
        # recover only the real teaching text after whole-context inspection.
        yield lead+'学员带走的是空白笔记模板，\n不是客户信息。', True


class LearnerArtifactTests(unittest.TestCase):
    def test_positive_context_keeps_literal_source_but_is_not_proof(self):
        for raw,positive in cases():
            if not positive:continue
            text=worker._normalized(raw);omitted=spans(text)
            self.assertEqual(len(omitted),1,raw)
            result=worker.evidence_units(text)
            self.assertTrue(result['complete'],raw);self.assertTrue(result['units'],raw)
            for unit in result['units']:
                self.assertEqual(unit['text'],text[unit['source_start']:unit['source_end']])
                self.assertEqual(text,text[unit['paragraph_start']:unit['paragraph_end']])
                self.assertNotIn(text[omitted[0][0]:omitted[0][1]],unit['text'])
                self.assertNotIn('笔记模板',unit['text'])

    def test_qualified_or_ambiguous_material_never_recovers_evidence(self):
        for text,positive in cases():
            if positive is not False:continue
            result=worker.evidence_units(worker._normalized(text))
            self.assertFalse(result['complete'],text);self.assertEqual(result['units'],[],text)

    def test_no_cross_paragraph_or_later_teaching_antecedent(self):
        lead='我曾给资料管理员完整讲授《档案目录核对》。'
        artifact='学员带走的是笔记模板，不是客户信息。'
        for separator in ('\n\n','\r\n\r\n','\n \n'):
            source=lead+separator+artifact
            self.assertEqual(spans(source),[])
            for unit in worker.evidence_units(source)['units']:
                self.assertLessEqual(unit['source_end'],len(lead))
        self.assertEqual(spans(artifact+lead),[]);self.assertEqual(spans(artifact),[])

    def test_distinct_artifacts_do_not_join_noncontiguous_quotes(self):
        lead='本人曾主讲《标签核对》。'
        artifact='学员带走的是笔记模板，不是客户信息。'
        middle='我讲解示例标签的核对方法。'
        source=lead+artifact+middle+artifact
        self.assertEqual(len(spans(source)),2)
        result=worker.evidence_units(source)
        self.assertTrue(result['complete'])
        self.assertEqual([u['text'] for u in result['units']],[lead,middle])

    def test_java_scope_and_events_protocol(self):
        java=os.environ.get('ACTOR_TEST_JAVA');cp=os.environ.get('ACTOR_TEST_CLASSPATH')
        if not java or not cp:self.skipTest('requires actual compiled Java classes')
        values=list(cases());values += [('  '+s+'\r\n',p) for s,p in values]
        run=subprocess.run([java,'-cp',cp,'com.training.LearnerArtifactTest','--inspect'],
                           input=json.dumps([s for s,_ in values]),text=True,capture_output=True,check=True)
        output=json.loads(run.stdout);self.assertEqual(len(output),len(values))
        for (raw,positive),row in zip(values,output):
            text=worker._normalized(raw);self.assertEqual(row['text'],text)
            self.assertEqual(row['omitted'],[list(s) for s in spans(text)],raw)
            if positive:
                self.assertTrue(row['allowed'],raw);self.assertTrue(row['positive'],raw)
                self.assertEqual(row['scoring_sections'],[],raw)
                self.assertTrue(all(r['verified'] is False for r in row['records']))
            else:
                self.assertFalse(row['allowed'],raw);self.assertEqual(row['positive'],[],raw)


if __name__=='__main__':unittest.main()
