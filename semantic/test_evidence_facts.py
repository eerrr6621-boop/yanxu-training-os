"""New synthetic fact-scope regressions; no historical labels or model calls."""
import unittest
from worker import evidence_units, independent_fact_sections, FACT_SCOPE_VERSION
from test_evidence_sections import compare


class IndependentFactTests(unittest.TestCase):
    def test_independent_intro_fact_survives_unrelated_placeholder(self):
        source = "个人介绍\n聘任岗位（筹）。\n本人曾面向社区长者主讲网络安全课程，说明防范陌生链接的方法。\n精品课程\n网络安全课程"
        result = compare(source)
        self.assertFalse(result["evidence_complete"])
        self.assertEqual(result["scoring_scope"], FACT_SCOPE_VERSION)
        self.assertTrue(any("社区长者" in e for e in result["evidence"]))

    def test_complete_fact_line_not_entire_intro_is_restored(self):
        source = "个人介绍\n" + "待补录资料。" * 60 + "\n本人曾主讲食品安全课程。\n精品课程\n食品安全"
        facts = independent_fact_sections(source)
        self.assertEqual([source[f["source_start"]:f["source_end"]] for f in facts], ["本人曾主讲食品安全课程。"])

    def test_exact_adjacent_title_run_before_catalog_heading(self):
        source = "个人介绍\n本人参加摄影课程培训。\n《仓库盘点与差异分析》\n《机械识图——尺寸与公差》\n精品课程\n质量管理"
        # An attendance qualification in this same context prevents claiming
        # the backward title run as the resume owner's courses.
        self.assertFalse(any(f["kind"] == "course_catalog_entry_v2" for f in independent_fact_sections(source)))
        source = source.replace("本人参加摄影课程培训。", "模板岗位待补充。")
        facts = [f for f in independent_fact_sections(source) if f["kind"] == "course_catalog_entry_v2"]
        self.assertEqual(len(facts), 2)
        self.assertTrue(all(f["heading_start"] > f["source_end"] for f in facts))

    def test_single_title_without_catalog_run_stays_ambiguous(self):
        self.assertEqual(independent_fact_sections("个人介绍\n《现场问题分析》\n精品课程\n质量管理"), [])

    def test_blank_page_break_does_not_imply_backward_catalog(self):
        self.assertEqual(independent_fact_sections("个人介绍\n《仓库管理》\n《质量管理》\n\n精品课程"), [])

    def test_non_catalog_following_heading_does_not_own_titles(self):
        self.assertEqual(independent_fact_sections("个人介绍\n《仓库管理》\n《质量管理》\n认证经历"), [])

    def test_planned_titles_before_heading_are_not_restored(self):
        for qualifier in ("以下是计划课程。", "拟开设课程如下。", "课程清单尚待确认。", "本人只参加以下课程培训。"):
            with self.subTest(qualifier=qualifier):
                text = "个人介绍\n" + qualifier + "\n《食品安全》\n《食品追溯》\n精品课程"
                self.assertEqual(independent_fact_sections(text), [])

    def test_other_actor_before_titles_invalidates_ownership(self):
        for actor in ("他人讲过以下课程。", "其他老师主讲食品安全。", "王老师曾主讲食品安全。"):
            with self.subTest(actor=actor):
                text = "个人介绍\n" + actor + "\n《食品安全》\n《食品追溯》\n精品课程"
                self.assertEqual(independent_fact_sections(text), [])

    def test_later_personal_denial_is_not_cut_away(self):
        text = "个人介绍\n本人曾主讲食品安全。\n上述经历并非本人授课。\n精品课程\n食品安全"
        self.assertIsNone(compare(text)["similarity"])

    def test_other_subject_line_cannot_be_discarded_to_certify_intro(self):
        text = "个人介绍\n他人主讲食品安全课程。\n本人擅长食品安全。\n精品课程\n食品安全"
        self.assertEqual(independent_fact_sections(text), [])

    def test_mixed_certification_and_event_retains_complete_line(self):
        text = "个人介绍\n本人取得食品安全认证，曾面向餐饮店长开展食品安全课堂。\n岗位（筹）。"
        result = compare(text)
        self.assertFalse(result["evidence_complete"])
        self.assertEqual(result["evidence"], ["本人取得食品安全认证，曾面向餐饮店长开展食品安全课堂。"])

    def test_certification_alone_is_not_independent_teaching(self):
        self.assertEqual(independent_fact_sections("个人介绍\n本人取得食品安全认证。\n岗位（筹）。"), [])

    def test_personal_fields_mixed_with_fact_stay_review(self):
        self.assertEqual(independent_fact_sections("个人介绍\n年龄：45。本人主讲食品安全。"), [])

    def test_demographic_line_does_not_change_independent_facts(self):
        source = "个人介绍\n本人曾主讲仓储安全。\n岗位（筹）。"
        self.assertEqual(compare(source)["evidence"], compare("性别：男。年龄：48。\n" + source)["evidence"])

    def test_original_offsets_and_full_context_survive_unicode(self):
        source = "🧪\r\n个人介绍：\r\n岗位（筹）。\r\n本人曾主讲仓储安全📚课程。\r\n"
        result = compare(source)
        self.assertEqual(result["scoring_scope"], FACT_SCOPE_VERSION)
        for scope in result["scoring_sections"]:
            self.assertEqual(source[scope["heading_start"]:scope["heading_end"]], scope["heading"])
            self.assertIn(source[scope["source_start"]:scope["source_end"]], source[scope["context_start"]:scope["context_end"]])

    def test_course_fact_does_not_hide_same_line_negation(self):
        for text in ("本人主讲仓储安全，但尚未实施授课。", "仓储安全课程由外聘讲师授课，本人负责资料。"):
            self.assertEqual(independent_fact_sections("个人介绍\n" + text), [])

    def test_unbounded_long_fact_not_arbitrarily_truncated(self):
        self.assertEqual(independent_fact_sections("个人介绍\n" + "本人主讲仓储安全课程" * 35), [])

    def test_explicit_specialty_heading_preserves_title_before_later_catalog(self):
        source = "个人介绍\n本人参加摄影课程培训。\n擅长领域\n《仓库盘点》\n《质量管理》\n精品课程\n采购管理"
        result = compare(source)
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertTrue(any(s["heading"] == "擅长领域" for s in result["scoring_sections"]))

    def test_forward_plan_survives_specialty_heading(self):
        source = "个人介绍\n以下是计划课程。\n擅长领域\n《仓库盘点》\n《质量管理》\n精品课程\n采购管理"
        self.assertIsNone(compare(source)["similarity"])

    def test_protocol_scope_capacity_fails_closed(self):
        source = "个人介绍\n" + "\n".join(f"本人曾主讲第{i}项仓储安全课程。" for i in range(65))
        with self.assertRaisesRegex(ValueError, "capacity_limit"):
            evidence_units(source)


if __name__ == "__main__":
    unittest.main()
