package com.cofco.qiqihar.graintrade.samplepoint.network.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkRepository;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.DesignSamplePointView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.SampleNetworkComparisonView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnnualSampleNetworkRepository implements AnnualSampleNetworkRepository {
    private final JdbcClient jdbc;

    public JdbcAnnualSampleNetworkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DesignSamplePointView> designPoints(
            String regionCode, Set<String> authorizedRegions) {
        return jdbc.sql("""
                WITH RECURSIVE selected_region(code) AS (
                  SELECT code FROM platform.region WHERE code=CAST(:region AS varchar)
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN selected_region parent ON child.parent_code=parent.code
                )
                SELECT design.village_region_code,design.village_name,
                       design.township_region_code,design.township_name,
                       design.county_region_code,design.county_name,
                       design.longitude,design.latitude,
                       design.coordinate_source_name,design.coordinate_source_revision,
                       design.coordinate_match_confidence,design.coordinate_review_status
                FROM registry.village_design_sample_point design
                WHERE design.village_region_code IN (:authorizedRegions)
                  AND (CAST(:region AS varchar) IS NULL
                       OR design.village_region_code IN (SELECT code FROM selected_region))
                ORDER BY design.county_name,design.township_name,design.village_name,
                         design.village_region_code
                """).param("region", regionCode).param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new DesignSamplePointView(
                        row.getString("village_region_code"), row.getString("village_name"),
                        row.getString("township_region_code"), row.getString("township_name"),
                        row.getString("county_region_code"), row.getString("county_name"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getString("coordinate_source_name"),
                        row.getString("coordinate_source_revision"),
                        row.getString("coordinate_match_confidence"),
                        row.getString("coordinate_review_status")))
                .list();
    }

    @Override
    public Optional<AnnualSampleNetworkView> find(
            int year, Set<String> authorizedRegions) {
        return header(year).map(header -> new AnnualSampleNetworkView(
                header.year(), header.status(), header.carriedFrom(), header.version(),
                header.createdBy(), header.createdAt(), header.submittedBy(), header.submittedAt(),
                header.reviewedBy(), header.reviewedAt(), header.reviewReason(),
                memberships(year, authorizedRegions)));
    }

    @Override
    public SampleNetworkComparisonView comparison(
            int year, String regionCode, Set<String> authorizedRegions) {
        List<SampleNetworkComparisonView.Point> points = jdbc.sql("""
                WITH RECURSIVE selected_region(code) AS (
                  SELECT code FROM platform.region WHERE code=CAST(:region AS varchar)
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN selected_region parent ON child.parent_code=parent.code
                )
                SELECT design.village_region_code,design.village_name,
                       design.township_region_code,design.township_name,
                       design.county_region_code,design.county_name,
                       design.longitude design_longitude,design.latitude design_latitude,
                       membership.sample_point_id,sample.canonical_name,
                       sample.kind_code,membership.status_code,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_X(sample.governed_point)::numeric(10,7) END actual_longitude,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_Y(sample.governed_point)::numeric(10,7) END actual_latitude,
                       CASE
                         WHEN membership.status_code='ACTIVE' THEN 'ACTIVE_MATCH'
                         WHEN membership.status_code='CANDIDATE' THEN 'CANDIDATE_MATCH'
                         WHEN membership.status_code='PAUSED' THEN 'PAUSED_MATCH'
                         WHEN membership.status_code='REMOVED' THEN 'REMOVED_MATCH'
                         ELSE 'UNMATCHED_DESIGN'
                       END comparison_state
                FROM registry.village_design_sample_point design
                LEFT JOIN registry.sample_network_membership membership
                  ON membership.network_year=:year
                 AND membership.village_region_code=design.village_region_code
                LEFT JOIN registry.sample_point sample
                  ON sample.sample_point_id=membership.sample_point_id
                WHERE design.village_region_code IN (:authorizedRegions)
                  AND (CAST(:region AS varchar) IS NULL
                       OR design.village_region_code IN (SELECT code FROM selected_region))
                ORDER BY CASE WHEN membership.status_code='ACTIVE' THEN 0 ELSE 1 END,
                         design.county_name,design.township_name,design.village_name,
                         design.village_region_code,membership.sample_point_id
                """).param("year", year).param("region", regionCode)
                .param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new SampleNetworkComparisonView.Point(
                        row.getString("village_region_code"), row.getString("village_name"),
                        row.getString("township_region_code"), row.getString("township_name"),
                        row.getString("county_region_code"), row.getString("county_name"),
                        row.getBigDecimal("design_longitude"), row.getBigDecimal("design_latitude"),
                        row.getObject("sample_point_id", UUID.class), row.getString("canonical_name"),
                        row.getString("kind_code"), row.getString("status_code"),
                        row.getBigDecimal("actual_longitude"), row.getBigDecimal("actual_latitude"),
                        row.getString("comparison_state")))
                .list();
        int designCount = (int) points.stream().map(SampleNetworkComparisonView.Point::villageRegionCode)
                .distinct().count();
        int activeCount = (int) points.stream()
                .filter(point -> "ACTIVE_MATCH".equals(point.comparisonState()))
                .map(SampleNetworkComparisonView.Point::samplePointId).distinct().count();
        int covered = (int) points.stream()
                .filter(point -> "ACTIVE_MATCH".equals(point.comparisonState()))
                .map(SampleNetworkComparisonView.Point::villageRegionCode).distinct().count();
        String status = header(year).map(NetworkHeader::status).orElse("NOT_CREATED");
        return new SampleNetworkComparisonView(
                year, status, designCount, activeCount, covered, designCount - covered, points);
    }

    @Override
    public boolean exists(int year) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM registry.sample_network_year WHERE network_year=:year)
                """).param("year", year).query(Boolean.class).single());
    }

    @Override
    public boolean isPublished(int year) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM registry.sample_network_year
                              WHERE network_year=:year AND status_code='PUBLISHED')
                """).param("year", year).query(Boolean.class).single());
    }

    @Override
    public boolean samplePointExists(UUID samplePointId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM registry.sample_point
                              WHERE sample_point_id=:id AND approval_state='APPROVED'
                                AND kind_code='SURVEY_SITE'
                                AND (effective_to IS NULL OR effective_to>=CURRENT_DATE))
                """).param("id", samplePointId).query(Boolean.class).single());
    }

    @Override
    public void create(int year, Integer carriedFromYear, String actor, Instant now) {
        jdbc.sql("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,carried_from_year,version,created_by,created_at)
                VALUES(:year,'DRAFT',:source,0,:actor,:now)
                """).param("year", year).param("source", carriedFromYear)
                .param("actor", actor).param("now", Timestamp.from(now)).update();
        if (carriedFromYear != null) {
            jdbc.sql("""
                    INSERT INTO registry.sample_network_membership(
                      network_year,sample_point_id,village_region_code,status_code,source_code,
                      version,decision_reason,decided_by,decided_at,created_by,created_at)
                    SELECT :year,sample_point_id,village_region_code,'CANDIDATE','CARRIED_FORWARD',
                           0,NULL,NULL,NULL,:actor,:now
                    FROM registry.sample_network_membership
                    WHERE network_year=:source AND status_code='ACTIVE'
                    """).param("year", year).param("source", carriedFromYear)
                    .param("actor", actor).param("now", Timestamp.from(now)).update();
        }
    }

    @Override
    public int upsertMembership(
            int year, UUID samplePointId, String villageRegionCode,
            String statusCode, String sourceCode, String reason, long version,
            String actor, Instant now) {
        boolean candidate = "CANDIDATE".equals(statusCode);
        return jdbc.sql("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decision_reason,decided_by,decided_at,created_by,created_at)
                SELECT :year,:samplePoint,:village,:status,:source,0,:reason,
                       :decidedBy,:decidedAt,:actor,:now
                WHERE EXISTS(SELECT 1 FROM registry.sample_network_year
                             WHERE network_year=:year AND status_code='DRAFT')
                ON CONFLICT(network_year,sample_point_id) DO UPDATE SET
                  village_region_code=EXCLUDED.village_region_code,
                  status_code=EXCLUDED.status_code,
                  source_code=EXCLUDED.source_code,
                  decision_reason=EXCLUDED.decision_reason,
                  decided_by=EXCLUDED.decided_by,
                  decided_at=EXCLUDED.decided_at,
                  version=registry.sample_network_membership.version+1
                WHERE registry.sample_network_membership.version=:version
                  AND EXISTS(SELECT 1 FROM registry.sample_network_year
                             WHERE network_year=:year AND status_code='DRAFT')
                """).param("year", year).param("samplePoint", samplePointId)
                .param("village", villageRegionCode).param("status", statusCode)
                .param("source", sourceCode).param("reason", reason)
                .param("decidedBy", candidate ? null : actor)
                .param("decidedAt", candidate ? null : Timestamp.from(now))
                .param("actor", actor).param("now", Timestamp.from(now))
                .param("version", version).update();
    }

    @Override
    public int submit(int year, long version, String actor, Instant now) {
        return jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='IN_REVIEW',submitted_by=:actor,submitted_at=:now,
                    reviewed_by=NULL,reviewed_at=NULL,review_reason=NULL,
                    published_by=NULL,published_at=NULL,version=version+1
                WHERE network_year=:year AND status_code='DRAFT' AND version=:version
                  AND EXISTS(SELECT 1 FROM registry.sample_network_membership
                             WHERE network_year=:year AND status_code='ACTIVE')
                """).param("year", year).param("version", version)
                .param("actor", actor).param("now", Timestamp.from(now)).update();
    }

    @Override
    public int approve(int year, long version, String actor, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='PUBLISHED',reviewed_by=:actor,reviewed_at=:now,
                    review_reason=:reason,published_by=:actor,published_at=:now,
                    version=version+1
                WHERE network_year=:year AND status_code='IN_REVIEW' AND version=:version
                  AND submitted_by<>:actor
                """).param("year", year).param("version", version)
                .param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    public int returnToDraft(
            int year, long version, String actor, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='DRAFT',submitted_by=NULL,submitted_at=NULL,
                    reviewed_by=NULL,reviewed_at=NULL,review_reason=:reason,
                    published_by=NULL,published_at=NULL,version=version+1
                WHERE network_year=:year AND status_code='IN_REVIEW' AND version=:version
                  AND submitted_by<>:actor
                """).param("year", year).param("version", version)
                .param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).update();
    }

    private Optional<NetworkHeader> header(int year) {
        return jdbc.sql("""
                SELECT network_year,status_code,carried_from_year,version,
                       created_by,created_at,submitted_by,submitted_at,
                       reviewed_by,reviewed_at,review_reason
                FROM registry.sample_network_year WHERE network_year=:year
                """).param("year", year).query((row, index) -> new NetworkHeader(
                        row.getInt("network_year"), row.getString("status_code"),
                        nullableInteger(row.getObject("carried_from_year")), row.getLong("version"),
                        row.getString("created_by"), instant(row.getTimestamp("created_at")),
                        row.getString("submitted_by"), instant(row.getTimestamp("submitted_at")),
                        row.getString("reviewed_by"), instant(row.getTimestamp("reviewed_at")),
                        row.getString("review_reason")))
                .optional();
    }

    private List<AnnualSampleNetworkView.Membership> memberships(
            int year, Set<String> authorizedRegions) {
        return jdbc.sql("""
                SELECT membership.sample_point_id,sample.canonical_name,sample.kind_code,
                       membership.village_region_code,village.name village_name,
                       membership.status_code,membership.source_code,membership.decision_reason,
                       membership.version,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_X(sample.governed_point)::numeric(10,7) END longitude,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_Y(sample.governed_point)::numeric(10,7) END latitude
                FROM registry.sample_network_membership membership
                JOIN registry.sample_point sample
                  ON sample.sample_point_id=membership.sample_point_id
                JOIN platform.region village ON village.code=membership.village_region_code
                WHERE membership.network_year=:year
                  AND membership.village_region_code IN (:authorizedRegions)
                ORDER BY CASE membership.status_code
                           WHEN 'ACTIVE' THEN 1 WHEN 'CANDIDATE' THEN 2
                           WHEN 'PAUSED' THEN 3 ELSE 4 END,
                         sample.canonical_name,membership.sample_point_id
                """).param("year", year).param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new AnnualSampleNetworkView.Membership(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("canonical_name"), row.getString("kind_code"),
                        row.getString("village_region_code"), row.getString("village_name"),
                        row.getString("status_code"), row.getString("source_code"),
                        row.getString("decision_reason"), row.getLong("version"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude")))
                .list();
    }

    private static Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record NetworkHeader(
            int year,
            String status,
            Integer carriedFrom,
            long version,
            String createdBy,
            Instant createdAt,
            String submittedBy,
            Instant submittedAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewReason) {}
}
