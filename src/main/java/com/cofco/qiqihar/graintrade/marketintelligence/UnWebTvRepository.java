package com.cofco.qiqihar.graintrade.marketintelligence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable observations; admission is a separate, administrator-owned decision. */
@Component
final class UnWebTvRepository {
    private static final long LOCK=716219280225L;
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    record Snapshot(List<UnWebTvScheduleParser.Event> events, List<UnWebTvProgrammeParser.Programme> programmes) { }
    record Playback(String provider,String entryId,String admission,String status,Instant validUntil) { }
    record Item(String sourceName,String title,String url,Instant startsAt,Instant fetchedAt,String sourcePageUrl,
                @JsonInclude(JsonInclude.Include.NON_NULL) Playback playback) { }
    UnWebTvRepository(JdbcTemplate sql, PlatformTransactionManager manager) {
        this.sql=sql;
        transactions=new TransactionTemplate(manager);
        transactions.setTimeout(75);
    }

    boolean refresh(Supplier<Snapshot> input, Instant at) {
        return Boolean.TRUE.equals(transactions.execute(tx->{
            if(!Boolean.TRUE.equals(sql.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,LOCK))) return false;
            // Hold a share lock until commit: a concurrent administrative revocation cannot race the write.
            var admitted=sql.queryForList("""
                    SELECT source_code FROM market_intelligence.webcast_source_admission
                    WHERE source_code='un-webtv' AND provider='un-webtv' AND fetch_allowed
                      AND verified_at<=? AND valid_until>? FOR SHARE
                    """,date(at),date(at));
            if(admitted.isEmpty()) return false;
            var previous=sql.queryForList("SELECT last_success_at FROM market_intelligence.source_sync_state WHERE source_code='un-webtv' AND last_success_at>=?",date(at));
            if(!previous.isEmpty()) return false;
            Snapshot snapshot=input.get();
            if(snapshot==null || snapshot.events()==null || snapshot.programmes()==null
                    || snapshot.events().isEmpty() || snapshot.programmes().isEmpty()
                    || snapshot.events().size()>5000 || snapshot.programmes().size()>500) throw new IllegalArgumentException("Incomplete webcast snapshot");
            var matches=new HashMap<String,UnWebTvProgrammeParser.Programme>();
            var ambiguous=new HashSet<String>();
            for(var programme:snapshot.programmes()) {
                UnWebTvProgrammeParser.match(programme,snapshot.events()).ifPresent(event->{
                    if(matches.putIfAbsent(event.uid(),programme)!=null) ambiguous.add(event.uid());
                });
            }
            ambiguous.forEach(matches::remove);
            UUID snapshotId=UUID.randomUUID();
            for(var event:snapshot.events()) {
                var programme=matches.get(event.uid());
                String state=event.cancelled()?"CANCELLED":programme!=null && programme.sourceReportsLive()?"LIVE":"UNKNOWN";
                sql.update("""
                        INSERT INTO market_intelligence.webcast_event(source_code,event_url,title,starts_at,fetched_at)
                        VALUES ('un-webtv',?,?,?,?) ON CONFLICT(source_code,event_url) DO UPDATE SET
                        title=EXCLUDED.title,starts_at=EXCLUDED.starts_at,fetched_at=EXCLUDED.fetched_at
                        """,event.url(),event.title(),date(event.startsAt()),date(at));
                sql.update("""
                        INSERT INTO market_intelligence.webcast_programme_state
                          (source_code,event_url,calendar_uid,programme_url,provider,entry_id,ends_at,source_modified_at,source_status,observed_at,snapshot_id,expires_at)
                        VALUES ('un-webtv',?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_code,event_url) DO UPDATE SET
                          calendar_uid=EXCLUDED.calendar_uid,programme_url=EXCLUDED.programme_url,provider=EXCLUDED.provider,
                          entry_id=EXCLUDED.entry_id,ends_at=EXCLUDED.ends_at,source_modified_at=EXCLUDED.source_modified_at,
                          source_status=EXCLUDED.source_status,observed_at=EXCLUDED.observed_at,
                          snapshot_id=EXCLUDED.snapshot_id,expires_at=EXCLUDED.expires_at
                        """,event.url(),event.uid(),programme==null?null:programme.url(),programme==null?null:"un-webtv",
                        programme==null?null:programme.entryId(),date(event.endsAt()),date(event.modifiedAt()),state,date(at),snapshotId,date(at.plusSeconds(180)));
            }
            // A complete newer snapshot withdraws disappeared playback immediately, without deleting history.
            sql.update("UPDATE market_intelligence.webcast_programme_state SET expires_at=LEAST(expires_at,?) WHERE source_code='un-webtv' AND snapshot_id<>?",date(at),snapshotId);
            sql.update("""
                    INSERT INTO market_intelligence.source_sync_state(source_code,last_attempt_at,last_success_at,latest_period,last_error)
                    VALUES ('un-webtv',?,?,NULL,NULL) ON CONFLICT(source_code) DO UPDATE SET
                      last_attempt_at=EXCLUDED.last_attempt_at,last_success_at=EXCLUDED.last_success_at,
                      latest_period=EXCLUDED.latest_period,last_error=NULL
                    """,date(at),date(at));
            return true;
        }));
    }

    List<Item> list(Instant now) {
        return sql.query("""
                SELECT e.title,COALESCE(s.programme_url,e.event_url) AS url,e.starts_at,e.fetched_at,
                    s.entry_id,s.source_status,
                    CASE WHEN a.fetch_allowed AND a.embed_allowed AND s.source_status='LIVE'
                        AND s.observed_at<=? AND s.expires_at>? AND s.ends_at>? AND e.starts_at<=?
                        THEN LEAST(s.expires_at,a.valid_until,s.ends_at) END AS playable_until
                FROM market_intelligence.webcast_event e
                JOIN market_intelligence.webcast_programme_state s USING(source_code,event_url)
                JOIN market_intelligence.webcast_source_admission a USING(source_code)
                WHERE e.source_code='un-webtv' AND a.provider='un-webtv' AND a.metadata_display_allowed
                    AND a.verified_at<=? AND a.valid_until>?
                ORDER BY (s.source_status='LIVE' AND s.expires_at>? AND s.ends_at>?) DESC,
                    (e.starts_at BETWEEN ? AND ?) DESC,e.starts_at DESC LIMIT 100
                """,(rs,row)->{
                    var expiry=rs.getObject("playable_until",OffsetDateTime.class);
                    return new Item("UN Web TV",rs.getString("title"),rs.getString("url"),
                        rs.getObject("starts_at",OffsetDateTime.class).toInstant(),rs.getObject("fetched_at",OffsetDateTime.class).toInstant(),
                        "https://webtv.un.org/en/schedule",expiry==null?null:new Playback("un-webtv",rs.getString("entry_id"),"APPROVED","LIVE",expiry.toInstant()));
                },date(now),date(now),date(now),date(now),date(now),date(now),date(now),date(now),date(now.minusSeconds(86400)),date(now.plusSeconds(604800)));
    }

    private static OffsetDateTime date(Instant value) {
        return value==null?null:OffsetDateTime.ofInstant(value,ZoneOffset.UTC);
    }
}
