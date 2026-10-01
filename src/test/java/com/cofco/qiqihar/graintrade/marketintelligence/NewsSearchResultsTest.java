package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsSearchResultsTest {
    private static final Instant DISCOVERED = Instant.parse("2026-09-28T13:00:00Z");

    @Test
    void parsesIqsCandidatesWithoutPromotingClaimedDatesToVerifiedPublication() {
        var result = NewsSearchResults.parse(200, """
            {"requestId":"redacted-test-id","pageItems":[
              {"title":"秋粮收获","link":"https://news.example/grain#one","publishedTime":"2026-09-28T19:00:00+08:00"},
              {"title":"同一文章","link":"https://news.example/grain#two"},
              {"title":"Wheat supply","link":"https://news.example/wheat","publishedTime":null}
            ]}
            """.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.CANDIDATES);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getFirst().searchClaimedDate()).isEqualTo("2026-09-28T19:00:00+08:00");
        assertThat(result.candidates().getLast().searchClaimedDate()).isNull();
    }

    @Test
    void iqsEmptyResultsAreNotFailedOrSuccessfulDiscovery() {
        assertThat(NewsSearchResults.parse(200, "{\"pageItems\":[]}".getBytes(StandardCharsets.UTF_8), DISCOVERED).state())
            .isEqualTo(NewsSearchResults.State.EMPTY);
    }

    @Test
    void iqsReusesUnsafeUrlAndInvalidRowScreening() {
        var result = NewsSearchResults.parse(200, """
            {"pageItems":[null,{"title":"Grain","link":"http://news.example/a"},
              {"title":"Grain","link":"https://127.0.0.1/a"},
              {"title":"Grain","link":"https://news.example/good"}]}
            """.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.DEGRADED);
        assertThat(result.rejectedItems()).isEqualTo(3);
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void rejectsAmbiguousProviderSchemas() {
        var result = NewsSearchResults.parse(200, "{\"pageItems\":[],\"results\":[]}".getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.FAILED);
    }

    @Test
    void emptyHttpSuccessIsNotCandidateSuccess() {
        var result = NewsSearchResults.parse(200, "{\"results\":[]}".getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.EMPTY);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void preservesSearchDateAsUnverifiedClaimAndDeduplicatesFragments() {
        var body = """
            {"results":[
              {"title":"Wheat supply update","url":"https://news.example/article#one","publishedDate":"2026-09-28"},
              {"title":"Duplicate","url":"https://news.example/article#two"}
            ]}
            """;
        var result = NewsSearchResults.parse(200, body.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.CANDIDATES);
        assertThat(result.candidates()).hasSize(1);
        var item = result.candidates().getFirst();
        assertThat(item.url().toString()).isEqualTo("https://news.example/article");
        assertThat(item.searchClaimedDate()).isEqualTo("2026-09-28");
        assertThat(item.discoveredAt()).isEqualTo(DISCOVERED);
    }

    @Test
    void missingPublicationDateStaysUnknown() {
        var result = NewsSearchResults.parse(200,
            "{\"results\":[{\"title\":\"粮食资讯\",\"url\":\"https://news.example/a\"}]}".getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().searchClaimedDate()).isNull();
    }

    @Test
    void engineFailureIsNotEmptySuccess() {
        var result = NewsSearchResults.parse(200,
            "{\"results\":[],\"unresponsive_engines\":[[\"bing\",\"timeout\"]]}".getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.FAILED);
        assertThat(result.failedEngines()).isEqualTo(1);
    }

    @Test
    void partialEngineFailureKeepsCandidatesButMarksDegraded() {
        var body = """
            {"results":[{"title":"Grain","url":"https://news.example/a"}],
             "unresponsive_engines":[["bing","timeout"]]}
            """;
        var result = NewsSearchResults.parse(200, body.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.DEGRADED);
        assertThat(result.candidates()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"results\":{}}", "{\"results\":[],\"unresponsive_engines\":true}"})
    void rejectsMalformedProviderPayload(String body) {
        assertThat(NewsSearchResults.parse(200, body.getBytes(StandardCharsets.UTF_8), DISCOVERED).state())
            .isEqualTo(NewsSearchResults.State.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://news.example/a", "https://user:pass@news.example/a", "https://127.0.0.1/a", "https://[::1]/a", "https://localhost/a", "https://host.internal/a", "https://news.example:8443/a", "file:///etc/passwd", "https://2130706433/a"})
    void rejectsObviouslyUnsafeCandidatesWithoutFetching(String url) {
        var body = "{\"results\":[{\"title\":\"Grain\",\"url\":\"" + url + "\"}]}";
        var result = NewsSearchResults.parse(200, body.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.rejectedItems()).isEqualTo(1);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.DEGRADED);
    }

    @Test
    void rateLimitAndOversizedPayloadFailClosed() {
        assertThat(NewsSearchResults.parse(429, new byte[0], DISCOVERED).reason()).isEqualTo("HTTP_429");
        assertThat(NewsSearchResults.parse(200, new byte[2_000_001], DISCOVERED).reason()).isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void rejectsBadRowsWithoutDiscardingValidCandidates() {
        var body = """
            {"results":[null,42,{"title":true,"url":"https://news.example/a"},
              {"title":"   ","url":"https://news.example/a"},
              {"title":"Grain","url":"https://NEWS.example:443/a?x=1%202#fragment"}]}
            """;
        var result = NewsSearchResults.parse(200, body.getBytes(StandardCharsets.UTF_8), DISCOVERED);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.DEGRADED);
        assertThat(result.rejectedItems()).isEqualTo(4);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().url().toString()).isEqualTo("https://news.example/a?x=1%202");
    }

    @Test
    void enforcesResultCountAndRequiredFieldBounds() {
        var many = "{\"results\":[" + "{},".repeat(100) + "{}]}";
        assertThat(NewsSearchResults.parse(200, many.getBytes(StandardCharsets.UTF_8), DISCOVERED).reason())
            .isEqualTo("TOO_MANY_RESULTS");
        var longTitle = "{\"results\":[{\"title\":\"" + "a".repeat(501) + "\",\"url\":\"https://news.example/a\"}]}";
        assertThat(NewsSearchResults.parse(200, longTitle.getBytes(StandardCharsets.UTF_8), DISCOVERED).rejectedItems())
            .isEqualTo(1);
        assertThat(NewsSearchResults.parse(200, null, DISCOVERED).state()).isEqualTo(NewsSearchResults.State.FAILED);
    }
}
