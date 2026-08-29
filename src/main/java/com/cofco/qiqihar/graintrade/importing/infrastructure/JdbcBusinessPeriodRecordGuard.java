package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.BusinessPeriodRecordGuard;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessPeriodRecordGuard implements BusinessPeriodRecordGuard {
    private static final String NORMALIZED_NAME =
            "regexp_replace(regexp_replace(normalize(lower(normalize(%s,NFKC)),NFKD),"
                    + "'[' || chr(768) || '-' || chr(879) || ']+','','g'),"
                    + "'[[:space:]]+','','g')";
    private static final String NORMALIZED_CONTACT =
            "regexp_replace(regexp_replace(normalize(lower(normalize(%s,NFKC)),NFKD),"
                    + "'[' || chr(768) || '-' || chr(879) || ']+','','g'),"
                    + "'[[:space:]()（）-]+','','g')";
    private final JdbcClient jdbc;

    public JdbcBusinessPeriodRecordGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockAndRequireAvailable(ImportDraft draft) {
        RawPeriodKey rawKey = RawPeriodKey.from(draft);
        if (rawKey == null) return;
        PeriodKey key = normalizeIdentity(rawKey);
        if (key.sampleName().isEmpty() || key.sampleContact().isEmpty()) return;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", key.businessFingerprint()).query((row, ignored) -> Boolean.TRUE).single();
        String canonicalRecordId = switch (key.domainCode()) {
            case "PRODUCTION" -> productionConflict(key);
            case "MARKET" -> marketConflict(key);
            case "LOGISTICS" -> logisticsConflict(key);
            default -> null;
        };
        if (canonicalRecordId != null) {
            throw new ClientRequestException("SAMPLE_PERIOD_RECORD_CONFLICT",
                    "相同样本、产品和调查期间已有当前记录 " + canonicalRecordId
                            + "；请在该记录的退回补充或修正流程中更新，不要重复导入");
        }
    }

    private PeriodKey normalizeIdentity(RawPeriodKey raw) {
        return jdbc.sql("""
                WITH normalized AS (
                  SELECT
                    regexp_replace(regexp_replace(
                      normalize(lower(normalize(CAST(:sampleName AS text),NFKC)),NFKD),
                      '[' || chr(768) || '-' || chr(879) || ']+','','g'),
                      '[[:space:]]+','','g') AS sample_name,
                    regexp_replace(regexp_replace(
                      normalize(lower(normalize(CAST(:sampleContact AS text),NFKC)),NFKD),
                      '[' || chr(768) || '-' || chr(879) || ']+','','g'),
                      '[[:space:]()（）-]+','','g') AS sample_contact
                )
                SELECT sample_name,sample_contact,
                  encode(sha256(convert_to(jsonb_build_array(
                    CAST(:domain AS text),CAST(:product AS text),CAST(:objectType AS text),
                    CAST(:year AS integer),CAST(:month AS integer),
                    sample_name,sample_contact)::text,'UTF8')),'hex') AS business_fingerprint
                FROM normalized
                """).param("sampleName", raw.sampleName()).param("sampleContact", raw.sampleContact())
                .param("domain", raw.domainCode()).param("product", raw.productCode())
                .param("objectType", raw.objectTypeCode())
                .param("year", raw.surveyYear())
                .param("month", raw.surveyMonth(), java.sql.Types.INTEGER)
                .query((row, ignored) -> new PeriodKey(raw.domainCode(), raw.productCode(),
                        raw.objectTypeCode(), raw.regionCode(), raw.surveyYear(), raw.surveyMonth(),
                        row.getString("sample_name"), row.getString("sample_contact"),
                        row.getString("business_fingerprint"))).single();
    }

    private String productionConflict(PeriodKey key) {
        return first("""
                SELECT record.record_id
                FROM production.production_record record
                WHERE record.product_code=:product AND record.object_type_code=:objectType
                  AND record.survey_year=:year
                  AND record.survey_month IS NOT DISTINCT FROM :month
                  AND record.status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')
                  AND EXISTS(SELECT 1 FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id AND metadata.field_code='PROD_SAMPLE_NAME'
                      AND %s=:sampleName)
                  AND EXISTS(SELECT 1 FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id AND metadata.field_code='PROD_SAMPLE_CONTACT'
                      AND %s=:sampleContact)
                ORDER BY record.created_at,record.record_id LIMIT 1
                """.formatted(NORMALIZED_NAME.formatted("metadata.value"),
                        NORMALIZED_CONTACT.formatted("metadata.value")), key);
    }

    private String marketConflict(PeriodKey key) {
        return first("""
                SELECT record.record_id
                FROM market.market_record record
                WHERE record.product_code=:product AND record.object_type_code=:objectType
                  AND record.survey_year=:year
                  AND record.survey_month IS NOT DISTINCT FROM :month
                  AND record.status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')
                  AND EXISTS(SELECT 1 FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id AND value.field_code='MKT_SAMPLE_NAME'
                      AND %s=:sampleName)
                  AND EXISTS(SELECT 1 FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id AND value.field_code='MKT_SAMPLE_CONTACT'
                      AND %s=:sampleContact)
                ORDER BY record.created_at,record.record_id LIMIT 1
                """.formatted(NORMALIZED_NAME.formatted("value.value"),
                        NORMALIZED_CONTACT.formatted("value.value")), key);
    }

    private String logisticsConflict(PeriodKey key) {
        return first("""
                SELECT event.event_id::text
                FROM logistics.route_event event
                WHERE event.product_code=:product
                  AND event.survey_year=:year AND event.survey_month IS NOT DISTINCT FROM :month
                  AND event.status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')
                  AND %s=:sampleName AND %s=:sampleContact
                ORDER BY event.created_at,event.event_id LIMIT 1
                """.formatted(NORMALIZED_NAME.formatted("event.source_organization"),
                        NORMALIZED_CONTACT.formatted("event.sample_contact")), key);
    }

    private String first(String sql, PeriodKey key) {
        return jdbc.sql(sql).param("product", key.productCode()).param("objectType", key.objectTypeCode())
                .param("year", key.surveyYear())
                .param("month", key.surveyMonth(), java.sql.Types.INTEGER)
                .param("sampleName", key.sampleName()).param("sampleContact", key.sampleContact())
                .query(String.class).optional().orElse(null);
    }

    private record PeriodKey(String domainCode, String productCode, String objectTypeCode,
            String regionCode, int surveyYear, Integer surveyMonth,
            String sampleName, String sampleContact, String businessFingerprint) {}

    private record RawPeriodKey(String domainCode, String productCode, String objectTypeCode,
            String regionCode, int surveyYear, Integer surveyMonth,
            String sampleName, String sampleContact) {
        private static RawPeriodKey from(ImportDraft draft) {
            String contactCode = switch (draft.domainCode()) {
                case "PRODUCTION" -> "PROD_SAMPLE_CONTACT";
                case "MARKET" -> "MKT_SAMPLE_CONTACT";
                case "LOGISTICS" -> "LOG_SAMPLE_CONTACT";
                default -> null;
            };
            if (contactCode == null) return null;
            String sampleName = draft.sampleName();
            String sampleContact = draft.values().get(contactCode);
            String yearValue = draft.values().get("surveyYear");
            String monthValue = draft.values().get("surveyMonth");
            if (blank(sampleName) || blank(sampleContact) || blank(yearValue)) return null;
            int year;
            Integer month = null;
            try {
                year = Integer.parseInt(yearValue.strip());
                if (!blank(monthValue)) month = Integer.valueOf(monthValue.strip());
            } catch (NumberFormatException exception) {
                return null;
            }
            String objectType = draft.objectTypeCode();
            if ("LOGISTICS".equals(draft.domainCode())) objectType = "ROUTE_EVENT";
            if (blank(objectType) || blank(draft.productCode()) || blank(draft.regionCode())) return null;
            return new RawPeriodKey(draft.domainCode(), draft.productCode().strip(), objectType.strip(),
                    draft.regionCode().strip(), year, month, sampleName, sampleContact);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
