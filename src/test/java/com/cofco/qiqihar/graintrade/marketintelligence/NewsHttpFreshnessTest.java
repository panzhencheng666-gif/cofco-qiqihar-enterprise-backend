package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsHttpFreshnessTest {
    final Instant start=Instant.parse("2026-09-28T15:50:00Z");
    final AtomicReference<Instant> now=new AtomicReference<>(start);
    final URI url=URI.create("https://news.example/a");
    final AtomicInteger calls=new AtomicInteger();
    final Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now.get();}
    };
    NewsRobotsCache cache(int status,Map<String,String> headers) {
        return new NewsRobotsCache(uri -> {
            calls.incrementAndGet();
            return new NewsSourceFetch.Result(uri.resolve("/robots.txt"),new byte[0],
                "ROBOTS_FETCHED",status,"text/plain",headers);
        },clock);
    }
    @ParameterizedTest @ValueSource(strings={"no-store","no-cache","private","max-age=invalid"})
    void forbiddenOrInvalidCachingCannotRetainAllow(String control) {
        var cache=cache(404,Map.of("cache-control",control));
        cache.refresh(url);
        assertThat(cache.check(url).allowed()).isFalse();
    }
    @Test void maxAgeAccountsForAgeAndExpiresCanShortenIt() {
        var cache=cache(404,Map.of("cache-control","max-age=30","age","20"));
        cache.refresh(url);
        assertThat(cache.check(url).allowed()).isTrue();
        now.set(start.plusSeconds(10));
        assertThat(cache.check(url).allowed()).isFalse();
        now.set(start);
        var expires=cache(404,Map.of("expires",DateTimeFormatter.RFC_1123_DATE_TIME.format(
            start.plusSeconds(5).atZone(ZoneOffset.UTC))));
        expires.refresh(url);
        now.set(start.plusSeconds(5));
        assertThat(expires.check(url).allowed()).isFalse();
    }
    @Test void retryAfterPreventsRefreshForBothDeltaAndHttpDate() {
        for(String value:List.of("600",DateTimeFormatter.RFC_1123_DATE_TIME.format(start.plusSeconds(600).atZone(ZoneOffset.UTC)))) {
            now.set(start); calls.set(0);
            var cache=cache(429,Map.of("retry-after",value));
            cache.refresh(url);
            now.set(start.plusSeconds(61));cache.refresh(url);
            assertThat(calls).hasValue(1);
            assertThat(cache.check(url).allowed()).isFalse();
            now.set(start.plusSeconds(600));cache.refresh(url);
            assertThat(calls).hasValue(2);
        }
    }
    @Test void sourceFetchCarriesOnlyTransportProvidedMetadata() throws Exception {
        var fetch=new NewsSourceFetch(uri -> true,host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")},
            (target,time) -> new NewsPinnedHttp.Response(429,null,"text/plain",new byte[0],Map.of("retry-after","600")),
            Duration.ofSeconds(1));
        assertThat(fetch.fetchRobots(url).headers()).containsEntry("retry-after","600");
    }
}
