import json
import os
import subprocess
import unittest
import worker


def sources():
    for topic in ('客户服务', '古籍页码核对', '园艺工具清洁'):
        lead='本人曾主讲《'+topic+'》。'
        method='方法先演示操作步骤，再指出动作需要调整，'
        for quote in ('“挺好”', '「可以」', '"不错"', '“👍不错”'):
            for verb in ('评价', '说', '回答', '反馈'):
                yield lead+method+'而不是只'+verb+quote+'。我随后讲解改进步骤。', True
        positive=lead+method+'而不是只评价“挺好”。'
        for tail in ('本人没有主讲上述课程。', '实际由同事主讲。', '本人仅担任助教。', '以上只是模板，不代表本人经历。'):
            yield positive+tail, False
        for quote in ('“本人已经讲完”','“同事主讲”','“没有授课”','“教学经历属实”','“尚未完成”','“计划授课”','“挺好','“挺好”但未主讲','“挺好”，实际上本人没有主讲'):
            yield lead+method+'而不是只评价'+quote+'。', False
        for prior in ('',lead+'\n\n','同事曾主讲《'+topic+'》。','本人参加《'+topic+'》培训。','以下转述原话：'+lead):
            yield prior+method+'而不是只评价“挺好”。', False
        for prefix in ('本人说明课程经历，','方法是同事说明具体步骤，','方法是假设本人说明具体步骤，','方法先说明具体步骤，但是没有授课，'):
            yield lead+prefix+'而不是只评价“挺好”。', False
        yield method+'而不是只评价“挺好”。'+lead, False


class MethodContrastTest(unittest.TestCase):
    def test_positive_spans_and_context(self):
        for raw,positive in sources():
            if not positive:
                continue
            text=worker._normalized(raw)
            spans=worker._method_contrast_spans(text)
            self.assertEqual(len(spans),1)
            result=worker.evidence_units(text)
            self.assertTrue(result['complete'])
            self.assertTrue(result['units'])
            for unit in result['units']:
                self.assertEqual(unit['text'],text[unit['source_start']:unit['source_end']])
                self.assertEqual(text,text[unit['paragraph_start']:unit['paragraph_end']])
                self.assertNotIn(text[spans[0][0]:spans[0][1]],unit['text'])

    def test_negative_evidence_is_not_recovered(self):
        for raw,positive in sources():
            if positive:
                continue
            text=worker._normalized(raw)
            result=worker.evidence_units(text)
            # A preceding, explicitly separate paragraph may still be valid.
            # The rejected method paragraph itself must never be recovered.
            for unit in result['units']:
                self.assertIn('\n\n', text)
                self.assertLessEqual(unit['source_end'],text.index('\n\n'))
                self.assertNotIn('方法',unit['text'])

    def test_java_span_parity(self):
        java=os.environ.get('ACTOR_TEST_JAVA')
        cp=os.environ.get('ACTOR_TEST_CLASSPATH')
        if not java or not cp:
            self.skipTest('Java parity requires actual compiled classes')
        values=[s for s,_ in sources()]
        values += ['  '+s+'\r\n' for s,_ in sources()]
        result=subprocess.run([java,'-cp',cp,'com.training.MethodContrastTest','--inspect'],input=json.dumps(values),text=True,capture_output=True,check=True)
        output=json.loads(result.stdout)
        self.assertEqual(len(output),len(values))
        for raw,row in zip(values,output):
            text=worker._normalized(raw)
            self.assertEqual(row['text'],text)
            self.assertEqual(row['omitted'],[list(s) for s in worker._method_contrast_spans(text)],raw)


if __name__=='__main__':
    unittest.main()
