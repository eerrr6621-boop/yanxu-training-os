import unittest
import worker


class CapabilitySentenceTest(unittest.TestCase):
    def test_contiguous_capability_not_participation(self):
        for topic in ('客户服务', '古籍页码核对', '园艺工具清洁'):
            claim='同时作为'+topic+'认证讲师,擅长用故事讲解'+topic+'课程。'
            attendance='参与社区志愿服务计划,担任服务项目认证讲师。'
            for body in (claim+attendance, attendance+claim):
                for padding in ('', '  ', '\t'):
                    raw=padding+body+'\r\n'
                    spans=worker._capability_sentence_spans(raw)
                    self.assertEqual([raw[a:b] for a,b in spans], [claim])
                    source='个人介绍\n'+raw+'精品课程\n无关主题'
                    scopes=[s for s in worker.scoring_sections(source) if s.get('kind')=='capability_sentence_v1']
                    self.assertEqual([source[s['source_start']:s['source_end']] for s in scopes],[claim])
                    for s in scopes:self.assertIn(attendance,source[s['context_start']:s['context_end']])

    def test_context_cannot_be_detached(self):
        claim='本人擅长客户服务课程。'
        attendance='参与社区志愿服务计划,担任项目认证讲师。'
        for tail in ('本人没有上述能力。','上述为模板内容。','上述是同事的能力介绍。','本人仅承担助教工作。'):
            source='个人介绍\n'+claim+attendance+tail
            self.assertFalse([s for s in worker.scoring_sections(source) if s.get('kind')=='capability_sentence_v1'])
        for heading in ('参训经历','助教经历','计划课程','团队案例'):
            source=heading+'\n'+claim+attendance
            self.assertFalse([s for s in worker.scoring_sections(source) if s.get('kind')=='capability_sentence_v1'])

    def test_no_comma_or_reported_assertion(self):
        attendance='参与社区志愿服务计划,担任项目认证讲师。'
        for claim in ('张老师擅长客户服务课程。','她擅长客户服务课程。','据说本人擅长客户服务课程。','“本人擅长客户服务课程”。'):
            self.assertEqual(worker._capability_sentence_spans(claim+attendance),[])
        self.assertEqual(worker._capability_sentence_spans('本人参加客户服务认证培训,擅长相关课程。'),[])

    def test_full_line_limit_and_offsets(self):
        prefix='参与社区志愿服务计划,担任项目认证讲师。'
        base='本人擅长'
        suffix='课程。'
        for size in (219,220,221):
            body=prefix+base+'甲'*(size-len(prefix+base+suffix))+suffix
            raw='  '+body+'\r\n'
            spans=worker._capability_sentence_spans(raw)
            self.assertEqual(bool(spans),size<=220)
            if spans:self.assertEqual(raw[spans[0][0]:spans[0][1]],body[len(prefix):])


if __name__=='__main__':unittest.main()
