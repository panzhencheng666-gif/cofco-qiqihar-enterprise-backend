package com.cofco.qiqihar.riskintelligence.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class JdbcSourceFactRepository implements SourceFactRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcSourceFactRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SourceFactSnapshot> find(SourceFactKey key) {
        return jdbc.sql("""
                SELECT snapshot_id,source_system,source_record_type,source_record_id,source_version,
                       business_occurred_at,ingested_at,payload_sha256,payload::text
                FROM risk.source_fact_snapshot
                WHERE source_system=:sourceSystem AND source_record_type=:recordType
                  AND source_record_id=:recordId AND source_version=:sourceVersion
                """)
                .param("sourceSystem", key.sourceSystem())
                .param("recordType", key.sourceRecordType())
                .param("recordId", key.sourceRecordId())
                .param("sourceVersion", key.sourceVersion())
                .query(this::snapshot)
                .optional();
    }

    @Override
    public boolean insert(SourceFactSnapshot snapshot) {
        return jdbc.sql("""
                INSERT INTO risk.source_fact_snapshot(
                  snapshot_id,source_system,source_record_type,source_record_id,source_version,
                  business_occurred_at,ingested_at,payload_sha256,payload)
                VALUES(:snapshotId,:sourceSystem,:recordType,:recordId,:sourceVersion,
                  :businessOccurredAt,:ingestedAt,:payloadSha256,CAST(:payload AS jsonb))
                ON CONFLICT(source_system,source_record_type,source_record_id,source_version)
                DO NOTHING
                """)
                .param("snapshotId", snapshot.snapshotId())
                .param("sourceSystem", snapshot.key().sourceSystem())
                .param("recordType", snapshot.key().sourceRecordType())
                .param("recordId", snapshot.key().sourceRecordId())
                .param("sourceVersion", snapshot.key().sourceVersion())
                .param("businessOccurredAt", Timestamp.from(snapshot.businessOccurredAt()))
                .param("ingestedAt", Timestamp.from(snapshot.ingestedAt()))
                .param("payloadSha256", snapshot.payloadSha256())
                .param("payload", serialize(snapshot.payload()))
                .update() == 1;
    }

    private SourceFactSnapshot snapshot(ResultSet row, int ignored) throws SQLException {
        return new SourceFactSnapshot(
                row.getObject("snapshot_id", UUID.class),
                new SourceFactKey(
                        row.getString("source_system"),
                        row.getString("source_record_type"),
                        row.getString("source_record_id"),
                        row.getString("source_version")),
                row.getTimestamp("business_occurred_at").toInstant(),
                row.getTimestamp("ingested_at").toInstant(),
                row.getString("payload_sha256"),
                deserialize(row.getString("payload")));
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Source payload cannot be serialized", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserialize(String payload) {
        try {
            return Map.copyOf(json.readValue(payload, Map.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Stored source payload cannot be read", exception);
        }
    }
}
