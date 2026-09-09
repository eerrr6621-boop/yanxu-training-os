"""Fixed, independent syntax pairs. Synthetic facts, not model accuracy."""
import unittest
from worker import evidence_units
from test_evidence_sections import compare


class EvidenceGrammarTests(unittest.TestCase):
    def assert_retrievable(self, text):
        row = compare(text)
        self.assertIsNotNone(row["similarity"])
        for quote in row["evidence"]:
            self.assertIn(quote, text)
        for unit in row["evidence_units"]:
            self.assertEqual(text[unit["source_start"]:unit["source_end"]], unit["text"])
        return row

    def test_ordinary_completed_personal_sentences(self):
        for text in (
            "本人已为园艺学员主讲土壤含水量观察课程，课程已结束。",
            "本人已经给社区讲解员独立讲授馆藏编号规则课程。",
            "该讲师实际主讲应急装备盘点课程，共完成两次授课。",
            "同一讲师实际主讲陶土成型课程，已完成教学。",
            "本人已给维修团队主讲紧固件识别课程，课程已结束。",
            "两份结课记录注明，同一讲师先为少年志愿者完成一场文物标签授课，再为成人志愿者完成另一场授课。",
        ):
            with self.subTest(text=text):
                self.assert_retrievable(text)

    def test_known_other_teacher_negation_keeps_only_affirmative_quotes(self):
        for suffix in ("其他讲师没有承担该课讲授", "其他老师未承担该课程的授课"):
            for separator in ("。", "；", "，"):
                text = "本人已给仓库管理员主讲货位标识课程" + separator + suffix + "。"
                with self.subTest(text=text):
                    row = self.assert_retrievable(text)
                    self.assertTrue(all(suffix not in q for q in row["evidence"]))

    def test_concrete_course_task_restriction_is_not_teaching_denial(self):
        for restriction in (
            "课程无需写代码或配置系统", "课堂不要求学员安装软件", "本课程不需要编程",
            "练习无需连接生产系统", "课程无需学员操作真实设备",
        ):
            text = "本人已为档案管理人员主讲文件命名规范课程。" + restriction + "。"
            with self.subTest(text=text):
                row = self.assert_retrievable(text)
                self.assertTrue(all(restriction not in q for q in row["evidence"]))

    def test_standalone_restriction_cannot_create_teaching_evidence(self):
        for text in ("课程无需写代码。", "其他讲师没有承担该课讲授。"):
            with self.subTest(text=text):
                self.assertIsNone(compare(text)["similarity"])

    def test_personal_denial_and_positive_other_actor_stay_blocked(self):
        for tail in (
            "本人没有承担该课讲授。", "该讲师未承担该课程的授课。", "其他讲师承担该课讲授。",
            "其他讲师没有不承担该课讲授。", "其他讲师没有承担该课讲授，但本人也没有授课。",
            "课程不需要本人授课。", "课程无需写代码，但本人尚未授课。",
        ):
            text = "本人已为学员主讲庭院植物识别课程。" + tail
            with self.subTest(text=text):
                self.assertIsNone(compare(text)["similarity"])

    def test_invitation_and_assistant_roles_cannot_borrow_a_verb(self):
        for text in (
            "本人已为学员邀请另一位老师主讲垃圾减量课程。",
            "本人已给学员安排其他讲师讲授水表抄录课程。",
            "本人担任助教。\n该讲师实际主讲水表抄录课程。",
            "本人为教学助理。\n本人已给学员主讲水表抄录课程。",
            "本人已给学员主讲水表抄录课程。\n本人未亲自授课。",
        ):
            with self.subTest(text=text):
                self.assertIsNone(compare(text)["similarity"])

    def test_course_restriction_does_not_hide_a_later_crossline_denial(self):
        text = "本人已给志愿者主讲导览标识课程。课程无需写代码。\n上述记录非本人授课。"
        self.assertIsNone(compare(text)["similarity"])

    def test_restricted_task_is_never_a_positive_retrieval_target(self):
        text = "本人已给检验人员主讲标签核对课程。课程无需写代码。本人已为保管员主讲封签课程。"
        units = evidence_units(text)
        self.assertTrue(units["complete"])
        self.assertTrue(units["units"])
        self.assertTrue(all("无需" not in u["text"] and "写代码" not in u["text"] for u in units["units"]))
        self.assertTrue(all(u["paragraph_start"] == 0 and u["paragraph_end"] == len(text) for u in units["units"]))

    def test_unknown_course_restrictions_do_not_silently_become_positive(self):
        for tail in ("课程不要求学员讲授。", "课堂不安排设备拆卸。", "课程无需写代码及承担主讲。"):
            with self.subTest(tail=tail):
                self.assertIsNone(compare("本人已给学员主讲庭院排水课程。" + tail)["similarity"])

    def test_same_teacher_reference_cannot_borrow_another_named_actor(self):
        for prefix in ("王明老师实际主讲盆栽养护课程。", "其他讲师已讲授盆栽养护课程。"):
            text = "个人介绍\n" + prefix + "\n主讲课程\n同一讲师已给学员主讲园艺工具课程。"
            with self.subTest(text=text):
                self.assertIsNone(compare(text)["similarity"])

    def test_completed_support_actions_are_not_completed_teaching(self):
        for text in (
            "本人已完成参加培训授课的报名。", "本人已完成授课报名，主讲人另行安排。",
            "本人已完成课程授课的准备材料。", "本人已为志愿者安排主讲老师的签到。",
        ):
            with self.subTest(text=text):
                self.assertFalse(__import__('worker')._completed_personal_event(text))

    def test_independent_fact_path_does_not_reinsert_a_nonproof_clause(self):
        text = "个人介绍\n本人已为包装工主讲标签核对课程；其他老师未承担该课程的授课。"
        row = self.assert_retrievable(text)
        self.assertTrue(all("其他老师" not in q for q in row["evidence"]))


if __name__ == "__main__":
    unittest.main()
