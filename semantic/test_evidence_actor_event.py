"""New-version actor/event pairs fixed before repair; synthetic and model-free."""
import unittest
from worker import _completed_personal_event
from test_evidence_sections import compare


class ActorEventTest(unittest.TestCase):
    TOPICS = ("茶叶含水量校准", "珐琅釉色辨识")

    def test_personal_completion_and_reverse_actor(self):
        for topic in self.TOPICS:
            for text in (
                f"本人独立讲完《{topic}》，课堂已结课。",
                f"《{topic}》课堂已结课，独立主讲是本人。",
                f"我已独立完成{topic}教学。",
                f"本人已给质检员独立完成{topic}教学。",
            ):
                with self.subTest(text=text):
                    self.assertTrue(_completed_personal_event(text))
                    self.assertIsNotNone(compare(text)["similarity"])

    def test_organization_reverse_other_actor_and_support_are_not_personal(self):
        for topic in self.TOPICS:
            for text in (
                f"本单位已有《{topic}》的结课案例，独立主讲是同事。本人负责讲义装订和发放。",
                f"《{topic}》课程已结课，主讲为另一位教员。本人是旁听学员。",
                f"《{topic}》课程已结课，本人是旁听学员。",
                f"本部门已举办《{topic}》培训，班级已结课。",
                f"本人负责《{topic}》讲义装订和发放。",
                f"本人独立完成《{topic}》课程教具制作，尚未开讲。",
                f"本人已独立完成{topic}教学助理工作。",
                f"本人已独立完成{topic}授课讲义。",
                f"本人已独立完成安排其他人进行{topic}教学。",
            ):
                with self.subTest(text=text):
                    self.assertIsNone(compare(text)["similarity"])

    def test_material_completion_is_not_teaching_event(self):
        for text in ("本人已完成茶叶校准教学的讲义。", "本人已完成釉色识别教学助教工作。",
                     "本人已完成安排釉色识别教学。", "本人已完成茶叶校准教学资格申请。",
                     "本人已完成茶叶校准教学记录。", "本人已完成其他教员的釉色识别教学。",
                     "《已经开设的课程》已结课。另一门《待安排的课程》的独立主讲是本人。"):
            with self.subTest(text=text):
                self.assertFalse(_completed_personal_event(text))

    def test_crossline_support_cannot_borrow_generic_history(self):
        text = "本人负责讲义装订和发放。\n2025年开展釉色识别课程。"
        self.assertIsNone(compare(text)["similarity"])

    def test_exclusive_support_overrides_conflicting_completion_but_incidental_work_does_not(self):
        for topic in self.TOPICS:
            prefix = f"本人独立讲完《{topic}》。"
            for role in ("本人仅负责讲义装订和发放。", "本人只承担课件制作。"):
                with self.subTest(role=role):
                    self.assertIsNone(compare(prefix + role)["similarity"])
            for role in ("本人负责讲义装订和发放。", "本人也负责课件制作。"):
                with self.subTest(role=role):
                    self.assertIsNotNone(compare(prefix + role)["similarity"])

    def test_bounded_content_exclusion_does_not_reappear_in_quotes(self):
        for prefix in ("本人独立讲完《茶叶含水量校准》。", "《茶叶含水量校准》已结课，由我独立主讲。"):
            text = prefix + "课堂不包含机械维修内容。"
            row = compare(text)
            self.assertIsNotNone(row["similarity"])
            self.assertTrue(all("机械维修" not in quote for quote in row["evidence"]))
            for unit in row["evidence_units"]:
                self.assertEqual(text[unit["source_start"]:unit["source_end"]], unit["text"])
                self.assertEqual(unit["paragraph_end"], len(text))

    def test_same_topic_denial_or_unknown_reference_stays_unresolved(self):
        for suffix in ("课堂不包含茶叶含水量校准内容。", "课堂不包含上述内容。",
                       "课堂不包含本人授课记录。", "本人并非该课程主讲。"):
            with self.subTest(suffix=suffix):
                self.assertIsNone(compare("本人独立讲完《茶叶含水量校准》。" + suffix)["similarity"])
        unrelated = "本人已完成机械维修教学。介绍《茶叶含水量校准》课程，课堂不包含焊接内容。"
        self.assertIsNone(compare(unrelated)["similarity"])


if __name__ == "__main__":
    unittest.main()
