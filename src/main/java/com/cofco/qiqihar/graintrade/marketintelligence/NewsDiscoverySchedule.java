package com.cofco.qiqihar.graintrade.marketintelligence;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import javax.sql.DataSource;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

final class NewsDiscoverySchedule {
    interface Search { NewsSearchResults.Result search(String engine,String query); }
    private record Job(UUID token,String engine,int failures) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final NewsDiscoveryRepository repository;
    private final Clock clock;
    private final Instant deadline;
    private final Search search;
    private final Supplier<String> worker;
    private final ReentrantLock running=new ReentrantLock();
    private volatile String lastCycle="NOT_RUN";

    NewsDiscoverySchedule(DataSource ds,Clock clock,Instant deadline,Search search,Supplier<String> worker) {
        jdbc=JdbcClient.create(ds);
        transaction=new TransactionTemplate(new JdbcTransactionManager(ds));
        transaction.setTimeout(10);
        repository=new NewsDiscoveryRepository(jdbc);
        this.clock=Objects.requireNonNull(clock);
        this.deadline=Objects.requireNonNull(deadline);
        this.search=Objects.requireNonNull(search);
        this.worker=Objects.requireNonNull(worker);
    }
    @Scheduled(scheduler="newsDiscoveryTaskScheduler",initialDelay=60000,fixedDelay=60000)
    void scheduledCycle() { lastCycle=cycle(); }

    String lastCycle() { return lastCycle; }

    String cycle() {
        if(!running.tryLock()) return "BUSY";
        Job job=null;
        try {
            Instant now=clock.instant();
            if(!now.isBefore(deadline)) return "CLOSED";
            UUID token=UUID.randomUUID();
            // One atomic cross-process reservation. A crash/restart does not reset the five-minute wait.
            job=jdbc.sql("""
                UPDATE market_intelligence.news_discovery_search_schedule
                SET attempt_token=:token,last_started_at=:now,next_search_at=:next,
                    last_state='RUNNING',last_reason='SEARCH_RESERVED'
                WHERE slot=1 AND next_search_at<=:now
                RETURNING next_engine,failures
                """).param("token",token).param("now",now.atOffset(ZoneOffset.UTC))
                .param("next",now.plusSeconds(300).atOffset(ZoneOffset.UTC))
                .query((rs,row) -> new Job(token,rs.getString("next_engine"),rs.getInt("failures"))).optional().orElse(null);
            if(job!=null && clock.instant().isBefore(deadline)) {
                String query=job.engine().equals("CNLiteBasic")?"粮食 农业 供需 最新":"grain agriculture supply demand latest";
                var result=search.search(job.engine(),query);
                finish(job,query,result);
            }
            if(clock.instant().isBefore(deadline)) worker.get();
            return "OK";
        } catch(RuntimeException failure) {
            if(job!=null) {
                try {
                    jdbc.sql("""
                        UPDATE market_intelligence.news_discovery_search_schedule
                        SET last_state='FAILED',last_reason='PIPELINE_UNAVAILABLE'
                        WHERE slot=1 AND attempt_token=:token
                        """).param("token",job.token()).update();
                } catch(RuntimeException unavailable) { /* Keep pre-reserved wait, never retry here. */ }
            }
            return "UNAVAILABLE";
        } finally { running.unlock(); }
    }
    private void finish(Job job,String query,NewsSearchResults.Result result) {
        Objects.requireNonNull(result);
        transaction.executeWithoutResult(status -> {
            var owner=jdbc.sql("""
                SELECT slot FROM market_intelligence.news_discovery_search_schedule
                WHERE slot=1 AND attempt_token=:token FOR UPDATE
                """).param("token",job.token()).query(Integer.class).optional();
            if(owner.isEmpty()) return;
            repository.save(job.engine(),query,result.candidates());
            boolean success=result.state()==NewsSearchResults.State.CANDIDATES || result.state()==NewsSearchResults.State.EMPTY;
            int failures=success?0:Math.min(30,job.failures()+1);
            long delay=success?300:Math.min(21600,300L << Math.min(7,failures-1));
            Instant now=clock.instant();
            String reason=result.reason()!=null && result.reason().matches("[A-Z0-9_]{1,80}")?result.reason():"SEARCH_RESULT";
            jdbc.sql("""
                UPDATE market_intelligence.news_discovery_search_schedule
                SET last_completed_at=:now,last_state=:state,last_reason=:reason,failures=:failures,
                    next_search_at=greatest(next_search_at,:next),next_engine=:engine
                WHERE slot=1 AND attempt_token=:token
                """).param("now",now.atOffset(ZoneOffset.UTC)).param("state",result.state().name())
                .param("reason",reason).param("failures",failures).param("next",now.plusSeconds(delay).atOffset(ZoneOffset.UTC))
                .param("engine",job.engine().equals("CNLiteBasic")?"GlobalAdvanced":"CNLiteBasic")
                .param("token",job.token()).update();
        });
    }
}
