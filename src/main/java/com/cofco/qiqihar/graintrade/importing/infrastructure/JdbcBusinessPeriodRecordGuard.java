package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.BusinessPeriodRecordGuard;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessPeriodRecordGuard implements BusinessPeriodRecordGuard {
    private static final String NORMALIZED_NAME =
            "lower(regexp_replace(normalize(%s,NFKC),'[[:space:]　]+','','g'))";
    private static final String NORMALIZED_CONTACT =
            "lower(regexp_replace(normalize(%s,NFKC),'[[:space:]　()（）-]+','','g'))";
    private final JdbcClient jdbc;

    public JdbcBusinessPeriodRecordGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockAndRequireAvailable(ImportDraft draft) {
        PeriodKey key = PeriodKey.from(draft);
        if (key == null) return;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", key.lockKey()).query((row, ignored) -> Boolean.TRUE).single();
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

    private String productionConflict(PeriodKey key) {
        return first("""
                SELECT record.record_id
                FROM production.production_record record
                WHERE record.product_code=:product AND record.object_type_code=:objectType
                  AND record.region_code=:region AND record.survey_year=:year
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
                  AND record.region_code=:region AND record.survey_year=:year
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
                WHERE event.product_code=:product AND event.business_region_code=:region
                  AND event.survey_year=:year AND event.survey_month IS NOT DISTINCT FROM :month
                  AND event.status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')
                  AND %s=:sampleName AND %s=:sampleContact
                ORDER BY event.created_at,event.event_id LIMIT 1
                """.formatted(NORMALIZED_NAME.formatted("event.source_organization"),
                        NORMALIZED_CONTACT.formatted("event.sample_contact")), key);
    }

    private String first(String sql, PeriodKey key) {
        return jdbc.sql(sql).param("product", key.productCode()).param("objectType", key.objectTypeCode())
                .param("region", key.regionCode()).param("year", key.surveyYear())
                .param("month", key.surveyMonth(), java.sql.Types.INTEGER)
                .param("sampleName", key.sampleName()).param("sampleContact", key.sampleContact())
                .query(String.class).optional().orElse(null);
    }

    private record PeriodKey(String domainCode, String productCode, String objectTypeCode,
            String regionCode, int surveyYear, Integer surveyMonth,
            String sampleName, String sampleContact) {
        private static PeriodKey from(ImportDraft draft) {
            String contactCode = switch (draft.domainCode()) {
                case "PRODUCTION" -> "PROD_SAMPLE_CONTACT";
                case "MARKET" -> "MKT_SAMPLE_CONTACT";
                case "LOGISTICS" -> "LOG_SAMPLE_CONTACT";
                default -> null;
            };
            if (contactCode == null) return null;
            String sampleName = SampleIdentityAssessment.normalizedName(draft.sampleName());
            String sampleContact = SampleIdentityAssessment.normalizedContact(draft.values().get(contactCode));
            String yearValue = draft.values().get("surveyYear");
            String monthValue = draft.values().get("surveyMonth");
            if (sampleName.isEmpty() || sampleContact.isEmpty() || blank(yearValue)) return null;
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
            return new PeriodKey(draft.domainCode(), draft.productCode().strip(), objectType.strip(),
                    draft.regionCode().strip(), year, month, sampleName, sampleContact);
        }

        private String lockKey() {
            return "BUSINESS_PERIOD_RECORD|" + framed(List.of(domainCode, productCode, objectTypeCode,
                    regionCode, Integer.toString(surveyYear), surveyMonth == null ? "" : surveyMonth.toString(),
                    sampleName, sampleContact));
        }

        private static String framed(List<String> values) {
            return values.stream().map(value -> value.length() + ":" + value)
                    .collect(java.util.stream.Collectors.joining("|"));
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
