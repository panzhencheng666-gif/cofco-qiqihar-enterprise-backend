import json
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

import remote_worker
import expert


class AssistantRoutingTest(unittest.TestCase):
    def setUp(self):
        remote_worker._web_search_retry_at = 0

    def test_official_docs_choose_requested_primary_source_over_blog(self):
        results = [
            {"title": "Python 列表博客", "url": "https://blog.example/post", "content": "第三方解释"},
            {"title": "冒充官网", "url": "https://docs.python.org.example/post", "content": "伪域名"},
            {"title": "Data structures", "url": "https://docs.python.org/3/tutorial/datastructures.html",
             "content": "Tuples are immutable, lists are mutable."},
        ]
        with patch.object(remote_worker, "request", return_value=(200, json.dumps({"results": results}).encode())) as req, \
             patch.object(remote_worker, "fetch_public_page_excerpt", return_value=None):
            sources = remote_worker.public_web_search("Python 官方文档中列表和元组有什么区别？")
        self.assertEqual([s["url"] for s in sources], [results[2]["url"]])
        self.assertIn("site:docs.python.org", parse_qs(urlsplit(req.call_args.args[1]).query)["q"][0])

    def test_official_page_excerpt_selects_relevant_later_paragraph(self):
        page = ("<html><body><p>" + "List methods and examples. " * 60 +
                "</p><p>Tuples are immutable, while lists are mutable.</p></body></html>")

        class Response:
            status = 200
            headers = Message()

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return page.encode()

        Response.headers["Content-Type"] = "text/html; charset=utf-8"
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = Response()
            excerpt = remote_worker.fetch_public_page_excerpt(
                "https://docs.python.org/3/tutorial/datastructures.html",
                "Tuples are immutable, lists are mutable.")
        self.assertIn("Tuples are immutable", excerpt)
        self.assertIn("lists are mutable", excerpt)

    def test_official_chinese_page_excerpt_keeps_comparison_evidence(self):
        page = ("<html><body><p>" + "列表方法及用法。" * 180 +
                "</p><p>元组是 immutable （不可变的），列表是 mutable （可变的）。</p>" +
                "<p>字典中的元组和列表可以直接比较。</p></body></html>")

        class Response:
            status = 200
            headers = Message()

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return page.encode()

        Response.headers["Content-Type"] = "text/html; charset=utf-8"
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = Response()
            excerpt = remote_worker.fetch_public_page_excerpt(
                "https://docs.python.org/zh-cn/3/tutorial/datastructures.html",
                "列表和元组在可变性上有什么区别？请给出直接支持的原文链接。")
        self.assertIn("元组是 immutable （不可变的）", excerpt)
        self.assertIn("列表是 mutable （可变的）", excerpt)
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = Response()
            mixed_excerpt = remote_worker.fetch_public_page_excerpt(
                "https://docs.python.org/zh-cn/3/tutorial/datastructures.html",
                "Python 中文文档具体怎样表述 tuple 与 list 的可变性？")
        self.assertIn("元组是 immutable （不可变的）", mixed_excerpt)
        self.assertIn("列表是 mutable （可变的）", mixed_excerpt)

    def test_explicit_site_does_not_fall_back_to_unrequested_sources(self):
        with patch.object(remote_worker, "request", return_value=(200, b'{"results":[]}')) as req, \
             patch.object(remote_worker, "public_wikipedia_search") as wiki:
            self.assertEqual(remote_worker.public_web_search("site:postgresql.org 事务隔离"), [])
        self.assertEqual(req.call_count, 2)
        self.assertLessEqual(sum(call.args[4] for call in req.call_args_list), 10)
        wiki.assert_not_called()

    def test_official_request_rejects_old_unscoped_cache(self):
        sources = [{"title": "Python 博客", "url": "https://blog.example/post", "snippet": "摘要"}]
        self.assertEqual(remote_worker.filter_search_relevance("Python官方文档", sources), [])

    def test_search_falls_back_to_bing_rss_when_meta_search_is_limited(self):
        rss = b'<rss><channel><item><title>Python documentation</title><link>https://docs.python.org/3/</link><description>Official Python documentation</description></item></channel></rss>'
        with patch.object(remote_worker, "request", side_effect=[(503, b"unavailable"), (200, rss)]) as req, \
             patch.object(remote_worker, "fetch_public_page_excerpt", return_value=None):
            sources = remote_worker.public_web_search("Python 官方文档")
        self.assertEqual([s["url"] for s in sources], ["https://docs.python.org/3/"])
        self.assertIn("www.bing.com/search?", req.call_args.args[1])
        self.assertEqual(sources[0]["sourceType"], "SEARCH_SNIPPET")

    def test_uncited_general_answer_cannot_claim_official_attribution(self):
        with patch.object(expert, "configured_model", return_value=Path("/tmp/model")), \
             patch.object(expert, "configured_timeout", return_value=10), \
             patch.object(expert, "retrieve", return_value=[]):
            answer = expert.answer_question({"question": "Python官方文档如何解释列表？"},
                generator=lambda *_: json.dumps({"answer": "Python 官方文档指出，列表是可变序列。"}, ensure_ascii=False))
        self.assertNotIn("官方文档指出", answer["answer"])
        self.assertIn("未核验", answer["answer"])
        self.assertIn("列表是可变序列", answer["answer"])
        self.assertEqual(answer["citations"], [])

    def test_unrelated_bing_results_do_not_block_encyclopedia_fallback(self):
        rss = b'<rss><channel><item><title>Macys credit card</title><link>https://forum.example/card</link><description>Credit card forum discussion</description></item></channel></rss>'
        source = {"id": "wiki", "title": "光合作用", "url": "https://zh.wikipedia.org/wiki/Photosynthesis", "snippet": "光合作用利用光能。"}
        with patch.object(remote_worker, "request", side_effect=[(503, b"unavailable"), (200, rss)]), \
             patch.object(remote_worker, "public_wikipedia_search", return_value=[source]) as wiki:
            results = remote_worker.public_web_search("请联网查询：光合作用是什么？请简要说明并给出来源。")
        self.assertEqual(results, [source])
        wiki.assert_called_once()

    def run_claim(self, question):
        worker = remote_worker.Worker.__new__(remote_worker.Worker)
        worker.cloud = "https://risk.example/training-node"
        worker.token, worker.node = "cloud-token", "node"
        worker.trainer, worker.trainer_token = "http://127.0.0.1:63202/v1/train", "local-token"
        worker.search_memory = Path("not-read.json")
        worker.public_knowledge = Path("not-read-library.json")
        claim = {"requestId": "11111111-1111-1111-1111-111111111111", "question": question, "attempt": 1}
        answer = {"status": "ANSWERED", "mode": "FOUNDATION_GENERAL", "modelReference": "/local/model",
                  "knowledgeVersion": "v1", "answer": "计算结果", "citations": [], "limitations": []}
        with patch.object(remote_worker, "request", side_effect=[(200, json.dumps(claim).encode()),
                (200, json.dumps(answer).encode()), (204, b"")]) as req, \
             patch.object(remote_worker, "public_web_search", return_value=[]) as search, \
             patch.object(remote_worker, "recalled_search", return_value=[]) as recall, \
             patch.object(remote_worker, "recalled_public_knowledge", return_value=[]) as library:
            self.assertTrue(worker.assistant_once())
        return json.loads(req.call_args_list[1].args[3]), search.call_count, recall.call_count + library.call_count

    def test_supplied_cost_calculation_uses_model_without_search_or_cache(self):
        payload, searched, recalled = self.run_claim("请计算：甲固定成本1200元每件8元，乙固定成本300元每件11元，采购250件哪个便宜？")
        self.assertEqual((searched, recalled), (0, 0))
        self.assertEqual(set(payload), {"question"})

    def test_explicit_no_search_is_respected(self):
        payload, searched, recalled = self.run_claim("不要联网，仅根据以下数据推理：A大于B，B大于C，谁最大？")
        self.assertEqual((searched, recalled), (0, 0))
        self.assertNotIn("webSearchFailed", payload)

    def test_current_and_explicit_search_requests_still_search(self):
        for q in ("计算今天玉米320吨的收购成本，目前单价多少？",
                  "[联网搜索] 计算1200元加300元的含义",
                  "请搜索Python官方文档"):
            with self.subTest(question=q):
                payload, searched, _ = self.run_claim(q)
                self.assertEqual(searched, 1)
                self.assertIn("webSources", payload)


if __name__ == "__main__":
    unittest.main()
