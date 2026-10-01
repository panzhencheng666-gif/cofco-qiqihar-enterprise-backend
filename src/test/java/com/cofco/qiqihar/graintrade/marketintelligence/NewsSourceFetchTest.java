package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NewsSourceFetchTest {
    static final URI URL=URI.create("https://news.example/article");
    static final NewsPublicTarget.Resolver DNS=host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
    static NewsPinnedHttp.Response response(int status,String location) {
        return new NewsPinnedHttp.Response(status,location,"text/html; charset=UTF-8","<title>Wheat</title>".getBytes());
    }
    @Test void followsApprovedRedirectAndReturnsFinalBody() {
        var client=new NewsSourceFetch(uri -> true,DNS,(target,remaining) ->
            target.uri().equals(URL) ? response(302,"/latest") : response(200,null),Duration.ofSeconds(1));
        var result=client.fetch(URL);
        assertThat(result.reason()).isEqualTo("FETCHED");
        assertThat(result.finalUri()).isEqualTo(URI.create("https://news.example/latest"));
        assertThat(result.body()).isNotEmpty();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).isEqualTo("text/html; charset=UTF-8");
    }
    @Test void policyDenialDoesNotResolveOrSend() {
        var client=new NewsSourceFetch(uri -> false,host -> {throw new AssertionError("must not resolve");},
            (target,remaining) -> {throw new AssertionError("must not send");},Duration.ofSeconds(1));
        assertThat(client.fetch(URL).reason()).isEqualTo("SOURCE_NOT_APPROVED");
    }
    @Test void crossHostRedirectChecksPolicyBeforeDns() {
        var resolutions=new AtomicInteger();
        var client=new NewsSourceFetch(uri -> uri.getHost().equals("news.example"),
            host -> {resolutions.incrementAndGet();return DNS.resolve(host);},
            (target,remaining) -> response(302,"https://other.example/article"),Duration.ofSeconds(1));
        assertThat(client.fetch(URL).reason()).isEqualTo("SOURCE_NOT_APPROVED");
        assertThat(resolutions).hasValue(1);
    }
    @Test void rejectsLoopAndPrivateRedirect() {
        var loop=new NewsSourceFetch(uri -> true,DNS,(target,remaining) -> response(302,"#again"),Duration.ofSeconds(1));
        assertThat(loop.fetch(URL).reason()).isEqualTo("REDIRECT_REJECTED");
        var privateTarget=new NewsSourceFetch(uri -> true,DNS,(target,remaining) ->
            response(302,"https://127.0.0.1/"),Duration.ofSeconds(1));
        assertThat(privateTarget.fetch(URL).reason()).isEqualTo("REDIRECT_REJECTED");
    }
    @Test void boundsTotalRedirectsAndDoesNotRetry429() {
        var calls=new AtomicInteger();
        var redirects=new NewsSourceFetch(uri -> true,DNS,(target,remaining) ->
            response(302,"/hop"+calls.incrementAndGet()),Duration.ofSeconds(1));
        assertThat(redirects.fetch(URL).reason()).isEqualTo("REDIRECT_LIMIT");
        assertThat(calls).hasValue(6);
        calls.set(0);
        var limited=new NewsSourceFetch(uri -> true,DNS,(target,remaining) -> {
            calls.incrementAndGet();return response(429,null);
        },Duration.ofSeconds(1));
        assertThat(limited.fetch(URL).reason()).isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
    }
    @Test void preservesFailureStatusAndRetryAfterWithoutKeepingFailureBody() {
        var client=new NewsSourceFetch(uri -> true,DNS,(target,remaining) ->
            new NewsPinnedHttp.Response(429,null,"text/html",new byte[]{1},
                java.util.Map.of("retry-after","600")),Duration.ofSeconds(1));
        var result=client.fetch(URL);
        assertThat(result.status()).isEqualTo(429);
        assertThat(result.headers()).containsEntry("retry-after","600");
        assertThat(result.body()).isEmpty();
    }
    @Test void redirectsShareOneDeadline() {
        var calls=new AtomicInteger();
        var client=new NewsSourceFetch(uri -> true,DNS,(target,remaining) -> {
            int index=calls.incrementAndGet();
            try {Thread.sleep(80);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            return response(302,"/next"+index);
        },Duration.ofMillis(120));
        assertThat(client.fetch(URL).reason()).isEqualTo("FETCH_TIMEOUT");
        assertThat(calls.get()).isLessThanOrEqualTo(2);
    }
    @Test void saturationFailsFastInsteadOfQueueingMoreDnsJobs() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(4);
        var release=new java.util.concurrent.CountDownLatch(1);
        var client=new NewsSourceFetch(uri -> true,host -> {
            started.countDown();
            try {release.await();} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            return DNS.resolve(host);
        },(target,remaining) -> response(200,null),Duration.ofSeconds(3));
        try(var callers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<NewsSourceFetch.Result>>();
            try {
                for(int i=0;i<4;i++) jobs.add(callers.submit(() -> client.fetch(URL)));
                assertThat(started.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(client.fetch(URL).reason()).isEqualTo("FETCH_BUSY");
            } finally {release.countDown();}
            for(var job:jobs) assertThat(job.get().reason()).isEqualTo("FETCHED");
        }
    }
    @Test void boundsDnsAndDiscardsBinaryOrEmptyResponses() {
        var slow=new NewsSourceFetch(uri -> true,host -> {
            try {Thread.sleep(2000);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            return DNS.resolve(host);
        },(target,remaining) -> response(200,null),Duration.ofMillis(50));
        long start=System.nanoTime();
        assertThat(slow.fetch(URL).reason()).isEqualTo("FETCH_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        var binary=new NewsSourceFetch(uri -> true,DNS,(target,remaining) ->
            new NewsPinnedHttp.Response(200,null,"application/octet-stream",new byte[]{1}),Duration.ofSeconds(1));
        assertThat(binary.fetch(URL).reason()).isEqualTo("UNSUPPORTED_CONTENT");
    }
}
