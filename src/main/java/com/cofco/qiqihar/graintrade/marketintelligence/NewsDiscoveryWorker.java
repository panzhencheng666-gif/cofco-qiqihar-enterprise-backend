package com.cofco.qiqihar.graintrade.marketintelligence;

import java.time.Clock;
import javax.sql.DataSource;
import java.net.URI;
import java.net.InetAddress;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Optional;
import java.util.Objects;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bounded one-request worker; no automatic scheduling or source-permission creation. */
final class NewsDiscoveryWorker {
    private final JdbcClient jdbc;
    private final NewsDiscoveryQueue queue;
    private final Clock clock;
    private final NewsPublicTarget.Resolver dns;
    private final NewsSourceFetch.Sender sender;
    private final NewsRobotsCache robots;
    private record Outcome(NewsCandidateReview.Decision decision, Instant retry, long delay) {}

    NewsDiscoveryWorker(DataSource ds, Clock clock) {
        this(ds,clock,InetAddress::getAllByName,NewsPinnedHttp::send);
    }
    NewsDiscoveryWorker(DataSource ds, Clock clock, NewsPublicTarget.Resolver dns, NewsSourceFetch.Sender sender) {
        this.jdbc=JdbcClient.create(ds);
        this.queue=new NewsDiscoveryQueue(ds);
        this.clock=Objects.requireNonNull(clock);
        this.dns=Objects.requireNonNull(dns);
        this.sender=Objects.requireNonNull(sender);
        this.robots=new NewsRobotsCache(page -> client(page,true).fetchRobots(page),clock);
    }
    String runOnce() {
        var claimed=queue.claim(clock.instant());
        if(claimed.isEmpty()) return "IDLE";
        var lease=claimed.get();
        Outcome outcome;
        try {
            outcome=process(lease);
        } catch(RuntimeException failure) {
            // No source response, credentials, raw exception, or SQL text enters the review record.
            outcome=new Outcome(pending(lease,"WORKER_FAILED"),clock.instant().plusSeconds(60),0);
        }
        boolean saved=queue.complete(lease,outcome.decision(),clock.instant(),outcome.retry(),outcome.delay());
        return !saved?"STALE":outcome.decision().state().equals("VERIFIED")?"VERIFIED":"DEFERRED";
    }
    private Outcome process(NewsDiscoveryQueue.Lease lease) {
        URI page=URI.create(lease.candidate().url());
        if(admission(page).isEmpty()) return new Outcome(pending(lease,"SOURCE_ADMISSION_REQUIRED"),null,0);
        var rules=robots.check(page);
        if(rules.reason().equals("ROBOTS_NOT_CACHED")) {
            robots.refresh(page);
            rules=robots.check(page);
            // One network request per turn: wait durably before the article, even when rules allow it.
            return new Outcome(pending(lease,rules.allowed()?"ROBOTS_READY":rules.reason()),
                robots.retryNotBefore(page),rules.crawlDelayMillis());
        }
        if(!rules.allowed()) return new Outcome(pending(lease,rules.reason()),
            robots.retryNotBefore(page),rules.crawlDelayMillis());
        var fetched=client(page,false).fetch(page);
        Instant now=clock.instant();
        var permit=admission(page); // Check expiry/revocation again after network work.
        if(permit.isEmpty()) return new Outcome(pending(lease,"SOURCE_ADMISSION_REQUIRED"),null,rules.crawlDelayMillis());
        if(!fetched.reason().equals("FETCHED"))
            return new Outcome(pending(lease,"ARTICLE_FETCH_FAILED"),
                NewsRobotsCache.retryUntil(fetched.headers(),now),rules.crawlDelayMillis());
        var decision=NewsCandidateReview.evaluate(lease.candidate(),fetched,permit.get(),rules,now);
        return new Outcome(decision,null,rules.crawlDelayMillis());
    }
    private NewsSourceFetch client(URI page, boolean forRobots) {
        URI exact=forRobots?page.resolve("/robots.txt"):page;
        // Even same-origin redirects are deferred for now: following them would be another request
        // without a persisted inter-request delay. Never send to another host under this lease.
        return new NewsSourceFetch(uri -> uri.equals(exact) && admission(page).isPresent()
            && (forRobots || robots.check(uri).allowed()),dns,sender,Duration.ofSeconds(15),false);
    }
    private Optional<NewsCandidateReview.Admission> admission(URI page) {
        var normalized=NewsSearchResults.candidateUri(page.toString());
        if(normalized==null) return Optional.empty();
        var origin=normalized.resolve("/");
        return jdbc.sql("""
            SELECT origin_uri,review_reference,checked_at,expires_at,metadata_display_allowed
            FROM market_intelligence.news_discovery_source_admission
            WHERE origin_uri=:origin AND fetch_allowed=true AND metadata_display_allowed=true
              AND checked_at<=:now AND expires_at>:now
            """).param("origin",origin.toString()).param("now",clock.instant().atOffset(ZoneOffset.UTC))
            .query((rs,row) -> new NewsCandidateReview.Admission(URI.create(rs.getString("origin_uri")),
                rs.getString("review_reference"),rs.getObject("checked_at",OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at",OffsetDateTime.class).toInstant(),rs.getBoolean("metadata_display_allowed")))
            .optional();
    }
    private static NewsCandidateReview.Decision pending(NewsDiscoveryQueue.Lease lease,String reason) {
        return new NewsCandidateReview.Decision("PENDING_VERIFICATION",reason,
            Map.of("articleUrl",lease.candidate().url(),"ruleVersion","news-worker-v1"));
    }
}
