package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses SearXNG or IQS metadata only. Candidates are NOT verified news, sources, or fetch permissions. */
final class NewsSearchResults {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    enum State { EMPTY, CANDIDATES, DEGRADED, FAILED }
    record Candidate(URI url, String title, String searchClaimedDate, Instant discoveredAt) {}
    record Result(State state, List<Candidate> candidates, int failedEngines, int rejectedItems, String reason) {
        Result {
            candidates = List.copyOf(candidates);
        }
    }

    static Result parse(int status, byte[] body, Instant discoveredAt) {
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (status != 200) return failed("HTTP_" + status);
        if (body == null || body.length == 0) return failed("INVALID_RESPONSE");
        if (body.length > 2_000_000) return failed("RESPONSE_TOO_LARGE");
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException invalid) {
            return failed("INVALID_RESPONSE");
        }
        if (root == null || !root.isObject()) {
            return failed("INVALID_RESPONSE");
        }
        boolean iqs = root.has("pageItems");
        // Do not silently choose a provider when contradictory envelopes are supplied.
        if (iqs && root.has("results")) return failed("INVALID_RESPONSE");
        var results = root.path(iqs ? "pageItems" : "results");
        if (!results.isArray()) return failed("INVALID_RESPONSE");
        var failures = root.get("unresponsive_engines");
        if (failures != null && !failures.isArray()) return failed("INVALID_RESPONSE");
        if (results.size() > 100) return failed("TOO_MANY_RESULTS");
        int failedEngines = failures == null ? 0 : failures.size();
        int rejected = 0;
        var candidates = new LinkedHashMap<URI, Candidate>();
        for (var item : results) {
            var title = text(item, "title", 500);
            var url = candidateUri(text(item, iqs ? "link" : "url", 2048));
            if (title == null || url == null) {
                rejected++;
                continue;
            }
            candidates.putIfAbsent(url, new Candidate(url, title,
                    text(item, iqs ? "publishedTime" : "publishedDate", 100), discoveredAt));
        }
        State state = failedEngines > 0 && candidates.isEmpty() ? State.FAILED
                : failedEngines > 0 || rejected > 0 ? State.DEGRADED
                : candidates.isEmpty() ? State.EMPTY : State.CANDIDATES;
        return new Result(state, List.copyOf(candidates.values()), failedEngines, rejected, state.name());
    }

    private static Result failed(String reason) {
        return new Result(State.FAILED, List.of(), 0, 0, reason);
    }

    private static String text(JsonNode node, String key, int limit) {
        var value = node.get(key);
        if (value == null || !value.isString()) return null;
        var text = value.asString().strip();
        return text.isEmpty() || text.length() > limit ? null : text;
    }

    /** Lexical screening only: the eventual fetcher MUST validate DNS and every redirect separately. */
    static URI candidateUri(String value) {
        if (value == null) return null;
        try {
            var uri = URI.create(value);
            var host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            host = host.toLowerCase(Locale.ROOT);
            // Domain names only; reject literal IPs, numeric hosts, and local namespaces.
            if (!host.matches("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}")
                    || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".test")
                    || host.endsWith(".invalid")) return null;
            var path = uri.getRawPath();
            var query = uri.getRawQuery();
            return URI.create("https://" + host + (path.isEmpty() ? "/" : path)
                    + (query == null ? "" : "?" + query)).normalize();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
