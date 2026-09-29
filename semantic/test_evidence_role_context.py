"""Independent synthetic cross-line role regressions, not model evaluation."""
import unittest
from test_evidence_sections import compare
from worker import role_safe_at


class RoleContextTests(unittest.TestCase):
    def test_assistant_attendee_roles_cannot_be_erased(self):
        for role in ("本人担任助教。", "本人担任课程助理。", "本人是授课助理。", "本人为教学助理。", "本人在现场旁听。", "本人未亲自授课。", "本人角色：助理。"):
            with self.subTest(role=role):
                text="个人介绍\n"+role+"\n2025年开展仓储安全课堂。\n精品课程\n仓储安全"
                self.assertIsNone(compare(text)["similarity"])

    def test_positive_heading_does_not_reset_an_unknown_role(self):
        text="个人介绍\n本人担任助教。\n实际授课记录\n2025年开展仓储安全课堂。"
        self.assertIsNone(compare(text)["similarity"])

    def test_explicit_new_independent_personal_event_recovers(self):
        text="个人介绍\n本人担任助教。\n另于2025年独立主讲仓储安全课程。"
        result=compare(text)
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual(result["evidence"],["另于2025年独立主讲仓储安全课程。"])

    def test_different_explicit_role_year_recovers_personal_teaching(self):
        text="教学经历\n2023年本人担任课程助理。\n2025年本人独立主讲焊接识图课程。"
        self.assertEqual(compare(text)["evidence"],["2025年本人独立主讲焊接识图课程。"])

    def test_reverse_order_different_years_does_not_delete_real_history(self):
        text="教学经历\n2023年本人亲自主讲焊接识图课程。\n2025年本人担任课程助理。"
        self.assertEqual(compare(text)["evidence"],["2023年本人亲自主讲焊接识图课程。"])

    def test_same_year_is_not_enough_to_separate_events(self):
        text="教学经历\n2025年本人担任课程助理。\n另于2025年独立主讲焊接识图课程。"
        self.assertIsNone(compare(text)["similarity"])

    def test_unknown_role_after_event_is_not_ignored(self):
        text="教学经历\n另于2025年独立主讲焊接识图课程。\n本人担任课程助理。"
        self.assertIsNone(compare(text)["similarity"])

    def test_undated_independence_does_not_prove_distinct_event(self):
        self.assertIsNone(compare("个人介绍\n本人担任助教。\n本人独立主讲焊接识图课程。")["similarity"])

    def test_mentoring_is_not_independent_lecturing(self):
        self.assertIsNone(compare("个人介绍\n本人担任助教。\n另于2025年带教焊接识图课程。")["similarity"])

    def test_personal_denial_still_dominates_later_heading(self):
        self.assertIsNone(compare("个人介绍\n本人没有承担任何教学工作。\n教学经历\n另于2025年独立主讲焊接识图课程。")["similarity"])

    def test_back_reference_qualification_is_not_a_different_role(self):
        text="个人介绍\n本人担任助教。\n另于2025年独立主讲焊接识图课程。\n上述经历仅为团队案例。"
        self.assertIsNone(compare(text)["similarity"])

    def test_role_and_event_same_line_cannot_be_split_into_proof(self):
        text="教学经历\n2023年本人担任课程助理，另于2025年独立主讲焊接识图课程。"
        self.assertIsNone(compare(text)["similarity"])

    def test_no_role_restriction_preserves_simple_event(self):
        self.assertIsNotNone(compare("教学经历\n2025年本人主讲焊接识图课程。")["similarity"])

    def test_raw_role_guard_is_shared_by_whole_document_path(self):
        text="本人担任助教。\n2025年开展焊接识图课堂。"
        start=text.index("2025")
        self.assertFalse(role_safe_at(text,start,len(text)))


if __name__ == '__main__':
    unittest.main()
