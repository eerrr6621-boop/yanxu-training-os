"""Synthetic source-scope tests. No model, trained weights or real resumes."""
import json
import os
import subprocess
import unittest
from requirement_polarity import postposed_exclusion_spans
from worker import requirement_units


def cases():
    for topic in ("古籍页码核对", "冷链温度记录", "园艺工具清洁"):
        for predicate in ("不算", "不计入", "不计作", "不作为", "不满足", "不能视为", "不能算作", "不能代替"):
            for comma in ("，", ",", ",\n", "，\r\n  "):
                body="只有学员现场练习而教师远程讲解"+comma+predicate+"所需现场面授经历"
                yield "培训主题：《"+topic+"》。必须面授。"+body+"。必须中文授课。",body


class Scope(unittest.TestCase):
    def test_all_scopes_retained_without_positive_reversal(self):
        for q,body in cases():
            with self.subTest(query=q):
                spans=postposed_exclusion_spans(q)
                self.assertEqual([q[a:b] for a,b in spans],[body])
                output=requirement_units(q)
                self.assertEqual(output["exclusions"],[body])
                self.assertNotIn("远程","".join(output["positive_fragments"]))
                self.assertIn("必须面授",output["positive_fragments"])
                self.assertTrue(output["requires_review"])
                self.assertFalse(output["exclusions_enforced"])

    def test_independent_words_and_boundaries_preserved(self):
        q="培训主题：工具清洁。必须远程授课。必须远程授课，不算现场经历。"
        self.assertIn("必须远程授课",requirement_units(q)["positive_fragments"])
        for boundary in ("。","；",";","\n","\r\n","！","?"):
            self.assertEqual(postposed_exclusion_spans("需要远程授课"+boundary+"不算现场经历"),[])
        for q in ("《远程授课，不算现场面授经历》","“远程授课，不算现场面授经历”", "（远程授课，不算现场面授经历）",'"远程授课，不算现场面授经历"',
                  "远程授课，不算","远程授课，不算   ","远程授课，\n师资要求：不算现场经历","远程授课（，不算现场经历"):
            self.assertEqual(postposed_exclusion_spans(q),[])
        q="必须中文授课，教师远程授课，不算现场经历。"
        self.assertEqual(requirement_units(q)["positive_fragments"],["必须中文授课"])

    def test_exclusions_alone_do_not_invent_positive_targets(self):
        for q in ("远程授课，不算现场经历。", "教师远程授课，不算现场经历，不计入现场记录。"):
            with self.assertRaises(ValueError):requirement_units(q)

    @unittest.skipUnless(os.environ.get("ACTOR_TEST_CLASSPATH"),"requires isolated Java classes")
    def test_cross_language_literal_offsets_and_pending_contract(self):
        queries=[q for q,_ in cases()]
        queries += ["🧭培训主题：《现场教学》。远程授课，不算现场面授经历。", "培训主题：工具清洁。远程授课，不算现场经历，不计入现场记录。",
                    "培训主题：工具清洁。必须远程授课。必须远程授课，不算现场经历。"]
        wrapped=["培训主题：《工具清洁》\n师资要求：教师远程授课，"+wrap+"不算现场面授经历。\n必须面授。" for wrap in ("\n","\r\n","\n\n")]
        queries += wrapped
        java=os.environ.get("ACTOR_TEST_JAVA","java")
        result=subprocess.run([java,"-cp",os.environ["ACTOR_TEST_CLASSPATH"],"com.training.PostposedExclusionTest","protocol"],
            input=json.dumps(queries,ensure_ascii=False),capture_output=True,text=True,check=True,timeout=60)
        outputs=json.loads(result.stdout)
        self.assertEqual(len(outputs),len(queries))
        for q,out in zip(queries,outputs):
            with self.subTest(query=q):
                expected=[dict(start=a,end=b,text=q[a:b]) for a,b in postposed_exclusion_spans(q)]
                self.assertEqual(out["spans"],expected)
                self.assertTrue(out["review"])
                self.assertFalse(requirement_units(q)["exclusions_enforced"])
                focused=requirement_units(out["focused_query"])
                self.assertTrue(focused["requires_review"])
                if q in wrapped:
                    self.assertNotIn("远程","".join(focused["positive_fragments"]))
                    self.assertIn("必须面授","".join(focused["positive_fragments"]))

if __name__=="__main__":unittest.main()
