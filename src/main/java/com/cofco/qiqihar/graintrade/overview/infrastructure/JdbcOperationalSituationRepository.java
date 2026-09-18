package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationCatalogue;
import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOperationalSituationRepository implements OperationalSituationRepository {
    private final JdbcClient jdbc;

    public JdbcOperationalSituationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot() {
        var weather = jdbc.sql("""
                SELECT weather.root_region_code,region.name AS region_name,
                       ST_X(ST_PointOnSurface(boundary.geometry)) AS longitude,
                       ST_Y(ST_PointOnSurface(boundary.geometry)) AS latitude,
                       weather.observed_at,weather.mean_temperature_c,weather.precipitation_mm,
                       weather.soil_moisture_percent,weather.risk,weather.assessment,
                       source.source_name,source.source_url,weather.fetched_at
                FROM production.regional_weather_snapshot weather
                JOIN production.regional_public_source source ON source.source_id=weather.source_id
                JOIN platform.region region ON region.code=weather.root_region_code
                JOIN overview.administrative_boundary boundary ON boundary.region_code=weather.root_region_code
                ORDER BY region.sort_order,region.name
                """).query((row, index) -> new OperationalSituationCatalogue.WeatherObservation(
                    row.getString("root_region_code"), row.getString("region_name"),
                    row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                    instant(row.getTimestamp("observed_at")), row.getBigDecimal("mean_temperature_c"),
                    row.getBigDecimal("precipitation_mm"), row.getBigDecimal("soil_moisture_percent"),
                    row.getString("risk"), row.getString("assessment"), row.getString("source_name"),
                    row.getString("source_url"), instant(row.getTimestamp("fetched_at")))).list();
        var events = jdbc.sql("""
                SELECT * FROM overview.public_event_snapshot
                ORDER BY observed_at DESC,event_id LIMIT 500
                """).query((row, index) -> new OperationalSituationCatalogue.PublicEvent(
                    row.getString("event_id"), row.getString("title"), row.getString("description"),
                    row.getString("category_code"), row.getString("category_label"),
                    row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                    instant(row.getTimestamp("observed_at")), row.getBigDecimal("magnitude_value"),
                    row.getString("magnitude_unit"), row.getString("event_url"),
                    row.getString("evidence_url"), instant(row.getTimestamp("fetched_at")))).list();
        var refresh = jdbc.sql("""
                SELECT * FROM overview.public_event_refresh_state ORDER BY source_code
                """).query((row, index) -> new RefreshState(
                    row.getString("source_code"), row.getString("source_name"), row.getString("source_url"),
                    instant(row.getTimestamp("last_attempt_at")), instant(row.getTimestamp("last_success_at")),
                    row.getString("last_error"), row.getInt("record_count"))).list();
        return new Snapshot(weather, events, refresh);
    }

    @Override
    @Transactional
    public void replaceEvents(String sourceCode, List<FeedEvent> events, Instant attemptedAt) {
        jdbc.sql("DELETE FROM overview.public_event_snapshot WHERE source_code=:source")
                .param("source", sourceCode).update();
        for (var event : events) {
            jdbc.sql("""
                    INSERT INTO overview.public_event_snapshot(
                      source_code,event_id,title,description,category_code,category_label,
                      longitude,latitude,observed_at,magnitude_value,magnitude_unit,
                      event_url,evidence_url,fetched_at)
                    VALUES(:source,:id,:title,:description,:categoryCode,:categoryLabel,
                      :longitude,:latitude,:observed,:magnitude,:unit,:eventUrl,:evidenceUrl,:fetched)
                    """).param("source", sourceCode).param("id", event.eventId())
                    .param("title", event.title()).param("description", event.description())
                    .param("categoryCode", event.categoryCode()).param("categoryLabel", event.categoryLabel())
                    .param("longitude", event.longitude()).param("latitude", event.latitude())
                    .param("observed", Timestamp.from(event.observedAt())).param("magnitude", event.magnitudeValue())
                    .param("unit", event.magnitudeUnit()).param("eventUrl", event.eventUrl())
                    .param("evidenceUrl", event.evidenceUrl()).param("fetched", Timestamp.from(attemptedAt)).update();
        }
        jdbc.sql("""
                UPDATE overview.public_event_refresh_state
                SET last_attempt_at=:attempted,last_success_at=:attempted,last_error=NULL,record_count=:count
                WHERE source_code=:source
                """).param("attempted", Timestamp.from(attemptedAt)).param("count", events.size())
                .param("source", sourceCode).update();
    }

    @Override
    public void recordFailure(String sourceCode, Instant attemptedAt, String message) {
        String safe = message == null ? "unknown" : message.substring(0, Math.min(500, message.length()));
        jdbc.sql("""
                UPDATE overview.public_event_refresh_state
                SET last_attempt_at=:attempted,last_error=:error WHERE source_code=:source
                """).param("attempted", Timestamp.from(attemptedAt)).param("error", safe)
                .param("source", sourceCode).update();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
