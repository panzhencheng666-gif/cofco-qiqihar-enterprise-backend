package com.cofco.qiqihar.graintrade.designsample.point.infrastructure;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointDraft;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointQuery;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointRepository;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointView;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDesignSamplePointRepository implements DesignSamplePointRepository {
    private static final String REGION_PATH = """
            WITH RECURSIVE region_path(code,parent_code,path) AS (
              SELECT code,parent_code,name::text
              FROM platform.region WHERE parent_code IS NULL
              UNION ALL
              SELECT child.code,child.parent_code,parent.path || ' / ' || child.name
              FROM platform.region child
              JOIN region_path parent ON parent.code=child.parent_code
            )
            """;
    private static final String SELECT = """
            SELECT point.design_sample_point_id,point.contract_version,contract.contract_digest,
                   point.domain_code,point.product_code,point.object_type_code,
                   point.values_json::text,point.sample_name,point.region_code,
                   region_path.path AS region_path,
                   ST_X(point.governed_point)::numeric AS longitude,
                   ST_Y(point.governed_point)::numeric AS latitude,
                   point.version,point.updated_at
            FROM platform.design_sample_point point
            JOIN platform.design_sample_contract contract
              ON contract.contract_version=point.contract_version
            JOIN region_path ON region_path.code=point.region_code
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcDesignSamplePointRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<BoundaryContainment> coordinateBoundaryState(
            String regionCode, BigDecimal longitude, BigDecimal latitude) {
        return jdbc.sql("""
                SELECT CASE
                  WHEN boundary.region_code IS NULL THEN 'UNAVAILABLE'
                  WHEN ST_Covers(boundary.geometry,
                    ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)) THEN 'INSIDE'
                  ELSE 'OUTSIDE'
                END
                FROM platform.region region
                LEFT JOIN overview.administrative_boundary boundary
                  ON boundary.region_code=region.code
                WHERE region.code=:regionCode
                """).param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).query(String.class).optional()
                .map(BoundaryContainment::valueOf);
    }

    @Override
    public PagedResult<DesignSamplePointView> findPage(DesignSamplePointQuery query) {
        QueryFilter filter = filter(query);
        if (filter.empty()) {
            return new PagedResult<>(List.of(), query.pageNumber(), query.pageSize(), 0);
        }
        long total = jdbc.sql("SELECT count(*) FROM platform.design_sample_point point "
                        + filter.where())
                .params(filter.parameters()).query(Long.class).single();
        List<DesignSamplePointView> items = jdbc.sql(REGION_PATH + SELECT + filter.where()
                        + " ORDER BY point.updated_at DESC,point.design_sample_point_id"
                        + " LIMIT :limit OFFSET :offset")
                .params(filter.parameters()).param("limit", query.pageSize())
                .param("offset", Math.multiplyExact((long) query.pageNumber(), query.pageSize()))
                .query(this::map).list();
        return new PagedResult<>(items, query.pageNumber(), query.pageSize(), total);
    }

    @Override
    public Optional<DesignSamplePointView> find(UUID id) {
        return jdbc.sql(REGION_PATH + SELECT
                        + " WHERE point.design_sample_point_id=:id")
                .param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<CreateResult> insert(
            UUID id,
            DesignSamplePointDraft draft,
            Map<String, JsonNode> normalizedValues,
            String sampleName,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            String idempotencyKey,
            String requestDigest,
            String actorSubjectId,
            Instant now) {
        jdbc.sql("""
                SELECT 1
                FROM pg_catalog.pg_advisory_xact_lock(
                  pg_catalog.hashtextextended(:lockKey,0))
                """).param("lockKey", actorSubjectId + "\n" + idempotencyKey)
                .query(Integer.class).single();
        Optional<InsertRow> row = jdbc.sql("""
                WITH inserted AS (
                  INSERT INTO platform.design_sample_point(
                    design_sample_point_id,contract_version,domain_code,product_code,
                    object_type_code,values_json,sample_name,region_code,governed_point,
                    idempotency_key,request_digest,version,created_by,created_at,updated_by,updated_at)
                  VALUES(:id,:contractVersion,:domainCode,:productCode,:objectTypeCode,
                    CAST(:values AS jsonb),:sampleName,:regionCode,
                    ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),
                    :idempotencyKey,:requestDigest,0,:actor,:now,:actor,:now)
                  ON CONFLICT(created_by,idempotency_key) DO NOTHING
                  RETURNING design_sample_point_id
                ), replay AS (
                  SELECT design_sample_point_id
                  FROM platform.design_sample_point
                  WHERE created_by=:actor AND idempotency_key=:idempotencyKey
                    AND request_digest=:requestDigest
                )
                SELECT design_sample_point_id,false AS replayed FROM inserted
                UNION ALL
                SELECT design_sample_point_id,true FROM replay
                WHERE NOT EXISTS(SELECT 1 FROM inserted)
                LIMIT 1
                """).param("id", id).param("contractVersion", draft.contractVersion())
                .param("domainCode", draft.context().domainCode())
                .param("productCode", draft.context().productCode())
                .param("objectTypeCode", draft.context().objectTypeCode())
                .param("values", write(normalizedValues)).param("sampleName", sampleName)
                .param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).param("idempotencyKey", idempotencyKey)
                .param("requestDigest", requestDigest).param("actor", actorSubjectId)
                .param("now", Timestamp.from(now))
                .query((result, index) -> new InsertRow(
                        result.getObject("design_sample_point_id", UUID.class),
                        result.getBoolean("replayed")))
                .optional();
        if (row.isEmpty()) {
            // ON CONFLICT may wait for a concurrent insert that was not visible in this
            // statement's snapshot. A new statement sees that commit and can distinguish
            // a valid replay from reuse of the key for a different request.
            row = jdbc.sql("""
                    SELECT design_sample_point_id,true AS replayed
                    FROM platform.design_sample_point
                    WHERE created_by=:actor AND idempotency_key=:idempotencyKey
                      AND request_digest=:requestDigest
                    """).param("actor", actorSubjectId)
                    .param("idempotencyKey", idempotencyKey)
                    .param("requestDigest", requestDigest)
                    .query((result, index) -> new InsertRow(
                            result.getObject("design_sample_point_id", UUID.class), true))
                    .optional();
        }
        return row.map(value -> new CreateResult(required(value.id()), value.replayed()));
    }

    @Override
    public Optional<DesignSamplePointView> update(
            UUID id,
            long expectedVersion,
            DesignSamplePointDraft draft,
            Map<String, JsonNode> normalizedValues,
            String sampleName,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            String actorSubjectId,
            Instant now) {
        int updated = jdbc.sql("""
                UPDATE platform.design_sample_point
                SET contract_version=:contractVersion,domain_code=:domainCode,
                    product_code=:productCode,object_type_code=:objectTypeCode,
                    values_json=CAST(:values AS jsonb),sample_name=:sampleName,
                    region_code=:regionCode,
                    governed_point=ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),
                    version=version+1,updated_by=:actor,updated_at=:now
                WHERE design_sample_point_id=:id AND version=:expectedVersion
                """).param("contractVersion", draft.contractVersion())
                .param("domainCode", draft.context().domainCode())
                .param("productCode", draft.context().productCode())
                .param("objectTypeCode", draft.context().objectTypeCode())
                .param("values", write(normalizedValues)).param("sampleName", sampleName)
                .param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).param("actor", actorSubjectId)
                .param("now", Timestamp.from(now)).param("id", id)
                .param("expectedVersion", expectedVersion).update();
        return updated == 0 ? Optional.empty() : find(id);
    }

    @Override
    public boolean delete(UUID id, long expectedVersion) {
        return jdbc.sql("""
                DELETE FROM platform.design_sample_point
                WHERE design_sample_point_id=:id AND version=:expectedVersion
                """).param("id", id).param("expectedVersion", expectedVersion).update() == 1;
    }

    private DesignSamplePointView required(UUID id) {
        return find(id).orElseThrow(() -> new ServerContractException(
                "DESIGN_SAMPLE_POINT_WRITE_LOST", "设计样本点写入结果不可重查"));
    }

    private DesignSamplePointView map(ResultSet row, int index) throws SQLException {
        return new DesignSamplePointView(
                row.getObject("design_sample_point_id", UUID.class),
                row.getString("contract_version"), row.getString("contract_digest"),
                new DesignSampleContext(row.getString("domain_code"),
                        row.getString("product_code"), row.getString("object_type_code")),
                read(row.getString("values_json")), row.getString("sample_name"),
                row.getString("region_code"), row.getString("region_path"),
                row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                row.getLong("version"), row.getTimestamp("updated_at").toInstant());
    }

    private QueryFilter filter(DesignSamplePointQuery query) {
        if (query.authorizedRegionCodes().isEmpty()) return QueryFilter.noRows();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (!query.authorizedRegionCodes().contains("*")) {
            where.append(" AND point.region_code IN (:authorizedRegions)");
            parameters.put("authorizedRegions", query.authorizedRegionCodes());
        }
        add(where, parameters, "domain_code", "domainCode", query.domainCode());
        add(where, parameters, "product_code", "productCode", query.productCode());
        add(where, parameters, "object_type_code", "objectTypeCode", query.objectTypeCode());
        add(where, parameters, "region_code", "regionCode", query.regionCode());
        if (query.keyword() != null) {
            where.append(" AND lower(point.sample_name) LIKE :keyword");
            parameters.put("keyword", "%" + query.keyword().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return new QueryFilter(where.toString(), parameters, false);
    }

    private static void add(
            StringBuilder where, Map<String, Object> parameters,
            String column, String parameter, String value) {
        if (value != null) {
            where.append(" AND point.").append(column).append("=:").append(parameter);
            parameters.put(parameter, value);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize design sample point values", exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JacksonException exception) {
            throw new ServerContractException(
                    "DESIGN_SAMPLE_POINT_VALUES_INVALID", "设计样本点持久化字段无效");
        }
    }

    private record InsertRow(UUID id, boolean replayed) {}

    private record QueryFilter(String where, Map<String, Object> parameters, boolean empty) {
        static QueryFilter noRows() {
            return new QueryFilter(" WHERE false", Map.of(), true);
        }
    }
}
