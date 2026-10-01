import json
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

import expert
import remote_worker


class PublicWebSearchTest(unittest.TestCase):
    def setUp(self):
        remote_worker._web_search_retry_at = 0
        fallback = patch.object(remote_worker, "bing_search_results", return_value=[])
        fallback.start()
        self.addCleanup(fallback.stop)

    def test_public_search_bounds_total_network_wait(self):
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'),
                (200, b'<rss><channel/></rss>'),
                (200, b'{"query":{"pages":{}}}')]) as request:
            self.assertEqual(remote_worker.public_web_search("黑河大豆产量"), [])
        self.assertEqual(request.call_count, 3)
        self.assertLessEqual(sum(call.args[4] for call in request.call_args_list), 14)

    def test_search_returns_only_public_https_snippets(self):
        response = {"results": [
            {"title": "官方标准", "url": "https://openstd.samr.gov.cn/standard/1",
             "content": "公开标准摘要"},
            {"title": "本机页面", "url": "https://localhost/private", "content": "不应出现"},
            {"title": "第二来源", "url": "https://www.gov.cn/example",
             "content": "公开信息摘要"},
        ]}
        with patch.object(remote_worker, "request", return_value=(200, json.dumps(response).encode())), \
             patch.object(remote_worker, "fetch_public_page_excerpt", return_value=None):
            sources = remote_worker.public_web_search("粮食储藏")
        self.assertEqual(len(sources), 2)
        self.assertEqual(sources[0]["title"], "官方标准")
        self.assertEqual(sources[1]["title"], "第二来源")
        self.assertRegex(sources[0]["searchedAt"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    def test_conflicting_numeric_claims_are_detected_across_sources(self):
        sources = [
            {"id": "source-a", "title": "甲来源", "snippet": "商品玉米水分含量不高于14.0%"},
            {"id": "source-b", "title": "乙来源", "snippet": "商品玉米水分含量不高于15.0%"},
        ]
        self.assertTrue(remote_worker.detect_search_conflict("玉米水分标准是多少？", sources))
        self.assertFalse(remote_worker.detect_search_conflict(
            "玉米水分标准是多少？", [
                {"id": "source-a", "title": "甲来源", "snippet": "商品玉米水分含量不高于14.0%"},
                {"id": "source-b", "title": "乙来源", "snippet": "商品玉米水分含量不高于14.0%"},
            ]))

    def test_soy_protein_question_searches_measurable_quality_terms(self):
        response = {"results": [{"title": "大豆质量报告",
                                  "url": "https://www.lswz.gov.cn/soy",
                                  "content": "粗蛋白质量报告"}]}
        with patch.object(remote_worker, "request", return_value=(200, json.dumps(response).encode())) as request, \
             patch.object(remote_worker, "fetch_public_page_excerpt", return_value=None):
            remote_worker.public_web_search("大豆蛋白一般达到多少算优质蛋白？")
        self.assertIn("%E7%B2%97%E8%9B%8B%E7%99%BD", request.call_args.args[1])
        self.assertNotIn("%E4%B8%80%E8%88%AC", request.call_args.args[1])

    def test_local_yield_forecast_searches_target_and_skips_unrelated_hits(self):
        response = {"results": [
            {"title": "全国大豆供需报告", "url": "https://example.gov.cn/national",
             "content": "全国大豆产量数据"},
            {"title": "黑河大豆面积报道", "url": "https://example.gov.cn/heihe",
             "content": "黑河大豆播种面积情况"},
        ]}
        question = "请预测2026年黑河地区大豆产量的变化趋势"
        with patch.object(remote_worker, "request", return_value=(
                200, json.dumps(response, ensure_ascii=False).encode())) as request, \
             patch.object(remote_worker, "fetch_public_page_excerpt", return_value=None):
            sources = remote_worker.public_web_search(question)
        self.assertEqual([source["title"] for source in sources], ["黑河大豆面积报道"])
        terms = parse_qs(urlsplit(request.call_args.args[1]).query)["q"][0]
        self.assertIn("2026 黑河 大豆 产量 播种面积 单产", terms)

    def test_empty_meta_search_falls_back_to_public_encyclopedia(self):
        search = {"query": {"pages": {"6368610": {
            "title": "大豆蛋白", "extract": "大豆中约含有36.5%蛋白质。"}}}}
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'),
                (200, json.dumps(search, ensure_ascii=False).encode())]) as request:
            sources = remote_worker.public_web_search("大豆蛋白")
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["url"], "https://zh.wikipedia.org/wiki/%E5%A4%A7%E8%B1%86%E8%9B%8B%E7%99%BD")
        self.assertIn("36.5%", sources[0]["snippet"])
        self.assertIn("zh.wikipedia.org", request.call_args_list[1].args[1])

    def test_soy_search_rejects_unrelated_tofu_news(self):
        rss = ("<rss><channel><item><title>吃豆腐对肾不好？动物蛋白优质论骗了多少年</title>"
               "<link>https://news.google.com/rss/articles/tofu</link>"
               "<pubDate>Mon, 14 Sep 2026 05:44:33 GMT</pubDate>"
               "<source>媒体</source></item></channel></rss>").encode()
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'), (200, b'{"query":{"pages":{}}}'),
                (200, rss)]) as request:
            self.assertEqual(remote_worker.public_web_search("大豆的蛋白一般多少为优秀蛋白？"), [])
        self.assertEqual(request.call_count, 3)

    def test_grain_disease_search_keeps_the_exact_topic(self):
        rss = ("<rss><channel>"
               "<item><title>小麦价格上涨，供应风险仍在</title>"
               "<link>https://news.google.com/rss/articles/price</link>"
               "<pubDate>Mon, 14 Sep 2026 05:44:33 GMT</pubDate><source>媒体</source></item>"
               "<item><title>小麦赤霉病防控技术培训</title>"
               "<link>https://news.google.com/rss/articles/disease</link>"
               "<pubDate>Mon, 14 Sep 2026 05:44:33 GMT</pubDate><source>农技机构</source></item>"
               "</channel></rss>").encode()
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'), (200, b'{"query":{"pages":{}}}'),
                (200, rss)]) as request:
            sources = remote_worker.public_web_search("小麦赤霉病有什么风险？")
        self.assertEqual([source["title"] for source in sources], ["小麦赤霉病防控技术培训"])
        self.assertEqual(sources[0]["publishedAt"], "2026-09-14T05:44:33Z")
        news_terms = parse_qs(urlsplit(request.call_args_list[2].args[1]).query)["q"][0]
        self.assertIn("赤霉病", news_terms)

    def test_current_corn_price_does_not_cite_local_tourism_headlines(self):
        rss = ("<rss><channel><item>"
               "<title>齐齐哈尔周边采摘农家乐去哪？齐齐哈尔四个去处</title>"
               "<link>https://news.google.com/rss/articles/tourism</link>"
               "<pubDate>Fri, 25 Sep 2026 05:44:33 GMT</pubDate>"
               "<source>媒体</source></item></channel></rss>").encode()
        with patch.object(remote_worker, "request", side_effect=[
                (200, b'{"results":[]}'), (200, rss)]), \
             patch.object(remote_worker, "public_wikipedia_search", return_value=[]):
            sources = remote_worker.public_web_search(
                "截至2026年9月25日，齐齐哈尔地区玉米现货收购价是多少？")
        self.assertEqual(sources, [])

    def test_search_memory_keeps_attributed_sources_for_later_questions(self):
        source = {"id": "source", "title": "大豆蛋白", "url": "https://zh.wikipedia.org/wiki/x",
                  "snippet": "大豆蛋白摘要", "searchedAt": "2026-09-24"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "search-memory.json"
            remote_worker.remember_search(path, "大豆蛋白", [source])
            self.assertEqual(remote_worker.recalled_search(path, "大豆蛋白"), [source])
            self.assertEqual(remote_worker.recalled_search(path, "玉米蛋白"), [])
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_web_snippet_remains_unverified_in_model_context(self):
        source = {"id": "11111111-1111-5111-8111-111111111111",
                  "title": "官方标准", "url": "https://openstd.samr.gov.cn/standard/1",
                  "snippet": "公开标准摘要", "searchedAt": "2026-09-24"}
        normalized = expert.searched_knowledge({"webSources": [source]})
        messages = expert.grounded_messages("粮食储藏", normalized)
        self.assertEqual(normalized[0]["provenance"]["summary"],
                         "UNVERIFIED_SEARCH_SNIPPET")
        self.assertTrue(normalized[0]["title"].startswith("搜索摘要（未核验）"))
        self.assertIn("UNVERIFIED_SEARCH_SNIPPET", messages[1]["content"])
        self.assertNotIn("VERIFIED_FULL_TEXT_EXCERPT", messages[1]["content"])

    def test_answer_with_web_result_keeps_search_limitation(self):
        source = {"id": "11111111-1111-5111-8111-111111111111",
                  "title": "官方标准", "url": "https://openstd.samr.gov.cn/standard/1",
                  "snippet": "公开标准摘要", "searchedAt": "2026-09-24"}
        answer = json.dumps({"status": "ANSWERED", "answer": "搜索摘要提示有相关标准，需核对原文。",
                             "citationIds": [source["id"]]}, ensure_ascii=False)
        with patch.object(expert, "configured_model", return_value=Path("/tmp/model")), \
             patch.object(expert, "configured_timeout", return_value=10):
            result = expert.answer_question(
                {"question": "公开标准摘要", "webSources": [source], "webSearchFailed": False},
                generator=lambda *_: answer)
        self.assertEqual(result["status"], "ANSWERED")
        self.assertEqual(result["citations"][0]["url"], source["url"])
        self.assertTrue(any("核验" in value for value in result["limitations"]))


if __name__ == "__main__":
    unittest.main()
