package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportDraftRepository;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Import draft JSON cannot be serialized", exception);
        }
    }
}
