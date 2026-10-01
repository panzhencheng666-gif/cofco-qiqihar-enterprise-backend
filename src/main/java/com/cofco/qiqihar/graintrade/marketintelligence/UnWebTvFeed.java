package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.URI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed public metadata inputs only. No redirects, source scripts, credentials, or media proxy. */
@Component
@Profile("!test")
@ConditionalOnProperty(name="qiqihar.market-intelligence.un-webtv.enabled",havingValue="true",matchIfMissing=false)
final class UnWebTvFeed {
    private static final URI CALENDAR=URI.create("https://ics.teamup.com/feed/ksf6ikyw6jst7sqii1/3807976.ics");
    private static final URI PROGRAMMES=URI.create("https://webtv.un.org/en/schedule");
    private final UnWebTvRepository repository;
    private final JdbcTemplate sql;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    interface Fetcher {String get(URI uri,int limit) throws IOException,InterruptedException;}
    UnWebTvFeed(UnWebTvRepository repository,JdbcTemplate sql) {this.repository=repository;this.sql=sql;}

    static UnWebTvRepository.Snapshot acquire(Fetcher fetcher) throws IOException,InterruptedException {
        var calendar=UnWebTvScheduleParser.parse(fetcher.get(CALENDAR,8_000_000));
        var programmes=UnWebTvProgrammeParser.parse(fetcher.get(PROGRAMMES,2_000_000));
        return new UnWebTvRepository.Snapshot(calendar,programmes);
    }

    @Scheduled(scheduler="officialNewsScheduler",
            initialDelayString="${qiqihar.market-intelligence.un-webtv.initial-delay:30s}",
            fixedDelayString="${qiqihar.market-intelligence.un-webtv.refresh-delay:1m}")
    public void refresh() {
        Instant at=Instant.now();
        try {
            repository.refresh(()->{
                try {return acquire(this::fetch);}
                catch(IOException e) {throw new UncheckedIOException(e);}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("Webcast acquisition interrupted");}
            },at);
        } catch(RuntimeException failure) {
            LoggerFactory.getLogger(UnWebTvFeed.class).warn("UN Web TV refresh failed: {}",failure.getClass().getSimpleName());
            var time=OffsetDateTime.ofInstant(at,ZoneOffset.UTC);
            sql.update("""
                    INSERT INTO market_intelligence.source_sync_state(source_code,last_attempt_at,last_error)
                    VALUES ('un-webtv',?,?) ON CONFLICT(source_code) DO UPDATE SET
                        last_attempt_at=EXCLUDED.last_attempt_at,last_error=EXCLUDED.last_error
                    WHERE source_sync_state.last_attempt_at IS NULL OR source_sync_state.last_attempt_at<=EXCLUDED.last_attempt_at
                    """,time,failure.getClass().getSimpleName());
        }
    }

    private String fetch(URI uri,int limit) throws IOException,InterruptedException {
        if(!uri.equals(CALENDAR) && !uri.equals(PROGRAMMES)) throw new IOException("Unsupported webcast input");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("User-Agent","QiLiang-MarketIntelligence/1.0 (+official webcast metadata)").GET().build();
        var response=http.send(request,info->new LimitedBody(info.statusCode()==200?limit:0));
        if(response.statusCode()!=200 || !response.uri().equals(uri)) throw new IOException("Webcast source unavailable");
        return new String(response.body(),StandardCharsets.UTF_8);
    }

    /** Cancels before accumulating an oversized body; HttpRequest timeout covers full body completion. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private int received;
        private Flow.Subscription subscription;
        private boolean done;
        LimitedBody(int limit) {this.limit=limit;}
        public CompletionStage<byte[]> getBody() {return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> buffers) {
            if(done) return;
            long size=buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if(size>limit-received) {
                done=true;subscription.cancel();delegate.onError(new IOException("Webcast response exceeds size limit"));return;
            }
            received+=(int)size;delegate.onNext(buffers);
        }
        public void onError(Throwable error) {if(!done) {done=true;delegate.onError(error);}}
        public void onComplete() {if(!done) {done=true;delegate.onComplete();}}
    }
}
