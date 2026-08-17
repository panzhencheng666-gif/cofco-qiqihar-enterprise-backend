package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportDraftRepository;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcImportDraftRepository implements ImportDraftRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcImportDraftRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportDraft insert(ImportDraft draft) {
        int inserted = jdbc.sql("""
                INSERT INTO platform.business_import_draft(import_draft_id,domain_code,product_code,
                  object_type_code,sample_name,region_code,survey_period,values_json,missing_fields_json,
                  completeness_percent,state_code,created_by,import_job_id,source_row_number,version,
                  created_at,updated_at)
                VALUES(:id,:domain,:product,:objectType,:sampleName,:region,:surveyPeriod,
                  CAST(:values AS jsonb),CAST(:missingFields AS jsonb),:completeness,:state,:createdBy,
                  :jobId,:rowNumber,:version,:createdAt,:updatedAt)
                """).param("id", draft.id()).param("domain", draft.domainCode())
                .param("product", draft.productCode()).param("objectType", draft.objectTypeCode())
                .param("sampleName", draft.sampleName()).param("region", draft.regionCode())
                .param("surveyPeriod", draft.surveyPeriod()).param("values", json(draft.values()))
                .param("missingFields", json(draft.missingFields()))
                .param("completeness", draft.completenessPercent()).param("state", draft.stateCode())
                .param("createdBy", draft.createdBy()).param("jobId", draft.importJobId())
                .param("rowNumber", draft.sourceRowNumber()).param("version", draft.version())
                .param("createdAt", Timestamp.from(draft.createdAt()))
                .param("updatedAt", Timestamp.from(draft.updatedAt())).update();
        if (inserted != 1) throw new IllegalStateException("Import draft was not inserted");
        return draft;
    }

    @Override
    public int bindEvidence(UUID draftId, List<UUID> evidenceIds, Instant now) {
        int bound = 0;
        for (int index = 0; index < evidenceIds.size(); index++) {
            bound += jdbc.sql("""
                    INSERT INTO platform.business_import_draft_evidence(import_draft_id,photo_id,sort_order,created_at)
                    VALUES(:draftId,:photoId,:sortOrder,:createdAt)
                    ON CONFLICT(photo_id) DO NOTHING
                    """).param("draftId", draftId).param("photoId", evidenceIds.get(index))
                    .param("sortOrder", index + 1).param("createdAt", Timestamp.from(now)).update();
        }
        return bound;
    }

    @Override
    public Optional<ImportDraft> findByIdForUpdate(UUID draftId) {
        return jdbc.sql(SELECT + " WHERE import_draft_id=:id FOR UPDATE")
                .param("id", draftId).query(this::draft).optional();
    }

    @Override
    public List<ImportDraft> findByJob(UUID importJobId, String createdBy) {
        return jdbc.sql(SELECT + """
                 WHERE import_job_id=:job AND created_by=:createdBy
                 ORDER BY source_row_number
                """).param("job", importJobId).param("createdBy", createdBy)
                .query(this::draft).list();
    }

    @Override
    public List<UUID> evidenceIds(UUID draftId) {
        return jdbc.sql("""
                SELECT photo_id FROM platform.business_import_draft_evidence
                WHERE import_draft_id=:id ORDER BY sort_order
                """).param("id", draftId).query(UUID.class).list();
    }

    @Override
    public ImportDraft markPromoted(
            UUID draftId, int expectedVersion, String canonicalRecordId, Instant now) {
        int updated = jdbc.sql("""
                UPDATE platform.business_import_draft
                SET state_code='PROMOTED',canonical_record_id=:recordId,
                  version=version+1,updated_at=:now
                WHERE import_draft_id=:id AND version=:version AND state_code='DRAFT'
                """).param("id", draftId).param("version", expectedVersion)
                .param("recordId", canonicalRecordId).param("now", Timestamp.from(now)).update();
        if (updated != 1) throw new IllegalStateException("Import draft promotion conflicted");
        return findByIdForUpdate(draftId).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> values(String json) {
        try {
            return java.util.Map.copyOf(objectMapper.readValue(json, java.util.Map.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Import draft values are invalid", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, List.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Import draft missing fields are invalid", exception);
        }
    }

    private ImportDraft draft(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new ImportDraft(
                row.getObject("import_draft_id", UUID.class), row.getString("domain_code"),
                row.getString("product_code"), row.getString("object_type_code"),
                row.getString("sample_name"), row.getString("region_code"),
                row.getString("survey_period"), values(row.getString("values_json")),
                strings(row.getString("missing_fields_json")), row.getInt("completeness_percent"),
                row.getString("state_code"), row.getString("created_by"),
                row.getObject("import_job_id", UUID.class), row.getInt("source_row_number"),
                row.getInt("version"), row.getString("canonical_record_id"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private static final String SELECT = """
            SELECT import_draft_id,domain_code,product_code,object_type_code,sample_name,region_code,
              survey_period,values_json::text,missing_fields_json::text,completeness_percent,state_code,
              created_by,import_job_id,source_row_number,version,canonical_record_id,created_at,updated_at
            FROM platform.business_import_draft
            """;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Import draft JSON cannot be serialized", exception);
        }
    }
}
