package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HexFormat;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Package-private trusted workflow; never accept Admission from a public request or fetched page. */
final class NewsCandidateReview {
    private static final Pattern TOPIC = Pattern.compile(
        "(?i)\\b(wheat|grain|corn|maize|soybeans?|rice|barley|sorghum|agricultur\\w*)\\b|粮食|小麦|大豆|玉米|水稻|稻米|油籽|农业");
    record Admission(URI origin, String reference, Instant checkedAt, Instant expiresAt,
                     boolean metadataDisplayAllowed) {}
    record Decision(String state, String reason, Map<String, Object> evidence) {
        Decision { evidence = Map.copyOf(evidence); }
    }
    static Decision evaluate(NewsDiscoveryRepository.Candidate candidate, NewsSourceFetch.Result fetched,
                             Admission admission, NewsRobotsPolicy.Decision robots, Instant reviewedAt) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("ruleVersion", "news-metadata-review-v1");
        if (candidate == null || reviewedAt == null || candidate.url() == null
                || NewsSearchResults.candidateUri(candidate.url()) == null)
            return pending("INVALID_CANDIDATE", evidence);
        evidence.put("articleUrl", candidate.url());
        evidence.put("reviewedAt", reviewedAt.toString());
        if (fetched == null || !"FETCHED".equals(fetched.reason()) || fetched.status() != 200
                || fetched.finalUri() == null || fetched.body() == null || fetched.body().length == 0
                || fetched.body().length > 2_000_000)
            return pending("FETCH_NOT_READY", evidence);
        var page = NewsSearchResults.candidateUri(fetched.finalUri().toString());
        if (page == null) return pending("FETCH_NOT_READY", evidence);
        if (!admitted(admission, page, reviewedAt)) return pending("SOURCE_ADMISSION_REQUIRED", evidence);
        evidence.put("admissionReference", admission.reference());
        evidence.put("admissionCheckedAt", admission.checkedAt().toString());
        evidence.put("admissionExpiresAt", admission.expiresAt().toString());
        evidence.put("finalUrl", page.toString());
        if (robots == null || !robots.allowed()) return pending("ROBOTS_NOT_ALLOWED", evidence);
        evidence.put("robotsReason", robots.reason());
        String contentType = fetched.contentType() == null ? "" : fetched.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.split(";", 2)[0].strip().equals("text/html"))
            return pending("UNSUPPORTED_DOCUMENT", evidence);
        // Deliberately support only UTF-8 now. Do not silently misdecode a legacy source.
        for (String part : contentType.split(";")) {
            if (part.strip().startsWith("charset=")
                    && !part.strip().substring(8).replace("\"", "").equals("utf-8"))
                return pending("UNSUPPORTED_ENCODING", evidence);
        }
        String html;
        try {
            html = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(fetched.body())).toString();
            evidence.put("documentSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fetched.body())));
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return pending("UNSUPPORTED_ENCODING", evidence);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required digest unavailable");
        }
        var publication = NewsPublicationEvidence.extract(page, html, reviewedAt);
        if (!"SOURCE_DECLARED".equals(publication.reason())) return pending(publication.reason(), evidence);
        evidence.put("precision", publication.precision());
        evidence.put("publishedOn", publication.publishedOn().toString());
        evidence.put("sourceValues", publication.sourceValues());
        if (publication.publishedAt() != null) evidence.put("publishedAt", publication.publishedAt().toString());
        var document = Jsoup.parse(html, page.toString());
        document.select("nav,footer,aside,script,style,template,[hidden],[aria-hidden=true]").remove();
        var articles = document.select("article");
        if (articles.size() != 1) return pending("ARTICLE_BODY_UNCONFIRMED", evidence);
        var article = articles.first();
        var headings = article.select("h1");
        if (headings.size() != 1) return pending("ARTICLE_BODY_UNCONFIRMED", evidence);
        String title = headings.first().text().strip();
        headings.remove();
        String body = article.text();
        if (title.isEmpty() || title.length() > 500 || body.length() < 80)
            return pending("ARTICLE_BODY_UNCONFIRMED", evidence);
        evidence.put("title", title);
        evidence.put("bodyCharacters", body.length());
        if (!TOPIC.matcher(title).find() || !TOPIC.matcher(body).find())
            return pending("TOPIC_UNCONFIRMED", evidence);
        // VERIFIED means these metadata gates passed, not truth, full-text rights, or media rights.
        return new Decision("VERIFIED", "METADATA_GATES_PASSED", evidence);
    }

    private static boolean admitted(Admission admission, URI page, Instant now) {
        if (admission == null || admission.origin() == null || admission.reference() == null
                || admission.reference().isBlank() || admission.reference().length() > 500
                || admission.checkedAt() == null || admission.expiresAt() == null
                || admission.checkedAt().isAfter(now) || !admission.expiresAt().isAfter(now)
                || !admission.metadataDisplayAllowed()) return false;
        var origin = admission.origin();
        var normalized = NewsSearchResults.candidateUri(origin.toString());
        return normalized != null && normalized.resolve("/").equals(page.resolve("/"))
            && (origin.getPath().isEmpty() || origin.getPath().equals("/"))
            && origin.getQuery() == null && origin.getFragment() == null;
    }

    private static Decision pending(String reason, Map<String, Object> evidence) {
        return new Decision("PENDING_VERIFICATION", reason, evidence);
    }
}
