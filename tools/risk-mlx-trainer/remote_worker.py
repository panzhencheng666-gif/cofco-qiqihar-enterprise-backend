from __future__ import annotations

import gzip
import hashlib
import html
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
import io
import ipaddress
import json
import os
import re
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any

import expert_training_artifacts


class ClaimExpired(RuntimeError):
    pass


EXPERT_FAILURES = {
    "DATASET_PREPARATION_FAILED": "专家训练数据准备失败，未生成候选工件。",
    "MODEL_LOAD_FAILED": "本地专家模型加载失败，未生成候选工件。",
    "LOCAL_TRAINING_FAILED": "本地专家训练失败，未生成候选工件。",
    "OUT_OF_MEMORY": "本地专家训练内存不足，未生成候选工件。",
    "PACKAGING_FAILED": "专家训练工件打包失败，未上传候选工件。",
}
EXPERT_PROGRESS = ((5, "PREPARING"), (10, "LOCAL_TRAINING"),
                   (75, "PACKAGING"), (85, "UPLOADING"), (95, "COMPLETING"))
SAFE_RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
ASSISTANT_MODEL_REFERENCE = "qiliang-foundation-rag-qwen3.8-27b"
WEB_SEARCH_PREFIX = "[联网搜索] "
WEB_SEARCH_URL = "http://127.0.0.1:63311/search"
WEB_SEARCH_ENGINES = "brave,bing,mojeek,google,duckduckgo"
WEB_SEARCH_RETRY_SECONDS = 60
_web_search_retry_at = 0.0
PUBLIC_TEXT_HOSTS = ("gov.cn", "wikipedia.org", "fao.org", "who.int", "un.org",
                     "docs.python.org", "developer.mozilla.org", "postgresql.org", "nodejs.org")
PUBLIC_KNOWLEDGE_QUERIES = (
    "粮食市场价格 农业农村部",
    "大豆 玉米 质量标准 国家粮食和物资储备局",
    "粮食仓储 安全 风险预警 官方",
    "黑龙江 粮食产量 统计公报",
)


def trainer_payload(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "modelId": job["modelId"],
        "modelCode": job["modelCode"],
        "baseModelReference": job["baseModelReference"],
        "domainCode": job["domainCode"],
        "candidateVersion": int(job["modelVersion"]),
        "trainingSnapshotId": job["trainingSnapshotId"],
        "randomSeed": int(job["randomSeed"]),
        "trainingKind": "LORA_ADAPTER",
        "examples": [dict(example, domainCode=job["domainCode"])
                     for example in job["examples"]],
    }


def bundle_artifact(directory: Path) -> bytes:
    directory = directory.resolve(strict=True)
    files = sorted(path for path in directory.rglob("*") if path.is_file())
    if not files or any(path.is_symlink() for path in directory.rglob("*")):
        raise ValueError("训练工件目录为空或包含符号链接")
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for path in files:
            relative = path.relative_to(directory).as_posix()
            info = archive.gettarinfo(str(path), arcname=relative)
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mtime = 0
            info.mode = 0o600
            with path.open("rb") as source:
                archive.addfile(info, source)
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0) as output:
        output.write(raw.getvalue())
    return compressed.getvalue()


def canonical_hash(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def expert_artifact_hash(directory: Path) -> str:
    return expert_training_artifacts.layout_hashes(
        directory, time.monotonic() + 300, manifest=True)[1]


def json_object(body: bytes) -> dict[str, Any]:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    value = json.loads(body, object_pairs_hook=unique,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
    if type(value) is not dict:
        raise ValueError("Expected JSON object")
    return value


def local_expert_urls(trainer: str) -> tuple[str, str]:
    parsed = urllib.parse.urlsplit(trainer)
    try:
        port = parsed.port
    except ValueError as error:
        raise RuntimeError("本地训练地址不合法") from error
    if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1")
            or port is None or parsed.username is not None or parsed.password is not None
            or parsed.path != "/v1/train" or parsed.query or parsed.fragment):
        raise RuntimeError("本地训练地址必须是固定回环端点")
    return (urllib.parse.urlunsplit(parsed._replace(path="/v1/expert-train")),
            urllib.parse.urlunsplit(parsed._replace(path="/v1/expert-train-cancel")))


def local_assistant_url(trainer: str) -> str:
    parsed = urllib.parse.urlsplit(trainer)
    try:
        port = parsed.port
    except ValueError as error:
        raise RuntimeError("本地训练地址不合法") from error
    if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1")
            or port is None or parsed.username is not None or parsed.password is not None
            or parsed.path != "/v1/train" or parsed.query or parsed.fragment):
        raise RuntimeError("本地训练地址必须是固定回环端点")
    return urllib.parse.urlunsplit(parsed._replace(path="/v1/expert-answer"))


def assistant_answer_for_cloud(answer: dict[str, Any]) -> dict[str, Any]:
    required = {"status", "mode", "modelReference", "knowledgeVersion",
                "answer", "citations", "limitations"}
    if set(answer) != required or answer.get("status") not in (
            "ANSWERED", "INSUFFICIENT_EVIDENCE") or answer.get("mode") not in (
            "FOUNDATION_RAG", "FOUNDATION_GENERAL"):
        raise ValueError("本地AI助手响应不合法")
    if not isinstance(answer.get("modelReference"), str) or not answer["modelReference"].strip():
        raise ValueError("本地AI助手模型引用不合法")
    for name, maximum in (("knowledgeVersion", 160), ("answer", 6000)):
        value = answer.get(name)
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise ValueError("本地AI助手文本字段不合法")
    citations = answer.get("citations")
    limitations = answer.get("limitations")
    if (not isinstance(citations, list) or len(citations) > 20
            or (answer["status"] == "ANSWERED" and answer["mode"] == "FOUNDATION_RAG"
                and not citations)
            or (answer["mode"] == "FOUNDATION_GENERAL" and citations)
            or not isinstance(limitations, list) or len(limitations) > 20):
        raise ValueError("本地AI助手证据字段不合法")
    safe_citations = []
    for citation in citations:
        if not isinstance(citation, dict):
            raise ValueError("本地AI助手引用不合法")
        safe = {name: citation.get(name) for name in ("id", "title", "url", "use")}
        if (not isinstance(safe["id"], str) or not safe["id"].strip()
                or len(safe["id"]) > 160 or not isinstance(safe["title"], str)
                or not safe["title"].strip() or len(safe["title"]) > 300
                or not isinstance(safe["url"], str) or len(safe["url"]) > 2000
                or safe["use"] != "RETRIEVAL_ONLY"):
            raise ValueError("本地AI助手引用不合法")
        parsed = urllib.parse.urlsplit(safe["url"])
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("本地AI助手引用地址不合法")
        for name in ("searchedAt", "publishedAt"):
            value = citation.get(name)
            if value is not None:
                if not isinstance(value, str) or not value.strip() or len(value) > 40:
                    raise ValueError("本地AI助手引用时间不合法")
                safe[name] = value
        source_type = citation.get("sourceType")
        if source_type is not None:
            if source_type not in ("SEARCH_SNIPPET", "PUBLIC_PAGE_EXCERPT", "NEWS_HEADLINE"):
                raise ValueError("本地AI助手引用类型不合法")
            safe["sourceType"] = source_type
        safe_citations.append(safe)
    if any(not isinstance(item, str) or not item.strip() or len(item) > 2000
           for item in limitations):
        raise ValueError("本地AI助手限制说明不合法")
    return {
        "status": answer["status"], "mode": answer["mode"],
        "modelReference": ASSISTANT_MODEL_REFERENCE,
        "knowledgeVersion": answer["knowledgeVersion"], "answer": answer["answer"],
        "citations": safe_citations, "limitations": limitations,
    }


def assistant_candidates_for_cloud(sources: list[dict[str, str]]) -> list[dict[str, str]]:
    candidates = []
    for source in sources:
        if len(candidates) == 2:
            break
        if not isinstance(source, dict):
            continue
        title, url, snippet = (source.get(key) for key in ("title", "url", "snippet"))
        if (not all(isinstance(value, str) and value.strip()
                    for value in (title, url, snippet))
                or len(title) > 300 or len(url) > 800 or len(snippet) > 4000):
            continue
        try:
            parsed = urllib.parse.urlsplit(url)
            host = (parsed.hostname or "").lower()
            if (parsed.scheme != "https" or not host or parsed.username or parsed.password
                    or parsed.fragment or parsed.port not in (None, 443)
                    or host == "localhost" or host.endswith(".local")
                    or ":" in host or re.fullmatch(r"[\d.]+", host)):
                continue
        except ValueError:
            continue
        candidates.append({"title": title, "url": url, "snippet": snippet})
    return candidates


class PublicPageText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.parts: list[str] = []
        self.paragraph_depth = 0
        self.paragraphs: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in ("script", "style", "noscript", "svg", "nav", "footer"):
            self.hidden += 1
        if tag == "p":
            self.paragraph_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in ("script", "style", "noscript", "svg", "nav", "footer"):
            self.hidden = max(0, self.hidden - 1)
        if tag == "p":
            self.paragraph_depth = max(0, self.paragraph_depth - 1)

    def handle_data(self, data: str) -> None:
        if not self.hidden and data.strip():
            self.parts.append(data)
            if self.paragraph_depth:
                self.paragraphs.append(data)


def public_page_target(url: str) -> str | None:
    """Limit proxy-mediated article fetches to known public information domains."""
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError:
        return None
    host = parsed.hostname
    if (parsed.scheme != "https" or not host or parsed.username or parsed.password
            or port not in (None, 443) or parsed.fragment or len(url) > 800):
        return None
    if any(ord(character) < 0x20 or ord(character) == 0x7f for character in url):
        return None
    try:
        host = host.encode("idna").decode("ascii").lower()
    except UnicodeError:
        return None
    try:
        ipaddress.ip_address(host)
        return None
    except ValueError:
        pass
    if (not re.fullmatch(r"[a-z0-9.-]+", host)
            or not any(host == suffix or host.endswith("." + suffix)
                       for suffix in PUBLIC_TEXT_HOSTS)):
        return None
    return url


def fetch_public_page_excerpt(url: str, focus: str = "") -> str | None:
    target = public_page_target(url)
    if target is None:
        return None
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(NoRedirect())
    try:
        request = urllib.request.Request(target, headers={
            "User-Agent": "QiLiangKnowledgeAssistant/1.0", "Accept": "text/html,text/plain",
            "Accept-Encoding": "identity"})
        with opener.open(request, timeout=3) as response:
            content_type = response.headers.get("Content-Type", "").lower()
            if response.status != 200 or not content_type.startswith(("text/html", "text/plain")):
                return None
            body = response.read(250_000)
            charset = response.headers.get_content_charset() or "utf-8"
        try:
            content = body.decode(charset, "replace")
        except LookupError:
            content = body.decode("utf-8", "replace")
        if content_type.startswith("text/html"):
            parser = PublicPageText()
            parser.feed(content)
            content = " ".join(parser.paragraphs or parser.parts)
        excerpt = re.sub(r"\s+", " ", html.unescape(content)).strip()
        if len(excerpt) < 60:
            return None
        # Search results can point to a long official page whose answer is far
        # beyond the first 800 characters. Select an actual page-text window
        # matching the result description; keep the original prefix otherwise.
        primary_focus = re.split(r"[？?。]", focus, maxsplit=1)[0]
        # Mixed-language Python questions often use tuple/list while the
        # Chinese documentation uses 元组/列表 in the relevant paragraph.
        mixed_terms = [term for pattern, term in ((r"\btuples?\b", "元组"),
                                                   (r"\blists?\b", "列表"))
                       if re.search(pattern, primary_focus, re.I)]
        if mixed_terms:
            chinese_terms = mixed_terms + [term for term in ("不可变", "可变")
                                           if term in primary_focus]
        else:
            chinese_terms = list(dict.fromkeys(
                part[i:i + 2]
                for part in re.findall(r"[\u4e00-\u9fff]+", search_topic_terms(primary_focus))
                for i in range(len(part) - 1)
            ))[:20]
        if len(chinese_terms) >= 2 and len(excerpt) > 800:
            # Score a short span so a later comparison paragraph wins over an
            # early section that only repeats one term throughout 800 chars.
            score, start = max(
                ((sum(term in excerpt[offset:offset + 320]
                      for term in chinese_terms), offset)
                 for offset in range(0, len(excerpt), 50)),
                key=lambda item: (item[0], -item[1]))
            if score >= 2:
                start = max(0, start - 50)
                return excerpt[start:start + 800]
        terms = list(dict.fromkeys(
            word[:-1] if word.endswith("s") and len(word) > 5 else word
            for word in re.findall(r"[a-z][a-z0-9]{3,}", focus.casefold())
            if word not in {"about", "from", "with", "that", "this", "which", "official", "documentation"}
        ))[:12]
        if len(terms) >= 2 and len(excerpt) > 800:
            lower = excerpt.casefold()
            def window_score(start: int) -> int:
                window = lower[start:start + 800]
                coverage = sum(term in window for term in terms)
                nearby_pairs = sum(bool(re.search(re.escape(left) + r".{0,80}" +
                                                   re.escape(right), window))
                                   for left, right in zip(terms, terms[1:]))
                return coverage + 2 * nearby_pairs

            windows = ((window_score(start), start)
                       for start in range(0, len(excerpt), 200))
            score, start = max(windows, key=lambda item: (item[0], -item[1]))
            if score >= 2:
                return excerpt[start:start + 800]
        return excerpt[:800]
    except (OSError, urllib.error.URLError, ValueError):
        return None


def search_topic_terms(question: str) -> str:
    return re.sub(
        r"site:[a-z0-9.-]+|请|联网|查询|检索|搜索|官方|资料|文档|简要|说明|给出|来源|"
        r"是什么|有哪些|有什么|为什么|如何|怎么|以及|主要|中文|[？?：:，,。]",
        " ", question, flags=re.I).strip()


def matches_search_topic(question: str, text: str) -> bool:
    topic = search_topic_terms(question).casefold()
    english = set(re.findall(r"[a-z][a-z0-9]{2,}", topic)) - {
        "what", "which", "how", "are", "the", "and", "please", "search", "official", "source", "sources"}
    chinese = {part[i:i + 2] for part in re.findall(r"[\u4e00-\u9fff]+", topic)
               for i in range(len(part) - 1)}
    text = text.casefold()
    return (not english and not chinese
            or any(re.search(r"(?<![a-z0-9])" + word + r"(?![a-z0-9])", text) for word in english)
            or bool(chinese) and sum(term in text for term in chinese) >= min(2, len(chinese)))


def public_wikipedia_search(question: str) -> list[dict[str, str]]:
    comparison_topics = [crop for crop in ("玉米", "大豆", "小麦", "水稻") if crop in question]
    if len(comparison_topics) >= 2 and re.search(r"区别|不同|比较|对比|差异", question):
        query = urllib.parse.urlencode({"action": "query", "titles": "|".join(comparison_topics[:2]),
                                        "prop": "extracts", "explaintext": "1", "exintro": "1",
                                        "format": "json"})
        status, body = request("GET", "https://zh.wikipedia.org/w/api.php?" + query,
                               {"Accept": "application/json",
                                "User-Agent": "QiLiangKnowledgeAssistant/1.0"}, None, 4)
        if status == 200 and len(body) <= 2_000_000:
            pages = json_object(body).get("query", {}).get("pages", {})
            if isinstance(pages, dict):
                sources = []
                for topic in comparison_topics[:2]:
                    page = next((item for item in pages.values()
                                 if isinstance(item, dict) and item.get("title") == topic), None)
                    if not page or not isinstance(page.get("extract"), str) or not page["extract"].strip():
                        continue
                    url = "https://zh.wikipedia.org/wiki/" + urllib.parse.quote(topic)
                    sources.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, url)),
                                    "title": "维基百科 · " + topic, "url": url,
                                    "snippet": page["extract"][:800],
                                    "sourceType": "PUBLIC_PAGE_EXCERPT",
                                    "searchedAt": utc_timestamp()})
                if sources:
                    return sources
    wiki_terms = ("大豆 蛋白质" if "大豆" in question and "蛋白" in question else
                  search_topic_terms(question).replace("多少", "").strip() or question)
    wiki_query = urllib.parse.urlencode({
        "action": "query", "generator": "search", "gsrsearch": wiki_terms,
        "gsrlimit": "3", "prop": "extracts", "explaintext": "1",
        "exintro": "1", "format": "json",
    })
    status, body = request("GET", "https://zh.wikipedia.org/w/api.php?" + wiki_query,
                           {"Accept": "application/json", "User-Agent": "QiLiangKnowledgeAssistant/1.0"},
                           None, 4)
    if status != 200 or len(body) > 2_000_000:
        return []
    pages = json_object(body).get("query", {}).get("pages", {})
    if not isinstance(pages, dict):
        return []
    sources: list[dict[str, str]] = []
    for page in pages.values():
        if not isinstance(page, dict):
            continue
        title, excerpt = page.get("title"), page.get("extract")
        if not isinstance(title, str) or not title.strip() or not isinstance(excerpt, str) or not excerpt.strip():
            continue
        relevant_terms = {wiki_terms[i:i + 2] for i in range(len(wiki_terms) - 1)
                          if "\u4e00" <= wiki_terms[i] <= "\u9fff"
                          and "\u4e00" <= wiki_terms[i + 1] <= "\u9fff"}
        if sum(term in title for term in relevant_terms) < 2:
            continue
        if wiki_terms == "大豆 蛋白质" and not ("大豆" in title or "豆" in title and "蛋白" in title):
            continue
        url = "https://zh.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        sources.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, url)),
                        "title": "维基百科 · " + title[:280], "url": url,
                        "snippet": excerpt[:800], "sourceType": "PUBLIC_PAGE_EXCERPT",
                        "searchedAt": utc_timestamp()})
        if len(sources) == 2:
            break
    return sources


def requested_search_domain(question: str) -> str | None:
    explicit = re.search(r"\bsite:([a-z0-9.-]+\.[a-z]{2,})(?=[\s/]|$)", question, re.I)
    if explicit:
        return explicit.group(1).lower()
    if re.search(r"官方|官网|official", question, re.I):
        for name, domain in ((r"python", "docs.python.org"),
                             (r"(?:mdn|javascript)", "developer.mozilla.org"),
                             (r"postgres(?:ql)?", "postgresql.org"),
                             (r"node(?:\.js|js)?", "nodejs.org")):
            if re.search(r"(?<![a-z0-9_])" + name + r"(?![a-z0-9_])", question, re.I):
                return domain
    return None


def needs_web_search(question: str, *, forced: bool = False) -> bool:
    if re.search(r"不要联网|无需联网|不用联网|不必联网|仅根据(?:以下|给定|上述)数据", question):
        return False
    if forced or re.search(r"联网|搜索|检索|官方|来源|标准|法规|今天|当前|最新|实时|今年|目前|site:", question, re.I):
        return True
    # Only bypass retrieval for self-contained numeric exercises. Unknown
    # questions retain the existing web-search default.
    return not (re.search(r"计算|算出|求解", question)
                and len(re.findall(r"\d+(?:\.\d+)?", question)) >= 3
                and re.search(r"固定成本|每件|单价为|假设|已知|方程|算术", question))


def bing_search_results(terms: str) -> list[dict[str, str]]:
    """Independent public search fallback; descriptions remain unverified snippets."""
    query = urllib.parse.urlencode({"q": terms, "format": "rss"})
    try:
        status, body = request("GET", "https://www.bing.com/search?" + query,
                               {"Accept": "application/rss+xml",
                                "User-Agent": "QiLiangKnowledgeAssistant/1.0"}, None, 4)
        if status != 200 or len(body) > 2_000_000:
            return []
        root = ET.fromstring(body)
        results = [{"title": html.unescape(item.findtext("title") or "").strip(),
                 "url": (item.findtext("link") or "").strip(),
                 "content": html.unescape(item.findtext("description") or "").strip()}
                for item in root.findall("./channel/item")[:20]]
        return [item for item in results
                if matches_search_topic(terms, item["title"] + " " + item["content"])]
    except (RuntimeError, ValueError, OSError, urllib.error.URLError, ET.ParseError):
        return []


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def detect_search_conflict(question: str, sources: list[dict[str, Any]]) -> bool:
    """Detect narrow numeric disagreements without treating every source difference as conflict."""
    if (len(sources) < 2
            or not re.search(r"标准|含量|水分|蛋白|价格|报价|产量|单产|面积|比例|阈值|多少|数值", question)):
        return False
    value_pattern = re.compile(
        r"(?<!\d)\d+(?:\.\d+)?\s*(?:%|％|万吨|吨|千克|公斤|万元|元|℃|摄氏度|万亩|亩|克/升|g/L)",
        re.I)
    claims = []
    for source in sources:
        if not isinstance(source, dict):
            continue
        text = " ".join(str(source.get(key, "")) for key in ("title", "snippet", "summary"))
        values = {re.sub(r"\s+", "", value).replace("％", "%").casefold()
                  for value in value_pattern.findall(text)}
        if len(values) == 1:
            claims.append(next(iter(values)))
    return len(claims) >= 2 and len(set(claims)) > 1


def public_web_search(question: str) -> list[dict[str, str]]:
    global _web_search_retry_at
    # A conversational question tends to surface nutrition Q&A pages instead
    # of grain quality reports. Search the measurable commodity term first.
    forecast_region = next((value for value in ("黑河", "齐齐哈尔", "黑龙江")
                            if value in question), None)
    forecast_crop = next((value for value in ("大豆", "玉米", "小麦", "水稻", "稻谷")
                          if value in question), None)
    disease_topic = next((value for value in (
        "赤霉病", "条锈病", "稻瘟病", "白粉病", "玉米螟", "麦蚜", "病虫害")
        if value in question), None)
    forecast_year = re.search(r"20\d{2}", question)
    if (forecast_region and forecast_crop and "产量" in question
            and re.search(r"预测|预估|预计|预判|推测", question)):
        terms = " ".join(part for part in (
            forecast_year.group() if forecast_year else "", forecast_region,
            forecast_crop, "产量 播种面积 单产") if part)
    elif "大豆" in question and "蛋白" in question:
        terms = "大豆 粗蛋白 含量 优质 标准"
    else:
        terms = question
    domain = requested_search_domain(question)
    if domain and "site:" not in terms.lower():
        terms = f"site:{domain} {terms}"
    query = urllib.parse.urlencode({"q": terms, "format": "json", "engines": WEB_SEARCH_ENGINES})
    results = []
    if time.monotonic() >= _web_search_retry_at:
        try:
            status, body = request("GET", WEB_SEARCH_URL + "?" + query,
                                   {"Accept": "application/json"}, None, 6)
            if status != 200 or len(body) > 2_000_000:
                raise RuntimeError("本地公开搜索不可用")
            results = json_object(body).get("results")
            if not isinstance(results, list):
                raise RuntimeError("本地公开搜索响应不合法")
            _web_search_retry_at = 0.0
        except (RuntimeError, ValueError, OSError, urllib.error.URLError):
            _web_search_retry_at = time.monotonic() + WEB_SEARCH_RETRY_SECONDS
            results = []
    if not results:
        results = bing_search_results(terms)
    sources: list[dict[str, str]] = []
    for result in results:
        if not isinstance(result, dict):
            continue
        title, url, snippet = (result.get(name) for name in ("title", "url", "content"))
        if not all(isinstance(value, str) and value.strip() for value in (title, url, snippet)):
            continue
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                or parsed.password or parsed.fragment or parsed.port not in (None, 443)
                or len(url) > 800 or len(title) > 300):
            continue
        host = parsed.hostname.lower()
        if domain and host != domain and not host.endswith("." + domain):
            continue
        if host == "localhost" or host.endswith(".local") or ":" in host or re.fullmatch(r"[\d.]+", host):
            continue
        if forecast_region and forecast_crop and "产量" in question:
            searchable = (title + " " + snippet).casefold()
            if forecast_region not in searchable or forecast_crop not in searchable:
                continue
        if "大豆" in question and "蛋白" in question:
            if "大豆" not in title or "蛋白" not in title + snippet:
                continue
        if disease_topic and disease_topic not in title + snippet:
            continue
        sources.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, url)), "title": title,
                        "url": url, "snippet": snippet[:800], "sourceType": "SEARCH_SNIPPET",
                        "searchedAt": utc_timestamp()})
        if len(sources) == 2:
            break
    if sources:
        with ThreadPoolExecutor(max_workers=2) as pool:
            excerpts = list(pool.map(lambda source: fetch_public_page_excerpt(
                source["url"], question + " " + source["snippet"]), sources))
        for source, excerpt in zip(sources, excerpts):
            if excerpt:
                source["snippet"] = excerpt
                source["sourceType"] = "PUBLIC_PAGE_EXCERPT"
        return sources
    if domain:
        return []
    current_question = bool(re.search(r"20\d{2}|今年|今天|最新|近期|实时|当前|新闻|价格|政策|发布|预测|预估|走势", question))
    if not current_question:
        try:
            sources = public_wikipedia_search(question)
            if sources:
                return sources
        except (RuntimeError, ValueError, OSError, urllib.error.URLError):
            pass
    # A public news feed can still provide dated article leads when web search
    # engines rate-limit. Headlines are not article bodies or verified claims.
    topic_words = re.findall(
        r"20\d{2}年?|黑河|黑龙江|齐齐哈尔|大豆|玉米|小麦|稻谷|水稻|粮食|"
        r"赤霉病|条锈病|稻瘟病|白粉病|玉米螟|麦蚜|病虫害|"
        r"产量|价格|政策|风险|仓储", question)
    news_terms = " ".join(dict.fromkeys(topic_words)) if len(topic_words) >= 2 else question[:80]
    news_query = urllib.parse.urlencode({"q": news_terms, "hl": "zh-CN", "gl": "CN",
                                         "ceid": "CN:zh-Hans"})
    try:
        news_status, news_body = request(
            "GET", "https://news.google.com/rss/search?" + news_query,
            {"Accept": "application/rss+xml", "User-Agent": "QiLiangKnowledgeAssistant/1.0"},
            None, 4)
        if news_status == 200 and len(news_body) <= 2_000_000:
            root = ET.fromstring(news_body)
            question_terms = {question[i:i + 2] for i in range(len(question) - 1)
                              if "\u4e00" <= question[i] <= "\u9fff"
                              and "\u4e00" <= question[i + 1] <= "\u9fff"}
            ranked = []
            for item in root.findall("./channel/item")[:30]:
                title = html.unescape(item.findtext("title") or "").strip()
                url = (item.findtext("link") or "").strip()
                publisher = (item.findtext("source") or "").strip()
                published = (item.findtext("pubDate") or "").strip()
                parsed = urllib.parse.urlsplit(url)
                score = sum(term in title for term in question_terms)
                if (score < 2 or not title or len(title) > 300 or len(url) > 800
                        or parsed.scheme != "https" or parsed.hostname != "news.google.com"):
                    continue
                if "大豆" in question and "蛋白" in question and (
                        "大豆" not in title or "蛋白" not in title):
                    continue
                if forecast_region and forecast_region not in title:
                    continue
                if forecast_crop and forecast_crop not in title:
                    continue
                if "价格" in question or "收购价" in question or "报价" in question:
                    if not re.search(r"价格|收购价|报价|行情|现货价", title):
                        continue
                if disease_topic and disease_topic not in title:
                    continue
                try:
                    published_at = parsedate_to_datetime(published).astimezone(timezone.utc)
                    date = published_at.date().isoformat()
                    published_timestamp = published_at.replace(microsecond=0).isoformat().replace(
                        "+00:00", "Z")
                except (TypeError, ValueError, IndexError):
                    continue
                snippet = (f"仅检索到新闻标题，未读取文章正文；发布于{date}，发布方"
                           f"{publisher[:80] or '未标明'}。标题：{title}")
                ranked.append((score, {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, url)),
                                       "title": title, "url": url, "snippet": snippet[:800],
                                       "sourceType": "NEWS_HEADLINE",
                                       "searchedAt": utc_timestamp(),
                                       "publishedAt": published_timestamp}))
            ranked.sort(key=lambda row: -row[0])
            sources = [source for _, source in ranked[:2]]
            if sources:
                return sources
    except (RuntimeError, ValueError, OSError, urllib.error.URLError, ET.ParseError):
        pass
    if current_question:
        try:
            return public_wikipedia_search(question)
        except (RuntimeError, ValueError, OSError, urllib.error.URLError):
            return []
    return []


def filter_search_relevance(question: str, sources: list[dict[str, str]]) -> list[dict[str, str]]:
    """Do not cite generic equipment pages for grain-store safety questions."""
    domain = requested_search_domain(question)
    if domain:
        scoped = []
        for source in sources:
            try:
                host = urllib.parse.urlsplit(source.get("url", "")).hostname or ""
            except ValueError:
                continue
            if host == domain or host.endswith("." + domain):
                scoped.append(source)
        sources = scoped
    if not (re.search(r"粮仓|粮堆|储粮|粮情|仓储", question)
            and re.search(r"测点|传感器|温度|霉变|虫害|投药|用药", question)):
        return [source for source in sources if matches_search_topic(
            question, str(source.get("title", "")) + " " + str(source.get("snippet", "")))]
    return [source for source in sources if re.search(
        r"粮仓|粮堆|储粮|粮情|仓储|粮食|粮油|谷物",
        str(source.get("title", "")) + " " + str(source.get("snippet", "")))]


def remember_search(path: Path, question: str, sources: list[dict[str, str]]) -> None:
    """Keep an attributed, unverified search cache shared by public assistant users."""
    if not sources:
        return
    try:
        state = load_state(path) or {}
    except (OSError, ValueError, RuntimeError):
        state = {}
    if not isinstance(state, dict):
        state = {}
    key = hashlib.sha256(question.strip().encode()).hexdigest()
    state[key] = {"savedAt": int(time.time()), "sources": sources[:2]}
    state = dict(sorted(state.items(), key=lambda item: item[1].get("savedAt", 0), reverse=True)[:200])
    save_state(path, state)


def recalled_search(path: Path, question: str) -> list[dict[str, str]]:
    try:
        state = load_state(path) or {}
        item = state.get(hashlib.sha256(question.strip().encode()).hexdigest(), {})
        if (not isinstance(item, dict) or int(time.time()) - item.get("savedAt", 0) > 7 * 86400):
            return []
        return item.get("sources", [])[:2]
    except (OSError, ValueError, RuntimeError, TypeError, AttributeError):
        return []


def remember_public_knowledge(path: Path, sources: list[dict[str, str]]) -> None:
    """Persist attributable public leads, excluding title-only news records."""
    try:
        state = load_state(path) or {}
    except (OSError, ValueError, RuntimeError):
        state = {}
    if not isinstance(state, dict):
        state = {}
    state = {key: value for key, value in state.items()
             if isinstance(value, dict) and isinstance(value.get("seenAt"), int)
             and isinstance(value.get("source"), dict)
             and value["source"].get("sourceType") != "NEWS_HEADLINE"}
    now = int(time.time())
    for source in sources:
        if (isinstance(source, dict) and isinstance(source.get("id"), str)
                and source.get("sourceType") != "NEWS_HEADLINE"):
            state[source["id"]] = {"seenAt": now, "source": source}
    state = dict(sorted(state.items(), key=lambda item: item[1].get("seenAt", 0),
                        reverse=True)[:2000])
    save_state(path, state)


def enrich_public_knowledge(path: Path, limit: int = 8) -> int:
    """Replace cached public search snippets with bounded source page text."""
    try:
        state = load_state(path) or {}
    except (OSError, ValueError, RuntimeError):
        return 0
    if not isinstance(state, dict):
        return 0
    candidates = []
    for item in sorted(state.values(), key=lambda value: value.get("seenAt", 0)
                       if isinstance(value, dict) else 0, reverse=True):
        source = item.get("source") if isinstance(item, dict) else None
        if (not isinstance(source, dict) or source.get("sourceType") in
                ("PUBLIC_PAGE_EXCERPT", "NEWS_HEADLINE")
                or not isinstance(source.get("url"), str)
                or public_page_target(source["url"]) is None):
            continue
        candidates.append(source)
        if len(candidates) >= limit:
            break
    if not candidates:
        return 0
    with ThreadPoolExecutor(max_workers=3) as pool:
        excerpts = list(pool.map(fetch_public_page_excerpt,
                                 (source["url"] for source in candidates)))
    promoted = [{**source, "snippet": excerpt, "sourceType": "PUBLIC_PAGE_EXCERPT"}
                for source, excerpt in zip(candidates, excerpts) if excerpt]
    if promoted:
        remember_public_knowledge(path, promoted)
    return len(promoted)


def recalled_public_knowledge(path: Path, question: str) -> list[dict[str, str]]:
    """Use recent related public leads only for questions without a live-data cue."""
    if re.search(r"20\d{2}|今天|今日|最新|现在|实时|当前|本周|本月", question):
        return []
    terms = {question[index:index + 2].casefold() for index in range(len(question) - 1)
             if "\u4e00" <= question[index] <= "\u9fff"
             and "\u4e00" <= question[index + 1] <= "\u9fff"}
    if len(terms) < 2:
        return []
    try:
        state = load_state(path) or {}
        if not isinstance(state, dict):
            return []
        ranked = []
        for item in state.values():
            source = item.get("source") if isinstance(item, dict) else None
            if (not isinstance(source, dict) or not isinstance(item.get("seenAt"), int)
                    or time.time() - item["seenAt"] > 30 * 86400):
                continue
            haystack = (str(source.get("title", "")) + " "
                        + str(source.get("snippet", ""))).casefold()
            score = sum(term in haystack for term in terms)
            if score >= 2 and score / len(terms) >= .35:
                ranked.append((score, item["seenAt"], source))
        ranked.sort(key=lambda row: (-row[0], -row[1]))
        return [row[2] for row in ranked[:2]]
    except (OSError, ValueError, RuntimeError, TypeError, AttributeError):
        return []


def refresh_public_knowledge_once(library: Path, marker: Path) -> bool:
    """Refresh one topic daily; retry an empty/failed search after six hours."""
    today = time.strftime("%Y-%m-%d", time.gmtime())
    now = int(time.time())
    try:
        previous = load_state(marker) or {}
    except (OSError, ValueError, RuntimeError):
        previous = {}
    if isinstance(previous, dict) and previous.get("date") == today:
        if previous.get("usable") is True:
            return False
        attempted_at = previous.get("attemptedAt", 0)
        if ("usable" in previous and type(attempted_at) is int and attempted_at > 0
                and 0 <= now - attempted_at < 6 * 3600):
            return False
    topic = PUBLIC_KNOWLEDGE_QUERIES[time.gmtime().tm_yday % len(PUBLIC_KNOWLEDGE_QUERIES)]
    try:
        sources = public_web_search(topic)
    except Exception:
        save_state(marker, {"date": today, "successful": False,
                            "usable": False, "attemptedAt": now})
        raise
    remember_public_knowledge(library, sources)
    enriched = enrich_public_knowledge(library)
    usable = enriched > 0 or any(source.get("sourceType") == "PUBLIC_PAGE_EXCERPT"
                               for source in sources)
    save_state(marker, {"date": today, "successful": bool(sources) or enriched > 0,
                        "usable": usable, "attemptedAt": now})
    return usable


def save_state(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix(".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, separators=(",", ":"))
        output.write("\n")
    os.replace(temporary, path)
    path.chmod(0o600)


def load_state(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if path.stat().st_mode & 0o077:
        raise RuntimeError("训练节点状态文件权限不安全")
    return json.loads(path.read_text(encoding="utf-8"))


def request(method: str, url: str, headers: dict[str, str], body: bytes | None,
            timeout: int) -> tuple[int, bytes]:
    call = urllib.request.Request(url, data=body, headers=headers, method=method)
    host = urllib.parse.urlsplit(url).hostname
    cloud_host = urllib.parse.urlsplit(os.environ.get("RISK_TRAINING_CLOUD_URL", "")).hostname
    # The configured private cloud route and loopback trainer must not depend
    # on the desktop's intermittent proxy; public reference search may use it.
    direct = host in ("127.0.0.1", "::1", "localhost", cloud_host,
                      "news.google.com", "zh.wikipedia.org")
    try:
        opener = (urllib.request.build_opener(urllib.request.ProxyHandler({}))
                  if direct else urllib.request.build_opener())
        with opener.open(call, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def post_assistant_result(url: str, headers: dict[str, str], body: bytes) -> tuple[int, bytes]:
    """Replay only the same idempotent result when its cloud acknowledgement is lost."""
    for attempt in range(5):
        try:
            status, response = request("POST", url, headers, body, 20)
        except (OSError, urllib.error.URLError):
            if attempt == 4:
                raise
        else:
            if status not in (500, 502, 503, 504) or attempt == 4:
                return status, response
        time.sleep(1 << attempt)
    raise AssertionError("Unreachable assistant result retry state")


class Worker:
    def __init__(self) -> None:
        self.cloud = required("RISK_TRAINING_CLOUD_URL").rstrip("/")
        if not self.cloud.startswith("https://") and os.environ.get(
                "RISK_TRAINING_ALLOW_HTTP", "false").lower() != "true":
            raise RuntimeError("云端训练 API 必须使用 HTTPS")
        self.token = required("RISK_TRAINING_NODE_TOKEN")
        self.node = required("RISK_TRAINING_NODE_ID")
        self.trainer = os.environ.get("RISK_LLM_TRAINER_URL",
                                      "http://127.0.0.1:63201/v1/train")
        self.expert_trainer, self.expert_cancel = local_expert_urls(self.trainer)
        self.trainer_token = required("RISK_LLM_BEARER_TOKEN")
        state_root = Path(os.environ.get(
            "RISK_TRAINING_NODE_STATE_ROOT", "var/risk-training-node")).resolve()
        self.state = state_root / "active-claim.json"
        self.expert_state = state_root / "active-expert-claim.json"
        self.artifact_index = state_root / "artifact-index.json"
        self.search_memory = state_root / "assistant-search-memory.json"
        self.public_knowledge = state_root / "assistant-public-knowledge.json"
        self.public_knowledge_refresh = state_root / "assistant-public-knowledge-refresh.json"
        configured_artifact_root = Path(os.environ.get(
            "RISK_LLM_ARTIFACT_ROOT", "var/risk-llm-adapters")).absolute()
        if configured_artifact_root.is_symlink():
            raise RuntimeError("本地训练工件根目录不合法")
        self.artifact_root = configured_artifact_root.resolve()
        self.poll_seconds = max(3, int(os.environ.get("RISK_TRAINING_NODE_POLL_SECONDS", "3")))
        self.heartbeat_seconds = max(
            30, int(os.environ.get("RISK_TRAINING_NODE_HEARTBEAT_SECONDS", "300")))

    def cloud_headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.token}",
            "X-Risk-Training-Node-Id": self.node,
        }

    def claim(self) -> dict[str, Any] | None:
        status, body = request("POST", self.cloud + "/claims", self.cloud_headers(), b"", 30)
        if status == 204:
            return None
        if status != 200:
            raise RuntimeError(f"云端训练任务领取失败 HTTP {status}")
        return json.loads(body)

    def _expert_state_from_claim(self, claim: dict[str, Any]) -> dict[str, Any]:
        task_id = str(uuid.UUID(str(claim["taskId"])))
        run_id = claim["runId"]
        if type(run_id) is not str or not SAFE_RUN_ID.fullmatch(run_id):
            raise ValueError("专家训练 runId 不合法")
        if type(claim["dataset"]) is not dict or type(claim["config"]) is not dict:
            raise ValueError("专家训练任务数据不合法")
        return {"taskId": task_id, "runId": run_id,
                "dataset": claim["dataset"], "config": claim["config"], "progress": 0}

    def _saved_expert_state(self, state: dict[str, Any]) -> dict[str, Any]:
        required = {"taskId", "runId", "dataset", "config"}
        allowed = required | {"progress", "result", "stored"}
        if type(state) is not dict or not required.issubset(state) or not set(state).issubset(allowed):
            raise ValueError("专家训练状态不合法")
        clean = self._expert_state_from_claim(state)
        progress = state.get("progress", 0)
        if type(progress) is not int or progress not in {0, 5, 10, 75, 85, 95}:
            raise ValueError("专家训练状态进度不合法")
        clean["progress"] = progress
        for key in ("result", "stored"):
            if key in state:
                if type(state[key]) is not dict:
                    raise ValueError("专家训练状态检查点不合法")
                clean[key] = state[key]
        return clean

    def expert_claim(self) -> dict[str, Any] | None:
        status, body = request("POST", self.cloud + "/expert-claims",
                               self.cloud_headers(), b"", 30)
        if status == 204:
            return None
        if status != 200:
            raise RuntimeError("云端专家训练任务领取失败")
        return self._expert_state_from_claim(json_object(body))

    def _expert_url(self, state: dict[str, Any], operation: str) -> str:
        return self.cloud + f"/expert-tasks/{state['taskId']}/{operation}"

    def expert_heartbeat(self, state: dict[str, Any]) -> bool:
        status, body = request("POST", self._expert_url(state, "heartbeat"),
                               self.cloud_headers(), b"", 30)
        if status == 409:
            raise ClaimExpired("专家训练租约已失效")
        if status != 200:
            raise RuntimeError("云端专家训练心跳失败")
        value = json_object(body)
        if type(value.get("cancelRequested")) is not bool:
            raise RuntimeError("云端专家训练心跳响应不合法")
        return value["cancelRequested"]

    def expert_progress(self, state: dict[str, Any], percent: int, phase: str) -> None:
        body = json.dumps({"percent": percent, "phase": phase},
                          separators=(",", ":")).encode()
        status, _ = request("POST", self._expert_url(state, "progress"),
                            self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
        if status == 409:
            raise ClaimExpired("专家训练租约已失效")
        if status != 204:
            raise RuntimeError("云端专家训练进度登记失败")

    def advance_expert_progress(self, state: dict[str, Any], percent: int, phase: str) -> None:
        if state["progress"] >= percent:
            return
        self.expert_progress(state, percent, phase)
        state["progress"] = percent
        save_state(self.expert_state, state)

    def acknowledge_expert_cancel(self, state: dict[str, Any]) -> None:
        status, _ = request("POST", self._expert_url(state, "cancelled"),
                            self.cloud_headers(), b"", 30)
        if status == 204:
            self.expert_state.unlink(missing_ok=True)
            return
        if status == 409:
            self.expert_state.unlink(missing_ok=True)
            raise ClaimExpired("专家训练取消确认时租约已失效")
        raise RuntimeError("云端专家训练取消确认失败")

    def report_expert_failure(self, state: dict[str, Any], code: str) -> None:
        body = json.dumps({"code": code, "message": EXPERT_FAILURES[code]},
                          ensure_ascii=False, separators=(",", ":")).encode()
        status, _ = request("POST", self._expert_url(state, "failure"),
                            self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
        if status == 204:
            self.expert_state.unlink(missing_ok=True)
            return
        if status == 409:
            self.expert_state.unlink(missing_ok=True)
            raise ClaimExpired("专家训练失败确认时租约已失效")
        raise RuntimeError("云端专家训练失败确认未完成")

    def _cancel_local_expert(self, state: dict[str, Any]) -> None:
        body = json.dumps({"runId": state["runId"]}, separators=(",", ":")).encode()
        try:
            request("POST", self.expert_cancel, {
                "Authorization": f"Bearer {self.trainer_token}",
                "Content-Type": "application/json",
            }, body, 30)
        except Exception:
            pass

    def _expert_heartbeat_loop(self, state: dict[str, Any], stop: threading.Event,
                               cancelled: threading.Event,
                               lease_lost: threading.Event) -> None:
        while not stop.wait(self.heartbeat_seconds):
            try:
                if self.expert_heartbeat(state):
                    cancelled.set()
                    self._cancel_local_expert(state)
            except ClaimExpired:
                lease_lost.set()
                return
            except Exception:
                continue

    def validate_expert_result(self, value: dict[str, Any],
                               expected_run_id: str | None = None) -> tuple[Path, str, dict[str, Any]]:
        if (type(value) is not dict or type(value.get("runId")) is not str
                or (expected_run_id is not None and value.get("runId") != expected_run_id)):
            raise ValueError("本地专家训练响应不合法")
        if (value.get("kind") != "EXPERT_SFT_ADAPTER" or value.get("status") != "CANDIDATE"
                or value.get("publicationStatus") != "NOT_EVALUATED"):
            raise ValueError("本地专家训练候选状态不合法")
        content_hash = value.get("artifactSha256")
        if type(content_hash) is not str or not re.fullmatch(r"[0-9a-f]{64}", content_hash):
            raise ValueError("本地专家训练工件哈希不合法")
        if type(value.get("artifactPath")) is not str:
            raise ValueError("本地专家训练工件路径不合法")
        raw_artifact = Path(value["artifactPath"])
        if raw_artifact.is_symlink():
            raise ValueError("本地专家训练工件路径不合法")
        artifact = raw_artifact.resolve(strict=True)
        root = self.artifact_root.resolve(strict=True)
        if artifact == root or root not in artifact.parents or not artifact.is_dir():
            raise ValueError("本地专家训练工件不在受控目录")
        if artifact.stat().st_uid != os.getuid() or expert_artifact_hash(artifact) != content_hash:
            raise ValueError("本地专家训练工件校验失败")
        metrics = self.validate_expert_metrics(value.get("metrics"))
        return artifact, content_hash, metrics

    def validate_expert_metrics(self, metrics: Any) -> dict[str, Any]:
        if type(metrics) is not dict:
            raise ValueError("本地专家训练指标不合法")
        encoded_metrics = json.dumps(metrics, ensure_ascii=False, separators=(",", ":"),
                                     allow_nan=False).encode()
        if len(encoded_metrics) > 4096:
            raise ValueError("本地专家训练指标超限")
        return metrics

    def validate_stored_checkpoint(self, stored: dict[str, Any]) -> dict[str, Any]:
        if (type(stored) is not dict
                or set(stored) != {"artifactReference", "bundleSha256", "contentSha256", "sizeBytes"}
                or type(stored["artifactReference"]) is not str or not stored["artifactReference"]
                or type(stored["bundleSha256"]) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", stored["bundleSha256"])
                or type(stored["contentSha256"]) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", stored["contentSha256"])
                or type(stored["sizeBytes"]) is not int or stored["sizeBytes"] <= 0):
            raise RuntimeError("云端专家训练工件检查点不合法")
        return stored

    def validate_stored_expert(self, stored: dict[str, Any], bundle_hash: str,
                               content_hash: str, size: int) -> dict[str, Any]:
        stored = self.validate_stored_checkpoint(stored)
        if (stored["bundleSha256"] != bundle_hash
                or stored["contentSha256"] != content_hash
                or stored["sizeBytes"] != size):
            raise RuntimeError("云端专家训练工件回执不合法")
        return stored

    def complete_expert(self, state: dict[str, Any], stored: dict[str, Any],
                        metrics: dict[str, Any]) -> None:
        completion = json.dumps({"artifactReference": stored["artifactReference"],
                                 "artifactSha256": stored["bundleSha256"],
                                 "metrics": metrics}, ensure_ascii=False,
                                separators=(",", ":"), allow_nan=False).encode()
        status, _ = request("POST", self._expert_url(state, "completion"),
                            self.cloud_headers() | {"Content-Type": "application/json"},
                            completion, 60)
        if status == 409:
            raise ClaimExpired("专家训练完成登记时租约已失效")
        if status != 204:
            raise RuntimeError("云端专家训练完成登记未确认")
        self.expert_state.unlink(missing_ok=True)

    def process_expert(self, state: dict[str, Any]) -> None:
        state = self._saved_expert_state(state)
        try:
            completion_replay = "stored" in state and state["progress"] == 95
            if completion_replay:
                stored = self.validate_stored_checkpoint(state["stored"])
                result = state.get("result")
                if type(result) is not dict:
                    raise RuntimeError("专家训练完成检查点不合法")
                metrics = self.validate_expert_metrics(result.get("metrics"))
                self.complete_expert(state, stored, metrics)
                return
            if not completion_replay:
                if self.expert_heartbeat(state):
                    self.acknowledge_expert_cancel(state)
                    return
            if "result" not in state:
                self.advance_expert_progress(state, *EXPERT_PROGRESS[0])
                self.advance_expert_progress(state, *EXPERT_PROGRESS[1])
                stopped = threading.Event()
                cancelled = threading.Event()
                lease_lost = threading.Event()
                heartbeat = threading.Thread(target=self._expert_heartbeat_loop,
                                             args=(state, stopped, cancelled, lease_lost), daemon=True)
                heartbeat.start()
                payload = json.dumps({"runId": state["runId"], "dataset": state["dataset"],
                                      "config": state["config"]},
                                     ensure_ascii=False, separators=(",", ":")).encode()
                try:
                    status, body = request("POST", self.expert_trainer, {
                        "Authorization": f"Bearer {self.trainer_token}",
                        "Content-Type": "application/json",
                    }, payload, 1900)
                finally:
                    stopped.set()
                    # request() bounds each heartbeat to 30 seconds. Wait for an
                    # in-flight response so a witnessed cancellation cannot lose
                    # a race with packaging/upload.
                    heartbeat.join()
                if lease_lost.is_set():
                    raise ClaimExpired("专家训练期间租约已失效")
                if cancelled.is_set():
                    self.acknowledge_expert_cancel(state)
                    return
                if self.expert_heartbeat(state):
                    self._cancel_local_expert(state)
                    self.acknowledge_expert_cancel(state)
                    return
                if status != 200:
                    try:
                        local_error = json_object(body).get("error")
                    except (ValueError, TypeError, RecursionError):
                        local_error = None
                    if status in (400, 422):
                        self.report_expert_failure(state, "DATASET_PREPARATION_FAILED")
                        return
                    if status == 503 and local_error == "EXPERT_TRAINING_UNAVAILABLE":
                        self.report_expert_failure(state, "LOCAL_TRAINING_FAILED")
                        return
                    raise RuntimeError("本地专家训练服务繁忙或暂不可用")
            try:
                trained = state["result"] if "result" in state else json_object(body)
                if trained.get("runId") != state["runId"]:
                    raise ValueError("本地专家训练 runId 不匹配")
                artifact, content_hash, metrics = self.validate_expert_result(
                    trained, state["runId"])
            except (OSError, ValueError, TypeError, KeyError, RecursionError):
                self.report_expert_failure(state, "LOCAL_TRAINING_FAILED")
                return
            if "result" not in state:
                state["result"] = {key: trained[key] for key in (
                    "runId", "kind", "status", "publicationStatus", "artifactSha256",
                    "artifactPath", "metrics")}
                save_state(self.expert_state, state)
            self.advance_expert_progress(state, *EXPERT_PROGRESS[2])
            try:
                bundle = bundle_artifact(artifact)
                bundle_hash = hashlib.sha256(bundle).hexdigest()
            except Exception:
                self.report_expert_failure(state, "PACKAGING_FAILED")
                return
            self.advance_expert_progress(state, *EXPERT_PROGRESS[3])
            if "stored" in state:
                stored = self.validate_stored_expert(
                    state["stored"], bundle_hash, content_hash, len(bundle))
            else:
                upload_headers = self.cloud_headers() | {
                    "Content-Type": "application/octet-stream",
                    "X-Risk-Artifact-Sha256": bundle_hash,
                    "X-Risk-Artifact-Content-Sha256": content_hash,
                }
                status, upload_body = request("POST", self._expert_url(state, "artifacts"),
                                              upload_headers, bundle, 300)
                if status == 409:
                    raise ClaimExpired("专家训练上传时租约已失效")
                if status != 201:
                    raise RuntimeError("云端专家训练工件上传未完成")
                stored = self.validate_stored_expert(
                    json_object(upload_body), bundle_hash, content_hash, len(bundle))
                state["stored"] = stored
                save_state(self.expert_state, state)
            self.advance_expert_progress(state, *EXPERT_PROGRESS[4])
            self.complete_expert(state, stored, metrics)
        except ClaimExpired:
            self.expert_state.unlink(missing_ok=True)
            raise

    def expert_once(self) -> bool:
        saved = load_state(self.expert_state)
        state = self._saved_expert_state(saved) if saved is not None else self.expert_claim()
        if state is None:
            return False
        if saved is None:
            save_state(self.expert_state, state)
        self.process_expert(state)
        return True

    def assistant_once(self) -> bool:
        started = time.monotonic()
        status, body = request("POST", self.cloud + "/assistant-claims",
                               self.cloud_headers(), b"", 30)
        if status == 204:
            return False
        if status != 200:
            raise RuntimeError("云端AI助手任务领取失败")
        claimed_at = time.monotonic()
        claim = json_object(body)
        try:
            request_id = str(uuid.UUID(str(claim["requestId"])))
            question = claim["question"]
            attempt = claim["attempt"]
            sources = claim.get("sources", [])
            if (type(question) is not str or not question.strip() or len(question) > 2000
                    or type(attempt) is not int or attempt < 1
                    or type(sources) is not list or len(sources) > 3):
                raise ValueError("AI助手任务不合法")
        except (KeyError, TypeError, ValueError):
            raise RuntimeError("云端AI助手任务不合法") from None
        forced_search = question.startswith(WEB_SEARCH_PREFIX)
        if forced_search:
            question = question[len(WEB_SEARCH_PREFIX):].strip()
            if not question:
                raise RuntimeError("联网搜索问题为空")
        web_sources = []
        web_search_failed = False
        web_search_conflict = False
        search_requested = needs_web_search(question, forced=forced_search)
        if search_requested:
            try:
                web_sources = filter_search_relevance(question, public_web_search(question))
                web_search_failed = not bool(web_sources)
            except (RuntimeError, ValueError, OSError, urllib.error.URLError):
                web_search_failed = True
        memory = getattr(self, "search_memory", None)
        library = getattr(self, "public_knowledge", None)
        if web_sources and memory is not None:
            remember_search(memory, question, web_sources)
            if library is not None:
                remember_public_knowledge(library, web_sources)
        elif search_requested and memory is not None:
            web_sources = filter_search_relevance(question, recalled_search(memory, question))
        if search_requested and not web_sources and library is not None:
            web_sources = filter_search_relevance(
                question, recalled_public_knowledge(library, question))
        web_search_conflict = search_requested and detect_search_conflict(question, web_sources)
        if not web_sources:
            web_search_failed = True
        searched_at = time.monotonic()
        assistant_payload = {"question": question}
        if sources:
            assistant_payload["sources"] = sources
        if search_requested:
            assistant_payload["webSources"] = web_sources
            assistant_payload["webSearchFailed"] = web_search_failed
            if web_search_conflict:
                assistant_payload["webSearchConflict"] = True
        payload = json.dumps(assistant_payload,
                             ensure_ascii=False,
                             separators=(",", ":")).encode()
        local_status, local_body = request("POST", local_assistant_url(self.trainer), {
            "Authorization": f"Bearer {self.trainer_token}",
            "Content-Type": "application/json",
        }, payload, 180)
        answered_at = time.monotonic()
        base = self.cloud + f"/assistant-requests/{request_id}"
        if local_status != 200:
            failure = json.dumps({"code": "LOCAL_ASSISTANT_UNAVAILABLE"},
                                 separators=(",", ":")).encode()
            cloud_status, _ = post_assistant_result(
                base + "/failure", self.cloud_headers() | {"Content-Type": "application/json"},
                failure)
            if cloud_status == 409:
                raise ClaimExpired("AI助手短租约已失效")
            if cloud_status != 204:
                raise RuntimeError("AI助手失败状态未登记")
            return True
        try:
            answer = assistant_answer_for_cloud(json_object(local_body))
        except (TypeError, ValueError, RecursionError):
            answer = None
        if answer is None:
            failure = json.dumps({"code": "LOCAL_ASSISTANT_INVALID_RESPONSE"},
                                 separators=(",", ":")).encode()
            cloud_status, _ = post_assistant_result(
                base + "/failure", self.cloud_headers() | {"Content-Type": "application/json"},
                failure)
        else:
            answer["webCandidates"] = assistant_candidates_for_cloud(web_sources)
            cloud_status, _ = post_assistant_result(
                base + "/completion", self.cloud_headers() | {"Content-Type": "application/json"},
                json.dumps(answer, ensure_ascii=False, separators=(",", ":")).encode())
        if cloud_status == 409:
            raise ClaimExpired("AI助手短租约已失效")
        if cloud_status != 204:
            raise RuntimeError("AI助手结果未登记")
        completed_at = time.monotonic()
        print("risk-assistant-latency: claim=%.2f search=%.2f local=%.2f completion=%.2f total=%.2f" % (
            claimed_at - started, searched_at - claimed_at, answered_at - searched_at,
            completed_at - answered_at, completed_at - started), file=sys.stderr, flush=True)
        return True

    def process(self, job: dict[str, Any]) -> None:
        save_state(self.state, job)
        stop = threading.Event()
        lease_lost = threading.Event()
        heartbeat = threading.Thread(
            target=self._heartbeat_loop, args=(job, stop, lease_lost), daemon=True)
        heartbeat.start()
        try:
            payload = json.dumps(
                trainer_payload(job), ensure_ascii=False, separators=(",", ":")).encode()
            status, body = request("POST", self.trainer, {
                "Authorization": f"Bearer {self.trainer_token}",
                "Content-Type": "application/json",
            }, payload, 1900)
            if status != 200:
                raise RuntimeError(f"本地 MLX 训练失败 HTTP {status}")
            trained = json.loads(body)
            artifact = Path(trained["artifactReference"])
            content_hash = str(trained["artifactSha256"])
            if not artifact.is_dir() or len(content_hash) != 64:
                raise RuntimeError("本地训练未返回可验证工件")
            bundle = bundle_artifact(artifact)
            bundle_hash = hashlib.sha256(bundle).hexdigest()
            upload_headers = self.cloud_headers() | {
                "Content-Type": "application/octet-stream",
                "X-Risk-Training-Execution-Id": job["executionId"],
                "X-Risk-Training-Run-Id": job["trainingRunId"],
                "X-Risk-Artifact-Sha256": bundle_hash,
                "X-Risk-Artifact-Content-Sha256": content_hash,
            }
            status, body = request(
                "POST", self.cloud + "/artifacts", upload_headers, bundle, 300)
            if status == 409:
                raise ClaimExpired("云端训练租约已过期，训练工件未接收")
            if status != 201:
                raise RuntimeError(f"训练工件上传失败 HTTP {status}")
            stored = json.loads(body)
            self.remember_artifact(stored["artifactReference"], artifact,
                                   content_hash, stored["bundleSha256"])
            if lease_lost.is_set():
                raise ClaimExpired("云端训练租约已经失效，拒绝提交候选模型")
            completion = json.dumps({
                "trainingRunId": job["trainingRunId"],
                "modelVersion": job["modelVersion"],
                "artifactReference": stored["artifactReference"],
                "artifactSha256": stored["bundleSha256"],
                "metrics": trained.get("metrics", {}),
                "thresholds": trained.get("thresholds", {}),
            }, ensure_ascii=False, separators=(",", ":")).encode()
            status, _ = request(
                "POST",
                self.cloud + f"/executions/{job['executionId']}/completion",
                self.cloud_headers() | {"Content-Type": "application/json"},
                completion, 60)
            if status == 409:
                raise ClaimExpired("云端训练租约已过期，候选模型未登记")
            if status != 204:
                raise RuntimeError(f"候选模型登记失败 HTTP {status}")
            self.state.unlink(missing_ok=True)
        finally:
            stop.set()
            heartbeat.join(timeout=2)

    def remember_artifact(self, reference: str, artifact: Path,
                          content_hash: str, bundle_hash: str) -> None:
        index = load_state(self.artifact_index) or {}
        index[reference] = {"path": str(artifact), "contentSha256": content_hash,
                            "bundleSha256": bundle_hash}
        save_state(self.artifact_index, index)

    def resolve_artifact(self, task: dict[str, Any]) -> tuple[Path, str]:
        reference = str(task["artifactReference"])
        bundle_hash = str(task["artifactSha256"])
        index = load_state(self.artifact_index) or {}
        candidate = index.get(reference)
        if candidate:
            path = Path(candidate["path"])
            if (path.is_dir() and candidate.get("bundleSha256") == bundle_hash
                    and canonical_hash(path) == candidate.get("contentSha256")):
                return path, str(candidate["contentSha256"])
        model_root = self.artifact_root / str(task["modelId"])
        for path in sorted(model_root.glob(f"v{int(task['modelVersion'])}-*")):
            if path.is_dir() and hashlib.sha256(bundle_artifact(path)).hexdigest() == bundle_hash:
                content_hash = canonical_hash(path)
                self.remember_artifact(reference, path, content_hash, bundle_hash)
                return path, content_hash
        raise RuntimeError("本地没有与云端候选版本匹配的不可变 LoRA 工件")

    def score_once(self) -> bool:
        status, body = request(
            "POST", self.cloud + "/scoring-claims", self.cloud_headers(), b"", 30)
        if status == 204:
            return False
        if status != 200:
            raise RuntimeError(f"云端影子评分任务领取失败 HTTP {status}")
        task = json.loads(body)
        artifact, content_hash = self.resolve_artifact(task)
        payload = json.dumps({
            "modelId": task["modelId"],
            "modelVersion": task["modelVersion"],
            "baseModelReference": task["baseModelReference"],
            "artifactReference": str(artifact),
            "artifactSha256": content_hash,
            "input": task["input"],
        }, ensure_ascii=False, separators=(",", ":")).encode()
        status, body = request("POST", self.trainer.replace("/v1/train", "/v1/score"), {
            "Authorization": f"Bearer {self.trainer_token}",
            "Content-Type": "application/json",
        }, payload, 300)
        if status != 200:
            raise RuntimeError(f"本地 MLX 影子评分失败 HTTP {status}")
        score = json.loads(body)
        completion = json.dumps({
            "modelId": task["modelId"],
            "modelVersion": task["modelVersion"],
            "assessmentId": task["assessmentId"],
            "artifactReference": task["artifactReference"],
            "artifactSha256": task["artifactSha256"],
            "predictedPositive": bool(score["predictedPositive"]),
            "positiveProbability": float(score["positiveProbability"]),
        }, separators=(",", ":")).encode()
        status, _ = request("POST", self.cloud + "/scoring-completion",
                            self.cloud_headers() | {"Content-Type": "application/json"},
                            completion, 60)
        if status == 409:
            raise ClaimExpired("云端影子评分租约已过期")
        if status != 204:
            raise RuntimeError(f"云端影子评分登记失败 HTTP {status}")
        return True

    def _heartbeat_loop(self, job: dict[str, Any], stop: threading.Event,
                        lease_lost: threading.Event) -> None:
        while not stop.wait(self.heartbeat_seconds):
            body = json.dumps({"trainingRunId": job["trainingRunId"]}).encode()
            try:
                status, _ = request(
                    "POST", self.cloud + f"/executions/{job['executionId']}/heartbeat",
                    self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
                if status != 204:
                    lease_lost.set()
                    return
            except Exception:
                continue

    def run_once(self) -> None:
        job = load_state(self.state) or self.claim()
        if job is None:
            return
        try:
            self.process(job)
        except ClaimExpired:
            self.state.unlink(missing_ok=True)
            raise

    def run_forever(self) -> None:
        while True:
            try:
                for _ in range(4):
                    if not self.assistant_once():
                        break
                self.expert_once()
                self.run_once()
            except Exception as error:
                print(f"risk-training-node: {str(error)[:2000]}", file=sys.stderr, flush=True)
            for _ in range(4):
                try:
                    if not self.score_once():
                        break
                except Exception as error:
                    print(f"risk-training-node-score: {str(error)[:2000]}",
                          file=sys.stderr, flush=True)
                    break
            try:
                refresh_public_knowledge_once(self.public_knowledge,
                                              self.public_knowledge_refresh)
            except Exception as error:
                print(f"risk-training-node-knowledge: {str(error)[:2000]}",
                      file=sys.stderr, flush=True)
            time.sleep(self.poll_seconds)


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"缺少训练节点配置: {name}")
    return value


if __name__ == "__main__":
    Worker().run_forever()
