package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecurityPrincipalRepository implements SecurityPrincipalRepository {
    private final JdbcClient jdbc;

    public JdbcSecurityPrincipalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> responsibleSubject(String regionCode, boolean countyReporting) {
        // Hold until the business write commits, including when no responsibility row exists yet.
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            jdbc.sql("LOCK TABLE platform.region_responsibility IN SHARE MODE").update();
        String function=countyReporting?"county_reporting_subject":"region_responsible_subject";
        return jdbc.sql("SELECT platform."+function+"(:region)").param("region",regionCode)
                .query((row,index)->row.getString(1)).optional();
    }

    @Override
    public Optional<SecurityPrincipal> findEnabled(String subjectId) {
        return jdbc.sql("""
                SELECT security_user.subject_id,security_user.display_name,security_user.work_unit_code,
                       work_unit.name,security_user.account_status,security_user.employment_status
                FROM platform.security_user security_user
                JOIN platform.work_unit work_unit
                  ON work_unit.code=security_user.work_unit_code AND work_unit.active
                WHERE security_user.subject_id = :subjectId
                  AND security_user.enabled
                  AND security_user.account_status='ACTIVE'
                  AND security_user.employment_status='ACTIVE'
                  AND (security_user.termination_effective_at IS NULL
                       OR security_user.termination_effective_at > CURRENT_TIMESTAMP)
                """).param("subjectId", subjectId).query((row, index) -> new SecuritySubject(
                row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getString(6)))
                .optional().map(subject -> new SecurityPrincipal(
                        subject.id(),subject.displayName(),subject.workUnitCode(),subject.workUnitName(),
                        subject.accountStatus(),subject.employmentStatus(),roles(subject.id()),positions(subject.id()),
                        permissions(subject.id()),regions(subject.id(),subject.workUnitCode()),
                        assignedRegions(subject.id(),subject.workUnitCode())));
    }

    @Override
    public Optional<SecurityPrincipal> findEnabledByOidcIdentity(String issuer,String providerSubject) {
        if(issuer==null||issuer.isBlank()||!issuer.startsWith("https://")
                ||providerSubject==null||providerSubject.isBlank())return Optional.empty();
        return jdbc.sql("""
                SELECT security_subject_id
                FROM platform.identity_provider_binding
                WHERE issuer_uri=:issuer
                  AND provider_subject=:providerSubject
                  AND state='ACTIVE'
                  AND CURRENT_TIMESTAMP>=valid_from
                  AND (valid_until IS NULL OR CURRENT_TIMESTAMP<valid_until)
                """).param("issuer",issuer).param("providerSubject",providerSubject)
                .query(String.class).optional().flatMap(this::findEnabled);
    }

    private Set<String> permissions(String subjectId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT role_permission.permission_code
                FROM platform.security_user_role user_role
                JOIN platform.access_role access_role ON access_role.code = user_role.role_code AND access_role.active
                JOIN platform.access_role_permission role_permission ON role_permission.role_code = access_role.code
                JOIN platform.access_permission permission ON permission.code = role_permission.permission_code AND permission.active
                WHERE user_role.subject_id = :subjectId
                  AND CURRENT_TIMESTAMP >= user_role.valid_from
                  AND (user_role.valid_until IS NULL OR CURRENT_TIMESTAMP < user_role.valid_until)
                  AND (user_role.review_due_at IS NULL OR CURRENT_TIMESTAMP < user_role.review_due_at)
                """).param("subjectId", subjectId).query(String.class).list());
    }

    private Set<String> regions(String subjectId, String workUnitCode) {
        return new LinkedHashSet<>(jdbc.sql("""
                WITH RECURSIVE unit_authorized(region_code) AS (
                    SELECT scope.region_code
                    FROM platform.work_unit_region_scope scope
                    WHERE scope.work_unit_code = :workUnitCode
                    UNION
                    SELECT child.code
                    FROM platform.region child
                    JOIN unit_authorized parent ON parent.region_code = child.parent_code
                ), assigned(region_code) AS (
                    SELECT scope.region_code
                    FROM platform.security_user_region_scope scope
                    JOIN unit_authorized
                      ON unit_authorized.region_code = scope.region_code
                    WHERE scope.subject_id = :subjectId
                      AND CURRENT_TIMESTAMP >= scope.valid_from
                      AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP < scope.valid_until)
                      AND (scope.review_due_at IS NULL OR CURRENT_TIMESTAMP < scope.review_due_at)
                ), covered(region_code) AS (
                    SELECT region_code FROM assigned
                    UNION
                    SELECT child.code
                    FROM platform.region child
                    JOIN covered parent ON parent.region_code = child.parent_code
                )
                SELECT region_code FROM covered
                UNION
                SELECT county.code FROM platform.region county
                JOIN unit_authorized unit ON unit.region_code=county.code
                WHERE county.administrative_level='COUNTY'
                  AND platform.county_reporting_subject(county.code)=:subjectId
                ORDER BY region_code
                """).param("subjectId", subjectId).param("workUnitCode", workUnitCode).query(String.class).list());
    }

    private List<SecurityPrincipal.RegionScope> assignedRegions(String subjectId,String workUnitCode) {
        return jdbc.sql("""
                WITH RECURSIVE unit_authorized(region_code) AS (
                    SELECT scope.region_code
                    FROM platform.work_unit_region_scope scope
                    WHERE scope.work_unit_code=:workUnitCode
                    UNION
                    SELECT child.code
                    FROM platform.region child
                    JOIN unit_authorized parent ON parent.region_code=child.parent_code
                ), assigned AS (
                    SELECT scope.region_code
                    FROM platform.security_user_region_scope scope
                    JOIN unit_authorized
                      ON unit_authorized.region_code=scope.region_code
                    WHERE scope.subject_id=:subjectId
                      AND CURRENT_TIMESTAMP>=scope.valid_from
                      AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP<scope.valid_until)
                      AND (scope.review_due_at IS NULL OR CURRENT_TIMESTAMP<scope.review_due_at)
                ), region_path(anchor_code,code,name,parent_code,depth) AS (
                    SELECT assigned.region_code,region.code,region.name,region.parent_code,0
                    FROM assigned JOIN platform.region region ON region.code=assigned.region_code
                    UNION ALL
                    SELECT path.anchor_code,parent.code,parent.name,parent.parent_code,path.depth+1
                    FROM region_path path JOIN platform.region parent ON parent.code=path.parent_code
                )
                SELECT assigned.region_code,anchor.administrative_level,
                       string_agg(path.name,' / ' ORDER BY path.depth DESC)
                FROM assigned
                JOIN platform.region anchor ON anchor.code=assigned.region_code
                JOIN region_path path ON path.anchor_code=assigned.region_code
                GROUP BY assigned.region_code,anchor.administrative_level
                ORDER BY assigned.region_code
                """).param("subjectId",subjectId).param("workUnitCode",workUnitCode)
                .query((row,index)->new SecurityPrincipal.RegionScope(
                        row.getString(1),row.getString(2),row.getString(3))).list();
    }

    private Set<String> roles(String subjectId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT role.code
                FROM platform.security_user_role user_role
                JOIN platform.access_role role ON role.code=user_role.role_code AND role.active
                WHERE user_role.subject_id=:subjectId
                  AND CURRENT_TIMESTAMP >= user_role.valid_from
                  AND (user_role.valid_until IS NULL OR CURRENT_TIMESTAMP < user_role.valid_until)
                  AND (user_role.review_due_at IS NULL OR CURRENT_TIMESTAMP < user_role.review_due_at)
                ORDER BY role.sort_order,role.code
                """).param("subjectId",subjectId).query(String.class).list());
    }

    private List<SecurityPrincipal.PositionAssignment> positions(String subjectId) {
        return jdbc.sql("""
                SELECT position.code,position.name,assignment.primary_position
                FROM platform.security_user_position assignment
                JOIN platform.position position ON position.code=assignment.position_code AND position.active
                WHERE assignment.subject_id=:subjectId
                  AND CURRENT_TIMESTAMP >= assignment.valid_from
                  AND (assignment.valid_until IS NULL OR CURRENT_TIMESTAMP < assignment.valid_until)
                ORDER BY assignment.primary_position DESC,position.sort_order,position.code
                """).param("subjectId",subjectId).query((row,index) ->
                        new SecurityPrincipal.PositionAssignment(
                                row.getString(1),row.getString(2),row.getBoolean(3))).list();
    }

    private record SecuritySubject(String id,String displayName,String workUnitCode,String workUnitName,
            String accountStatus,String employmentStatus) {}
}
