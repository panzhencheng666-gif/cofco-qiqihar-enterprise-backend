package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.http.HttpRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Request construction only; no transport or credential retrieval. */
final class IqsSearchRequest {
    private static final String HOST = "iqs.cn-zhangjiakou.aliyuncs.com";
    private static final String PATH = "/linked-retrieval/linked-retrieval-entry/v1/iqs/search/unified";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    record Credentials(String id, String secret, String token, Instant expiresAt) {
        Credentials {
            if (!validHeader(id) || !validHeader(secret) || !validHeader(token))
                throw new IllegalArgumentException("Invalid credential fields");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    static String authorization(String path, String query, Map<String, String> headers, String id, String secret) {
        var sorted = new TreeMap<>(headers);
        var names = String.join(";", sorted.keySet());
        var canonicalHeaders = new StringBuilder();
        sorted.forEach((key, value) -> canonicalHeaders.append(key).append(':').append(value.strip()).append('\n'));
        var canonical = String.join("\n", "POST", path, query, canonicalHeaders.toString(), names,
                headers.get("x-acs-content-sha256"));
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var signature = HexFormat.of().formatHex(mac.doFinal(("ACS3-HMAC-SHA256\n"
                    + hash(canonical.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8)));
            return "ACS3-HMAC-SHA256 Credential=" + id + ",SignedHeaders=" + names + ",Signature=" + signature;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Signing unavailable");
        }
    }
    static HttpRequest create(Credentials credentials, String engine, String query, Instant now, String nonce) {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(now, "now");
        if (!credentials.expiresAt().isAfter(now.plusSeconds(60)))
            throw new IllegalArgumentException("Expired or nearly expired credentials");
        if (nonce == null || !nonce.matches("[A-Za-z0-9-]{1,80}"))
            throw new IllegalArgumentException("Invalid nonce");
        var body = payload(engine, query).getBytes(StandardCharsets.UTF_8);
        var headers = new TreeMap<String, String>();
        headers.put("host", HOST);
        headers.put("content-type", "application/json");
        headers.put("x-acs-action", "UnifiedSearch");
        headers.put("x-acs-version", "2024-11-11");
        headers.put("x-acs-date", now.truncatedTo(ChronoUnit.SECONDS).toString());
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-content-sha256", hash(body));
        headers.put("x-acs-security-token", credentials.token());
        var auth = authorization(PATH, "", headers, credentials.id(), credentials.secret());
        var builder = HttpRequest.newBuilder(URI.create("https://" + HOST + PATH))
            .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        // HttpClient supplies Host from the fixed URI; setting Host explicitly is prohibited.
        headers.forEach((key, value) -> { if (!key.equals("host")) builder.header(key, value); });
        return builder.header("Authorization", auth).build();
    }
    static String payload(String engine, String query) {
        if (!("CNLiteBasic".equals(engine) || "GlobalAdvanced".equals(engine)))
            throw new IllegalArgumentException("Unsupported search engine");
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 100
                || query.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid search query");
        var data = new LinkedHashMap<String, Object>();
        data.put("engineType", engine);
        data.put("query", query);
        data.put("numResults", 10);
        data.put("contents", Map.of("summary", false, "mainText", false, "markdownText", false, "richMainBody", false));
        if (engine.equals("CNLiteBasic")) data.put("timeRange", "OneDay");
        return JSON.writeValueAsString(data);
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("Hash unavailable"); }
    }
    private static boolean validHeader(String value) {
        return value != null && !value.isBlank() && value.length() <= 16384
            && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
}
