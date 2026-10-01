package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Source-declared evidence, never automatic approval to publish. */
final class NewsPublicationEvidence {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    record Result(String precision, Instant publishedAt, LocalDate publishedOn, String reason, List<String> sourceValues) {
        Result { sourceValues = List.copyOf(sourceValues); }
        Result(String precision, Instant publishedAt, LocalDate publishedOn, String reason) {
            this(precision, publishedAt, publishedOn, reason, List.of());
        }
    }

    // Pure parsing of an already bounded, correctly decoded document. No network or script execution.
    // DATE retains the source's calendar date: without a zone it cannot be converted to Beijing.
    static Result extract(URI page, String html, Instant fetchedAt) {
        if (page == null || fetchedAt == null || html == null || html.isBlank()
                || html.length() > 2_000_000) return unknown("INVALID_DOCUMENT");
        page = NewsSearchResults.candidateUri(page.toString());
        if (page == null) return unknown("INVALID_DOCUMENT");
        var document = Jsoup.parse(html, page.toString());
        for (var link : document.select("link[rel~=canonical], meta[property=og:url]")) {
            String identity = link.hasAttr("href") ? link.attr("href") : link.attr("content");
            if (!samePage(page, identity)) return unknown("PAGE_IDENTITY_MISMATCH");
        }
        List<String> values = new ArrayList<>();
        for (var meta : document.select("meta[property=article:published_time]")) {
            values.add(meta.attr("content"));
        }
        if (values.size() > 32) return unknown("TOO_MUCH_EVIDENCE");
        int nodes = 0;
        var scripts = document.select("script[type=application/ld+json]");
        if (scripts.size() > 32) return unknown("TOO_MUCH_EVIDENCE");
        for (var script : scripts) {
            JsonNode root;
            try {
                root = JSON.readTree(script.data());
            } catch (RuntimeException invalid) {
                return unknown("INVALID_STRUCTURED_DATA");
            }
            if (root == null || !(root.isArray() || root.isObject())) return unknown("INVALID_STRUCTURED_DATA");
            var pending = new ArrayDeque<JsonNode>();
            pending.add(root);
            // Only document-level nodes and @graph; never recurse into recommendations/itemListElement.
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (++nodes > 256) return unknown("TOO_MUCH_EVIDENCE");
                if (node.isArray()) {
                    if (node.size() + pending.size() > 256) return unknown("TOO_MUCH_EVIDENCE");
                    node.forEach(pending::addLast);
                } else if (node.isObject()) {
                    if (node.has("@graph")) pending.addLast(node.get("@graph"));
                    if (isArticle(node.get("@type")) && boundToPage(page, node) && node.has("datePublished")) {
                        var published = node.get("datePublished");
                        if (!published.isString()) return unknown("INVALID_PUBLICATION");
                        values.add(published.asString());
                        if (values.size() > 32) return unknown("TOO_MUCH_EVIDENCE");
                    }
                }
            }
        }
        if (values.isEmpty()) return unknown("MISSING_PUBLICATION");
        var dates = new HashSet<LocalDate>();
        var localDates = new HashSet<LocalDate>();
        var instants = new HashSet<Instant>();
        for (String raw : values) {
            String value = raw.strip();
            try {
                if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    LocalDate date = LocalDate.parse(value);
                    // A date may be in UTC+14; no arbitrary default zone is assigned.
                    if (date.isAfter(fetchedAt.atOffset(java.time.ZoneOffset.ofHours(14)).toLocalDate()))
                        return unknown("FUTURE_PUBLICATION");
                    dates.add(date);
                } else {
                    if (value.length() > 64) return unknown("INVALID_PUBLICATION");
                    var time = OffsetDateTime.parse(value);
                    if (time.toInstant().isAfter(fetchedAt)) return unknown("FUTURE_PUBLICATION");
                    instants.add(time.toInstant());
                    localDates.add(time.toLocalDate());
                }
            } catch (java.time.DateTimeException invalid) {
                return unknown("INVALID_PUBLICATION");
            }
        }
        if (dates.size() > 1 || instants.size() > 1
                || (!instants.isEmpty() && !localDates.containsAll(dates)))
            return unknown("CONFLICTING_PUBLICATION");
        if (!instants.isEmpty()) {
            Instant time = instants.iterator().next();
            return new Result("INSTANT", time, time.atZone(BEIJING).toLocalDate(), "SOURCE_DECLARED", values);
        }
        return new Result("DATE", null, dates.iterator().next(), "SOURCE_DECLARED", values);
    }

    private static boolean isArticle(JsonNode type) {
        if (type == null) return false;
        if (type.isArray()) {
            for (var item : type) if (item.isString() && isArticle(item)) return true;
            return false;
        }
        return type.isString() && (type.asString().equals("NewsArticle") || type.asString().equals("Article"));
    }

    private static boolean boundToPage(URI page, JsonNode node) {
        boolean bound = false;
        for (String field : List.of("url", "mainEntityOfPage")) {
            var identity = node.get(field);
            if (identity == null) continue;
            if (identity.isObject()) identity = identity.get("@id");
            if (identity == null || !identity.isString() || !samePage(page, identity.asString())) return false;
            bound = true;
        }
        return bound;
    }

    private static boolean samePage(URI page, String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return false;
        try {
            return page.equals(NewsSearchResults.candidateUri(page.resolve(value.strip()).toString()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Result unknown(String reason) {
        return new Result("UNKNOWN", null, null, reason);
    }
}
