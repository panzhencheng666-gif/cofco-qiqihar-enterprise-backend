package com.cofco.qiqihar.graintrade.marketintelligence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable per-host scheduling. Not a source grant or an automatically enabled collector. */
final class NewsDiscoveryQueue {
    record Lease(UUID token, String host, NewsDiscoveryRepository.Candidate candidate, Instant until) {}
    private final JdbcClient jdbc;
    private final NewsDiscoveryRepository repository;
    private final TransactionTemplate transaction;
    NewsDiscoveryQueue(DataSource dataSource) {
        jdbc = JdbcClient.create(Objects.requireNonNull(dataSource));
        repository = new NewsDiscoveryRepository(jdbc);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setTimeout(10);
    }
    Optional<Lease> claim(Instant now) {
        Objects.requireNonNull(now);
        return transaction.execute(status -> {
            jdbc.sql("""
                INSERT INTO market_intelligence.news_discovery_host_schedule(source_host)
                SELECT DISTINCT source_host FROM market_intelligence.news_discovery_candidate
                WHERE review_state='PENDING_VERIFICATION'
                ON CONFLICT DO NOTHING
                """).update();
            var host = jdbc.sql("""
                SELECT s.source_host FROM market_intelligence.news_discovery_host_schedule s
                WHERE s.next_attempt_at<=:now AND s.lease_until<=:now
                  AND EXISTS (SELECT 1 FROM market_intelligence.news_discovery_candidate c
                    WHERE c.source_host=s.source_host AND c.review_state='PENDING_VERIFICATION'
                      AND c.next_attempt_at<=:now)
                ORDER BY s.next_attempt_at,s.source_host
                LIMIT 1 FOR UPDATE OF s SKIP LOCKED
                """).param("now", time(now)).query(String.class).optional();
            if (host.isEmpty()) return Optional.empty();
            var candidate = jdbc.sql("""
                SELECT article_url,title,search_claimed_date,first_discovered_at,last_discovered_at,review_state
                FROM market_intelligence.news_discovery_candidate
                WHERE source_host=:host AND review_state='PENDING_VERIFICATION' AND next_attempt_at<=:now
                ORDER BY next_attempt_at,last_discovered_at,article_url
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param("host", host.get()).param("now", time(now)).query((rs, row) ->
                    new NewsDiscoveryRepository.Candidate(rs.getString("article_url"),rs.getString("title"),
                        rs.getString("search_claimed_date"),rs.getObject("first_discovered_at",OffsetDateTime.class).toInstant(),
                        rs.getObject("last_discovered_at",OffsetDateTime.class).toInstant(),rs.getString("review_state"))).optional();
            if (candidate.isEmpty()) return Optional.empty();
            UUID token = UUID.randomUUID();
            Instant until = now.plusSeconds(120);
            jdbc.sql("""
                UPDATE market_intelligence.news_discovery_host_schedule SET lease_token=:token,lease_until=:until,
                  next_attempt_at=greatest(next_attempt_at,:until,
                    CAST(:now AS timestamptz)+crawl_delay_millis*interval '1 millisecond')
                WHERE source_host=:host
                """).param("token", token).param("until", time(until)).param("now",time(now)).param("host",host.get()).update();
            return Optional.of(new Lease(token,host.get(),candidate.get(),until));
        });
    }
    boolean complete(Lease lease, NewsCandidateReview.Decision decision, Instant now,
                     Instant retryNotBefore, long crawlDelayMillis) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(decision);
        Objects.requireNonNull(now);
        // Out-of-range server delays must fail closed, not wrap into immediate retries.
        if (crawlDelayMillis < 0) throw new IllegalArgumentException("Invalid crawl delay");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var previous = jdbc.sql("""
                SELECT failures,crawl_delay_millis FROM market_intelligence.news_discovery_host_schedule
                WHERE source_host=:host AND lease_token=:token AND lease_until>:now
                FOR UPDATE
                """).param("host",lease.host()).param("token",lease.token()).param("now",time(now))
                .query((rs,row) -> new long[]{rs.getLong(1),rs.getLong(2)}).optional();
            if (previous.isEmpty()) return false;
            boolean updated = repository.review(lease.candidate(),decision,now);
            boolean success = updated && "VERIFIED".equals(decision.state());
            int failures = success ? 0 : (int)Math.min(30,previous.get()[0]+1);
            long delay = Math.max(previous.get()[1],crawlDelayMillis);
            long backoff = success ? 1 : Math.min(21600,60L << Math.min(9,failures-1));
            String next;
            if (delay > 31_536_000_000L || (retryNotBefore != null
                    && retryNotBefore.isAfter(Instant.parse("9999-01-01T00:00:00Z")))) {
                // PostgreSQL infinity survives process restarts. No automatic reset/release.
                next = "infinity";
            } else {
                Instant planned = now.plusMillis(Math.max(delay,backoff*1000));
                if (retryNotBefore != null && retryNotBefore.isAfter(planned)) planned=retryNotBefore;
                next = planned.toString();
            }
            jdbc.sql("""
                UPDATE market_intelligence.news_discovery_candidate
                SET next_attempt_at=greatest(next_attempt_at,CAST(:next AS timestamptz))
                WHERE article_url=:url
                """).param("next",next).param("url",lease.candidate().url()).update();
            jdbc.sql("""
                UPDATE market_intelligence.news_discovery_host_schedule
                SET lease_token=NULL,lease_until=:now,next_attempt_at=CAST(:next AS timestamptz),
                    failures=:failures,crawl_delay_millis=:delay
                WHERE source_host=:host AND lease_token=:token
                """).param("now",time(now)).param("next",next).param("failures",failures)
                .param("delay",delay).param("host",lease.host()).param("token",lease.token()).update();
            return updated;
        }));
    }
    private static OffsetDateTime time(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
