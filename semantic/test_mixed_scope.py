"""Fixed synthetic range-union tests, no encoder weights/real/held-out data."""
import copy
import unittest
from unittest.mock import patch
import worker

def fixture(topic="岩样编号核对", catalog="岩样封存"):
    text=f"✓ {topic}\n✓ 分类标记整理\n个人介绍\n本人参加过陶艺课程培训。\n精品课程\n《{catalog}》\n\n实际授课记录\n\n本人已独立面向新员工讲授过{topic}。"
    start=text.index("本人已独立")
    scope=dict(event_id="event-"+str(start),source_start=start,source_end=len(text)-1,
        heading="",heading_start=start,heading_end=start,kind=worker.EVENT_SCOPE,offset_unit="unicode_code_point")
    return dict(id=1,text=text,server_teaching_event_scopes=[scope])

def encoder():
    result=object.__new__(worker.Encoder);result.np=type("Dot",(),{"dot":staticmethod(lambda a,b:0.5)})
    result.precision="int8";result.encode=lambda text,query=False:[1.0]
    return result

class MixedScopeTests(unittest.TestCase):
    def test_three_domain_union_preserves_literals_and_unknown_remainder(self):
        for topic,catalog in (("岩样编号核对","岩样封存"),("皮革裁片归档","库存盘点"),("酿造桶号标识","容器清洗")):
            doc=fixture(topic,catalog);before=worker.evidence_units(doc["text"]);saved=copy.deepcopy(before)
            actual=worker.server_event_units(doc,before)
            self.assertEqual(before,saved)
            self.assertFalse(actual["complete"])
            self.assertEqual(actual["review_reasons"],before["review_reasons"])
            self.assertEqual(actual["scoring_scope_override"],worker.MIXED_SCOPE)
            self.assertEqual(len(actual["scoring_sections"]),len(before["scoring_sections"])+1)
            for term in ("✓ "+topic,"《"+catalog+"》","本人已独立"):
                self.assertTrue(any(term in u["text"] for u in actual["scoped_units"]))
            for unit in actual["scoped_units"]:
                self.assertEqual(unit["text"],doc["text"][unit["source_start"]:unit["source_end"]])
                self.assertEqual(unit["kind"],"retrieval_only")
            self.assertEqual(sum("本人已独立" in u["text"] for u in actual["scoped_units"]),1)
            self.assertFalse(any("参加过" in u["text"] for u in actual["scoped_units"]))

    def test_complete_document_and_absent_events_keep_old_protocol(self):
        doc=fixture();doc["text"]=doc["text"].replace("本人参加过陶艺课程培训。","擅长纸张对折。")
        start=doc["text"].index("本人已独立");doc["server_teaching_event_scopes"][0].update(event_id="event-"+str(start),source_start=start,heading_start=start,heading_end=start,source_end=len(doc["text"])-1)
        row=encoder().compare(dict(query="岩样编号核对",documents=[doc]))["results"][0]
        self.assertTrue(row["evidence_complete"]);self.assertFalse(row["scoped_evidence_complete"])
        self.assertEqual(row["scoring_scope"],"document");self.assertEqual(row["scoring_sections"],[])
        original=worker.evidence_units(fixture()["text"])
        self.assertIs(worker.server_event_units(dict(text=fixture()["text"]),original),original)

    def test_mixed_wire_metadata_and_actual_encode_set(self):
        model=encoder();seen=[];model.encode=lambda text,query=False:seen.append((query,text)) or [1.0]
        row=model.compare(dict(query="岩样编号核对",documents=[fixture()]))["results"][0]
        self.assertEqual(row["scoring_scope"],worker.MIXED_SCOPE)
        self.assertFalse(row["evidence_complete"]);self.assertTrue(row["scoped_evidence_complete"])
        self.assertTrue(any(not q and "《岩样封存》" in t for q,t in seen))
        self.assertEqual(sum(not q and "本人已独立" in t for q,t in seen),1)

    def test_full_source_denial_and_duplicate_event_not_hidden(self):
        doc=fixture();doc["text"]+="\n以上不是本人授课经历。";analysis=worker.evidence_units(doc["text"])
        self.assertIs(worker.server_event_units(doc,analysis),analysis)
        doc=fixture();doc["server_teaching_event_scopes"]*=2
        with self.assertRaisesRegex(ValueError,"invalid_source_event_range"):
            worker.server_event_units(doc,worker.evidence_units(doc["text"]))

    def test_combined_scope_limit_is_not_per_family(self):
        doc=fixture();analysis=worker.evidence_units(doc["text"]);analysis["scoring_sections"]*=22
        with self.assertRaisesRegex(ValueError,"capacity_limit"):
            worker.server_event_units(doc,analysis)

    def test_effective_scored_unit_capacity_checked_after_merge(self):
        doc=fixture();analysis=worker.evidence_units(doc["text"]);actual=worker.server_event_units(doc,analysis)
        self.assertGreater(len(actual["scoped_units"]),1)
        with patch.object(worker,"MAX_CHUNKS",len(actual["scoped_units"])-1):
            with self.assertRaisesRegex(ValueError,"capacity_limit"):
                encoder().compare(dict(query="岩样编号核对",documents=[doc]))
        # The old pre-merge units count must not substitute for the effective
        # list. This mocked analysis exercises accounting, not source proof.
        inflated=dict(analysis);inflated["units"]=[]
        with patch.object(worker,"evidence_units",return_value=inflated),patch.object(worker,"MAX_CHUNKS",1):
            with self.assertRaisesRegex(ValueError,"capacity_limit"):
                encoder().compare(dict(query="岩样编号核对",documents=[doc]))

    def test_identical_different_occurrences_do_not_lose_provenance(self):
        doc=fixture();analysis=worker.evidence_units(doc["text"])
        text="甲乙\n甲乙";first=dict(text="甲乙",source_start=0,source_end=2,paragraph_start=0,paragraph_end=2,offset_unit="unicode_code_point",kind="retrieval_only",context_status="paragraph_preserved",section_heading=None)
        analysis.update(scoped_units=[first],scoped_complete=True,scoring_sections=[dict(heading="",heading_start=0,heading_end=0,source_start=0,source_end=2,offset_unit="unicode_code_point")])
        scope=doc["server_teaching_event_scopes"][0];scope.update(source_start=3,source_end=5,heading_start=3,heading_end=3)
        doc["text"]=text
        # Structural worker-only fixture; Java must independently certify any
        # event before acceptance. Repeated content is not an event-count proof.
        output=worker.server_event_units(doc,analysis)
        self.assertEqual([(u["source_start"],u["source_end"]) for u in output["scoped_units"]],[(0,2),(3,5)])

    def test_catalog_quote_before_heading_keeps_literal_ownership(self):
        for topic,catalog in (("岩样编号核对","岩样封存"),("皮革裁片归档","库存盘点"),("酿造桶号标识","容器清洗")):
            doc=fixture(topic,catalog)
            doc["text"]=f"教育背景\n本人参加过陶艺课程培训。\n个人介绍\n擅长资料整理。\n《{catalog}》\n《样本分类》\n精品课程\n《文档编号》\n\n实际授课记录\n\n本人已独立面向新员工讲授过{topic}。"
            start=doc["text"].index("本人已独立");scope=doc["server_teaching_event_scopes"][0]
            scope.update(event_id="event-"+str(start),source_start=start,heading_start=start,heading_end=start,source_end=len(doc["text"])-1)
            output=worker.server_event_units(doc,worker.evidence_units(doc["text"]))
            self.assertEqual(output["scoring_scope_override"],worker.MIXED_SCOPE)
            quotes=[s for s in output["scoring_sections"] if s.get("kind")=="course_catalog_entry_v2"]
            self.assertEqual(len(quotes),2)
            for entry in quotes:
                self.assertGreater(entry["heading_start"],entry["source_end"])
                self.assertTrue(any(u["text"]==doc["text"][entry["source_start"]:entry["source_end"]]
                    and u["section_heading"]==entry["heading"] for u in output["scoped_units"]))

if __name__=="__main__":unittest.main()
