import itertools
import unittest

from requirement_content import is_instructional_status_comparison
from worker import Encoder, evidence_units, requirement_units


OBJECTIVE = "课堂要教如何分清已核实内容和待确认说法"
ORIGINAL = "需要过去亲自给回访坐席完整教过通话纪要整理的老师，" + OBJECTIVE + "，并记录下一步联系安排。"


class InstructionalStatusTests(unittest.TestCase):
    def test_original_query_is_preserved_not_replaced_by_topic(self):
        value = requirement_units(ORIGINAL)
        self.assertEqual(value["positive_fragments"], [ORIGINAL[:-1]])
        self.assertEqual(value["exclusions"], [])
        self.assertFalse(value["exclusions_enforced"])

    def test_status_nominals_with_independent_or_shared_heads(self):
        objects = ("已核实内容和待确认说法", "已经确认的事实与尚未核实的信息",
                   "已核验材料及待核验的单据", "已核实和待确认的信息",
                   "待核验的数据、已经核验的数据", "尚未确认结论与已确认结论")
        for subject, directive, comparison, obj in itertools.product(
                ("课堂", "本课程", "这门课程", "本次课程"),
                ("要教如何", "需要讲解怎样", "必须讲清怎么"),
                ("区分", "分清", "辨别", "核对"), objects):
            source = subject + directive + comparison + obj
            with self.subTest(source=source):
                self.assertTrue(is_instructional_status_comparison(source))
                self.assertEqual(requirement_units(source)["positive_fragments"], [source])

    def test_unknown_or_teacher_predicates_are_not_content_scope(self):
        cases = (
            "讲师资料待确认", "老师要教如何分清已核实内容和待确认说法",
            "课堂要教如何分清已核实内容和没有授课经验的老师",
            "课堂要教如何分清已核实内容和待确认的讲师资格",
            "课堂要教如何分清已核实内容和待确认信息的老师",
            "课堂要教如何分清已核实内容和待确认信息但讲师尚未授课",
            "课堂要教如何分清已核实内容和待确认信息且老师无教学经验",
            "课堂要教如何分清已核实内容和待确认说法而非本人讲授",
            "课堂要教如何分清已核实内容和待确认信息中的授课记录",
            "课堂要教如何分清已核实内容和待确认的", "课堂要教如何分清已核实和待确认",
            "课堂要教如何分清已核实内容和待确认信息和未主讲经历",
            "课堂计划教如何分清已核实内容和待确认说法",
            "课堂不需要教如何分清已核实内容和待确认说法",
            "课堂不要求教如何分清已核实内容和待确认说法",
            "课堂可能要教如何分清已核实内容和待确认说法",
        )
        for source in cases:
            with self.subTest(source=source):
                self.assertFalse(is_instructional_status_comparison(source))
                with self.assertRaisesRegex(ValueError, "complex_requirement_requires_review"):
                    requirement_units(source)

    def test_full_clause_cannot_swallow_qualification_at_any_boundary(self):
        for separator in ("，", ",", "。", ";", "；", "\n", "\r\n"):
            for clause in ("讲师履历待确认", "老师尚未实际授课", "可能不是本人讲授"):
                for source in (OBJECTIVE + separator + clause, clause + separator + OBJECTIVE):
                    with self.subTest(source=source), self.assertRaisesRegex(ValueError, "complex_requirement_requires_review"):
                        requirement_units(source)

    def test_exclusions_remain_exclusions_and_are_not_enforced(self):
        for source in (OBJECTIVE + "；只做过在线录屏讲解不满足条件",
                       OBJECTIVE + "，只有学员到场而讲师远程教学，不算现场面授经历"):
            value = requirement_units(source)
            self.assertEqual(value["positive_fragments"], [OBJECTIVE])
            self.assertEqual(len(value["exclusions"]), 1)
            self.assertIn(value["exclusions"][0], source)
            self.assertTrue(value["requires_review"])
            self.assertFalse(value["exclusions_enforced"])
        with self.assertRaises(ValueError):
            requirement_units("不要" + OBJECTIVE)

    def test_whitespace_normalization_never_changes_returned_source(self):
        source = "  课堂 要教 如何分清 已核实内容 和待确认说法  "
        self.assertEqual(requirement_units(source)["positive_fragments"], [source.strip()])
        with self.assertRaisesRegex(ValueError, "personal_requirement_requires_review"):
            requirement_units(OBJECTIVE + "；年龄：30")

    def test_resume_qualification_is_not_reinterpreted_as_course_content(self):
        for source in (OBJECTIVE, "本人主讲《记录整理》。" + OBJECTIVE,
                       "本人主讲《记录整理》。讲师履历待确认。"):
            with self.subTest(source=source):
                value = evidence_units(source)
                self.assertFalse(value["complete"])
                self.assertEqual(value["units"], [])
                self.assertIn("qualified_or_unconfirmed_paragraph", value["review_reasons"])

    def test_retrieval_keeps_all_content_but_claims_no_verified_evidence(self):
        encoder = object.__new__(Encoder)
        encoder.np = type("Dot", (), {"dot": staticmethod(lambda a, b: 0.75)})
        encoder.precision = "int8"
        encoded = []
        encoder.encode = lambda text, query=False: encoded.append((text, query)) or [1.0]
        response = encoder.compare({"query": ORIGINAL, "documents": [
            {"id": 1, "text": "本人主讲《记录整理》。讲师履历待确认。"}]})
        self.assertEqual([text for text, query in encoded if query], [ORIGINAL[:-1]])
        self.assertFalse(response["results"][0]["evidence_complete"])
        self.assertIsNone(response["results"][0]["similarity"])
        self.assertNotIn("verified", response["results"][0])
        self.assertEqual(response["results"][0]["evidence"], [])


if __name__ == "__main__":
    unittest.main()
