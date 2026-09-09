"""New bounded source-subject pairs, not training/held-out labels."""
import unittest
import worker

class NarrativeEntityEvidenceTest(unittest.TestCase):
    def test_organization_work_exclusion_does_not_deny_personal_teaching(self):
        for course in ("陶泥接缝观察","展柜湿度登记","天文底片编号"):
            source=f"中心为新学员开设《{course}》。这些组织工作不涉及课堂讲解。这堂面授课由我独立讲完。"
            result=worker.evidence_units(source)
            self.assertTrue(result["complete"],course)
            self.assertTrue(result["units"],course)

    def test_personal_denials_and_other_actors_remain_blocked(self):
        for ending in ("本人不涉及课堂讲解。", "我没有授课。", "我仅担任助教。", "实际由同事主讲。"):
            source="中心为新学员开设《展柜湿度登记》。这堂面授课由我独立讲完。"+ending
            result=worker.evidence_units(source)
            self.assertFalse(result["complete"],ending)

    def test_work_disclaimer_needs_same_paragraph_own_event(self):
        for source in (
            "中心开设《展柜湿度登记》。这些组织工作不涉及课堂讲解。这堂课由同事讲完。",
            "中心开设《展柜湿度登记》。这些组织工作不涉及课堂讲解。\n\n这堂面授课由我独立讲完。",
            "中心开设《展柜湿度登记》和《设备整理》。这些组织工作不涉及课堂讲解。这堂面授课由我独立讲完。"):
            self.assertFalse(worker.evidence_units(source)["complete"])

    def test_declaration_frame_is_not_past_personal_fact(self):
        for head in ("中心明年将为新学员开设《展柜湿度登记》", "如果中心为新学员开设《展柜湿度登记》", "转述张老师在中心为新学员开设《展柜湿度登记》", "中心将开设《展柜湿度登记》", "中心准备开设《展柜湿度登记》"):
            source=head+"。这些组织工作不涉及课堂讲解。这门课由我独立讲完。"
            self.assertFalse(worker.evidence_units(source)["complete"])
            self.assertFalse(worker._referenced_personal_completion(source))

    def test_excluded_audience_is_not_affirmative_retrieval(self):
        source="中心开设《展柜湿度登记》。这门课由我独立讲完。本班学员为老员工而非新学员。"
        self.assertFalse(worker.evidence_units(source)["complete"])

if __name__=="__main__":unittest.main()
