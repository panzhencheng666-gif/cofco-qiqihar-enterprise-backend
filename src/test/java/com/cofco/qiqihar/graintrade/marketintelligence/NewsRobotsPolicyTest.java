package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsRobotsPolicyTest {
    static final Instant NOW=Instant.parse("2026-09-28T15:40:00Z");
    static final URI ROBOTS=URI.create("https://news.example/robots.txt");
    NewsRobotsPolicy.Decision rule(String path,String text) {
        return NewsRobotsPolicy.evaluate(URI.create("https://news.example"+path),ROBOTS,200,
            text.getBytes(StandardCharsets.UTF_8),"text/plain",NOW,NOW);
    }
    @Test void exactAgentOverridesWildcardAndMatchingGroupsMerge() {
        String text="User-agent: *\nDisallow: /\nUser-agent: QiliangNewsDiscovery\nDisallow: /private\n"
            +"User-agent: qiliangnewsdiscovery\nDisallow: /draft\n";
        assertThat(rule("/public",text).allowed()).isTrue();
        assertThat(rule("/private/a",text).reason()).isEqualTo("ROBOTS_DISALLOWED");
        assertThat(rule("/draft/a",text).allowed()).isFalse();
    }
    @Test void longestRuleAllowTieWildcardEndAndEncodedPath() {
        String text="User-agent: *\nDisallow: /private\nAllow: /private/public\nDisallow: /*.pdf$\n";
        assertThat(rule("/private/public/a",text).allowed()).isTrue();
        assertThat(rule("/private/a",text).allowed()).isFalse();
        assertThat(rule("/file.pdf",text).allowed()).isFalse();
        assertThat(rule("/file.pdf/other",text).allowed()).isTrue();
        assertThat(rule("/%70rivate/a",text).allowed()).isFalse();
        assertThat(rule("/same","User-agent: *\nAllow: /same\nDisallow: /same\n").allowed()).isTrue();
    }
    @Test void returnsCrawlDelayForSchedulerRatherThanSleeping() {
        assertThat(rule("/public","User-agent: *\nCrawl-delay: 10\n").crawlDelayMillis()).isEqualTo(10000);
    }
    @ParameterizedTest @ValueSource(ints={301,401,403,429,500,503})
    void failsClosedForRedirectsAndAccessOrServerErrors(int status) {
        assertThat(NewsRobotsPolicy.evaluate(URI.create("https://news.example/a"),ROBOTS,status,
            new byte[0],"text/plain",NOW,NOW).reason()).isEqualTo("ROBOTS_UNAVAILABLE");
    }
    @Test void notFoundMeansNoRobotsRuleNotContentAuthorization() {
        assertThat(NewsRobotsPolicy.evaluate(URI.create("https://news.example/a"),ROBOTS,404,
            new byte[0],"text/plain",NOW,NOW).reason()).isEqualTo("ROBOTS_ABSENT");
    }
    @Test void staleFutureAndWrongOriginCannotAuthorize() {
        for(var fetched: new Instant[]{NOW.minusSeconds(86401),NOW.plusSeconds(1)}) {
            assertThat(NewsRobotsPolicy.evaluate(URI.create("https://news.example/a"),ROBOTS,404,
                new byte[0],"text/plain",fetched,NOW).reason()).isEqualTo("ROBOTS_STALE");
        }
        assertThat(NewsRobotsPolicy.evaluate(URI.create("https://other.example/a"),ROBOTS,404,
            new byte[0],"text/plain",NOW,NOW).reason()).isEqualTo("ROBOTS_SCOPE_MISMATCH");
    }
    @Test void htmlInvalidUtf8AndOversizedFileCannotBecomeAllowAll() {
        for(byte[] bytes:new byte[][]{"<html>captcha</html>".getBytes(StandardCharsets.UTF_8),
                {(byte)0xc3,(byte)0x28},new byte[512001]}) {
            assertThat(NewsRobotsPolicy.evaluate(URI.create("https://news.example/a"),ROBOTS,200,
                bytes,"text/plain",NOW,NOW).reason()).isEqualTo("ROBOTS_INVALID");
        }
        assertThat(NewsRobotsPolicy.evaluate(URI.create("https://news.example/a"),ROBOTS,200,
            new byte[0],"text/html",NOW,NOW).reason()).isEqualTo("ROBOTS_INVALID");
    }
    @Test void emptyRulesAreAllowedButMalformedRecognizedRuleNeedsReview() {
        assertThat(rule("/a","").reason()).isEqualTo("ROBOTS_ALLOWED");
        assertThat(rule("/a","User-agent: *\nCrawl-delay: invalid\n").reason()).isEqualTo("ROBOTS_INVALID");
    }
}
