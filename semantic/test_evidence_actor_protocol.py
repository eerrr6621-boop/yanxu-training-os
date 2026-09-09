"""Cross-language source/offset protocol, fixed vectors only; no model or DB.

Set ACTOR_TEST_CLASSPATH to freshly compiled classes plus the normal jars.
These synthetic vectors/identity can never be used as production model results.
"""
import json
import os
import subprocess
import unittest
from worker import Encoder


@unittest.skipUnless(os.environ.get("ACTOR_TEST_CLASSPATH"), "requires explicit isolated Java classpath")
class ActorProtocolTest(unittest.TestCase):
    def test_real_worker_preprocessing_through_unchanged_protocol(self):
        identity = dict(expected_model="synthetic/fixed-vector-actor-test", expected_revision="fixture-v1", expected_precision="int8")
        java = os.environ.get("ACTOR_TEST_JAVA", "java")
        cp = os.environ["ACTOR_TEST_CLASSPATH"]
        def replay(cases, phase):
            payload = dict(identity, cases=cases, phase=phase)
            run = subprocess.run([java, "-cp", cp, "com.training.PosttrainingV2Probe"],
                                 input=json.dumps(payload, ensure_ascii=False), text=True,
                                 capture_output=True, check=True, timeout=30)
            return json.loads(run.stdout)["cases"]
        query = "指定课程：《茶叶含水量校准》；必须有实际授课记录"
        values = [
            ("本人独立讲完《茶叶含水量校准》，课堂已结课。", True),
            ("《茶叶含水量校准》课堂已结课，独立主讲是本人。", True),
            ("我已独立完成茶叶含水量校准教学。", True),
            ("本单位已有《茶叶含水量校准》的结课案例，独立主讲是同事。本人负责讲义装订和发放。", False),
            ("《茶叶含水量校准》课程已结课，主讲为另一位教员。本人是旁听学员。", False),
            ("本人负责《茶叶含水量校准》讲义装订和发放。", False),
            ("本人已独立完成茶叶含水量校准教学助理工作。", False),
            ("本人独立讲完《茶叶含水量校准》。课堂不包含机械维修内容。", True),
            ("本人独立讲完《茶叶含水量校准》。课堂不包含茶叶含水量校准内容。", False),
            ("本人独立讲完《茶叶含水量校准》。本人仅负责讲义装订和发放。", False),
            ("本人独立讲完《茶叶含水量校准》。本人也负责课件制作。", True),
        ]
        cases = [dict(id="actor-protocol-"+str(i), query=query,
                      documents=[dict(id=1, text=text)]) for i, (text, _) in enumerate(values)]
        prepared = replay(cases, "prepare")
        encoder = object.__new__(Encoder)
        encoder.np = type("Dot", (), {"dot": staticmethod(lambda a, b: 0.75)})
        encoder.precision = "int8"
        encoder.encode = lambda text, query=False: [1.0]
        for case, prep in zip(cases, prepared):
            response = encoder.compare(dict(query=prep["focused_query"], documents=prep["prepared_documents"]))
            response.update(model=identity["expected_model"], revision=identity["expected_revision"], experimental_evaluation=True)
            case["semantic_response"] = response
        results = replay(cases, "evaluate")
        for result, (_, expected) in zip(results, values):
            with self.subTest(id=result["id"]):
                self.assertEqual(result["semantic_state"]["status"], "ready")
                self.assertEqual(result["admitted_ids"], [1] if expected else [])


if __name__ == "__main__":
    unittest.main()
