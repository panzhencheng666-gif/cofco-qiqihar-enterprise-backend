package com.cofco.qiqihar.graintrade.formalsampleobservation.infrastructure;

import com.cofco.qiqihar.graintrade.formalsampleobservation.application.EligibleFormalSample;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationDomain;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationRepository;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePoint;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePointReader;
import com.cofco.qiqihar.graintrade.shared.application.FormalSampleIdentity;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationResult;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationHistoryItem;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationHistoryPage;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.StoredFormalSampleObservation;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFormalSampleObservationRepository implements FormalSampleObservationRepository {
    private static final String LOGISTICS_PUBLIC_VALUES_SQL = """
            jsonb_build_object('productCode',event.product_code,
              'surveyYear',event.survey_year::text,
              'surveyMonth',COALESCE(event.survey_month::text,''),
              'LOG_SAMPLE_NAME',event.source_organization,
              'LOG_REGION',event.business_region_code,
              'LOG_REPORTER',event.reporter,
              'LOG_SAMPLE_CONTACT',event.sample_contact,
              'LOG_SAMPLE_LATITUDE',event.sample_latitude::text,
              'LOG_SAMPLE_LONGITUDE',event.sample_longitude::text,
              'LOG_TRANSPORT_MODE',event.transport_mode_code,
              'LOG_DIRECTION',event.direction_code)
            || COALESCE((SELECT jsonb_object_agg(value.field_code,value.value)
                FROM logistics.route_event_core_value value
                WHERE value.event_id=event.event_id),'{}'::jsonb)
            || COALESCE((SELECT jsonb_object_agg(definition.code,fact.value::text)
                FROM logistics.route_fact fact
                JOIN platform.logistics_core_field_definition definition
                  ON definition.binding='FACT.' || fact.fact_code
                JOIN platform.logistics_core_field_applicability applicability
                  ON applicability.field_code=definition.code
                 AND applicability.product_code=event.product_code
                WHERE fact.event_id=event.event_id),'{}'::jsonb)
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentOverviewSamplePointReader currentOverviewSamplePoints;

    public JdbcFormalSampleObservationRepository(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            CurrentOverviewSamplePointReader currentOverviewSamplePoints) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.currentOverviewSamplePoints = currentOverviewSamplePoints;
    }

    @Override
    public List<EligibleFormalSample> findEligibleSamples(
            FormalSampleObservationDomain domain,
            String productCode,
            String regionCode,
            String objectTypeCode,
            String keywordPattern,
            LocalDate observedOn,
            Set<String> authorizedRegionCodes) {
        if (authorizedRegionCodes.isEmpty()) return List.of();
        Set<UUID> currentSamplePointIds = currentOverviewSamplePoints.readAtLifecycleCutoff(
                        observedOn.getYear(), productCode, regionCode, domain.name(), observedOn,
                        authorizedRegionCodes).stream()
                .map(CurrentOverviewSamplePoint::samplePointId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (currentSamplePointIds.isEmpty()) return List.of();
        String latest = switch (domain) {
            case PRODUCTION -> """
                    SELECT record.record_id source_record_id,
                           record.object_type_code,
                           record.reported_at latest_observed_at,
                           jsonb_build_object(
                             'productCode',record.product_code,
                             'objectTypeCode',record.object_type_code,
                             'regionCode',record.region_code,
                             'surveyYear',record.survey_year::text,
                             'surveyMonth',COALESCE(record.survey_month::text,''),
                             'cultivatedAreaMu',record.cultivated_area_mu::text,
                             'yieldPerMuKilograms',record.yield_per_mu_kg::text)
                           || COALESCE((SELECT jsonb_object_agg(metadata.field_code,metadata.value)
                               FROM production.production_record_submission_metadata metadata
                               WHERE metadata.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.quality_code,value.value::text)
                               FROM production.production_record_quality value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.cost_code,value.value::text)
                               FROM production.production_record_cost value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.insurance_code,value.value::text)
                               FROM production.production_record_insurance value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.subsidy_code,value.value::text)
                               FROM production.production_record_subsidy value
                               WHERE value.record_id=record.record_id),'{}'::jsonb) latest_values
                    FROM production.production_record record
                    WHERE record.sample_point_id=point.sample_point_id
                      AND record.product_code=:productCode
                      AND record.survey_year=:year
                      AND record.status_code='APPROVED'
                      AND record.survey_period_governance_state='CONFIRMED'
                    ORDER BY record.survey_date DESC,record.updated_at DESC,record.record_id DESC
                    LIMIT 1
                    """;
            case MARKET -> """
                    SELECT record.record_id source_record_id,
                           record.object_type_code,
                           record.reported_at latest_observed_at,
                           jsonb_strip_nulls(jsonb_build_object('productCode',record.product_code,
                             'MKT_OBJECT_TYPE',record.object_type_code,
                             'MKT_REGION',record.region_code,
                             'MKT_TRADE_DATE',record.trade_date::text,
                             'MKT_PURCHASE_BASE_PRICE',record.purchase_base_price::text,
                             'MKT_SALE_BASE_PRICE',record.sale_base_price::text,
                             'MKT_CARRIAGE_BOARD_AMOUNT',record.carriage_board_amount::text,
                             'MKT_PACKAGING_AMOUNT',record.packaging_amount::text,
                             'MKT_FREIGHT_AMOUNT',record.freight_amount::text,
                             'MKT_PACKAGING_FORM',record.packaging_form))
                           || COALESCE((SELECT jsonb_object_agg(value.field_code,value.value)
                               FROM market.market_record_core_value value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(fact.fact_code,fact.value::text)
                               FROM market.market_record_fact fact
                               WHERE fact.record_id=record.record_id),'{}'::jsonb) latest_values
                    FROM market.market_record record
                    WHERE record.sample_point_id=point.sample_point_id
                      AND record.product_code=:productCode
                      AND record.survey_year=:year
                      AND record.status_code='APPROVED'
                      AND record.survey_period_governance_state='CONFIRMED'
                    ORDER BY record.trade_date DESC,record.updated_at DESC,record.record_id DESC
                    LIMIT 1
                    """;
            case LOGISTICS -> """
                    SELECT event.event_id::text source_record_id,
                           NULL::varchar object_type_code,
                           event.reported_at latest_observed_at,
                           %s latest_values
                    FROM logistics.route_event event
                    WHERE event.sample_point_id=point.sample_point_id
                      AND event.product_code=:productCode
                      AND event.survey_year=:year
                      AND event.status_code='APPROVED'
                      AND event.survey_period_governance_state='CONFIRMED'
                    ORDER BY event.collection_date DESC,event.updated_at DESC,event.event_id DESC
                    LIMIT 1
                    """.formatted(LOGISTICS_PUBLIC_VALUES_SQL);
        };
        return jdbc.sql("""
                SELECT point.sample_point_id,point.canonical_name,latest.object_type_code,
                       object_type.name object_type_name,point.region_code,region.name region_name,
                       trim(to_char(ST_Y(point.governed_point),'FM999990.0000000')) latitude,
                       trim(to_char(ST_X(point.governed_point),'FM999990.0000000')) longitude,
                       point.version coordinate_version,point.effective_from,point.effective_to,
                       latest.source_record_id,latest.latest_observed_at,latest.latest_values
                FROM registry.sample_point point
                JOIN platform.region region ON region.code=point.region_code
                JOIN LATERAL (
                """ + latest + """
                ) latest ON true
                LEFT JOIN platform.object_type object_type ON object_type.code=latest.object_type_code
                WHERE point.kind_code=CASE WHEN :domain='LOGISTICS' THEN point.kind_code ELSE 'SURVEY_SITE' END
                  AND (:domain<>'LOGISTICS' OR point.kind_code IN ('SURVEY_SITE','LOGISTICS_NODE'))
                  AND point.approval_state='APPROVED' AND point.location_state='VALID'
                  AND point.governed_point IS NOT NULL
                  AND point.effective_from<=:observedOn
                  AND (point.effective_to IS NULL OR point.effective_to>=:observedOn)
                  AND point.sample_point_id IN (:currentSamplePointIds)
                  AND point.region_code IN (:authorizedRegionCodes)
                  AND (CAST(:regionCode AS varchar) IS NULL OR point.region_code=:regionCode)
                  AND (CAST(:objectTypeCode AS varchar) IS NULL OR latest.object_type_code=:objectTypeCode)
                  AND (CAST(:keywordPattern AS varchar) IS NULL
                    OR point.canonical_name ILIKE :keywordPattern ESCAPE '\\'
                    OR region.name ILIKE :keywordPattern ESCAPE '\\')
                ORDER BY point.canonical_name,point.sample_point_id
                """).param("productCode", productCode)
                .param("year", observedOn.getYear())
                .param("domain", domain.name())
                .param("observedOn", observedOn)
                .param("currentSamplePointIds", currentSamplePointIds)
                .param("authorizedRegionCodes", authorizedRegionCodes)
                .param("regionCode", regionCode, java.sql.Types.VARCHAR)
                .param("objectTypeCode", objectTypeCode, java.sql.Types.VARCHAR)
                .param("keywordPattern", keywordPattern, java.sql.Types.VARCHAR)
                .query((row, ignored) -> new EligibleFormalSample(
                        row.getObject("sample_point_id", java.util.UUID.class),
                        row.getString("canonical_name"), row.getString("object_type_code"),
                        row.getString("object_type_name"), domain, productCode,
                        row.getString("region_code"), row.getString("region_name"),
                        row.getString("latitude"), row.getString("longitude"),
                        row.getLong("coordinate_version"),
                        row.getObject("effective_from", LocalDate.class),
                        row.getObject("effective_to", LocalDate.class),
                        row.getString("source_record_id"),
                        row.getObject("latest_observed_at", OffsetDateTime.class),
                        json(row.getString("latest_values"))))
                .list();
    }

    @Override
    public Optional<String> findObjectTypeName(
            FormalSampleObservationDomain domain, String productCode, String objectTypeCode) {
        if (domain == FormalSampleObservationDomain.LOGISTICS) return Optional.empty();
        return jdbc.sql("""
                SELECT object_type.name
                FROM platform.object_type object_type
                JOIN platform.product_object_type_applicability applicability
                  ON applicability.object_type_code=object_type.code
                WHERE applicability.product_code=:productCode
                  AND object_type.business_domain=:domain
                  AND object_type.code=:objectTypeCode
                """).param("productCode", productCode).param("domain", domain.name())
                .param("objectTypeCode", objectTypeCode).query(String.class).optional();
    }

    @Override
    public FormalSampleObservationHistoryPage findHistory(
            FormalSampleObservationDomain domain,
            UUID samplePointId,
            String productCode,
            int year,
            int pageNumber,
            int pageSize,
            Set<String> authorizedRegionCodes) {
        if (authorizedRegionCodes.isEmpty()) {
            return new FormalSampleObservationHistoryPage(List.of(), 0, pageNumber, pageSize);
        }
        String history = switch (domain) {
            case PRODUCTION -> """
                    SELECT record.record_id::text source_record_id,record.reported_at observed_at,
                           COALESCE(receipt.official_saved_at,record.updated_at,record.reported_at) official_saved_at,
                           COALESCE(NULLIF(actor.display_name,''),'历史导入') actor_display_name,
                           receipt.observation_id,receipt.projection_version,record.survey_year,
                           jsonb_build_object('productCode',record.product_code,
                             'objectTypeCode',record.object_type_code,'regionCode',record.region_code,
                             'surveyYear',record.survey_year::text,
                             'surveyMonth',COALESCE(record.survey_month::text,''),
                             'cultivatedAreaMu',record.cultivated_area_mu::text,
                             'yieldPerMuKilograms',record.yield_per_mu_kg::text)
                           || COALESCE((SELECT jsonb_object_agg(value.field_code,value.value)
                               FROM production.production_record_submission_metadata value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.quality_code,value.value::text)
                               FROM production.production_record_quality value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.cost_code,value.value::text)
                               FROM production.production_record_cost value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.insurance_code,value.value::text)
                               FROM production.production_record_insurance value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.subsidy_code,value.value::text)
                               FROM production.production_record_subsidy value
                               WHERE value.record_id=record.record_id),'{}'::jsonb) values,
                           ROW_NUMBER() OVER (ORDER BY record.survey_date DESC,record.updated_at DESC,
                             record.record_id DESC)=1 latest
                    FROM production.production_record record
                    JOIN registry.sample_point point ON point.sample_point_id=record.sample_point_id
                    LEFT JOIN platform.formal_sample_observation receipt
                      ON receipt.source_domain='PRODUCTION' AND receipt.source_record_id=record.record_id::text
                    LEFT JOIN platform.security_user actor ON actor.subject_id=record.last_modified_by
                    WHERE record.sample_point_id=:samplePointId AND record.product_code=:productCode
                      AND record.status_code='APPROVED'
                      AND record.survey_period_governance_state='CONFIRMED'
                      AND point.region_code IN (:authorizedRegionCodes)
                    """;
            case MARKET -> """
                    SELECT record.record_id::text source_record_id,record.reported_at observed_at,
                           COALESCE(receipt.official_saved_at,record.updated_at,record.reported_at) official_saved_at,
                           COALESCE(NULLIF(actor.display_name,''),'历史导入') actor_display_name,
                           receipt.observation_id,receipt.projection_version,record.survey_year,
                           jsonb_strip_nulls(jsonb_build_object('productCode',record.product_code,
                             'MKT_OBJECT_TYPE',record.object_type_code,'MKT_REGION',record.region_code,
                             'MKT_TRADE_DATE',record.trade_date::text,
                             'MKT_PURCHASE_BASE_PRICE',record.purchase_base_price::text,
                             'MKT_SALE_BASE_PRICE',record.sale_base_price::text,
                             'MKT_CARRIAGE_BOARD_AMOUNT',record.carriage_board_amount::text,
                             'MKT_PACKAGING_AMOUNT',record.packaging_amount::text,
                             'MKT_FREIGHT_AMOUNT',record.freight_amount::text,
                             'MKT_PACKAGING_FORM',record.packaging_form))
                           || COALESCE((SELECT jsonb_object_agg(value.field_code,value.value)
                               FROM market.market_record_core_value value
                               WHERE value.record_id=record.record_id),'{}'::jsonb)
                           || COALESCE((SELECT jsonb_object_agg(value.fact_code,value.value::text)
                               FROM market.market_record_fact value
                               WHERE value.record_id=record.record_id),'{}'::jsonb) values,
                           ROW_NUMBER() OVER (ORDER BY record.trade_date DESC,record.updated_at DESC,
                             record.record_id DESC)=1 latest
                    FROM market.market_record record
                    JOIN registry.sample_point point ON point.sample_point_id=record.sample_point_id
                    LEFT JOIN platform.formal_sample_observation receipt
                      ON receipt.source_domain='MARKET' AND receipt.source_record_id=record.record_id::text
                    LEFT JOIN platform.security_user actor ON actor.subject_id=record.last_modified_by
                    WHERE record.sample_point_id=:samplePointId AND record.product_code=:productCode
                      AND record.status_code='APPROVED'
                      AND record.survey_period_governance_state='CONFIRMED'
                      AND point.region_code IN (:authorizedRegionCodes)
                    """;
            case LOGISTICS -> """
                    SELECT event.event_id::text source_record_id,event.reported_at observed_at,
                           COALESCE(receipt.official_saved_at,event.updated_at,event.reported_at) official_saved_at,
                           COALESCE(NULLIF(actor.display_name,''),'历史导入') actor_display_name,
                           receipt.observation_id,receipt.projection_version,event.survey_year,
                           %s values,
                           ROW_NUMBER() OVER (ORDER BY event.collection_date DESC,event.updated_at DESC,
                             event.event_id DESC)=1 latest
                    FROM logistics.route_event event
                    JOIN registry.sample_point point ON point.sample_point_id=event.sample_point_id
                    LEFT JOIN platform.formal_sample_observation receipt
                      ON receipt.source_domain='LOGISTICS' AND receipt.source_record_id=event.event_id::text
                    LEFT JOIN platform.security_user actor ON actor.subject_id=event.last_modified_by
                    WHERE event.sample_point_id=:samplePointId AND event.product_code=:productCode
                      AND event.status_code='APPROVED'
                      AND event.survey_period_governance_state='CONFIRMED'
                      AND point.region_code IN (:authorizedRegionCodes)
                    """.formatted(LOGISTICS_PUBLIC_VALUES_SQL);
        };
        long total = jdbc.sql("""
                WITH all_history AS (
                """ + history + """
                )
                SELECT COUNT(*) FROM all_history WHERE survey_year=:year
                """).param("samplePointId", samplePointId).param("productCode", productCode)
                .param("authorizedRegionCodes", authorizedRegionCodes).param("year", year)
                .query(Long.class).single();
        List<FormalSampleObservationHistoryItem> items = new java.util.ArrayList<>();
        jdbc.sql("""
                WITH all_history AS (
                """ + history + """
                )
                SELECT * FROM all_history WHERE survey_year=:year
                ORDER BY observed_at DESC,official_saved_at DESC,source_record_id DESC
                OFFSET :offset ROWS FETCH NEXT :pageSize ROWS ONLY
                """).param("samplePointId", samplePointId).param("productCode", productCode)
                .param("authorizedRegionCodes", authorizedRegionCodes).param("year", year)
                .param("offset", pageNumber * pageSize).param("pageSize", pageSize)
                .query((row, ignored) -> {
                    return new FormalSampleObservationHistoryItem(
                            row.getObject("observation_id", UUID.class),
                            row.getObject("observed_at", OffsetDateTime.class),
                            row.getObject("official_saved_at", OffsetDateTime.class),
                            row.getString("actor_display_name"), row.getString("projection_version"),
                            synchronizedModules(domain), json(row.getString("values")), row.getBoolean("latest"));
                }).list().forEach(items::add);
        return new FormalSampleObservationHistoryPage(items, total, pageNumber, pageSize);
    }

    @Override
    public void lockIdempotencyScope(
            String actorSubjectId,
            FormalSampleObservationDomain domain,
            String idempotencyKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .param("scope", actorSubjectId + ":" + domain + ":" + idempotencyKey)
                .query((row, ignored) -> Boolean.TRUE).single();
    }

    @Override
    public Optional<StoredFormalSampleObservation> findStored(
            String actorSubjectId,
            FormalSampleObservationDomain domain,
            String idempotencyKey) {
        return jdbc.sql("""
                SELECT request_sha256,response_json::text
                FROM platform.formal_sample_observation
                WHERE actor_subject_id=:actor AND source_domain=:domain
                  AND idempotency_key=:idempotencyKey
                """).param("actor", actorSubjectId).param("domain", domain.name())
                .param("idempotencyKey", idempotencyKey)
                .query((row, ignored) -> new StoredFormalSampleObservation(
                        row.getString("request_sha256"),
                        read(row.getString("response_json"), FormalSampleObservationResult.class)))
                .optional();
    }

    @Override
    public FormalSampleIdentity lockEligibleSample(
            FormalSampleObservationDomain domain,
            UUID samplePointId,
            String productCode,
            LocalDate observedOn,
            Set<String> authorizedRegionCodes) {
        if (authorizedRegionCodes.isEmpty()) throw unavailable();
        String regionCode = jdbc.sql("""
                SELECT region_code FROM registry.sample_point
                WHERE sample_point_id=:samplePointId
                  AND approval_state='APPROVED' AND location_state='VALID'
                  AND governed_point IS NOT NULL
                  AND effective_from<=:observedOn
                  AND (effective_to IS NULL OR effective_to>=:observedOn)
                  AND region_code IN (:authorizedRegionCodes)
                FOR UPDATE
                """).param("samplePointId", samplePointId).param("observedOn", observedOn)
                .param("authorizedRegionCodes", authorizedRegionCodes)
                .query(String.class).optional().orElseThrow(JdbcFormalSampleObservationRepository::unavailable);
        EligibleFormalSample eligible = findEligibleSamples(
                domain, productCode, regionCode, null, null, observedOn, authorizedRegionCodes).stream()
                .filter(sample -> sample.samplePointId().equals(samplePointId))
                .findFirst().orElseThrow(JdbcFormalSampleObservationRepository::unavailable);
        return new FormalSampleIdentity(
                eligible.samplePointId(), eligible.sampleName(), productCode,
                eligible.regionCode(), eligible.latitude(), eligible.longitude(),
                eligible.effectiveFrom(), eligible.effectiveTo(), eligible.latestValues());
    }

    @Override
    public void store(
            String actorSubjectId,
            String idempotencyKey,
            String requestSha256,
            String sourceRecordId,
            FormalSampleObservationResult result) {
        jdbc.sql("""
                INSERT INTO platform.formal_sample_observation(
                  observation_id,source_domain,source_record_id,sample_point_id,product_code,
                  observed_at,official_saved_at,actor_subject_id,idempotency_key,request_sha256,
                  projection_version,response_json)
                VALUES(:observationId,:domain,:sourceRecordId,:samplePointId,:productCode,
                  :observedAt,:officialSavedAt,:actor,:idempotencyKey,:requestSha256,
                  :projectionVersion,CAST(:responseJson AS jsonb))
                """).param("observationId", result.observationId())
                .param("domain", result.domain().name()).param("sourceRecordId", sourceRecordId)
                .param("samplePointId", result.samplePointId()).param("productCode", result.productCode())
                .param("observedAt", result.observedAt()).param("officialSavedAt", result.officialSavedAt())
                .param("actor", actorSubjectId).param("idempotencyKey", idempotencyKey)
                .param("requestSha256", requestSha256).param("projectionVersion", result.projectionVersion())
                .param("responseJson", write(result)).update();
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Formal sample observation cannot be serialized", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored formal sample observation is invalid", exception);
        }
    }

    private static List<String> synchronizedModules(FormalSampleObservationDomain domain) {
        return switch (domain) {
            case PRODUCTION -> List.of("OVERVIEW", "PRODUCTION_ANALYSIS", "REPORTS");
            case MARKET -> List.of("OVERVIEW", "MARKET_ANALYSIS", "REPORTS");
            case LOGISTICS -> List.of("OVERVIEW", "LOGISTICS_ANALYSIS", "REPORTS");
        };
    }

    private static ResourceNotFoundException unavailable() {
        return new ResourceNotFoundException(
                "FORMAL_SAMPLE_NOT_AVAILABLE", "该正式样本不存在、已失效或不在当前授权范围");
    }
}
