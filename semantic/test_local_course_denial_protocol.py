"""Synthetic fixed-vector cross-language regression, NOT model accuracy."""
import json
import os
import subprocess
import unittest
from worker import Encoder


@unittest.skipUnless(os.environ.get("ACTOR_TEST_CLASSPATH"), "requires isolated Java classpath")
class LocalCourseDenialProtocolTest(unittest.TestCase):
    def test_original_denials_survive_scoped_similarity_and_admission(self):
        positive = "实际授课记录\n2025年本人主讲服务礼仪课程，完成课堂点评。"
        values = []
        for heading in ("未授课课程", "未讲授课程："):
            for denial in ("本人没有讲授《数字营销》。", "我从未主讲过《数字营销》这门课。", "本人尚未亲自讲授《数字营销》课程。"):
                values.append(("📚\n" + heading + "\r\n" + denial + "\r\n" + positive, True))
        for denial in ("本人没有讲授《服务礼仪》。", "我从未主讲过《服务礼仪》这门课。", "本人尚未亲自讲授《服务礼仪》课程。"):
            values.append(("未授课课程\n" + denial + "\n" + positive, False))
        for prefix in ("", "个人介绍\n", "未授课课程\n个人介绍\n"):
            values.append((prefix + "本人没有讲授《数字营销》。\n" + positive, False))
        values.append(("未授课课程\n本人没有讲授该课程。\n" + positive, False))
        values.append(("未授课课程\n本人没有讲授《数字营销》。\n" + positive + "\n以上内容不代表本人授课经历。", False))
        # Also exercise the partial-evidence/scoped-source path, including
        # checking original contradictions BEFORE Java masks scoring sections.
        values += [("个人介绍\n本人参加过摄影课程培训。\n" + source, expected) for source, expected in list(values)]
        identity = dict(expected_model="synthetic/fixed-vector-local-denial-test", expected_revision="fixture-v1", expected_precision="int8")
        def replay(cases, phase):
            run = subprocess.run([os.environ.get("ACTOR_TEST_JAVA", "java"), "-cp", os.environ["ACTOR_TEST_CLASSPATH"], "com.training.PosttrainingV2Probe"],
                                 input=json.dumps(dict(identity, cases=cases, phase=phase), ensure_ascii=False),
                                 text=True, capture_output=True, check=True, timeout=30)
            return json.loads(run.stdout)["cases"]
        cases = [dict(id="local-course-denial-" + str(i), query="培训主题：服务礼仪", documents=[dict(id=1, text=source)])
                 for i, (source, _) in enumerate(values)]
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
        self.assertEqual(len(results), len(values))
        for result, (_, expected) in zip(results, values):
            with self.subTest(id=result["id"]):
                self.assertEqual(result["semantic_state"]["status"], "ready")
                self.assertEqual(result["admitted_ids"], [1] if expected else [])


if __name__ == "__main__":
    unittest.main()
