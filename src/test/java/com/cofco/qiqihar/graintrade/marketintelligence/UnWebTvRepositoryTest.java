package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class UnWebTvRepositoryTest {
    static final Instant NOW=Instant.parse("2026-09-28T21:00:00Z");
    JdbcTemplate sql;
    UnWebTvRepository repo;
    @BeforeEach void setup() {
        var ds=ProtectedTestDatabase.shared().dataSource();
        sql=new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.webcast_programme_state");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.webcast_source_admission");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.webcast_event");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.source_sync_state");
        sql.execute("CREATE TABLE market_intelligence.webcast_event(source_code varchar(40),event_url text,title text NOT NULL,starts_at timestamptz NOT NULL,fetched_at timestamptz NOT NULL,PRIMARY KEY(source_code,event_url))");
        sql.execute("CREATE TABLE market_intelligence.source_sync_state(source_code text PRIMARY KEY,last_attempt_at timestamptz,last_success_at timestamptz,latest_period date,last_error varchar(400))");
        sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='qiqihar_enterprise_runtime') THEN CREATE ROLE qiqihar_enterprise_runtime; END IF; END $$");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/news-webcast-release/V225__webcast_programme_state.sql")).execute(ds);
        repo=new UnWebTvRepository(sql,new DataSourceTransactionManager(ds));
    }
    static UnWebTvRepository.Snapshot snapshot(boolean live,boolean cancelled) {
        var event=new UnWebTvScheduleParser.Event("TU123","Test programme","https://teamup.com/ksf6ikyw6jst7sqii1/events/123",NOW.minusSeconds(60),NOW.plusSeconds(3600),null,cancelled);
        var programme=new UnWebTvProgrammeParser.Programme("Test programme","https://webtv.un.org/en/asset/k1a/k1abcdefgh","1_abcdefgh",live,NOW.minusSeconds(60));
        return new UnWebTvRepository.Snapshot(List.of(event),List.of(programme));
    }
    void admit() {
        sql.update("INSERT INTO market_intelligence.webcast_source_admission(source_code,provider,fetch_allowed,metadata_display_allowed,embed_allowed,evidence,verified_at,valid_until) VALUES ('un-webtv','un-webtv',true,true,true,'TEST FIXTURE ONLY',?,?)",OffsetDateTime.ofInstant(NOW.minusSeconds(10),ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW.plusSeconds(600),ZoneOffset.UTC));
    }
    @Test void schemaHasNoAdmissionSeedsAndDefaultsDeny() {
        assertThat(sql.queryForObject("SELECT to_regclass('market_intelligence.webcast_source_admission') IS NOT NULL",Boolean.class)).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM market_intelligence.webcast_source_admission",Integer.class)).isZero();
    }
    @Test void noAdmissionDoesNotFetch() {
        var fetched=new AtomicBoolean();
        assertThat(repo.refresh(()->{fetched.set(true);return snapshot(true,false);},NOW)).isFalse();
        assertThat(fetched).isFalse();
    }
    @Test void savedSnapshotSurvivesReopenAndExpiryRetainsHistory() {
        admit();
        assertThat(repo.refresh(()->snapshot(true,false),NOW)).isTrue();
        var reopened=new UnWebTvRepository(sql,new DataSourceTransactionManager(sql.getDataSource()));
        assertThat(reopened.list(NOW)).singleElement().satisfies(item->{
            assertThat(item.url()).isEqualTo("https://webtv.un.org/en/asset/k1a/k1abcdefgh");
            assertThat(item.playback().status()).isEqualTo("LIVE");
            assertThat(item.playback().validUntil()).isEqualTo(NOW.plusSeconds(180));
        });
        assertThat(reopened.list(NOW.plusSeconds(180))).singleElement().satisfies(item->assertThat(item.playback()).isNull());
    }
    @Test void admissionRevocationAndDeadlineRemovePlaybackImmediately() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        sql.update("UPDATE market_intelligence.webcast_source_admission SET valid_until=?",OffsetDateTime.ofInstant(NOW.plusSeconds(30),ZoneOffset.UTC));
        assertThat(repo.list(NOW)).singleElement().satisfies(item->assertThat(item.playback().validUntil()).isEqualTo(NOW.plusSeconds(30)));
        assertThat(repo.list(NOW.plusSeconds(30))).isEmpty();
        sql.update("UPDATE market_intelligence.webcast_source_admission SET embed_allowed=false");
        assertThat(repo.list(NOW)).singleElement().satisfies(item->assertThat(item.playback()).isNull());
        sql.update("UPDATE market_intelligence.webcast_source_admission SET metadata_display_allowed=false");
        assertThat(repo.list(NOW)).isEmpty();
    }
    @Test void cancellationAndNotLiveNeverBecomeRecording() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        repo.refresh(()->snapshot(false,false),NOW.plusSeconds(5));
        assertThat(repo.list(NOW.plusSeconds(5))).singleElement().satisfies(item->assertThat(item.playback()).isNull());
        repo.refresh(()->snapshot(true,true),NOW.plusSeconds(10));
        assertThat(repo.list(NOW.plusSeconds(10))).singleElement().satisfies(item->assertThat(item.playback()).isNull());
        assertThat(sql.queryForObject("SELECT source_status FROM market_intelligence.webcast_programme_state",String.class)).isEqualTo("CANCELLED");
    }
    @Test void rollbackOfMidSnapshotFailurePreservesPreviousStateAndSuccess() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        sql.execute("ALTER TABLE market_intelligence.webcast_programme_state ADD CONSTRAINT force_test_failure CHECK (source_status <> 'UNKNOWN')");
        assertThatThrownBy(()->repo.refresh(()->snapshot(false,false),NOW.plusSeconds(5))).isInstanceOf(RuntimeException.class);
        assertThat(repo.list(NOW)).singleElement().satisfies(item->assertThat(item.playback().status()).isEqualTo("LIVE"));
        assertThat(sql.queryForObject("SELECT last_success_at FROM market_intelligence.source_sync_state",OffsetDateTime.class).toInstant()).isEqualTo(NOW);
    }
    @Test void failedAcquisitionKeepsHistoryAndSuccessMarker() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        assertThatThrownBy(()->repo.refresh(()->{throw new IllegalStateException("fixture failure");},NOW.plusSeconds(5))).isInstanceOf(IllegalStateException.class);
        assertThat(repo.list(NOW)).hasSize(1);
        assertThat(sql.queryForObject("SELECT last_success_at FROM market_intelligence.source_sync_state",OffsetDateTime.class).toInstant()).isEqualTo(NOW);
    }
    @Test void secondInstanceCannotFetchOrCommitWhileWriterOwnsLock() throws Exception {
        admit();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->repo.refresh(()->{
                entered.countDown();try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return snapshot(true,false);
            },NOW));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var called=new AtomicBoolean();
                assertThat(repo.refresh(()->{called.set(true);return snapshot(true,false);},NOW.plusSeconds(1))).isFalse();
                assertThat(called).isFalse();
            } finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void emptySnapshotCannotEraseHistoryAndOlderSnapshotCannotReplaceNewer() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        assertThatThrownBy(()->repo.refresh(()->new UnWebTvRepository.Snapshot(List.of(),List.of()),NOW.plusSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.refresh(()->snapshot(false,false),NOW.minusSeconds(1))).isFalse();
        assertThat(repo.list(NOW)).singleElement().satisfies(item->assertThat(item.playback()).isNotNull());
    }
    @Test void disappearedProgrammeExpiresButRemainsPersisted() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        var old=snapshot(true,false);
        var other=new UnWebTvScheduleParser.Event("TU456","Different","https://teamup.com/ksf6ikyw6jst7sqii1/events/456",NOW,NOW.plusSeconds(60),null,false);
        repo.refresh(()->new UnWebTvRepository.Snapshot(List.of(other),old.programmes()),NOW.plusSeconds(5));
        assertThat(repo.list(NOW.plusSeconds(5))).hasSize(2).allSatisfy(item->assertThat(item.playback()).isNull());
    }
    @Test void ambiguousProgrammeMatchHasNoPlayback() {
        admit();var snap=snapshot(true,false);var p=snap.programmes().getFirst();
        var second=new UnWebTvProgrammeParser.Programme(p.title(),"https://webtv.un.org/en/asset/k1b/k1bcdefghi","1_bcdefghi",true,p.startsAt());
        repo.refresh(()->new UnWebTvRepository.Snapshot(snap.events(),List.of(p,second)),NOW);
        assertThat(repo.list(NOW)).singleElement().satisfies(item->assertThat(item.playback()).isNull());
    }
    @Test void runtimeHasReadOnlyAdmissionAndCannotDeleteHistory() {
        assertThat(sql.queryForObject("SELECT has_table_privilege('qiqihar_enterprise_runtime','market_intelligence.webcast_source_admission','SELECT')",Boolean.class)).isTrue();
        for(String privilege:List.of("INSERT","UPDATE","DELETE","TRUNCATE"))
            assertThat(sql.queryForObject("SELECT has_table_privilege('qiqihar_enterprise_runtime','market_intelligence.webcast_source_admission',?)",Boolean.class,privilege)).isFalse();
        assertThat(sql.queryForObject("SELECT has_table_privilege('qiqihar_enterprise_runtime','market_intelligence.webcast_programme_state','DELETE')",Boolean.class)).isFalse();
    }
    @Test void uniqueControllerKeepsFaoAndAddsApprovedUnAndNoStore() {
        admit();repo.refresh(()->snapshot(true,false),NOW);
        sql.update("INSERT INTO market_intelligence.webcast_event VALUES ('fao-webcast','https://www.fao.org/webcast/detail/test/en','FAO fixture',?,?)",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));
        var controller=new FaoWebcastFeed.Controller(org.springframework.jdbc.core.simple.JdbcClient.create(sql.getDataSource()),repo,true,java.time.Clock.fixed(NOW,ZoneOffset.UTC));
        var response=controller.list();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().data()).hasSize(2).extracting(UnWebTvRepository.Item::sourceName).containsExactly("UN Web TV","FAO Webcast");
        var disabled=new FaoWebcastFeed.Controller(org.springframework.jdbc.core.simple.JdbcClient.create(sql.getDataSource()),repo,false,java.time.Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(disabled.list().getBody().data()).singleElement().satisfies(item->assertThat(item.sourceName()).isEqualTo("FAO Webcast"));
    }

}
