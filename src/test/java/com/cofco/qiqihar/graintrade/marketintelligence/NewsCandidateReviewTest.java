package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewsCandidateReviewTest {
    static final Instant NOW = Instant.parse("2026-09-28T16:00:00Z");
    static final URI PAGE = URI.create("https://news.example/grain");
    static final String HTML = """
        <meta property="article:published_time" content="2026-09-28">
        <article><h1>Wheat harvest update</h1><p>Wheat production and grain supply rose
        after the harvest. Farmers reported updated crop estimates for the region.</p></article>
        """;
    static NewsDiscoveryRepository.Candidate candidate() {
        return new NewsDiscoveryRepository.Candidate(PAGE.toString(), "Untrusted search title",
            "2020-01-01", NOW.minusSeconds(60), NOW.minusSeconds(60), "PENDING_VERIFICATION");
    }
    static NewsCandidateReview.Admission admission() {
        return new NewsCandidateReview.Admission(URI.create("https://news.example"),
            "source-review:test-fixture-only", NOW.minusSeconds(120), NOW.plusSeconds(120), true);
    }
    static NewsSourceFetch.Result fetched(String html) {
        return new NewsSourceFetch.Result(PAGE, html.getBytes(StandardCharsets.UTF_8),
            "FETCHED", 200, "text/html; charset=utf-8", Map.of());
    }
    static NewsCandidateReview.Decision evaluate(String html) {
        return NewsCandidateReview.evaluate(candidate(), fetched(html), admission(),
            new NewsRobotsPolicy.Decision(true, "ROBOTS_ALLOWED", 0), NOW);
    }
    @Test void combinesPageEvidenceAndSourceAdmissionWithoutUsingSearchDate() {
        var decision = evaluate(HTML);
        assertThat(decision.state()).isEqualTo("VERIFIED");
        assertThat(decision.evidence()).containsEntry("precision", "DATE")
            .containsEntry("publishedOn", "2026-09-28").containsEntry("title", "Wheat harvest update");
        assertThat(decision.evidence()).doesNotContainKey("publishedAt");
        assertThat(decision.evidence().get("documentSha256").toString()).matches("[a-f0-9]{64}");
        assertThat(decision.evidence().get("sourceValues")).isEqualTo(java.util.List.of("2026-09-28"));
    }
    @Test void missingExpiredOrWrongOriginAdmissionNeverVerifies() {
        var robots = new NewsRobotsPolicy.Decision(true, "ROBOTS_ALLOWED", 0);
        for (var permit : java.util.Arrays.asList(null,
                new NewsCandidateReview.Admission(URI.create("https://other.example"), "test", NOW, NOW.plusSeconds(5), true),
                new NewsCandidateReview.Admission(URI.create("https://news.example"), "test", NOW.minusSeconds(60), NOW, true),
                new NewsCandidateReview.Admission(URI.create("https://news.example"), "test", NOW, NOW.plusSeconds(5), false))) {
            assertThat(NewsCandidateReview.evaluate(candidate(), fetched(HTML), permit, robots, NOW).reason())
                .isEqualTo("SOURCE_ADMISSION_REQUIRED");
        }
    }
    @Test void robotsDenialOrDownloadFailureNeverVerifies() {
        assertThat(NewsCandidateReview.evaluate(candidate(), fetched(HTML), admission(),
            new NewsRobotsPolicy.Decision(false, "ROBOTS_DENIED", 0), NOW).reason()).isEqualTo("ROBOTS_NOT_ALLOWED");
        var failed = new NewsSourceFetch.Result(PAGE, new byte[0], "FETCH_TIMEOUT");
        assertThat(NewsCandidateReview.evaluate(candidate(), failed, admission(),
            new NewsRobotsPolicy.Decision(true, "ROBOTS_ALLOWED", 0), NOW).reason()).isEqualTo("FETCH_NOT_READY");
    }
    @Test void navigationKeywordsDoNotMakeUnrelatedArticleRelevant() {
        assertThat(evaluate(HTML.replace("Wheat harvest update", "Celebrity news")
            .replace("Wheat production and grain supply rose", "A famous performer appeared")
            .replace("after the harvest. Farmers reported updated crop estimates for the region.",
                "at the latest entertainment awards ceremony with many invited guests.")
            + "<nav>wheat grain corn soybean</nav>").reason()).isEqualTo("TOPIC_UNCONFIRMED");
    }
    @Test void missingPublicationStaysPendingWithHashNotSearchTime() {
        var result = evaluate(HTML.replace("article:published_time", "article:modified_time"));
        assertThat(result.reason()).isEqualTo("MISSING_PUBLICATION");
        assertThat(result.evidence()).containsKey("documentSha256").doesNotContainKey("publishedAt");
    }
    @Test void invalidUtf8IsNotSilentlyReplaced() {
        var bad = new NewsSourceFetch.Result(PAGE, new byte[]{(byte)0xc3,0x28}, "FETCHED",200,"text/html",Map.of());
        assertThat(NewsCandidateReview.evaluate(candidate(), bad, admission(),
            new NewsRobotsPolicy.Decision(true,"ROBOTS_ALLOWED",0), NOW).reason()).isEqualTo("UNSUPPORTED_ENCODING");
    }
    @Test void onlyClearlyIdentifiedSingleArticleIsProcessed() {
        assertThat(evaluate(HTML + "<article><h1>Another grain report</h1></article>").reason())
            .isEqualTo("ARTICLE_BODY_UNCONFIRMED");
    }
}
