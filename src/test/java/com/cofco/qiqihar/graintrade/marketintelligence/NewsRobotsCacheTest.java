package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class NewsRobotsCacheTest {
    static final URI ARTICLE=URI.create("https://news.example/a");
    static final Instant NOW=Instant.parse("2026-09-28T15:45:00Z");
    @Test void robotsDownloadPreserves404AndEmpty200WithoutRelaxingArticleFetch() {
        var code=new AtomicInteger(404);
        var fetch=new NewsSourceFetch(uri -> true,host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
            (target,time) -> {
                assertThat(target.uri().getPath()).isEqualTo("/robots.txt");
                return new NewsPinnedHttp.Response(code.get(),null,"text/plain",new byte[0]);
            },Duration.ofSeconds(1));
        assertThat(fetch.fetchRobots(ARTICLE).status()).isEqualTo(404);
        code.set(200);
        assertThat(fetch.fetchRobots(ARTICLE).reason()).isEqualTo("ROBOTS_FETCHED");
        assertThat(fetch.fetch(ARTICLE.resolve("/robots.txt")).reason()).isEqualTo("UNSUPPORTED_CONTENT");
    }
    @Test void redirectsStillRequireApprovedPublicTargetAndRulesAreSizeBounded() {
        var fetch=new NewsSourceFetch(uri -> true,host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
            (target,time) -> new NewsPinnedHttp.Response(302,"https://127.0.0.1/","text/plain",new byte[0]),Duration.ofSeconds(1));
        assertThat(fetch.fetchRobots(ARTICLE).reason()).isEqualTo("REDIRECT_REJECTED");
        var large=new NewsSourceFetch(uri -> true,host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
            (target,time) -> new NewsPinnedHttp.Response(200,null,"text/plain",new byte[512001]),Duration.ofSeconds(1));
        assertThat(large.fetchRobots(ARTICLE).reason()).isEqualTo("UNSUPPORTED_CONTENT");
    }
    @Test void missingCacheDeniesAndSameOriginReusesRulesWithoutNetworkInCheck() {
        var calls=new AtomicInteger();
        var cache=new NewsRobotsCache(uri -> {
            calls.incrementAndGet();
            return new NewsSourceFetch.Result(uri.resolve("/robots.txt"),
                "User-agent: *\nDisallow: /private\n".getBytes(),"ROBOTS_FETCHED",200,"text/plain");
        },Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(cache.check(ARTICLE).reason()).isEqualTo("ROBOTS_NOT_CACHED");
        assertThat(calls).hasValue(0);
        cache.refresh(ARTICLE);
        cache.refresh(ARTICLE.resolve("/b"));
        assertThat(cache.check(ARTICLE).allowed()).isTrue();
        assertThat(cache.check(ARTICLE.resolve("/private/a")).allowed()).isFalse();
        assertThat(calls).hasValue(1);
    }
    @Test void cacheHasFiniteOriginCapacity() {
        var cache=new NewsRobotsCache(uri -> new NewsSourceFetch.Result(uri.resolve("/robots.txt"),
            new byte[0],"ROBOTS_FETCHED",404,"text/plain"),Clock.fixed(NOW,ZoneOffset.UTC));
        var urls=new java.util.ArrayList<URI>();
        for(int i=0;i<33;i++) {
            var uri=URI.create("https://source"+i+".example/article");
            urls.add(uri); cache.refresh(uri);
        }
        assertThat(urls.stream().filter(uri -> cache.check(uri).allowed()).count()).isEqualTo(32);
    }
    @Test void expiryDeniesUntilRefreshAndFailuresDoNotRetainOldAllow() {
        var now=new AtomicReference<>(NOW);
        var clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var code=new AtomicInteger(404);
        var calls=new AtomicInteger();
        var cache=new NewsRobotsCache(uri -> {
            calls.incrementAndGet();
            return new NewsSourceFetch.Result(uri.resolve("/robots.txt"),new byte[0],"ROBOTS_FETCHED",code.get(),"text/plain");
        },clock);
        cache.refresh(ARTICLE);
        assertThat(cache.check(ARTICLE).allowed()).isTrue();
        now.set(NOW.plusSeconds(3601));
        assertThat(cache.check(ARTICLE).reason()).isEqualTo("ROBOTS_NOT_CACHED");
        code.set(503);
        cache.refresh(ARTICLE);
        assertThat(cache.check(ARTICLE).allowed()).isFalse();
        cache.refresh(ARTICLE);
        assertThat(calls).hasValue(2);
        now.set(now.get().plusSeconds(61));
        cache.refresh(ARTICLE);
        assertThat(calls).hasValue(3);
    }
}
