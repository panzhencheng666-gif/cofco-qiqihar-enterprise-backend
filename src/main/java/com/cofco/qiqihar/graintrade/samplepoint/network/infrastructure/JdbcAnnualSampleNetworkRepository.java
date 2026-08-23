package com.cofco.qiqihar.graintrade.samplepoint.network.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkRepository;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.DesignSamplePointView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.SampleNetworkComparisonView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
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
        List<SampleNetworkComparisonView.DesignPoint> designPoints =
                designPoints(regionCode, authorizedRegions).stream()
                        .map(point -> new SampleNetworkComparisonView.DesignPoint(
                                point.villageRegionCode(), point.villageName(),
                                point.townshipRegionCode(), point.townshipName(),
                                point.countyRegionCode(), point.countyName(),
                                point.longitude(), point.latitude()))
                        .toList();
        List<SampleNetworkComparisonView.ActualPoint> actualPoints = jdbc.sql("""
                WITH RECURSIVE selected_region(code) AS (
                  SELECT code FROM platform.region WHERE code=CAST(:region AS varchar)
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN selected_region parent ON child.parent_code=parent.code
                ),
                scoped_design(design_village_region_code) AS (
                  SELECT design.village_region_code
                  FROM registry.village_design_sample_point design
                  WHERE design.village_region_code IN (:authorizedRegions)
                    AND (CAST(:region AS varchar) IS NULL
                         OR design.village_region_code IN (SELECT code FROM selected_region))
                ),
                design_ancestor(design_village_region_code,ancestor_region_code) AS (
                  SELECT design_village_region_code,design_village_region_code
                  FROM scoped_design
                  UNION ALL
                  SELECT ancestor.design_village_region_code,region.parent_code
                  FROM design_ancestor ancestor
                  JOIN platform.region region ON region.code=ancestor.ancestor_region_code
                  WHERE region.parent_code IS NOT NULL
                ),
                explicitly_related_actual(sample_point_id) AS (
                  SELECT DISTINCT relation.sample_point_id
                  FROM registry.sample_network_design_relation relation
                  JOIN scoped_design design
                    ON design.design_village_region_code=relation.design_village_region_code
                  WHERE relation.network_year=:year
                )
                SELECT membership.sample_point_id,sample.canonical_name,sample.kind_code,
                       membership.status_code,sample.region_code,located.name region_name,
                       located.administrative_level,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_X(sample.governed_point)::numeric(10,7) END actual_longitude,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_Y(sample.governed_point)::numeric(10,7) END actual_latitude,
                       sample.location_state
                FROM registry.sample_network_membership membership
                JOIN registry.sample_point sample
                  ON sample.sample_point_id=membership.sample_point_id
                JOIN platform.region located ON located.code=sample.region_code
                WHERE membership.network_year=:year
                  AND sample.region_code IN (:authorizedRegions)
                  AND (CAST(:region AS varchar) IS NULL
                       OR sample.region_code IN (SELECT code FROM selected_region)
                       OR membership.sample_point_id IN (
                            SELECT sample_point_id FROM explicitly_related_actual)
                       OR (located.administrative_level IN (
                              'PREFECTURE','COUNTY','TOWNSHIP')
                           AND sample.region_code IN (
                              SELECT ancestor_region_code FROM design_ancestor)))
                ORDER BY CASE membership.status_code
                           WHEN 'ACTIVE' THEN 1 WHEN 'CANDIDATE' THEN 2
                           WHEN 'PAUSED' THEN 3 ELSE 4 END,
                         CASE located.administrative_level
                           WHEN 'PREFECTURE' THEN 1 WHEN 'COUNTY' THEN 2
                           WHEN 'TOWNSHIP' THEN 3 ELSE 4 END,
                         sample.canonical_name,membership.sample_point_id
                """).param("year", year).param("region", regionCode)
                .param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new SampleNetworkComparisonView.ActualPoint(
                        row.getObject("sample_point_id", UUID.class), row.getString("canonical_name"),
                        row.getString("kind_code"), row.getString("status_code"),
                        row.getString("region_code"), row.getString("region_name"),
                        row.getString("administrative_level"),
                        row.getBigDecimal("actual_longitude"), row.getBigDecimal("actual_latitude"),
                        row.getString("location_state")))
                .list();
        List<SampleNetworkComparisonView.Relation> relations = relations(
                year, regionCode, authorizedRegions);

        Set<UUID> activeIds = new HashSet<>();
        int prefectures = 0;
        int counties = 0;
        int townships = 0;
        int villages = 0;
        for (SampleNetworkComparisonView.ActualPoint point : actualPoints) {
            if (!"ACTIVE".equals(point.membershipStatusCode())) {
                continue;
            }
            activeIds.add(point.samplePointId());
            switch (point.locatedRegionLevel()) {
                case "PREFECTURE" -> prefectures++;
                case "COUNTY" -> counties++;
                case "TOWNSHIP" -> townships++;
                case "VILLAGE" -> villages++;
                default -> { }
            }
        }
        Set<String> exact = new HashSet<>();
        Set<String> represented = new HashSet<>();
        Set<String> regional = new HashSet<>();
        relations.stream()
                .filter(relation -> activeIds.contains(relation.samplePointId()))
                .filter(relation -> "APPROVED".equals(relation.reviewStatus()))
                .filter(relation -> "EXACT_VILLAGE".equals(relation.relationType()))
                .map(SampleNetworkComparisonView.Relation::designVillageRegionCode)
                .forEach(exact::add);
        relations.stream()
                .filter(relation -> activeIds.contains(relation.samplePointId()))
                .filter(relation -> "APPROVED".equals(relation.reviewStatus()))
                .filter(relation -> "EXPLICIT_REPRESENTATION".equals(relation.relationType()))
                .map(SampleNetworkComparisonView.Relation::designVillageRegionCode)
                .filter(code -> !exact.contains(code))
                .forEach(represented::add);
        relations.stream()
                .filter(relation -> activeIds.contains(relation.samplePointId()))
                .filter(relation -> "REGIONAL_ASSOCIATION".equals(relation.relationType()))
                .map(SampleNetworkComparisonView.Relation::designVillageRegionCode)
                .filter(code -> !exact.contains(code) && !represented.contains(code))
                .forEach(regional::add);
        int associated = exact.size() + represented.size() + regional.size();
        String status = header(year).map(NetworkHeader::status).orElse("NOT_CREATED");
        return new SampleNetworkComparisonView(
                year, status, designPoints.size(), activeIds.size(), exact.size(),
                represented.size(), regional.size(), designPoints.size() - associated,
                new SampleNetworkComparisonView.LevelCounts(
                        prefectures, counties, townships, villages),
                designPoints, actualPoints, relations);
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
    public Optional<AnnualSampleNetworkRepository.SamplePointLocation> samplePointLocation(
            UUID samplePointId) {
        return jdbc.sql("""
                SELECT sample.region_code,region.administrative_level
                FROM registry.sample_point sample
                JOIN platform.region region ON region.code=sample.region_code
                WHERE sample.sample_point_id=:id AND sample.approval_state='APPROVED'
                  AND sample.kind_code='SURVEY_SITE'
                  AND (sample.effective_to IS NULL OR sample.effective_to>=CURRENT_DATE)
                """).param("id", samplePointId)
                .query((row, index) -> new AnnualSampleNetworkRepository.SamplePointLocation(
                        row.getString("region_code"), row.getString("administrative_level")))
                .optional();
    }

    @Override
    public boolean lockDraft(int year) {
        return jdbc.sql("""
                SELECT status_code
                FROM registry.sample_network_year
                WHERE network_year=:year
                FOR UPDATE
                """).param("year", year).query(String.class).optional()
                .filter("DRAFT"::equals).isPresent();
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
                      network_year,sample_point_id,status_code,source_code,
                      version,decision_reason,decided_by,decided_at,created_by,created_at)
                    SELECT :year,sample_point_id,'CANDIDATE','CARRIED_FORWARD',
                           0,NULL,NULL,NULL,:actor,:now
                    FROM registry.sample_network_membership
                    WHERE network_year=:source AND status_code='ACTIVE'
                    """).param("year", year).param("source", carriedFromYear)
                    .param("actor", actor).param("now", Timestamp.from(now)).update();
            jdbc.sql("""
                    INSERT INTO registry.sample_network_design_relation(
                      network_year,sample_point_id,design_village_region_code,relation_type,
                      evidence_reference,review_status,created_by,created_at)
                    SELECT :year,relation.sample_point_id,relation.design_village_region_code,
                           relation.relation_type,relation.evidence_reference,
                           'PENDING_REVIEW',:actor,:now
                    FROM registry.sample_network_design_relation relation
                    JOIN registry.sample_network_membership target
                      ON target.network_year=:year
                     AND target.sample_point_id=relation.sample_point_id
                    WHERE relation.network_year=:source
                      AND relation.review_status='APPROVED'
                    """).param("year", year).param("source", carriedFromYear)
                    .param("actor", actor).param("now", Timestamp.from(now)).update();
        }
    }

    @Override
    public AnnualSampleNetworkRepository.MembershipWriteResult upsertMembership(
            int year, UUID samplePointId, String designVillageRegionCode,
            String relationType, String evidenceReference, String statusCode,
            String sourceCode, String reason, long version, String actor, Instant now) {
        boolean candidate = "CANDIDATE".equals(statusCode);
        int changed = jdbc.sql("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,status_code,source_code,
                  version,decision_reason,decided_by,decided_at,created_by,created_at)
                SELECT :year,:samplePoint,:status,:source,0,:reason,
                       :decidedBy,:decidedAt,:actor,:now
                WHERE EXISTS(SELECT 1 FROM registry.sample_network_year
                             WHERE network_year=:year AND status_code='DRAFT')
                ON CONFLICT(network_year,sample_point_id) DO UPDATE SET
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
                .param("status", statusCode).param("source", sourceCode).param("reason", reason)
                .param("decidedBy", candidate ? null : actor)
                .param("decidedAt", candidate ? null : Timestamp.from(now))
                .param("actor", actor).param("now", Timestamp.from(now))
                .param("version", version).update();
        int relationChanges = 0;
        if (changed == 1 && designVillageRegionCode != null) {
            relationChanges = jdbc.sql("""
                    INSERT INTO registry.sample_network_design_relation(
                      network_year,sample_point_id,design_village_region_code,relation_type,
                      evidence_reference,review_status,created_by,created_at)
                    SELECT :year,:samplePoint,:designVillage,:relationType,
                           :evidence,'PENDING_REVIEW',:actor,:now
                    WHERE EXISTS(SELECT 1 FROM registry.sample_network_year
                                 WHERE network_year=:year AND status_code='DRAFT')
                    ON CONFLICT(network_year,sample_point_id,
                                design_village_region_code,relation_type) DO UPDATE SET
                      evidence_reference=EXCLUDED.evidence_reference,
                      review_status='PENDING_REVIEW',created_by=EXCLUDED.created_by,
                      created_at=EXCLUDED.created_at,reviewed_by=NULL,reviewed_at=NULL
                    """).param("year", year).param("samplePoint", samplePointId)
                    .param("designVillage", designVillageRegionCode)
                    .param("relationType", relationType).param("evidence", evidenceReference)
                    .param("actor", actor).param("now", Timestamp.from(now)).update();
        }
        return new AnnualSampleNetworkRepository.MembershipWriteResult(changed, relationChanges);
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
        int changed = jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='PUBLISHED',reviewed_by=:actor,reviewed_at=:now,
                    review_reason=:reason,published_by=:actor,published_at=:now,
                    version=version+1
                WHERE network_year=:year AND status_code='IN_REVIEW' AND version=:version
                  AND submitted_by<>:actor
                  AND NOT EXISTS(
                    SELECT 1 FROM registry.sample_network_design_relation relation
                    WHERE relation.network_year=:year
                      AND relation.review_status<>'RETURNED'
                      AND relation.created_by=:actor)
                """).param("year", year).param("version", version)
                .param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).update();
        if (changed == 1) {
            jdbc.sql("""
                    UPDATE registry.sample_network_design_relation
                    SET review_status='APPROVED',reviewed_by=:actor,reviewed_at=:now
                    WHERE network_year=:year AND review_status<>'RETURNED'
                    """).param("year", year).param("actor", actor)
                    .param("now", Timestamp.from(now)).update();
        }
        return changed;
    }

    @Override
    public int returnToDraft(
            int year, long version, String actor, String reason, Instant now) {
        int changed = jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='DRAFT',submitted_by=NULL,submitted_at=NULL,
                    reviewed_by=NULL,reviewed_at=NULL,review_reason=:reason,
                    published_by=NULL,published_at=NULL,version=version+1
                WHERE network_year=:year AND status_code='IN_REVIEW' AND version=:version
                  AND submitted_by<>:actor
                """).param("year", year).param("version", version)
                .param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).update();
        if (changed == 1) {
            jdbc.sql("""
                    UPDATE registry.sample_network_design_relation
                    SET review_status='RETURNED',reviewed_by=:actor,reviewed_at=:now
                    WHERE network_year=:year AND review_status='PENDING_REVIEW'
                    """).param("year", year).param("actor", actor)
                    .param("now", Timestamp.from(now)).update();
        }
        return changed;
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
                       sample.region_code,located.name region_name,
                       located.administrative_level,
                       membership.status_code,membership.source_code,membership.decision_reason,
                       membership.version,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_X(sample.governed_point)::numeric(10,7) END longitude,
                       CASE WHEN sample.governed_point IS NULL THEN NULL
                            ELSE ST_Y(sample.governed_point)::numeric(10,7) END latitude,
                       sample.location_state
                FROM registry.sample_network_membership membership
                JOIN registry.sample_point sample
                  ON sample.sample_point_id=membership.sample_point_id
                JOIN platform.region located ON located.code=sample.region_code
                WHERE membership.network_year=:year
                  AND sample.region_code IN (:authorizedRegions)
                ORDER BY CASE membership.status_code
                           WHEN 'ACTIVE' THEN 1 WHEN 'CANDIDATE' THEN 2
                           WHEN 'PAUSED' THEN 3 ELSE 4 END,
                         CASE located.administrative_level
                           WHEN 'PREFECTURE' THEN 1 WHEN 'COUNTY' THEN 2
                           WHEN 'TOWNSHIP' THEN 3 ELSE 4 END,
                         sample.canonical_name,membership.sample_point_id
                """).param("year", year).param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new AnnualSampleNetworkView.Membership(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("canonical_name"), row.getString("kind_code"),
                        row.getString("region_code"), row.getString("region_name"),
                        row.getString("administrative_level"),
                        row.getString("status_code"), row.getString("source_code"),
                        row.getString("decision_reason"), row.getLong("version"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getString("location_state")))
                .list();
    }

    private List<SampleNetworkComparisonView.Relation> relations(
            int year, String regionCode, Set<String> authorizedRegions) {
        return jdbc.sql("""
                WITH RECURSIVE selected_region(code) AS (
                  SELECT code FROM platform.region WHERE code=CAST(:region AS varchar)
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN selected_region parent ON child.parent_code=parent.code
                ),
                scoped_design(design_village_region_code) AS (
                  SELECT design.village_region_code
                  FROM registry.village_design_sample_point design
                  WHERE design.village_region_code IN (:authorizedRegions)
                    AND (CAST(:region AS varchar) IS NULL
                         OR design.village_region_code IN (SELECT code FROM selected_region))
                ),
                design_ancestor(design_village_region_code,ancestor_region_code) AS (
                  SELECT design_village_region_code,design_village_region_code
                  FROM scoped_design
                  UNION ALL
                  SELECT ancestor.design_village_region_code,region.parent_code
                  FROM design_ancestor ancestor
                  JOIN platform.region region ON region.code=ancestor.ancestor_region_code
                  WHERE region.parent_code IS NOT NULL
                ),
                explicitly_related_actual(sample_point_id) AS (
                  SELECT DISTINCT relation.sample_point_id
                  FROM registry.sample_network_design_relation relation
                  JOIN scoped_design design
                    ON design.design_village_region_code=relation.design_village_region_code
                  WHERE relation.network_year=:year
                ),
                scoped_actual(
                  sample_point_id,located_region_code,located_region_level,
                  membership_status_code) AS (
                  SELECT membership.sample_point_id,sample.region_code,
                         located.administrative_level,membership.status_code
                  FROM registry.sample_network_membership membership
                  JOIN registry.sample_point sample
                    ON sample.sample_point_id=membership.sample_point_id
                  JOIN platform.region located ON located.code=sample.region_code
                  WHERE membership.network_year=:year
                    AND sample.region_code IN (:authorizedRegions)
                    AND (CAST(:region AS varchar) IS NULL
                         OR sample.region_code IN (SELECT code FROM selected_region)
                         OR membership.sample_point_id IN (
                              SELECT sample_point_id FROM explicitly_related_actual)
                         OR (located.administrative_level IN (
                                'PREFECTURE','COUNTY','TOWNSHIP')
                             AND sample.region_code IN (
                                SELECT ancestor_region_code FROM design_ancestor)))
                )
                SELECT combined.sample_point_id,combined.design_village_region_code,
                       combined.relation_type,combined.evidence_reference,
                       combined.review_status,combined.created_by,combined.created_at,
                       combined.reviewed_by,combined.reviewed_at
                FROM (
                  SELECT explicit_relation.sample_point_id,
                         explicit_relation.design_village_region_code,
                         explicit_relation.relation_type,
                         explicit_relation.evidence_reference,
                         explicit_relation.review_status,explicit_relation.created_by,
                         explicit_relation.created_at,explicit_relation.reviewed_by,
                         explicit_relation.reviewed_at
                  FROM registry.sample_network_design_relation explicit_relation
                  JOIN scoped_actual actual
                    ON actual.sample_point_id=explicit_relation.sample_point_id
                  JOIN scoped_design design
                    ON design.design_village_region_code=
                       explicit_relation.design_village_region_code
                  WHERE explicit_relation.network_year=:year
                  UNION ALL
                  SELECT actual.sample_point_id,ancestor.design_village_region_code,
                         'REGIONAL_ASSOCIATION',NULL,NULL,NULL,NULL,NULL,NULL
                  FROM scoped_actual actual
                  JOIN design_ancestor ancestor
                    ON ancestor.ancestor_region_code=actual.located_region_code
                  WHERE actual.membership_status_code<>'REMOVED'
                    AND actual.located_region_level IN (
                      'PREFECTURE','COUNTY','TOWNSHIP')
                    AND NOT EXISTS (
                      SELECT 1
                      FROM registry.sample_network_design_relation explicit_relation
                      WHERE explicit_relation.network_year=:year
                        AND explicit_relation.sample_point_id=actual.sample_point_id
                        AND explicit_relation.design_village_region_code=
                            ancestor.design_village_region_code
                        AND explicit_relation.review_status='APPROVED')
                ) combined
                ORDER BY CASE combined.relation_type
                           WHEN 'EXACT_VILLAGE' THEN 1
                           WHEN 'EXPLICIT_REPRESENTATION' THEN 2 ELSE 3 END,
                         combined.design_village_region_code,combined.sample_point_id
                """).param("year", year).param("region", regionCode)
                .param("authorizedRegions", authorizedRegions)
                .query((row, index) -> new SampleNetworkComparisonView.Relation(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("design_village_region_code"),
                        row.getString("relation_type"), row.getString("evidence_reference"),
                        row.getString("review_status"), row.getString("created_by"),
                        instant(row.getTimestamp("created_at")), row.getString("reviewed_by"),
                        instant(row.getTimestamp("reviewed_at"))))
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
