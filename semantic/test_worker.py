"""Pure validation tests; works without downloading or loading a model."""
import unittest
from unittest.mock import patch
from worker import Encoder, MAX_CHARS, chunks, evidence_units, requirement_units


class ChunkTests(unittest.TestCase):
    def test_source_boundaries(self):
        source = "主讲客户情绪安抚与投诉沟通方法。教授投诉实战案例和服务补救方法。"
        self.assertEqual(chunks(source), [source])
        self.assertTrue(all(part in source for part in chunks(source)))

    def test_negation_not_cut_into_positive_evidence(self):
        for prefix in ("不擅长", "不具备", "仅参加", "仅听过", "未曾", "不包含", "不需要", "无需", "不是", "没有", "尚未", "待核验"):
            self.assertEqual(chunks(prefix + "客户投诉处理课程与实战案例" * 40), [])

    def test_manual_negative_and_positive_profile_are_separate(self):
        positive = "主讲Excel数据整理与办公效能课程"
        negative = "没有" + "银行客户投诉处理与情绪疏导" * 30 + "的授课经验"
        # A blank line is a structural boundary; punctuation alone is not.
        self.assertEqual(chunks(positive + "\n\n" + negative), [positive])

    def test_personal_fields_are_not_professional_evidence(self):
        course = "主讲银行适老化服务与女性客户服务沟通课程"
        self.assertEqual(chunks("性别：男，年龄：45，某讲师。\n\n" + course), [course])
        self.assertEqual(chunks("性别：女，年龄：35，某讲师。\n\n" + course), [course])

    def test_slow_final_embedding_is_not_returned_as_complete(self):
        encoder = object.__new__(Encoder)
        clock = [0]
        def encode(_text, query=False):
            clock[0] += 1 if query else 7
            return None
        encoder.encode = encode
        with patch("worker.time.monotonic", side_effect=lambda: clock[0]):
            with self.assertRaises(TimeoutError):
                encoder.compare({"query": "客户服务", "documents": [{"id": 1, "text": "主讲客户服务与投诉处理课程"}]})

    def test_normalization_and_deduplication(self):
        # Normalize for deduplication, but show the exact original source quote.
        self.assertEqual(chunks("教授Ｅｘｃｅｌ数据整理与函数课程\n\n教授Excel数据整理与函数课程"), ["教授Ｅｘｃｅｌ数据整理与函数课程"])

    def test_long_material_not_arbitrarily_split(self):
        text = "".join(f"主讲第{i}项培训沟通技巧" for i in range(70))
        analysis = evidence_units(text)
        self.assertEqual(analysis["units"], [])
        self.assertFalse(analysis["complete"])
        self.assertEqual(analysis["review_reasons"], ["context_unit_too_long"])

    def test_short_query_accepted(self):
        self.assertEqual(chunks("客户投诉", query=True), ["客户投诉"])

    def test_attendance_and_negative_section_not_teaching(self):
        self.assertEqual(chunks("参加亲子财商课程培训并取得结业证书"), [])
        source = "未授课课程\n亲子财商课程的实际教学\n金融反诈课程设计与宣讲\n主讲课程\n主讲Excel办公课程"
        self.assertEqual(chunks(source), ["主讲Excel办公课程"])

    def test_city_and_accessibility_not_negation(self):
        source = "在无锡开展网点无障碍服务培训，拥有教学经验"
        self.assertEqual(chunks(source), [source])

    def test_complex_negative_query_is_not_rewritten(self):
        with self.assertRaises(ValueError):
            chunks("不需要没有银行经验的老师", query=True)


class EvidenceStructureTests(unittest.TestCase):
    def test_following_negation_cannot_launder_completed_course(self):
        for boundary in ("。", "；", "\n", "\r\n"):
            source = "产品讲解课程已经完成" + boundary + "本人没有承担本次讲授，仅做资料归档"
            self.assertEqual(chunks(source), [])
            self.assertFalse(evidence_units(source)["complete"])

    def test_preceding_negation_remains_attached(self):
        source = "本人未讲授此项目。课程已结课，主讲人员提交了教学记录。"
        self.assertEqual(chunks(source), [])

    def test_negative_late_in_long_paragraph_cannot_leave_positive_prefix(self):
        source = "培训已全部结课，课程覆盖产品工艺与实验记录。" + "记录材料已经完成归档。" * 30 + "本人没有讲授。"
        self.assertEqual(chunks(source), [])
        self.assertFalse(evidence_units(source)["complete"])

    def test_wrapped_personal_role_is_not_separated_from_team_teaching(self):
        source = "我负责课程签到和资料整理。\n团队专家完成工艺检查培训的授课与点评。"
        self.assertEqual(chunks(source), [])

    def test_attendance_record_is_not_automatically_negative(self):
        source = "本人主讲了产品检测培训。授课记录和签到表均完成归档，课堂作业已点评。"
        self.assertEqual(chunks(source), [source])
        self.assertTrue(evidence_units(source)["complete"])

    def test_genuine_teacher_may_also_help_with_attendance(self):
        source = "本人主讲产品使用方法，并负责收集签到记录，课后亲自完成学员成果点评。"
        self.assertEqual(chunks(source), [source])

    def test_participants_are_not_the_teachers_role(self):
        source = "本人讲授产品操作培训，参加培训的工人均完成了示范练习。"
        self.assertEqual(chunks(source), [source])

    def test_attending_remains_not_teaching(self):
        for source in ("我参加设备维护培训并取得课程结业证书。", "本人仅负责签到，专家讲授设备维护课程。", "本人邀请老师讲授设备维护课程。"):
            with self.subTest(source=source):
                self.assertEqual(chunks(source), [])

    def test_other_actor_is_not_personal_teaching(self):
        source = "我负责签到，老师主讲设备维护课程，班级已结课。"
        self.assertEqual(chunks(source), [])

    def test_heading_like_role_and_teaching_noun_not_personal_action(self):
        for source in ("主讲老师是合作机构专家，本人负责签到和资料整理。", "本人是设备维护课学员，老师主讲并点评了课堂作品。"):
            with self.subTest(source=source):
                self.assertEqual(chunks(source), [])

    def test_later_disclaimer_of_personal_teaching_keeps_its_scope(self):
        for wording in ("不代表本人授课", "不表示该讲师的教学经历", "不代表个人经历"):
            source = "本人实际主讲机械识图课程。\n上行内容为宣传样例，" + wording + "。"
            self.assertEqual(chunks(source), [])
            self.assertFalse(evidence_units(source)["complete"])

    def test_external_named_teacher_not_owned_by_resume_author(self):
        for source in ("2025年机械识图课由外部讲师王工主讲，本人负责设备调试。", "2025年陈老师主讲机械识图课程，我旁听并负责资料编写。", "授课讲师：合作单位专家，课程记录已归档。"):
            with self.subTest(source=source):
                self.assertEqual(chunks(source), [])
                self.assertFalse(evidence_units(source)["complete"])

    def test_explicit_owner_assignment_with_attendance_is_retained(self):
        source = "主讲老师是本人，机械识图课程已完成，签到表已归档。"
        self.assertEqual(chunks(source), [source])
        self.assertTrue(evidence_units(source)["complete"])

    def test_other_person_organizing_does_not_negate_own_teaching(self):
        source = "本人主讲机械识图课程，其他老师负责签到资料，课后本人完成点评。"
        self.assertEqual(chunks(source), [source])

    def test_mixed_own_and_other_teacher_scope_requires_review(self):
        source = "本人主讲机械识图课程，其他讲师主讲设备维护课程，共交付三场。"
        self.assertFalse(evidence_units(source)["complete"])
        self.assertEqual(chunks(source), [])

    def test_passive_voice_owner_is_not_an_external_teacher(self):
        for source in ("机械识图课程由该讲师主讲，课堂记录已归档。", "机械识图课程由本人担任讲师主讲，课堂记录已归档。", "机械识图课程由我负责讲授，课堂记录已归档。"):
            with self.subTest(source=source):
                self.assertEqual(chunks(source), [source])
                self.assertTrue(evidence_units(source)["complete"])

    def test_team_section_not_promoted_to_personal_record(self):
        source = "团队业绩\n主讲多场设备维护课程\n\n公司组织学员参加培训\n实际授课记录\n本人讲授机械识图课程"
        self.assertEqual(chunks(source), ["本人讲授机械识图课程"])

    def test_source_offsets_and_unicode_preserved(self):
        source = "  主讲课程\r\n  本人主讲Ｅｘｃｅｌ课程，含图表📊。\r\n继续完成课堂练习。 \r\n\r\n本人主讲数据核查。"
        units = evidence_units(source)["units"]
        self.assertEqual(len(units), 2)
        for unit in units:
            self.assertEqual(unit["text"], source[unit["source_start"]:unit["source_end"]])
            self.assertEqual(unit["offset_unit"], "unicode_code_point")
            self.assertLessEqual(len(unit["text"]), MAX_CHARS)
        self.assertIn("Ｅｘｃｅｌ", units[0]["text"])
        self.assertIn("\r\n", units[0]["text"])

    def test_mixed_personal_fields_withheld_without_splicing(self):
        source = "本人主讲机械识图。联系电话：13800000000。"
        analysis = evidence_units(source)
        self.assertEqual(analysis["units"], [])
        self.assertIn("mixed_personal_fields", analysis["review_reasons"])

    def test_standalone_demographic_line_not_incomplete_evidence(self):
        course = "本人主讲机械识图课程，并完成课堂练习点评。"
        for prefix in ("性别：女。年龄：52。\n", "性别：男，年龄：28\n", "联系电话：13800000000\n", "电子邮箱：example@example.invalid\n"):
            with self.subTest(prefix=prefix):
                source = prefix + course
                analysis = evidence_units(source)
                self.assertTrue(analysis["complete"])
                self.assertEqual(analysis["review_reasons"], [])
                self.assertEqual([unit["text"] for unit in analysis["units"]], [course])
                self.assertEqual(source[analysis["units"][0]["source_start"]:], course)

    def test_mixed_personal_professional_line_is_not_metadata_boundary(self):
        for source in ("性别：女。本人主讲机械识图课程。", "年龄：52，授课经历丰富", "性别：男，未承担本次工作"):
            with self.subTest(source=source):
                self.assertFalse(evidence_units(source)["complete"])
                self.assertEqual(chunks(source), [])

    def test_long_structured_document_can_use_complete_paragraphs(self):
        paragraphs = [f"本人讲授第{i}批设备维护课程，并点评课堂记录。" for i in range(40)]
        analysis = evidence_units("\n\n".join(paragraphs))
        self.assertTrue(analysis["complete"])
        self.assertEqual([u["text"] for u in analysis["units"]], paragraphs)

    def test_long_independent_teaching_sentences_split_on_boundaries(self):
        source = "".join(f"本人讲授第{i}批设备维护课程，并点评课堂记录。" for i in range(40))
        analysis = evidence_units(source)
        self.assertTrue(analysis["complete"])
        self.assertEqual("".join(unit["text"] for unit in analysis["units"]), source)
        for unit in analysis["units"]:
            self.assertLessEqual(len(unit["text"]), MAX_CHARS)
            self.assertEqual(unit["text"], source[unit["source_start"]:unit["source_end"]])
            self.assertEqual(unit["context_status"], "self_contained_sentences_after_paragraph_review")
            self.assertEqual(unit["paragraph_end"], len(source))

    def test_long_paragraph_with_cross_references_not_split(self):
        source = "".join(f"本人讲授第{i}批设备维护课程。" for i in range(40)) + "其中部分课程由其他讲师讲授。"
        self.assertFalse(evidence_units(source)["complete"])
        self.assertEqual(chunks(source), [])

    def test_long_numbered_list_keeps_unambiguous_course_at_either_end(self):
        background = "\n".join(f"第{i}项：介绍设备记录整理和流程改进方法。" for i in range(60))
        course = "本人主讲机械识图课程，并完成课堂练习点评。"
        for source in (course + "\n" + background, background + "\n" + course):
            analysis = evidence_units(source)
            self.assertTrue(analysis["complete"])
            self.assertTrue(any(course in unit["text"] for unit in analysis["units"]))
            self.assertTrue(all(unit["text"] in source for unit in analysis["units"]))

    def test_numbered_list_does_not_hide_late_denial(self):
        source = "\n".join(f"第{i}项：主讲设备记录整理课程。" for i in range(60)) + "\n上述课程本人没有承担讲授。"
        self.assertFalse(evidence_units(source)["complete"])
        self.assertEqual(chunks(source), [])


class RequirementStructureTests(unittest.TestCase):
    def test_exclusions_are_not_positive_vectors(self):
        cases = (
            "本人实际讲授机械识图课程；只编写教材不满足要求。",
            "本人实际讲授机械识图课程，已取消的场次和尚未实施的排期不计入。",
            "本人实际讲授机械识图课程；岗位操作不是本题证据。",
            "本人实际讲授机械识图课程，而不是团队其他成员讲授的记录。",
            "本人实际讲授机械识图课程；不要只有组织经验的人员。",
        )
        for source in cases:
            with self.subTest(source=source):
                analysis = requirement_units(source)
                self.assertEqual(analysis["positive_fragments"], ["本人实际讲授机械识图课程"])
                self.assertEqual(len(analysis["exclusions"]), 1)
                self.assertTrue(analysis["requires_review"])
                self.assertFalse(analysis["exclusions_enforced"])

    def test_exclusion_text_is_exact_substring(self):
        source = "主讲Ｅｘｃｅｌ课程，岗位经验不计入。"
        analysis = requirement_units(source)
        for part in analysis["positive_fragments"] + analysis["exclusions"]:
            self.assertIn(part, source)

    def test_affirmative_comma_clauses_keep_shared_subject(self):
        source = "找本人已主讲机械识图课程，也亲自点评学员作品；只整理教材不满足要求。"
        analysis = requirement_units(source)
        self.assertEqual(analysis["positive_fragments"], ["找本人已主讲机械识图课程，也亲自点评学员作品"])

    def test_removed_exclusion_does_not_join_noncontiguous_requirements(self):
        source = "机械识图课程，不包括只整理教材的经历，还需课堂练习点评"
        self.assertEqual(requirement_units(source)["positive_fragments"], ["机械识图课程", "还需课堂练习点评"])

    def test_ambiguous_and_double_negative_fail_closed(self):
        for source in ("不需要没有授课经验的人", "不需要无相关教学经验的人", "主讲机械识图，不需要不是工程师的人", "主讲机械识图，可能不是本人讲授", "主讲机械识图，不排除他没有经验"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                requirement_units(source)

    def test_exclusion_only_not_a_positive_request(self):
        with self.assertRaises(ValueError):
            requirement_units("只做会务不满足条件")

    def test_long_positive_query_not_split_mid_clause(self):
        with self.assertRaisesRegex(ValueError, "context_unit_too_long"):
            requirement_units("课程" * (MAX_CHARS + 1))

    def test_professional_city_names_and_topic_negations_not_rewritten(self):
        self.assertEqual(chunks("无锡无障碍服务课程", query=True), ["无锡无障碍服务课程"])
        with self.assertRaises(ValueError):
            requirement_units("性别：男；主讲机械识图课程")


class ComparisonMetadataTests(unittest.TestCase):
    def encoder(self):
        # Pure-Python vector facade avoids adding a NumPy/model dependency to
        # protocol tests. Every vector has a known finite positive similarity.
        encoder = object.__new__(Encoder)
        encoder.np = type("Dot", (), {"dot": staticmethod(lambda a, b: 0.75)})
        encoder.precision = "int8"
        encoder.encode = lambda text, query=False: [1.0]
        return encoder

    def test_partial_evidence_is_unscored_and_explicit(self):
        result = self.encoder().compare({"query": "机械识图课程", "documents": [{"id": 1, "text": "本人主讲机械识图课程。\n\n同一项目没有提供授课确认记录。"}]})["results"][0]
        self.assertIsNone(result["similarity"])
        self.assertFalse(result["evidence_complete"])
        self.assertTrue(result["evidence_review_reasons"])
        self.assertEqual(result["evidence"], ["本人主讲机械识图课程。"])

    def test_exclusions_never_sent_to_encoder(self):
        encoder, encoded = self.encoder(), []
        encoder.encode = lambda text, query=False: encoded.append((text, query)) or [1.0]
        result = encoder.compare({"query": "本人实际讲授机械识图课程；只编写教材不满足要求", "documents": [{"id": 1, "text": "本人主讲机械识图课程。"}]})
        self.assertEqual([text for text, query in encoded if query], ["本人实际讲授机械识图课程"])
        self.assertTrue(result["requirement_analysis"]["requires_review"])
        self.assertTrue(result["results"][0]["evidence_complete"])
        self.assertEqual(result["results"][0]["similarity"], 0.75)

    def test_valid_and_review_candidate_are_independent(self):
        result = self.encoder().compare({"query": "机械识图课程", "documents": [{"id": 1, "text": "本人主讲机械识图课程。"}, {"id": 2, "text": "没有授课经验。"}]})
        self.assertEqual(result["results"][0]["similarity"], 0.75)
        self.assertIsNone(result["results"][1]["similarity"])
        self.assertTrue(result["complete"])

    def test_demographic_prefix_changes_neither_score_nor_evidence_completeness(self):
        encoder = self.encoder()
        course = "本人主讲机械识图课程。"
        original = encoder.compare({"query": "机械识图课程", "documents": [{"id": 1, "text": course}]})["results"][0]
        for prefix in ("性别：女。年龄：52。\n", "性别：男。年龄：21。\n"):
            modified = encoder.compare({"query": "机械识图课程", "documents": [{"id": 1, "text": prefix + course}]})["results"][0]
            for field in ("id", "similarity", "evidence", "evidence_complete", "evidence_review_reasons"):
                self.assertEqual(original[field], modified[field])

    def test_identifiers_capacity_and_facet_limit_remain_fail_closed(self):
        invalid = (
            {"query": "机械识图", "documents": [{"id": True, "text": "课程"}]},
            {"query": "机械识图", "documents": [{"id": 1, "text": "课程"}, {"id": 1, "text": "课程"}]},
            {"query": ";".join(f"主题{i}" for i in range(9)), "documents": []},
            {"query": "机械识图", "documents": [{"id": 1, "text": "课" * (512 * 1024 + 1)}]},
        )
        for payload in invalid:
            with self.subTest(payload_type=len(payload["query"])), self.assertRaises(ValueError):
                self.encoder().compare(payload)


if __name__ == "__main__":
    unittest.main()
