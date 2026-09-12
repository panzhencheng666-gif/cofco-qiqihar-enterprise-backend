package com.cofco.qiqihar.graintrade.testsupport;

import javax.sql.DataSource;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.CurrentSecuritySubject;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.security.infrastructure.JdbcSecurityPrincipalRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestConfiguration(proxyBeanMethods = false)
public class ProtectedTestDatabaseConfiguration {

    @Bean
    @Primary
    DataSource protectedTestDataSource() {
        return ProtectedTestDatabase.shared().dataSource();
    }

    @Bean
    ApplicationRunner provisionSecurityTestSubjects(DataSource dataSource) {
        return arguments -> {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            provisionSecurityTestSubjects(jdbc);
        };
    }

    @Bean
    @Primary
    SecurityPrincipalRepository testSecurityPrincipalRepository(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        SecurityPrincipalRepository delegate = new JdbcSecurityPrincipalRepository(jdbc);
        return new SecurityPrincipalRepository() {
            @Override
            public java.util.Optional<SecurityPrincipal> findEnabled(String subjectId) {
                return delegate.findEnabled(subjectId).map(principal -> ordinaryTestPrincipal(principal, jdbc));
            }

            @Override
            public java.util.Optional<SecurityPrincipal> findEnabledByOidcIdentity(
                    String issuer, String providerSubject) {
                return delegate.findEnabledByOidcIdentity(issuer, providerSubject)
                        .map(principal -> ordinaryTestPrincipal(principal, jdbc));
            }

            @Override
            public java.util.Optional<String> responsibleSubject(String regionCode, boolean countyReporting) {
                return delegate.responsibleSubject(regionCode, countyReporting);
            }
        };
    }

    @Bean
    @Primary
    AccessControl testAccessControl(
            CurrentSecuritySubject currentSubject, SecurityPrincipalRepository principals) {
        return new AccessControl(currentSubject, principals, true) {
            @Override
            public AuthorizedReadScope requireReadScope() {
                return currentSubject.subjectId().isEmpty()
                        ? AuthorizedReadScope.unrestricted()
                        : super.requireReadScope();
            }
        };
    }

    /** Restores shared test identities after a test intentionally replaces the security fixture. */
    public static void provisionSecurityTestSubjects(JdbcClient jdbc) {
            provisionSecurityTestSubjects(jdbc, true);
    }

    /** Provisions only identities for migration-upgrade tests that must preserve boundary absence. */
    public static void provisionSecurityTestSubjectsWithoutBoundaries(JdbcClient jdbc) {
            provisionSecurityTestSubjects(jdbc, false);
    }

    private static void provisionSecurityTestSubjects(
            JdbcClient jdbc, boolean includeCoordinateBoundaries) {
            jdbc.sql("""
                    INSERT INTO platform.work_unit(code,name,sort_order)
                    VALUES ('DATABASE_AUTOMATION','数据库受控自动化',9899)
                    ON CONFLICT(code) DO UPDATE SET active=true
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                    VALUES ('database-master-data-automation','主数据受控自动化','DATABASE_AUTOMATION')
                    ON CONFLICT(subject_id) DO UPDATE SET
                      display_name=EXCLUDED.display_name,
                      work_unit_code=EXCLUDED.work_unit_code,
                      enabled=true,account_status='ACTIVE',employment_status='ACTIVE',
                      termination_effective_at=NULL
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.work_unit(code,name,sort_order)
                    VALUES ('TEST','自动化测试工作单位',9900)
                    ON CONFLICT(code) DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                    SELECT 'TEST', code FROM platform.region
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                    VALUES ('production-tester','产情测试员','TEST'),
                           ('market-tester','市场测试员','TEST'),
                           ('logistics-tester','物流测试员','TEST'),
                           ('supply-reviewer','供需测试员','TEST'),
                           ('data-fault-test','数据边界测试员','TEST'),
                           ('metadata-fault-test','元数据边界测试员','TEST')
                    ON CONFLICT(subject_id) DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.access_role(code,name,active,sort_order)
                    VALUES ('TEST_AUTOMATION','自动化测试角色',true,9999)
                    ON CONFLICT(code) DO UPDATE SET active=true
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.access_role_permission(role_code,permission_code)
                    SELECT 'TEST_AUTOMATION',code FROM platform.access_permission WHERE active
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_role(subject_id,role_code)
                    SELECT subject_id, 'SYSTEM_ADMIN' FROM platform.security_user
                    WHERE work_unit_code = 'TEST'
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                    SELECT security_user.subject_id, unit_scope.region_code
                    FROM platform.security_user
                    CROSS JOIN platform.work_unit_region_scope unit_scope
                    WHERE security_user.work_unit_code = 'TEST'
                      AND unit_scope.work_unit_code = 'TEST'
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_role(subject_id,role_code)
                    SELECT subject_id, 'TEST_AUTOMATION' FROM platform.security_user
                    WHERE work_unit_code = 'TEST'
                    ON CONFLICT DO NOTHING
                    """).update();
            if (includeCoordinateBoundaries
                    && jdbc.sql("SELECT to_regclass('overview.administrative_boundary_dataset') IS NOT NULL")
                    .query(Boolean.class).single()) {
                provisionBusinessCoordinateTestBoundaries(jdbc);
            }
    }

    private static SecurityPrincipal ordinaryTestPrincipal(SecurityPrincipal principal, JdbcClient jdbc) {
        if (!principal.roleCodes().contains("TEST_AUTOMATION")
                || principal.roleCodes().contains("BUSINESS_REVIEWER")
                || principal.roleCodes().contains("ACCOUNT_OWNER")) return principal;
        java.util.Set<String> roles = new java.util.LinkedHashSet<>(principal.roleCodes());
        roles.remove("SYSTEM_ADMIN");
        java.util.Set<String> permissions = new java.util.LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT role_permission.permission_code
                FROM platform.security_user_role assignment
                JOIN platform.access_role role ON role.code=assignment.role_code AND role.active
                JOIN platform.access_role_permission role_permission ON role_permission.role_code=role.code
                JOIN platform.access_permission permission ON permission.code=role_permission.permission_code AND permission.active
                WHERE assignment.subject_id=:subject
                  AND assignment.role_code<>'SYSTEM_ADMIN'
                  AND CURRENT_TIMESTAMP>=assignment.valid_from
                  AND (assignment.valid_until IS NULL OR CURRENT_TIMESTAMP<assignment.valid_until)
                  AND (assignment.review_due_at IS NULL OR CURRENT_TIMESTAMP<assignment.review_due_at)
                ORDER BY role_permission.permission_code
                """).param("subject", principal.subjectId()).query(String.class).list());
        java.util.Set<String> regions = new java.util.LinkedHashSet<>(jdbc.sql("""
                WITH RECURSIVE assigned(region_code) AS (
                    SELECT region_code FROM platform.security_user_region_scope
                    WHERE subject_id=:subject
                      AND CURRENT_TIMESTAMP>=valid_from
                      AND (valid_until IS NULL OR CURRENT_TIMESTAMP<valid_until)
                      AND (review_due_at IS NULL OR CURRENT_TIMESTAMP<review_due_at)
                ), covered(region_code) AS (
                    SELECT region_code FROM assigned
                    UNION
                    SELECT child.code FROM platform.region child
                    JOIN covered parent ON parent.region_code=child.parent_code
                )
                SELECT region_code FROM covered ORDER BY region_code
                """).param("subject", principal.subjectId()).query(String.class).list());
        return new SecurityPrincipal(
                principal.subjectId(), principal.displayName(), principal.workUnitCode(),
                principal.workUnitName(), principal.accountStatus(), principal.employmentStatus(),
                roles, principal.positions(), permissions, regions,
                principal.assignedRegionScopes());
    }

    private static void provisionBusinessCoordinateTestBoundaries(JdbcClient jdbc) {
        boolean governedOpenBoundaries = jdbc.sql("""
                SELECT to_regclass('overview.administrative_boundary_dataset') IS NOT NULL
                """).query(Boolean.class).single();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES
                  ('230200',ST_Multi(ST_MakeEnvelope(122,46,125,49,4326)),
                   'protected business coordinate fixture','urn:test:protected-business-coordinate',
                   'test-v1','Test fixture','230200',DATE '2026-08-28',repeat('8',64)),
                  ('230202',ST_Multi(ST_MakeEnvelope(123.4,47.1,124.3,48.0,4326)),
                   'protected business coordinate fixture','urn:test:protected-business-coordinate',
                   'test-v1','Test fixture','230202',DATE '2026-08-28',repeat('9',64)),
                  ('230208',ST_Multi(ST_MakeEnvelope(123.4,47.1,124.3,48.0,4326)),
                   'protected business coordinate fixture','urn:test:protected-business-coordinate',
                   'test-v1','Test fixture','230208',DATE '2026-08-28',repeat('a',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,
                  source_url=EXCLUDED.source_url,
                  source_name=EXCLUDED.source_name,
                  source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,
                  source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                WHERE NOT :governedOpenBoundaries
                """).param("governedOpenBoundaries", governedOpenBoundaries).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary_render(
                  region_code,geometry,geo_json,simplify_tolerance,full_point_count,
                  render_point_count,source_geometry_sha256,source_name,source_revision,source_license)
                SELECT region_code,geometry,ST_AsGeoJSON(geometry),0,ST_NPoints(geometry),
                  ST_NPoints(geometry),geometry_sha256,source_name,source_revision,source_license
                FROM overview.administrative_boundary
                WHERE source_url='urn:test:protected-business-coordinate'
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,
                  geo_json=EXCLUDED.geo_json,
                  simplify_tolerance=EXCLUDED.simplify_tolerance,
                  full_point_count=EXCLUDED.full_point_count,
                  render_point_count=EXCLUDED.render_point_count,
                  source_geometry_sha256=EXCLUDED.source_geometry_sha256,
                  refreshed_at=now(),
                  source_name=EXCLUDED.source_name,
                  source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license
                """).update();
    }
}
