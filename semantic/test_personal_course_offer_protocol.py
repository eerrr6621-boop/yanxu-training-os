"""Synthetic fixed-vector worker-to-business tests; no model, DB or real resume."""
import json
import os
import subprocess
import unittest
from worker import Encoder

@unittest.skipUnless(os.environ.get("ACTOR_TEST_CLASSPATH"), "requires isolated compiled Java classpath")
class PersonalCourseOfferProtocol(unittest.TestCase):
    def test_layout_offer_and_history_are_distinct(self):
        identity=dict(expected_model="synthetic/fixed-vector-course-offer",expected_revision="fixture-v1",expected_precision="int8")
        def replay(cases,phase):
            run=subprocess.run([os.environ.get("ACTOR_TEST_JAVA","java"),"-cp",os.environ["ACTOR_TEST_CLASSPATH"],"com.training.PosttrainingV2Probe"],
                input=json.dumps(dict(identity,cases=cases,phase=phase),ensure_ascii=False),text=True,capture_output=True,check=True,timeout=60)
            return json.loads(run.stdout)["cases"]
        cases=[];expectations=[]
        for title in ("工单处理顺序","器材借用登记","课堂问答组织"):
            for sep in ("","\n","\n\n"):
                intro=f"《{title}》课程讨论如何安排处理顺序。"
                documents=[dict(id=1,text=intro+sep+"我可以提供这门课程，并亲自承担讲授。"),
                    dict(id=2,text=intro+sep+"同事可以提供这门课程并讲授，我只制作纸卡。"),
                    dict(id=3,text=f"本人安排了《{title}》。主讲由某老师完成。")]
                for history in (False,True):
                    query=f"培训主题：《{title}》；"+("必须有实际授课记录。" if history else "请推荐内容匹配的讲师。")
                    cases.append(dict(id="offer-protocol-"+str(len(cases)),query=query,documents=documents))
                    expectations.append([] if history else [1])
        prepared=replay(cases,"prepare")
        encoder=object.__new__(Encoder);encoder.np=type("Dot",(),{"dot":staticmethod(lambda a,b:0.75)})
        encoder.precision="int8";encoder.encode=lambda text,query=False:[1.0]
        for case,ready in zip(cases,prepared):
            response=encoder.compare(dict(query=ready["focused_query"],documents=ready["prepared_documents"]))
            response.update(model=identity["expected_model"],revision=identity["expected_revision"],experimental_evaluation=True)
            case["semantic_response"]=response
        outputs=replay(cases,"evaluate")
        for output,expected in zip(outputs,expectations):
            with self.subTest(case=output["id"]):
                self.assertEqual(output["semantic_state"]["status"],"ready")
                self.assertEqual(output["admitted_ids"],expected)
                self.assertEqual([r["teacher_id"] for r in output["selected"]],expected)
                for c in output["audit"]["candidates"]:
                    self.assertTrue(all(x.get("verified") is False for x in c["candidate_after_assessment"]["requirement_coverage"]))

if __name__=="__main__":unittest.main()
