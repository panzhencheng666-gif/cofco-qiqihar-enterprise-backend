package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Candidate;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.SubjectInput;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSampleIdentityGovernanceRepository {
    private final JdbcClient jdbc;

    public JdbcSampleIdentityGovernanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SampleIdentityAssessment assess(SubjectInput input) {
        String nameKey = SampleIdentityAssessment.normalizedName(input.sampleName());
        String contactKey = SampleIdentityAssessment.normalizedContact(input.sampleContact());
        List<Candidate> candidates = jdbc.sql("""
                WITH observed AS (
                  SELECT record.sample_point_id,sample_name.value canonical_name,
                         sample_contact.value sample_contact,record.survey_date observed_on
                  FROM production.production_record record
                  JOIN production.production_record_submission_metadata sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='PROD_SAMPLE_NAME'
                  LEFT JOIN production.production_record_submission_metadata sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='PROD_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                  UNION ALL
                  SELECT event.sample_point_id,event.source_organization,event.sample_contact,
                         event.collection_date
                  FROM logistics.route_event event
                  WHERE event.status_code='APPROVED' AND event.sample_point_id IS NOT NULL
                  UNION ALL
                  SELECT record.sample_point_id,sample_name.value canonical_name,
                         sample_contact.value sample_contact,record.trade_date observed_on
                  FROM market.market_record record
                  JOIN market.market_record_core_value sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='MKT_SAMPLE_NAME'
                  LEFT JOIN market.market_record_core_value sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='MKT_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                ), normalized AS (
                  SELECT observed.*,
                         regexp_replace(regexp_replace(
                           normalize(lower(normalize(btrim(observed.canonical_name),NFKC)),NFKD),
                           '[' || chr(768) || '-' || chr(879) || ']+','','g'),
                           '[[:space:]]+','','g') name_key,
                         regexp_replace(regexp_replace(
                           normalize(lower(normalize(btrim(coalesce(observed.sample_contact,'')),NFKC)),NFKD),
                           '[' || chr(768) || '-' || chr(879) || ']+','','g'),
                           '[[:space:]()（）-]+','','g') contact_key,
                         count(*) OVER (PARTITION BY observed.sample_point_id) approved_record_count
                  FROM observed
                ), ranked AS (
                  SELECT point.sample_point_id,
                         coalesce(evidence.canonical_name,point.canonical_name) canonical_name,
                         coalesce(evidence.sample_contact,'') sample_contact,
                         coalesce(evidence.approved_record_count,0) approved_record_count,
                         point.region_code,ST_X(point.governed_point)::numeric longitude,
                         ST_Y(point.governed_point)::numeric latitude,point.effective_from
                  FROM registry.sample_point point
                  LEFT JOIN LATERAL (
                    SELECT normalized.canonical_name,normalized.sample_contact,
                           normalized.approved_record_count,normalized.name_key
                    FROM normalized
                    WHERE normalized.sample_point_id=point.sample_point_id
                    ORDER BY (normalized.contact_key=:contactKey) DESC,
                             normalized.observed_on DESC
                    LIMIT 1
                  ) evidence ON true
                  WHERE point.kind_code=CASE WHEN :domainCode='LOGISTICS'
                         THEN 'LOGISTICS_NODE' ELSE 'SURVEY_SITE' END
                    AND point.approval_state='APPROVED'
                    AND point.location_state='VALID'
                    AND point.governed_point IS NOT NULL
                    AND (coalesce(evidence.name_key,
                           regexp_replace(regexp_replace(
                             normalize(lower(normalize(btrim(point.canonical_name),NFKC)),NFKD),
                             '[' || chr(768) || '-' || chr(879) || ']+','','g'),
                             '[[:space:]]+','','g'))=:nameKey
                      OR ST_Equals(point.governed_point,
                           ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                )
                SELECT * FROM ranked ORDER BY effective_from,sample_point_id
                """).param("nameKey", nameKey).param("contactKey", contactKey)
                .param("domainCode", input.domainCode())
                .param("longitude", input.longitude()).param("latitude", input.latitude())
                .query((row, ignored) -> new Candidate(
                        row.getObject("sample_point_id", java.util.UUID.class),
                        row.getString("canonical_name"), row.getString("sample_contact"),
                        row.getString("region_code"), row.getBigDecimal("longitude"),
                        row.getBigDecimal("latitude"), row.getInt("approved_record_count"),
                        row.getObject("effective_from", LocalDate.class)))
                .list();
        return SampleIdentityAssessment.assess(input, candidates);
    }

    public boolean isCoordinateWithinDeclaredRegion(SubjectInput input) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1
                  FROM overview.administrative_boundary boundary
                  WHERE boundary.region_code=:regionCode
                    AND ST_Covers(boundary.geometry,
                      ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                """).param("regionCode", input.regionCode())
                .param("longitude", input.longitude()).param("latitude", input.latitude())
                .query(Boolean.class).single();
    }

    /** Both the submitted precision and the persisted representation must be covered by one boundary. */
    public boolean areCoordinateRepresentationsWithinDeclaredRegion(
            SubjectInput submitted, SubjectInput persisted) {
        if (!submitted.regionCode().equals(persisted.regionCode())) return false;
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1
                  FROM overview.administrative_boundary boundary
                  WHERE boundary.region_code=:regionCode
                    AND ST_Covers(boundary.geometry,
                      ST_SetSRID(ST_MakePoint(:submittedLongitude,:submittedLatitude),4326))
                    AND ST_Covers(boundary.geometry,
                      ST_SetSRID(ST_MakePoint(:persistedLongitude,:persistedLatitude),4326)))
                """).param("regionCode", submitted.regionCode())
                .param("submittedLongitude", submitted.longitude())
                .param("submittedLatitude", submitted.latitude())
                .param("persistedLongitude", persisted.longitude())
                .param("persistedLatitude", persisted.latitude())
                .query(Boolean.class).single();
    }

    public boolean isActiveSamplePoint(UUID samplePointId) {
        if (samplePointId == null) return false;
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM registry.sample_point
                  WHERE sample_point_id=:samplePointId AND kind_code='SURVEY_SITE'
                    AND approval_state='APPROVED' AND location_state='VALID')
                """).param("samplePointId", samplePointId).query(Boolean.class).single();
    }
}
