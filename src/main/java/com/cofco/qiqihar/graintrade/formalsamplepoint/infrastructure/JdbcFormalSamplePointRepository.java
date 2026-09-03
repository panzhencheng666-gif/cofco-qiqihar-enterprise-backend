package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointDraft;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleMaintainerView;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointRepository;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointView;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFormalSamplePointRepository implements FormalSamplePointRepository {
    private static final String SELECT_VIEW = """
            SELECT point.sample_point_id,point.kind_code,point.canonical_name,
                   point.region_code,point.approval_state,point.location_state,
                   profile.object_type_code,object_type.name AS object_type_name,
                   object_type.business_domain,profile.address,
                   point.maintainer_subject_id,maintainer.display_name maintainer_display_name,
                   ST_X(point.governed_point) longitude,
                   ST_Y(point.governed_point) latitude,
                   point.effective_from,point.effective_to,point.version,
                   ((SELECT count(*) FROM production.production_record record
                       WHERE record.sample_point_id=point.sample_point_id)
                    +(SELECT count(*) FROM market.market_record record
                       WHERE record.sample_point_id=point.sample_point_id)
                    +(SELECT count(*) FROM logistics.route_event event
                       WHERE event.sample_point_id=point.sample_point_id)) annual_observation_count,
                   (SELECT count(*) FROM registry.sample_network_membership membership
                       WHERE membership.sample_point_id=point.sample_point_id) network_membership_count
            FROM registry.sample_point point
            LEFT JOIN registry.formal_sample_point_profile profile
              ON profile.sample_point_id=point.sample_point_id
            LEFT JOIN platform.object_type object_type
              ON object_type.code=profile.object_type_code
            LEFT JOIN platform.security_user maintainer
              ON maintainer.subject_id=point.maintainer_subject_id
            """;
    private final JdbcClient jdbc;

    public JdbcFormalSamplePointRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public PagedResult<FormalSamplePointView> findPage(
            String regionCode, String keyword, int pageNumber, int pageSize,
            Set<String> authorizedRegionCodes) {
        Filter filter = filter(regionCode, keyword, authorizedRegionCodes);
        long total = jdbc.sql("SELECT count(*) FROM registry.sample_point point " + filter.sql())
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) pageNumber, pageSize);
        var items = jdbc.sql(SELECT_VIEW + filter.sql() + """
                         ORDER BY point.canonical_name,point.sample_point_id
                        LIMIT :limit OFFSET :offset
                        """).params(filter.parameters())
                .param("limit", pageSize).param("offset", offset)
                .query((row, ignored) -> view(row)).list();
        return new PagedResult<>(items, pageNumber, pageSize, total);
    }

    @Override
    public Optional<FormalSamplePointView> find(UUID id) {
        return jdbc.sql(SELECT_VIEW + """
                        WHERE point.kind_code IN ('SURVEY_SITE','LOGISTICS_NODE')
                          AND point.deletion_state='ACTIVE'
                          AND point.sample_point_id=:id
                        """).param("id", id).query((row, ignored) -> view(row)).optional();
    }

    @Override
    public Optional<FormalSampleMaintainerView> findMaintainerTarget(UUID id) {
        return jdbc.sql("""
                SELECT point.sample_point_id,point.kind_code,point.canonical_name,
                       point.region_code,point.maintainer_subject_id,
                       maintainer.display_name maintainer_display_name,point.version
                FROM registry.sample_point point
                LEFT JOIN platform.security_user maintainer
                  ON maintainer.subject_id=point.maintainer_subject_id
                WHERE point.sample_point_id=:id
                  AND point.kind_code IN ('SURVEY_SITE','LOGISTICS_NODE')
                  AND point.deletion_state='ACTIVE'
                """).param("id", id).query((row, ignored) -> new FormalSampleMaintainerView(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("kind_code"), row.getString("canonical_name"),
                        row.getString("region_code"), row.getString("maintainer_subject_id"),
                        row.getString("maintainer_display_name"), row.getLong("version")))
                .optional();
    }

    @Override
    public Optional<FormalSampleMaintainerView> assignMaintainer(
            UUID id, long expectedVersion, String maintainerSubjectId,
            String actorSubjectId, Instant now) {
        int updated = jdbc.sql("""
                UPDATE registry.sample_point
                SET maintainer_subject_id=:maintainer,version=version+1,
                    updated_by=:actor,updated_at=:now
                WHERE sample_point_id=:id
                  AND kind_code IN ('SURVEY_SITE','LOGISTICS_NODE')
                  AND deletion_state='ACTIVE'
                  AND version=:expectedVersion
                """).param("maintainer", maintainerSubjectId)
                .param("actor", actorSubjectId).param("now", Timestamp.from(now))
                .param("id", id).param("expectedVersion", expectedVersion).update();
        return updated == 0 ? Optional.empty() : findMaintainerTarget(id);
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
    public boolean isSupportedObjectType(String objectTypeCode) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.object_type
                  WHERE code=:code AND overview_enabled)
                """).param("code", objectTypeCode).query(Boolean.class).single();
    }

    @Override
    public Optional<FormalSamplePointView> insert(
            UUID id, FormalSamplePointDraft draft, String actorSubjectId,
            LocalDate effectiveFrom, Instant now) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,version,
                  maintainer_subject_id,created_by,created_at,updated_by,updated_at)
                VALUES(:id,'SURVEY_SITE',:name,:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),:effectiveFrom,0,
                  :maintainer,:actor,:now,:actor,:now)
                """).param("id", id).param("name", draft.canonicalName())
                .param("region", draft.regionCode()).param("longitude", draft.longitude())
                .param("latitude", draft.latitude()).param("effectiveFrom", effectiveFrom)
                .param("maintainer", draft.maintainerSubjectId())
                .param("actor", actorSubjectId).param("now", Timestamp.from(now)).update();
        insertProfile(id, draft, actorSubjectId, now);
        return find(id);
    }

    @Override
    public Optional<FormalSamplePointView> update(
            UUID id, long expectedVersion, FormalSamplePointDraft draft,
            String actorSubjectId, Instant now) {
        int updated = jdbc.sql("""
                UPDATE registry.sample_point
                SET canonical_name=:name,region_code=:region,
                    governed_point=ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),
                    maintainer_subject_id=:maintainer,
                    version=version+1,updated_by=:actor,updated_at=:now
                WHERE sample_point_id=:id AND kind_code='SURVEY_SITE'
                  AND deletion_state='ACTIVE'
                  AND version=:expectedVersion
                """).param("name", draft.canonicalName()).param("region", draft.regionCode())
                .param("longitude", draft.longitude()).param("latitude", draft.latitude())
                .param("maintainer", draft.maintainerSubjectId())
                .param("actor", actorSubjectId).param("now", Timestamp.from(now))
                .param("id", id).param("expectedVersion", expectedVersion).update();
        if (updated == 0) return Optional.empty();
        jdbc.sql("""
                INSERT INTO registry.formal_sample_point_profile(
                  sample_point_id,object_type_code,address,created_by,created_at,updated_by,updated_at)
                VALUES(:id,:objectType,:address,:actor,:now,:actor,:now)
                ON CONFLICT(sample_point_id) DO UPDATE SET
                  object_type_code=EXCLUDED.object_type_code,address=EXCLUDED.address,
                  updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at
                """).param("id", id).param("objectType", draft.objectTypeCode())
                .param("address", draft.address())
                .param("actor", actorSubjectId)
                .param("now", Timestamp.from(now)).update();
        return find(id);
    }

    @Override
    public DeleteResult delete(
            UUID id, long expectedVersion, String expectedRegionCode, String actorSubjectId) {
        String result = jdbc.sql("""
                SELECT registry.delete_formal_sample_point(:id,:version,:region,:actor)
                """).param("id", id).param("version", expectedVersion)
                .param("region", expectedRegionCode)
                .param("actor", actorSubjectId)
                .query(String.class).single();
        return DeleteResult.valueOf(result);
    }

    private static Filter filter(
            String regionCode, String keyword, Set<String> authorizedRegionCodes) {
        StringBuilder sql = new StringBuilder(
                " WHERE point.kind_code='SURVEY_SITE' AND point.deletion_state='ACTIVE'");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (!authorizedRegionCodes.contains("*")) {
            if (authorizedRegionCodes.isEmpty()) {
                sql.append(" AND 1=0");
            } else {
                sql.append(" AND point.region_code IN (:authorizedRegions)");
                parameters.put("authorizedRegions", authorizedRegionCodes);
            }
        }
        if (regionCode != null) {
            sql.append(" AND point.region_code=:regionCode");
            parameters.put("regionCode", regionCode);
        }
        if (keyword != null) {
            sql.append(" AND point.canonical_name ILIKE :keyword ESCAPE E'\\\\'");
            parameters.put("keyword", "%" + escape(keyword) + "%");
        }
        return new Filter(sql.toString(), parameters);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static FormalSamplePointView view(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new FormalSamplePointView(
                row.getObject("sample_point_id", UUID.class),
                row.getString("kind_code"), row.getString("canonical_name"),
                row.getString("region_code"), row.getString("object_type_code"),
                row.getString("object_type_name"), row.getString("business_domain"),
                row.getString("address"), row.getString("maintainer_subject_id"),
                row.getString("maintainer_display_name"), row.getString("approval_state"),
                row.getString("location_state"), row.getBigDecimal("longitude"),
                row.getBigDecimal("latitude"),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_to", LocalDate.class),
                row.getLong("version"), row.getLong("annual_observation_count"),
                row.getLong("network_membership_count"));
    }

    private void insertProfile(
            UUID id, FormalSamplePointDraft draft, String actorSubjectId, Instant now) {
        jdbc.sql("""
                INSERT INTO registry.formal_sample_point_profile(
                  sample_point_id,object_type_code,address,created_by,created_at,updated_by,updated_at)
                VALUES(:id,:objectType,:address,:actor,:now,:actor,:now)
                """).param("id", id).param("objectType", draft.objectTypeCode())
                .param("address", draft.address())
                .param("actor", actorSubjectId)
                .param("now", Timestamp.from(now)).update();
    }

    private record Filter(String sql, Map<String, Object> parameters) {}
}
