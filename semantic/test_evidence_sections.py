"""Independent synthetic partition tests. No model, real data or label changes."""
import unittest
from worker import Encoder, evidence_units, scoring_sections, _global_qualification


def compare(text):
    encoder = object.__new__(Encoder)
    encoder.np = type("Dot", (), {"dot": staticmethod(lambda a, b: 0.75)})
    encoder.precision = "int8"
    encoder.encode = lambda text, query=False: [1.0]
    return encoder.compare({"query": "服务礼仪", "documents": [{"id": 1, "text": text}]})["results"][0]


class IndependentSectionTests(unittest.TestCase):
    def test_named_course_denial_is_local_only_in_explicit_excluded_section(self):
        positive = "实际授课记录\n2025年本人主讲服务礼仪课程，完成课堂点评。"
        for heading in ("未授课课程", "未讲授课程："):
            for denial in ("本人没有讲授《数字营销》。", "我从未主讲过《数字营销》这门课。", "本人尚未亲自讲授《数字营销》课程。"):
                with self.subTest(heading=heading, denial=denial):
                    source = "📚\n个人介绍\n本人参加过摄影课程培训。\n" + heading + "\r\n" + denial + "\r\n" + positive
                    self.assertFalse(_global_qualification(source))
                    result = compare(source)
                    self.assertFalse(result["evidence_complete"])
                    self.assertTrue(result["scoped_evidence_complete"])
                    self.assertEqual(result["similarity"], 0.75)
                    self.assertEqual(result["evidence"], [positive.splitlines()[1]])
                    for section in result["scoring_sections"]:
                        body = source[section["source_start"]:section["source_end"]]
                        self.assertIn(result["evidence"][0], body)
                        self.assertNotIn("数字营销", body)

    def test_local_denial_exception_never_hides_unbounded_or_referenced_denials(self):
        positive = "实际授课记录\n2025年本人主讲服务礼仪课程，完成课堂点评。"
        for text in (
            "本人没有讲授《数字营销》。\n" + positive,
            "个人介绍\n本人没有讲授《数字营销》。\n" + positive,
            "未授课课程\n本人没有讲授该课程。\n" + positive,
            "未授课课程\n本人从未有过任何授课经历。\n" + positive,
            "未授课课程\n以上所有课程都并非本人主讲。\n" + positive,
            "未授课课程\n本人没有讲授《数字营销》，上述经历均为团队案例。\n" + positive,
            "未授课课程\n本人没有讲授《数字营销》。\n" + positive + "\n以上内容不代表本人授课经历。",
            "未授课课程\n本人没有讲授《数字营销》。\n" + positive + "\n个人介绍\n本人没有讲授《服务礼仪》。",
            "未授课课程\n本人没有讲授《以上全部课程》。\n" + positive,
            "以上记录：\n未授课课程\n本人没有讲授《数字营销》。\n" + positive,
        ):
            with self.subTest(source=text):
                self.assertTrue(_global_qualification(text))
                self.assertEqual(scoring_sections(text), [])
                self.assertIsNone(compare(text)["similarity"])

    def test_unrelated_attendance_does_not_suppress_explicit_course_section(self):
        text = "个人介绍\n本人参加过摄影课程培训。\n实际授课记录\n2025年本人主讲服务礼仪课程，完成两次课堂点评。"
        result = compare(text)
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual(result["similarity"], 0.75)
        self.assertEqual(result["evidence"], ["2025年本人主讲服务礼仪课程，完成两次课堂点评。"])

    def test_unbounded_blank_line_is_not_a_section(self):
        result = compare("本人主讲服务礼仪课程。\n\n本人参加摄影课程培训。")
        self.assertIsNone(result["similarity"])
        self.assertFalse(result["scoped_evidence_complete"])

    def test_course_catalog_never_becomes_full_document_completeness(self):
        result = compare("个人介绍\n某业务岗位任职（筹）。\n精品课程\n服务礼仪课程\n客户投诉沟通")
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual(result["scoring_scope"], "independent_sections_v1")

    def test_template_instruction_is_not_a_qualification(self):
        text = "精品课程\n服务礼仪课程\n替换个人照片\n客户投诉沟通"
        result = compare(text)
        self.assertTrue(result["evidence_complete"])
        self.assertNotIn("替换个人照片", "".join(result["evidence"]))

    def test_mixed_placeholder_record_is_not_cleaned_into_real_teaching(self):
        result = compare("实际授课记录\n2025年xxx主讲服务礼仪课程。")
        self.assertIsNone(result["similarity"])

    def test_invalid_teaching_section_does_not_invalidate_separate_catalog(self):
        result = compare("精品课程\n服务礼仪课程\n实际授课记录\nxxx主讲摄影课")
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual([s["heading"] for s in result["scoring_sections"]], ["精品课程"])

    def test_direct_personal_denial_survives_new_headings(self):
        text = "实际授课记录\n2025年本人主讲服务礼仪课程。\n个人介绍\n本人没有承担相关教学工作。"
        result = compare(text)
        self.assertIsNone(result["similarity"])
        self.assertFalse(result["scoped_evidence_complete"])

    def test_back_reference_survives_page_and_heading(self):
        for denial in ("上述课程均未由本人主讲。", "以上内容只是团队案例。", "这些记录非本人授课。", "前页授课记录待确认。"):
            with self.subTest(denial=denial):
                text = "实际授课记录\n2025年本人主讲服务礼仪课程。\n\n个人介绍\n" + denial
                self.assertEqual(scoring_sections(text), [])
                self.assertIsNone(compare(text)["similarity"])

    def test_team_section_not_part_of_personal_scoring(self):
        result = compare("个人介绍\n本人参加摄影课程培训。\n团队业绩\n主讲服务礼仪课程。\n主讲课程\n数据分析课程")
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual(result["evidence"], ["数据分析课程"])

    def test_forward_disclaimer_cannot_be_reset_by_heading(self):
        text = "个人介绍\n以下内容只是团队案例。\n实际授课记录\n2025年本人主讲服务礼仪。"
        self.assertIsNone(compare(text)["similarity"])

    def test_other_person_inside_positive_heading_not_certified(self):
        result = compare("实际授课记录\n外部讲师主讲服务礼仪课程，本人负责签到。")
        self.assertIsNone(result["similarity"])

    def test_planned_role_in_own_section_is_still_not_completed(self):
        result = compare("实际授课记录\n本人计划授课服务礼仪。")
        self.assertIsNone(result["similarity"])

    def test_complete_long_catalog_lines_do_not_need_arbitrary_cut(self):
        text = "主讲课程\n" + "\n".join(f"第{i}门课程：服务礼仪与案例练习" for i in range(30))
        result = compare(text)
        self.assertIsNotNone(result["similarity"])
        self.assertTrue(all(quote in text and len(quote) <= 220 for quote in result["evidence"]))

    def test_long_unstructured_prose_is_not_a_scoped_escape(self):
        text = "实际授课记录\n" + "本人主讲服务礼仪" * 50
        result = compare(text)
        self.assertIsNone(result["similarity"])
        self.assertFalse(result["scoped_evidence_complete"])

    def test_later_qualification_within_catalog_invalidates_whole_section(self):
        text = "主讲课程\n" + "\n".join(f"第{i}门服务礼仪课程" for i in range(25)) + "\n其中部分课程由其他讲师主讲。"
        self.assertIsNone(compare(text)["similarity"])

    def test_scoped_offsets_preserve_unicode_and_independent_original_body(self):
        text = "个人介绍\n本人参加摄影课程培训。\r\n实际授课记录\r\n本人主讲服务礼仪📚课程。\r\n"
        result = compare(text)
        self.assertTrue(result["scoped_evidence_complete"])
        for section in result["scoring_sections"]:
            self.assertEqual(text[section["heading_start"]:section["heading_end"]], section["heading"])
            self.assertIn(result["evidence"][0], text[section["source_start"]:section["source_end"]])

    def test_demographic_metadata_does_not_change_scoped_selection(self):
        course = "个人介绍\n本人参加摄影课程培训。\n实际授课记录\n本人主讲服务礼仪课程。"
        a, b = compare(course), compare("性别：女。年龄：52。\n" + course)
        self.assertEqual(a["similarity"], b["similarity"])
        self.assertEqual(a["evidence"], b["evidence"])


if __name__ == "__main__":
    unittest.main()
