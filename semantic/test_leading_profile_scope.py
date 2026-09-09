"""New fixed synthetic source cases, no model/real resume/held-out input."""
import unittest
from worker import evidence_units, Encoder, server_event_units, EVENT_SCOPE

def source(topic):
    return f"✓ {topic}\n✓ 分类标记整理\n个人介绍\n本人参加过陶艺课程培训。\n精品课程\n纸张对折课程"

def leading(text):
    return [s for s in evidence_units(text)["scoring_sections"] if s.get("kind") == "leading_profile_list_v1"]

class LeadingProfileTests(unittest.TestCase):
    def test_content_scope_without_full_completeness(self):
        for topic in ("岩样编号核对", "皮革裁片归档", "酿造桶号标识"):
            text = source(topic)
            analysis = evidence_units(text)
            self.assertFalse(analysis["complete"])
            self.assertIn("non_teaching_or_ambiguous_role", analysis["review_reasons"])
            self.assertEqual(len(leading(text)), 1)
            scope = leading(text)[0]
            self.assertEqual(scope["heading"], "")
            self.assertEqual(scope["heading_start"], scope["heading_end"])
            self.assertEqual(text[scope["source_start"]:scope["source_end"]], f"✓ {topic}\n✓ 分类标记整理")
            self.assertTrue(any(topic in u["text"] for u in analysis["scoped_units"]))
            self.assertEqual(analysis["scoring_scope_override"], "independent_profiles_v1")

    def test_full_source_role_and_qualification(self):
        text = source("岩样编号核对")
        for preface in ("团队业绩\n", "以下是他人的专业列表。\n", "客户希望采购以下课程。\n", "课程样例\n"):
            with self.subTest(preface=preface): self.assertEqual(leading(preface + text), [])
        for suffix in ("\n以上不是本人专业方向。", "\n上述列表属于团队能力。", "\n这些勾选项来自课程模板。", "\n本人角色：助教", "\n该列表还待核实。"):
            with self.subTest(suffix=suffix): self.assertEqual(leading(text + suffix), [])

    def test_contiguous_closed_list_only(self):
        text = source("岩样编号核对")
        for changed in (text.replace("✓ 分类标记整理", "本人仅负责会务\n✓ 分类标记整理"),
                        text.replace("✓ 分类标记整理", "• 分类标记整理"),
                        text.replace("\n✓ 分类标记整理", "\n\n✓ 分类标记整理"),
                        text.replace("✓ 岩样编号核对\n", ""),
                        text.replace("✓ 岩样编号核对", "✓ 本人已主讲岩样编号核对"),
                        "✓ 岩样编号核对\n✓ 分类标记整理",
                        "✓ 岩样编号核对\n\n未承载说明\n个人介绍\n本人参加陶艺培训。"):
            with self.subTest(source=changed): self.assertEqual(leading(changed), [])
        long_list = "\n".join(["✓ " + "标签整理" * 8] * 12) + "\n个人介绍\n本人参加陶艺培训。"
        self.assertEqual(leading(long_list), [])

    def test_unicode_and_metadata_are_literal(self):
        for text in (source("岩样编号📚核对"), source("岩样编号核对").replace("\n", "\r\n"),
                     "性别：女。年龄：52。\n" + source("岩样编号核对")):
            self.assertEqual(len(leading(text)), 1)
            self.assertTrue(all(u["text"] == text[u["source_start"]:u["source_end"]] for u in evidence_units(text)["scoped_units"]))

    def test_qualification_content_is_not_a_possession_claim(self):
        text = source("岩样编号核对")
        self.assertEqual(len(leading(text.replace("✓ 分类标记整理", "✓ 资格认证流程"))), 1)
        for claim in ("已取得讲师资格", "获得专业认证", "持有合格证书"):
            self.assertEqual(leading(text.replace("✓ 分类标记整理", "✓ " + claim)), [])

    def test_encoder_protocol_uses_new_scope_identity(self):
        # A constant stub tests routing only; not a model or fabricated result.
        encoder = object.__new__(Encoder)
        encoder.np = type("Dot", (), {"dot": staticmethod(lambda a,b: 0.5)})
        encoder.precision = "int8"
        seen = []
        encoder.encode = lambda text, query=False: seen.append((query,text)) or [1.0]
        result = encoder.compare({"query":"岩样编号核对", "documents":[{"id":1,"text":source("岩样编号核对")}]})["results"][0]
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["scoped_evidence_complete"])
        self.assertEqual(result["scoring_scope"], "independent_profiles_v1")
        self.assertTrue(any(not query and "岩样编号核对" in text for query,text in seen))

    def test_complete_document_is_not_mislabelled_scoped(self):
        text = "✓ 岩样编号核对\n✓ 分类标记整理\n个人介绍\n擅长纸张对折。"
        analysis = evidence_units(text)
        self.assertTrue(analysis["complete"])
        self.assertNotIn("scoring_scope_override", analysis)
        encoder = object.__new__(Encoder)
        encoder.np = type("Dot", (), {"dot": staticmethod(lambda a,b: 0.5)})
        encoder.precision = "int8"
        encoder.encode = lambda text, query=False: [1.0]
        result = encoder.compare({"query":"岩样编号核对", "documents":[{"id":1,"text":text}]})["results"][0]
        self.assertTrue(result["evidence_complete"])
        self.assertFalse(result["scoped_evidence_complete"])
        self.assertEqual(result["scoring_scope"], "document")
        self.assertEqual(result["scoring_sections"], [])

    def test_noun_substrings_are_not_modality_or_actor_claims(self):
        for noun in ("自我调节方法", "拟合误差检查", "计划编排方法"):
            self.assertEqual(len(leading(source(noun))), 1)

    def test_only_complete_known_heading_is_not_a_reference(self):
        text = source("岩样编号核对")
        self.assertEqual(len(leading(text.replace("精品课程", "课程列表"))), 1)
        self.assertEqual(leading(text + "\n上述列表不是本人方向。"), [])
        self.assertEqual(leading(text + "\n课程列表仅为团队产品。"), [])

    def test_existing_event_scope_no_longer_overwrites_profile(self):
        # This is an explicit protocol input, not a fabricated teaching proof.
        # Java separately computes/validates these event ranges in production.
        # The original fixture is unchanged. Its old EVENT-overwrites-profile
        # expectation is archived in leading-scope-final; root authorised this
        # one descriptive defect expectation to change with the mixed repair.
        text = source("岩样编号核对") + "\n\n2025年本人独立主讲纸张对折课程。"
        analysis = evidence_units(text)
        self.assertFalse(analysis["complete"])
        self.assertTrue(any("岩样编号核对" in u["text"] for u in analysis["scoped_units"]))
        start = text.index("2025年")
        event = dict(event_id="event-"+str(start),source_start=start,source_end=len(text),heading="",heading_start=start,heading_end=start,
                     kind=EVENT_SCOPE,offset_unit="unicode_code_point")
        combined = server_event_units(dict(text=text,server_teaching_event_scopes=[event]),analysis)
        self.assertEqual(combined["scoring_scope_override"], "mixed_source_scopes_v1")
        self.assertTrue(any("岩样编号核对" in u["text"] for u in combined["scoped_units"]))
        self.assertFalse(combined["complete"])

if __name__ == "__main__": unittest.main()
