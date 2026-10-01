package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsPublicationEvidenceTest {
    private static final URI PAGE = URI.create("https://news.example/grain");
    private static final Instant FETCHED = Instant.parse("2026-09-28T15:00:00Z");

    private NewsPublicationEvidence.Result extract(String html) {
        return NewsPublicationEvidence.extract(PAGE, html, FETCHED);
    }

    private String meta(String value) {
        return "<meta property='article:published_time' content='" + value + "'>";
    }

    @Test void offsetTimeConvertsToBeijingDateWithoutChangingInstant() {
        var value = extract(meta("2026-09-27T20:00:00-04:00"));
        assertThat(value.precision()).isEqualTo("INSTANT");
        assertThat(value.publishedAt()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(value.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
    }

    @Test void dateOnlyDoesNotInventMidnightOrTimezone() {
        var value = extract(meta("2026-09-28"));
        assertThat(value.precision()).isEqualTo("DATE");
        assertThat(value.publishedAt()).isNull();
        assertThat(value.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
    }

    @Test void currentArticleJsonLdGraphIgnoresUnrelatedRecommendations() {
        var value = extract("""
            <script type="application/ld+json">{"@graph":[
              {"@type":"NewsArticle","url":"/other","datePublished":"2020-01-01"},
              {"@type":["Article","NewsArticle"],"mainEntityOfPage":{"@id":"/grain"},
               "datePublished":"2026-09-28T10:00:00+08:00"}
            ]}</script>
            """);
        assertThat(value.publishedAt()).isEqualTo(Instant.parse("2026-09-28T02:00:00Z"));
    }

    @Test void unboundJsonLdAndModifiedTimeAreNotPublicationEvidence() {
        var value = extract("""
            <meta property="article:modified_time" content="2026-09-28T12:00:00Z">
            <script type="application/ld+json">
              {"@type":"NewsArticle","datePublished":"2026-09-28"}
            </script><time>2026-09-28</time>
            """);
        assertThat(value.reason()).isEqualTo("MISSING_PUBLICATION");
        assertThat(value.publishedAt()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"2026-02-30", "2026-09-28T10:00:00", "yesterday", ""})
    void invalidOrTimezoneMissingNeedsReview(String value) {
        assertThat(extract(meta(value)).reason()).isEqualTo("INVALID_PUBLICATION");
    }

    @Test void futurePublicationNeedsReview() {
        assertThat(extract(meta("2026-09-28T16:00:00Z")).reason()).isEqualTo("FUTURE_PUBLICATION");
        assertThat(extract(meta("2026-09-30")).reason()).isEqualTo("FUTURE_PUBLICATION");
    }

    @Test void conflictingDatesOrInstantsDoNotSilentlyPickOne() {
        assertThat(extract(meta("2026-09-27") + meta("2026-09-28")).reason())
            .isEqualTo("CONFLICTING_PUBLICATION");
        assertThat(extract(meta("2026-09-28T01:00:00Z") + meta("2026-09-28T02:00:00Z")).reason())
            .isEqualTo("CONFLICTING_PUBLICATION");
    }

    @Test void equivalentOffsetsAgreeAndDateOnlyMatchesSourceLocalDate() {
        var value = extract(meta("2026-09-27") + meta("2026-09-27T20:00:00-04:00")
            + meta("2026-09-28T00:00:00Z"));
        assertThat(value.precision()).isEqualTo("INSTANT");
        assertThat(value.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
    }

    @Test void foreignCanonicalAndOgUrlCannotRebindCurrentPage() {
        assertThat(extract("<link rel='canonical' href='https://other.example/a'>" + meta("2026-09-28"))
            .reason()).isEqualTo("PAGE_IDENTITY_MISMATCH");
        assertThat(extract("<meta property='og:url' content='/other'>" + meta("2026-09-28"))
            .reason()).isEqualTo("PAGE_IDENTITY_MISMATCH");
    }

    @Test void invalidStructuredDataDoesNotFallBackToFetchTime() {
        assertThat(extract("<script type='application/ld+json'>{broken</script>").reason())
            .isEqualTo("INVALID_STRUCTURED_DATA");
    }

    @Test void bodyLimitIsCheckedBeforeParsing() {
        assertThat(extract("x".repeat(2_000_001)).reason()).isEqualTo("INVALID_DOCUMENT");
    }
}
