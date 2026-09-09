"""Offline real-worker tests. Temporary cache and a generated in-memory test token.
No external endpoints and no production data. Optional --java-classpath tests Java.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--model-dir", required=True)
parser.add_argument("--java-classpath")
parser.add_argument("--java", default="java")
parser.add_argument("--faculty-suite", action="store_true", help="Also run faculty API regression in a fresh throwaway database")
parser.add_argument("--rail-suite", action="store_true", help="Run sourced railway API regression with actual local model in a fresh DB")
args = parser.parse_args()
if (args.faculty_suite or args.rail_suite) and not args.java_classpath:
    parser.error("--faculty-suite requires --java-classpath with freshly compiled application and tests")
if args.faculty_suite and args.rail_suite:
    parser.error("Run each API suite in its own fresh database")
secret = secrets.token_hex(32)
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
env = dict(os.environ, YANXU_SEMANTIC_TOKEN=secret, YANXU_SEMANTIC_PORT=str(port))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with tempfile.TemporaryDirectory(prefix="yanxu-semantic-test-") as directory:
    process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("worker.py")),
                                "--model-dir", args.model_dir, "--cache", directory + "/vectors.sqlite",
                                "--port", str(port)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        def request(payload, token=secret):
            data = json.dumps(payload, ensure_ascii=False).encode()
            req = urllib.request.Request(f"http://127.0.0.1:{port}/compare", data=data,
                                         headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
            try:
                with opener.open(req, timeout=10) as response:
                    assert response.headers.get("Cache-Control") == "no-store"
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                return error.code, json.load(error)
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError("Worker failed to start: " + process.stderr.read().decode())
            try:
                status, _ = request({}, token="invalid")
                if status == 401:
                    break
            except (OSError, urllib.error.URLError):
                time.sleep(.05)
        else:
            raise TimeoutError("Worker did not start")
        payload = {"query": "银行客户服务", "documents": [{"id": 1, "text": "主讲银行客户投诉处理与情绪安抚方法"}]}
        status, first = request(payload)
        assert status == 200 and first["complete"] and first["results"][0]["similarity"] is not None
        assert request(payload)[1]["results"] == first["results"], "Cache output must be stable"
        payload["documents"][0]["text"] = "人工核对后仅主讲Excel数据整理与函数课程"
        edited = request(payload)[1]["results"][0]
        assert "Excel" in edited["evidence"][0] and "银行" not in edited["evidence"][0], "Same-ID edits invalidate evidence"
        payload["documents"][0]["text"] = "没有银行客户投诉处理课程的教学经验"
        unavailable = request(payload)[1]["results"][0]
        assert unavailable["similarity"] is None and unavailable["evidence"] == []
        payload["documents"][0]["text"] = "主讲Excel数据整理与函数课程。不是银行客户投诉课程的专业讲师"
        mixed = request(payload)[1]["results"][0]
        assert mixed["evidence"] == ["主讲Excel数据整理与函数课程"], "Negated manual claims never become semantic evidence"
        assert request({"query": "客户投诉", "documents": [{"id": True, "text": "不正确的教师编号"}]})[0] == 422
        assert request({"query": "客户投诉", "documents": [{"id": 1, "text": "主讲银行客户投诉处理课程"}]*201})[0] == 422
        assert request({"query": "客户投诉", "documents": [{"id": 1, "text": "主讲銀行課程"},{"id": 1, "text": "主讲银行课程"}]})[0] == 422
        if args.java_classpath:
            subprocess.run([args.java, "-cp", args.java_classpath, "com.training.LocalSemanticTest", "live"],
                           env=env, check=True, timeout=15)
        if args.faculty_suite or args.rail_suite:
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                api_port = probe.getsockname()[1]
            with open(directory + "/application.log", "w+") as log:
                application = subprocess.Popen([args.java, "-Dfile.encoding=UTF-8", "-Dbootstrap.demo=true",
                    "-Ddispatch.timetable.file=" + (str(Path(__file__).resolve().parent.parent / "config/rail-timetable.json") if args.rail_suite else ""),
                    "-Ddispatch.transport.file=",
                    "-Dbind.address=127.0.0.1", "-Ddata.dir=" + directory + "/test-data",
                    "-cp", args.java_classpath, "com.training.Main", str(api_port)],
                    env=env, stdout=log, stderr=log)
                try:
                    for _ in range(120):
                        if application.poll() is not None:
                            raise RuntimeError("Isolated API failed to start")
                        try:
                            with opener.open(f"http://127.0.0.1:{api_port}/", timeout=1) as response:
                                if response.status == 200:
                                    break
                        except (OSError, urllib.error.URLError):
                            time.sleep(.1)
                    else:
                        raise TimeoutError("Isolated API did not start")
                    subprocess.run(["node", "scripts/test_rail_api.cjs" if args.rail_suite else "test_faculty.js"], env=dict(env,
                        TRAINING_API_BASE=f"http://127.0.0.1:{api_port}/api",
                        TRAINING_EXPECT_LOCAL_SEMANTIC="1"), check=True, timeout=180)
                finally:
                    application.terminate()
                    try:
                        application.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        application.kill(); application.wait()
        print("Offline worker auth, cache, edit, negation and bounds: passed")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait()
