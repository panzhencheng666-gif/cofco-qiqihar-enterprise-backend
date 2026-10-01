import hashlib
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import Mock, patch

import remote_worker


class RemoteWorkerContractTest(unittest.TestCase):
    def setUp(self):
        remote_worker._web_search_retry_at = 0.0
        fallback = patch.object(remote_worker, "bing_search_results", return_value=[])
        fallback.start()
        self.addCleanup(fallback.stop)

    def test_cloud_search_candidates_keep_only_public_bounded_sources(self):
        sources = [
            {"title": "官方报道", "url": "https://www.gov.cn/report", "snippet": "搜索摘要"},
            {"title": "私有地址", "url": "https://127.0.0.1/private", "snippet": "内部内容"},
            {"title": "另一报道", "url": "https://www.lswz.gov.cn/news", "snippet": "正文片段"},
        ]
        self.assertEqual(remote_worker.assistant_candidates_for_cloud(sources), [
            {"title": "官方报道", "url": "https://www.gov.cn/report", "snippet": "搜索摘要"},
            {"title": "另一报道", "url": "https://www.lswz.gov.cn/news", "snippet": "正文片段"},
        ])
        self.assertEqual(remote_worker.assistant_candidates_for_cloud([
            {"title": "过长", "url": "https://www.gov.cn/report", "snippet": "字" * 4001}
        ]), [])

    def test_cloud_citations_preserve_search_time_and_source_type(self):
        answer = {
            "status": "ANSWERED", "mode": "FOUNDATION_RAG",
            "modelReference": "ignored", "knowledgeVersion": "v1", "answer": "回答",
            "citations": [{"id": "source-1", "title": "来源",
                           "url": "https://example.gov.cn/source", "use": "RETRIEVAL_ONLY",
                           "searchedAt": "2026-09-27T16:30:00Z",
                           "publishedAt": "2026-09-26T08:15:00Z",
                           "sourceType": "NEWS_HEADLINE"}],
            "limitations": ["限制"],
        }
        result = remote_worker.assistant_answer_for_cloud(answer)
        self.assertEqual(result["citations"][0]["searchedAt"], "2026-09-27T16:30:00Z")
        self.assertEqual(result["citations"][0]["publishedAt"], "2026-09-26T08:15:00Z")
        self.assertEqual(result["citations"][0]["sourceType"], "NEWS_HEADLINE")

    def test_grain_store_safety_search_drops_generic_instrument_pages(self):
        question = "某粮仓温度测点突然显示35℃，能否立即投药？"
        generic = {"title": "温度测试仪现场施工布点", "snippet": "设备安装和数据可靠性"}
        grain = {"title": "储粮粮情监测", "snippet": "粮仓测温技术"}
        self.assertEqual(remote_worker.filter_search_relevance(question, [generic, grain]), [grain])
        self.assertEqual(remote_worker.filter_search_relevance("温度测试仪怎么安装？", [generic]), [generic])

    def test_public_knowledge_persists_and_recalls_related_non_live_question(self):
        source = {"id": "00000000-0000-0000-0000-000000000001",
                  "title": "大豆粗蛋白含量", "url": "https://example.gov.cn/soy",
                  "snippet": "大豆蛋白含量调查", "searchedAt": "2026-09-24"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "public-knowledge.json"
            remote_worker.remember_public_knowledge(path, [source])
            self.assertEqual(remote_worker.recalled_public_knowledge(path, "大豆蛋白多少"), [source])
            self.assertEqual(remote_worker.recalled_public_knowledge(path, "2026年大豆蛋白多少"), [])
            self.assertEqual(path.stat().st_mode & 0o077, 0)

    def test_public_knowledge_refresh_runs_once_per_utc_day(self):
        source = {"id": "00000000-0000-0000-0000-000000000001",
                  "title": "粮食资料", "url": "https://example.gov.cn/grain",
                  "snippet": "粮食质量", "searchedAt": "2026-09-24",
                  "sourceType": "PUBLIC_PAGE_EXCERPT"}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(remote_worker, "public_web_search", return_value=[source]) as search:
            library = Path(directory) / "knowledge.json"
            marker = Path(directory) / "refresh.json"
            self.assertTrue(remote_worker.refresh_public_knowledge_once(library, marker))
            self.assertFalse(remote_worker.refresh_public_knowledge_once(library, marker))
            self.assertEqual(search.call_count, 1)
            self.assertEqual(len(remote_worker.load_state(library)), 1)

    def test_empty_daily_refresh_retries_after_six_hours(self):
        source = {"id": "00000000-0000-0000-0000-000000000002",
                  "title": "粮食资料", "url": "https://example.gov.cn/grain",
                  "snippet": "粮食仓储资料", "searchedAt": "2026-09-25",
                  "sourceType": "PUBLIC_PAGE_EXCERPT"}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(remote_worker, "public_web_search", side_effect=[[], [source]]) as search:
            library = Path(directory) / "knowledge.json"
            marker = Path(directory) / "refresh.json"
            with patch.object(remote_worker.time, "time", return_value=100000):
                self.assertFalse(remote_worker.refresh_public_knowledge_once(library, marker))
            with patch.object(remote_worker.time, "time", return_value=100001):
                self.assertFalse(remote_worker.refresh_public_knowledge_once(library, marker))
            self.assertEqual(search.call_count, 1)
            with patch.object(remote_worker.time, "time", return_value=121600):
                self.assertTrue(remote_worker.refresh_public_knowledge_once(library, marker))
            self.assertEqual(search.call_count, 2)
            self.assertTrue(remote_worker.load_state(marker)["successful"])

    def test_headline_only_refresh_is_not_a_knowledge_success(self):
        headline = {"id": "00000000-0000-0000-0000-000000000003",
                    "title": "新闻标题", "url": "https://news.google.com/rss/articles/example",
                    "snippet": "仅检索到标题", "searchedAt": "2026-09-25",
                    "sourceType": "NEWS_HEADLINE"}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(remote_worker, "public_web_search", return_value=[headline]):
            library = Path(directory) / "knowledge.json"
            marker = Path(directory) / "refresh.json"
            self.assertFalse(remote_worker.refresh_public_knowledge_once(library, marker))
            self.assertEqual(remote_worker.load_state(library), {})
            self.assertFalse(remote_worker.load_state(marker)["usable"])

    def test_daily_refresh_promotes_cached_official_page_text(self):
        source = {"id": "00000000-0000-0000-0000-000000000004",
                  "title": "大豆质量资料", "url": "https://www.lswz.gov.cn/example",
                  "snippet": "公开搜索摘要", "searchedAt": "2026-09-24",
                  "sourceType": "SEARCH_SNIPPET"}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(remote_worker, "public_web_search", return_value=[]), \
             patch.object(remote_worker, "fetch_public_page_excerpt",
                          return_value="国家粮食和物资储备局公开网页正文片段") as fetch:
            library = Path(directory) / "knowledge.json"
            marker = Path(directory) / "refresh.json"
            remote_worker.remember_public_knowledge(library, [source])
            self.assertTrue(remote_worker.refresh_public_knowledge_once(library, marker))
            stored = remote_worker.load_state(library)[source["id"]]["source"]
            self.assertEqual(stored["sourceType"], "PUBLIC_PAGE_EXCERPT")
            self.assertEqual(stored["snippet"], "国家粮食和物资储备局公开网页正文片段")
            self.assertEqual(stored["url"], source["url"])
            self.assertTrue(remote_worker.load_state(marker)["usable"])
            fetch.assert_called_once_with(source["url"])

    def test_legacy_refresh_marker_rechecks_usable_sources(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(remote_worker, "public_web_search", return_value=[]) as search:
            library = Path(directory) / "knowledge.json"
            marker = Path(directory) / "refresh.json"
            remote_worker.save_state(marker, {
                "date": remote_worker.time.strftime("%Y-%m-%d", remote_worker.time.gmtime()),
                "successful": True, "attemptedAt": 100000})
            with patch.object(remote_worker.time, "time", return_value=100001):
                self.assertFalse(remote_worker.refresh_public_knowledge_once(library, marker))
            search.assert_called_once()
            self.assertFalse(remote_worker.load_state(marker)["usable"])

    def test_public_search_reads_public_page_and_uses_multiple_engines(self):
        body = json.dumps({"results": [{"title": "大豆蛋白官方资料", "url": "https://example.gov.cn/a",
                                         "content": "大豆粗蛋白资料"}]}).encode()
        with patch.object(remote_worker, "request", return_value=(200, body)) as call, \
             patch.object(remote_worker, "fetch_public_page_excerpt",
                          return_value="公开网页正文片段"):
            sources = remote_worker.public_web_search("大豆蛋白")
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["url"], "https://example.gov.cn/a")
        self.assertEqual(sources[0]["snippet"], "公开网页正文片段")
        self.assertEqual(sources[0]["sourceType"], "PUBLIC_PAGE_EXCERPT")
        self.assertIn("127.0.0.1:63311/search", call.call_args.args[1])
        self.assertIn("brave%2Cbing%2Cmojeek", call.call_args.args[1])

    def test_failed_metasearch_temporarily_uses_fallback_then_retries(self):
        with patch.object(remote_worker, "request", return_value=(503, b"")) as request, \
             patch.object(remote_worker, "public_wikipedia_search", return_value=[]), \
             patch.object(remote_worker.time, "monotonic", side_effect=[100, 100, 101, 161, 161]):
            remote_worker.public_web_search("玉米有什么用途？")
            remote_worker.public_web_search("大豆有什么用途？")
            remote_worker.public_web_search("小麦有什么用途？")
        metasearch_calls = [call for call in request.call_args_list
                            if remote_worker.WEB_SEARCH_URL in call.args[1]]
        self.assertEqual(len(metasearch_calls), 2)

    def test_comparison_question_retrieves_each_crop_article(self):
        response = {"query": {"pages": {
            "1": {"title": "玉米", "extract": "玉米属于禾本科。"},
            "2": {"title": "大豆", "extract": "大豆属于豆科。"}}}}
        with patch.object(remote_worker, "request", return_value=(
                200, json.dumps(response, ensure_ascii=False).encode())) as call:
            sources = remote_worker.public_wikipedia_search("玉米和大豆有什么区别？")
        self.assertEqual([source["title"] for source in sources],
                         ["维基百科 · 玉米", "维基百科 · 大豆"])
        self.assertTrue(all(source["sourceType"] == "PUBLIC_PAGE_EXCERPT" for source in sources))
        self.assertIn("titles=%E7%8E%89%E7%B1%B3%7C%E5%A4%A7%E8%B1%86", call.call_args.args[1])

    def test_public_page_fetch_rejects_internal_and_unapproved_hosts(self):
        for url in ("http://www.gov.cn/page", "https://localhost/private",
                    "https://127.0.0.1/private", "https://gov.cn.evil.test/page",
                    "https://www.gov.cn:8080/page", "https://user@www.gov.cn/page"):
            with self.subTest(url=url):
                self.assertIsNone(remote_worker.public_page_target(url))
        self.assertEqual(remote_worker.public_page_target("https://www.gov.cn/page"),
                         "https://www.gov.cn/page")
        parser = remote_worker.PublicPageText()
        parser.feed('<nav>忽略导航</nav><p>正文第一句。</p><script>忽略脚本</script><p>正文第二句。</p>')
        self.assertEqual("".join(parser.paragraphs), "正文第一句。正文第二句。")

    def test_public_search_uses_dated_news_headline_when_engines_are_limited(self):
        rss = ("<rss><channel><item><title>黑河大豆丰收记 - 新华网</title>"
               "<link>https://news.google.com/rss/articles/example</link>"
               "<pubDate>Thu, 24 Sep 2026 05:44:33 GMT</pubDate>"
               "<source>新华网</source></item></channel></rss>").encode()
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'), (200, rss)]) as call:
            sources = remote_worker.public_web_search("2026年黑河大豆产量")
        self.assertEqual(len(sources), 1)
        self.assertIn("仅检索到新闻标题，未读取文章正文", sources[0]["snippet"])
        self.assertIn("2026-09-24", sources[0]["snippet"])
        self.assertIn("news.google.com/rss/search", call.call_args_list[1].args[1])

    def test_unrelated_wikipedia_pages_do_not_become_evidence(self):
        wiki = {"query": {"pages": {"1": {"title": "中国地理", "extract": "中国地理介绍"}}}}
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'), (200, b'<rss><channel/></rss>'),
                (200, json.dumps(wiki).encode())]):
            self.assertEqual(remote_worker.public_web_search("黑河大豆产量是否公布"), [])

    def assistant_worker(self):
        worker = remote_worker.Worker.__new__(remote_worker.Worker)
        worker.cloud = "https://risk.example/training-node"
        worker.token = "cloud-token"
        worker.node = "mac-node"
        worker.trainer = "http://127.0.0.1:63201/v1/train"
        worker.trainer_token = "trainer-token"
        return worker

    def test_assistant_claim_is_answered_by_the_fixed_local_rag_endpoint(self):
        worker = self.assistant_worker()
        claim = {
            "requestId": "11111111-1111-1111-1111-111111111111",
            "question": "玉米水分标准是什么？", "attempt": 1,
            "leaseUntil": "2026-09-22T15:00:00Z",
        }
        answer = {
            "status": "ANSWERED", "mode": "FOUNDATION_RAG",
            "modelReference": "/private/cache/models/local-model", "knowledgeVersion": "v1",
            "answer": "回答", "citations": [{"id": "source-1", "title": "来源",
              "url": "https://example.test/source", "use": "RETRIEVAL_ONLY",
              "summary": "must not leave the node"}], "limitations": ["限制"],
        }
        responses = [
            (200, json.dumps(claim).encode()),
            (200, json.dumps(answer).encode()),
            (204, b""),
        ]
        with patch.object(remote_worker, "request", side_effect=responses) as call, \
             patch.object(remote_worker, "public_web_search", return_value=[]):
            self.assertTrue(worker.assistant_once())

        self.assertEqual(call.call_args_list[0].args[1], worker.cloud + "/assistant-claims")
        self.assertEqual(call.call_args_list[1].args[1],
                         "http://127.0.0.1:63201/v1/expert-answer")
        self.assertEqual(json.loads(call.call_args_list[1].args[3]),
                         {"question": "玉米水分标准是什么？", "webSources": [],
                          "webSearchFailed": True})
        self.assertEqual(call.call_args_list[2].args[1], worker.cloud +
                         "/assistant-requests/11111111-1111-1111-1111-111111111111/completion")
        uploaded = json.loads(call.call_args_list[2].args[3])
        self.assertEqual(uploaded["modelReference"],
                         "qiliang-foundation-rag-qwen3.8-27b")
        self.assertEqual(uploaded["citations"], [{"id": "source-1", "title": "来源",
                                                  "url": "https://example.test/source",
                                                  "use": "RETRIEVAL_ONLY"}])

    def test_assistant_completion_retries_lost_response_without_regenerating(self):
        worker = self.assistant_worker()
        claim = {"requestId": "11111111-1111-1111-1111-111111111111",
                 "question": "问候", "attempt": 1, "leaseUntil": "later"}
        answer = {"status": "ANSWERED", "mode": "FOUNDATION_GENERAL",
                  "modelReference": "/private/model", "knowledgeVersion": "v1",
                  "answer": "你好。", "citations": [], "limitations": ["通识回答"]}
        responses = [(200, json.dumps(claim).encode()),
                     (200, json.dumps(answer).encode()),
                     urllib.error.URLError("lost completion response"),
                     (502, b""), (204, b"")]
        with patch.object(remote_worker, "request", side_effect=responses) as call, \
             patch.object(remote_worker, "public_web_search", return_value=[]), \
             patch.object(remote_worker.time, "sleep") as sleep:
            self.assertTrue(worker.assistant_once())
        self.assertEqual(call.call_count, 5)
        completion = call.call_args_list[2:]
        self.assertTrue(all(item.args[1].endswith("/completion") for item in completion))
        self.assertEqual(len({item.args[3] for item in completion}), 1)
        self.assertEqual(sleep.call_count, 2)

    def test_assistant_local_unavailability_is_recorded_without_leaking_details(self):
        worker = self.assistant_worker()
        claim = {"requestId": "11111111-1111-1111-1111-111111111111",
                 "question": "问题", "attempt": 1, "leaseUntil": "later"}
        responses = [(200, json.dumps(claim).encode()),
                     (503, b'{"error":"private path details"}'), (204, b"")]
        with patch.object(remote_worker, "request", side_effect=responses) as call, \
             patch.object(remote_worker, "public_web_search", return_value=[]):
            self.assertTrue(worker.assistant_once())
        failure = json.loads(call.call_args_list[2].args[3])
        self.assertEqual(failure, {"code": "LOCAL_ASSISTANT_UNAVAILABLE"})

    def test_assistant_general_answer_keeps_mode_without_citations(self):
        worker = self.assistant_worker()
        claim = {"requestId": "11111111-1111-1111-1111-111111111111",
                 "question": "你好", "attempt": 1, "leaseUntil": "later"}
        answer = {"status": "ANSWERED", "mode": "FOUNDATION_GENERAL",
                  "modelReference": "/private/model", "knowledgeVersion": "v1",
                  "answer": "你好。", "citations": [], "limitations": ["通识回答"]}
        responses = [(200, json.dumps(claim).encode()),
                     (200, json.dumps(answer).encode()), (204, b"")]
        with patch.object(remote_worker, "request", side_effect=responses) as call, \
             patch.object(remote_worker, "public_web_search", return_value=[]):
            self.assertTrue(worker.assistant_once())
        uploaded = json.loads(call.call_args_list[2].args[3])
        self.assertEqual(uploaded["mode"], "FOUNDATION_GENERAL")
        self.assertEqual(uploaded["citations"], [])

    def test_assistant_malformed_citation_is_failed_closed(self):
        worker = self.assistant_worker()
        claim = {"requestId": "11111111-1111-1111-1111-111111111111",
                 "question": "问题", "attempt": 1, "leaseUntil": "later"}
        answer = {"status": "ANSWERED", "mode": "FOUNDATION_RAG",
                  "modelReference": "/private/model", "knowledgeVersion": "v1",
                  "answer": "回答", "citations": [{"id": "s1", "title": "来源",
                    "url": "javascript:alert(1)", "use": "RETRIEVAL_ONLY"}],
                  "limitations": ["限制"]}
        responses = [(200, json.dumps(claim).encode()),
                     (200, json.dumps(answer).encode()), (204, b"")]
        with patch.object(remote_worker, "request", side_effect=responses) as call, \
             patch.object(remote_worker, "public_web_search", return_value=[]):
            self.assertTrue(worker.assistant_once())
        failure = json.loads(call.call_args_list[2].args[3])
        self.assertEqual(failure, {"code": "LOCAL_ASSISTANT_INVALID_RESPONSE"})

    def expert_worker(self, root):
        worker = remote_worker.Worker.__new__(remote_worker.Worker)
        worker.cloud = "https://risk.example/training-node"
        worker.token = "cloud-token"
        worker.node = "mac-node"
        worker.trainer = "http://127.0.0.1:63201/v1/train"
        worker.expert_trainer = "http://127.0.0.1:63201/v1/expert-train"
        worker.expert_cancel = "http://127.0.0.1:63201/v1/expert-train-cancel"
        worker.trainer_token = "trainer-token"
        worker.expert_state = root / "state/active-expert-claim.json"
        worker.artifact_root = root / "artifacts"
        worker.heartbeat_seconds = .01
        return worker

    def expert_claim(self):
        return {
            "taskId": "11111111-1111-1111-1111-111111111111",
            "runId": "expert-run-1",
            "snapshotId": "22222222-2222-2222-2222-222222222222",
            "datasetId": "authoritative-dataset",
            "datasetVersion": 3,
            "datasetSha256": "a" * 64,
            "dataset": {"schemaVersion": 1, "records": ["private"]},
            "config": {"iterations": 1, "learningRate": 1e-5,
                       "maxSeqLength": 256, "numLayers": 1, "seed": 0},
            "modelReference": "fixed-cloud-model",
            "leaseUntil": "2026-09-22T05:00:00Z",
            "attempt": 1,
            "promptPath": "/must/not/be/used",
        }

    def candidate(self, worker):
        artifact = worker.artifact_root / "expert-sft/expert-run-1"
        artifact.mkdir(parents=True, mode=0o700)
        dataset = artifact / "dataset"
        dataset.mkdir(mode=0o700)
        for name in ("records.json", "manifest.json", "train.jsonl", "valid.jsonl", "test.jsonl"):
            (dataset / name).write_text("{}\n", encoding="utf-8")
        for name, content in (("adapters.safetensors", b"weights"),
                              ("adapter_config.json", b"{}"),
                              ("training_metrics.json", b"{}"),
                              ("model_manifest.json", b"{}")):
            (artifact / name).write_bytes(content)
        for path in (artifact, dataset):
            path.chmod(0o700)
        for path in artifact.rglob("*"):
            if path.is_file():
                path.chmod(0o600)
        content_hash = remote_worker.expert_artifact_hash(artifact)
        return artifact, {
            "runId": "expert-run-1", "kind": "EXPERT_SFT_ADAPTER",
            "status": "CANDIDATE", "publicationStatus": "NOT_EVALUATED",
            "artifactSha256": content_hash, "artifactPath": str(artifact),
            "metrics": {"iterations": 1, "testCount": 1},
        }

    def test_maps_cloud_job_to_local_trainer_contract(self):
        job = {
            "modelId": "11111111-1111-1111-1111-111111111111",
            "modelCode": "qiliang-risk-llm-v1",
            "baseModelReference": "mlx-community/Qwen3.8-27B-4bit",
            "domainCode": "CROSS_DOMAIN",
            "modelVersion": 3,
            "trainingSnapshotId": "22222222-2222-2222-2222-222222222222",
            "randomSeed": 7,
            "examples": [{
                "resolvedAt": "2026-09-21T01:00:00Z",
                "input": "真实证据",
                "positive": True,
            }],
        }

        payload = remote_worker.trainer_payload(job)

        self.assertEqual(payload["candidateVersion"], 3)
        self.assertEqual(payload["trainingKind"], "LORA_ADAPTER")
        self.assertEqual(payload["examples"][0]["input"], "真实证据")

    def test_bundles_only_the_declared_adapter_directory_deterministically(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "adapter"
            artifact.mkdir()
            (artifact / "adapter_config.json").write_text("{}", encoding="utf-8")
            (artifact / "adapters.safetensors").write_bytes(b"weights")

            first = remote_worker.bundle_artifact(artifact)
            second = remote_worker.bundle_artifact(artifact)

            self.assertEqual(first, second)
            self.assertEqual(hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest())

    def test_state_file_is_private_and_round_trips_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state.json"
            expected = {"executionId": "execution-1", "trainingRunId": "run-1"}

            remote_worker.save_state(state, expected)

            self.assertEqual(remote_worker.load_state(state), expected)
            self.assertEqual(state.stat().st_mode & 0o777, 0o600)

    def test_expired_claim_is_discarded_so_later_daily_work_can_continue(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.state = Path(temporary) / "active-claim.json"
            remote_worker.save_state(worker.state, {
                "executionId": "execution-1", "trainingRunId": "run-1"})
            worker.process = Mock(side_effect=remote_worker.ClaimExpired("expired"))

            with self.assertRaises(remote_worker.ClaimExpired):
                worker.run_once()

            self.assertFalse(worker.state.exists())

    def test_upload_conflict_marks_the_claim_expired(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "adapter"
            artifact.mkdir()
            (artifact / "adapters.safetensors").write_bytes(b"weights")
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.cloud = "https://risk.example/training-node"
            worker.token = "cloud-token"
            worker.node = "mac-node"
            worker.trainer = "http://127.0.0.1:63201/v1/train"
            worker.trainer_token = "trainer-token"
            worker.state = Path(temporary) / "active-claim.json"
            worker.artifact_index = Path(temporary) / "artifact-index.json"
            worker.artifact_root = Path(temporary)
            worker.heartbeat_seconds = 300
            job = {
                "executionId": "11111111-1111-1111-1111-111111111111",
                "trainingRunId": "22222222-2222-2222-2222-222222222222",
                "modelId": "33333333-3333-3333-3333-333333333333",
                "modelCode": "qiliang-risk-llm-v1",
                "baseModelReference": "mlx-community/Qwen3.8-27B-4bit",
                "domainCode": "CROSS_DOMAIN",
                "modelVersion": 3,
                "trainingSnapshotId": "44444444-4444-4444-4444-444444444444",
                "randomSeed": 7,
                "examples": [],
            }
            trained = json.dumps({
                "artifactReference": str(artifact),
                "artifactSha256": "a" * 64,
            }).encode()
            with patch.object(remote_worker, "request", side_effect=[(200, trained), (409, b"")]):
                with self.assertRaises(remote_worker.ClaimExpired):
                    worker.process(job)

    def test_remote_shadow_scoring_uses_the_exact_local_adapter(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "models" / "model-1" / "v3-snapshot"
            artifact.mkdir(parents=True)
            (artifact / "adapter_config.json").write_text("{}", encoding="utf-8")
            (artifact / "adapters.safetensors").write_bytes(b"weights")
            content_hash = remote_worker.canonical_hash(artifact)
            bundle_hash = hashlib.sha256(remote_worker.bundle_artifact(artifact)).hexdigest()
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.cloud = "https://risk.example/training-node"
            worker.token = "cloud-token"
            worker.node = "mac-node"
            worker.trainer = "http://127.0.0.1:63201/v1/train"
            worker.trainer_token = "trainer-token"
            worker.artifact_root = root / "models"
            worker.artifact_index = root / "state/artifact-index.json"
            task = {
                "modelId": "model-1", "modelVersion": 3,
                "baseModelReference": "base", "assessmentId": "assessment-1",
                "artifactReference": "risk-artifact://sha256/" + bundle_hash,
                "artifactSha256": bundle_hash, "input": "真实证据",
            }
            responses = [
                (200, json.dumps(task).encode()),
                (200, b'{"predictedPositive":true,"positiveProbability":0.81}'),
                (204, b""),
            ]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                self.assertTrue(worker.score_once())

            sent = json.loads(call.call_args_list[2].args[3])
            self.assertEqual(sent["artifactSha256"], bundle_hash)
            index = remote_worker.load_state(worker.artifact_index)
            self.assertEqual(index[task["artifactReference"]]["contentSha256"], content_hash)

    def test_expert_claim_maps_only_fixed_local_contract_and_is_saved_privately(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            claim = self.expert_claim()
            with patch.object(remote_worker, "request", return_value=(200, json.dumps(claim).encode())):
                saved = worker.expert_claim()
            self.assertEqual(set(saved), {"taskId", "runId", "dataset", "config", "progress"})
            self.assertEqual({key: saved[key] for key in ("runId", "dataset", "config")},
                             {key: claim[key] for key in ("runId", "dataset", "config")})
            self.assertNotIn("promptPath", json.dumps(saved))
            remote_worker.save_state(worker.expert_state, saved)
            self.assertEqual(worker.expert_state.stat().st_mode & 0o777, 0o600)

    def test_saved_expert_state_resumes_before_requesting_new_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            saved = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, saved)
            with patch.object(worker, "process_expert") as process, patch.object(
                    worker, "expert_claim", side_effect=AssertionError("must resume")):
                self.assertTrue(worker.expert_once())
            process.assert_called_once_with(saved | {"progress": 0})

    def test_preflight_cancellation_acknowledges_without_calling_local_trainer(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":true,"leaseUntil":"later"}'), (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            self.assertEqual(call.call_args_list[0].args[1], worker.cloud +
                             "/expert-tasks/11111111-1111-1111-1111-111111111111/heartbeat")
            self.assertEqual(call.call_args_list[1].args[1], worker.cloud +
                             "/expert-tasks/11111111-1111-1111-1111-111111111111/cancelled")
            self.assertFalse(worker.expert_state.exists())

    def test_inflight_cancellation_calls_exact_local_cancel_and_never_uploads(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            local_started = threading.Event()
            calls = []
            def transport(method, url, headers, body, timeout):
                calls.append((url, body))
                if url.endswith("/heartbeat"):
                    count = sum(item[0].endswith("/heartbeat") for item in calls)
                    return (200, json.dumps({"cancelRequested": count > 1, "leaseUntil": "later"}).encode())
                if url == worker.expert_trainer:
                    local_started.set()
                    time.sleep(.05)
                    return 409, b'{"error":"EXPERT_TRAINING_CANCELLED"}'
                if url == worker.expert_cancel:
                    return 200, b'{"status":"CANCEL_REQUESTED"}'
                if url.endswith("/cancelled"):
                    return 204, b""
                if url.endswith("/progress"):
                    return 204, b""
                raise AssertionError(url)
            with patch.object(remote_worker, "request", side_effect=transport):
                worker.process_expert(state)
            cancel = next(body for url, body in calls if url == worker.expert_cancel)
            self.assertEqual(json.loads(cancel), {"runId": "expert-run-1"})
            self.assertFalse(any(url.endswith("/artifacts") or url.endswith("/completion") for url, _ in calls))
            self.assertFalse(worker.expert_state.exists())

    def test_lease_loss_discards_saved_state_without_upload_or_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            with patch.object(remote_worker, "request", return_value=(409, b"")):
                with self.assertRaises(remote_worker.ClaimExpired):
                    worker.process_expert(state)
            self.assertFalse(worker.expert_state.exists())

    def test_transient_cloud_failure_retains_expert_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            with patch.object(remote_worker, "request", side_effect=OSError("offline-secret")):
                with self.assertRaises(OSError):
                    worker.process_expert(state)
            self.assertEqual(remote_worker.load_state(worker.expert_state), state)

    def test_local_workload_busy_is_retryable_and_does_not_mark_cloud_failed(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""),
                         (503, b'{"error":"WORKLOAD_BUSY"}'),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}')]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                with self.assertRaisesRegex(RuntimeError, "本地专家训练服务繁忙"):
                    worker.process_expert(state)
            self.assertFalse(any(item.args[1].endswith("/failure") for item in call.call_args_list))
            self.assertTrue(worker.expert_state.exists())

    def test_permanent_local_failure_is_redacted_and_deletes_only_after_ack(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""),
                         (503, b'{"error":"EXPERT_TRAINING_UNAVAILABLE",'
                               b'"message":"raw stderr credential secret"}'),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            failure = json.loads(call.call_args_list[-1].args[3])
            self.assertEqual(failure, {"code": "LOCAL_TRAINING_FAILED",
                                       "message": "本地专家训练失败，未生成候选工件。"})
            self.assertFalse(worker.expert_state.exists())

    def test_malformed_local_success_is_acknowledged_as_redacted_permanent_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""), (200, b'raw malformed success'),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            self.assertEqual(json.loads(call.call_args_list[-1].args[3]), {
                "code": "LOCAL_TRAINING_FAILED", "message": "本地专家训练失败，未生成候选工件。"})
            self.assertFalse(worker.expert_state.exists())

    def test_strict_artifact_validation_rejects_outside_root_and_unbounded_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            worker = self.expert_worker(root)
            artifact, candidate = self.candidate(worker)
            worker.validate_expert_result(candidate)
            outside = root / "outside"
            artifact.rename(outside)
            candidate["artifactPath"] = str(outside)
            with self.assertRaises(ValueError):
                worker.validate_expert_result(candidate)
            candidate["artifactPath"] = str(artifact)
            outside.rename(artifact)
            candidate["metrics"] = {"value": "x" * 5000}
            with self.assertRaises(ValueError):
                worker.validate_expert_result(candidate)

    def test_deterministic_upload_and_exact_completion_then_deletes_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            artifact, candidate = self.candidate(worker)
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            expected_bundle = remote_worker.bundle_artifact(artifact)
            expected_bundle_hash = hashlib.sha256(expected_bundle).hexdigest()
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""), (200, json.dumps(candidate).encode()),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""),
                         (201, json.dumps({"artifactReference": "expert://stored/1",
                                          "bundleSha256": expected_bundle_hash,
                                          "contentSha256": candidate["artifactSha256"],
                                          "sizeBytes": len(expected_bundle)}).encode()),
                         (204, b""), (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            local = next(item for item in call.call_args_list if item.args[1] == worker.expert_trainer)
            self.assertEqual(json.loads(local.args[3]), {
                "runId": state["runId"], "dataset": state["dataset"], "config": state["config"]})
            upload = next(item for item in call.call_args_list if item.args[1].endswith("/artifacts"))
            self.assertEqual(upload.args[3], expected_bundle)
            completion = next(item for item in call.call_args_list if item.args[1].endswith("/completion"))
            self.assertEqual(json.loads(completion.args[3]), {
                "artifactReference": "expert://stored/1", "artifactSha256": expected_bundle_hash,
                "metrics": candidate["metrics"]})
            self.assertFalse(worker.expert_state.exists())

    def test_completion_response_loss_replays_only_exact_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            artifact, candidate = self.candidate(worker)
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            bundle = remote_worker.bundle_artifact(artifact)
            bundle_hash = hashlib.sha256(bundle).hexdigest()
            stored = {"artifactReference": "expert://stored/replay",
                      "bundleSha256": bundle_hash,
                      "contentSha256": candidate["artifactSha256"], "sizeBytes": len(bundle)}
            first = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                     (204, b""), (204, b""), (200, json.dumps(candidate).encode()),
                     (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                     (204, b""), (204, b""), (201, json.dumps(stored).encode()),
                     (204, b"")]
            with patch.object(remote_worker, "request", side_effect=first + [OSError("lost response")]):
                with self.assertRaises(OSError):
                    worker.process_expert(state)
            retained = remote_worker.load_state(worker.expert_state)
            shutil.rmtree(artifact)
            replay_calls = []
            def replay(method, url, headers, body, timeout):
                replay_calls.append((url, body))
                if url.endswith("/heartbeat"):
                    return 200, b'{"cancelRequested":false,"leaseUntil":"later"}'
                if url.endswith("/completion"):
                    return 204, b""
                raise AssertionError("completion replay regressed or repeated work: " + url)
            with patch.object(remote_worker, "request", side_effect=replay):
                worker.process_expert(retained)
            self.assertEqual(json.loads(replay_calls[-1][1]), {
                "artifactReference": stored["artifactReference"],
                "artifactSha256": stored["bundleSha256"], "metrics": candidate["metrics"]})
            self.assertFalse(worker.expert_state.exists())

    def test_trainer_url_must_be_loopback_and_expert_urls_are_derived(self):
        base = {"RISK_TRAINING_CLOUD_URL": "https://risk.example/training-node",
                "RISK_TRAINING_NODE_TOKEN": "cloud", "RISK_TRAINING_NODE_ID": "node",
                "RISK_LLM_BEARER_TOKEN": "local"}
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, base | {"RISK_TRAINING_NODE_STATE_ROOT": temporary,
                                    "RISK_LLM_ARTIFACT_ROOT": temporary,
                                    "RISK_LLM_TRAINER_URL": "http://127.0.0.1:63201/v1/train"}, clear=True):
            worker = remote_worker.Worker()
            self.assertEqual(worker.expert_trainer, "http://127.0.0.1:63201/v1/expert-train")
            self.assertEqual(worker.expert_cancel, "http://127.0.0.1:63201/v1/expert-train-cancel")
        for value in ("https://example.com/v1/train", "http://127.0.0.1:63201/wrong",
                      "http://user@127.0.0.1:63201/v1/train?prompt=bad"):
            with self.subTest(value=value), patch.dict(os.environ, base | {
                    "RISK_LLM_TRAINER_URL": value}, clear=True):
                with self.assertRaises(RuntimeError):
                    remote_worker.Worker()


if __name__ == "__main__":
    unittest.main()
