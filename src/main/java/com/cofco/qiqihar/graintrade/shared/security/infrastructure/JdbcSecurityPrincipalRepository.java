package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
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
    public Optional<SecurityPrincipal> findEnabled(String subjectId) {
        return jdbc.sql("""
                SELECT subject_id, work_unit_code
                FROM platform.security_user
                WHERE subject_id = :subjectId AND enabled
                """).param("subjectId", subjectId).query((row, index) -> new SecuritySubject(
                row.getString(1), row.getString(2))).optional().map(subject -> new SecurityPrincipal(
                subject.id(), subject.workUnitCode(), permissions(subject.id()), regions(subject.id(), subject.workUnitCode())));
    }

    private Set<String> permissions(String subjectId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT role_permission.permission_code
                FROM platform.security_user_role user_role
                JOIN platform.access_role access_role ON access_role.code = user_role.role_code AND access_role.active
                JOIN platform.access_role_permission role_permission ON role_permission.role_code = access_role.code
                JOIN platform.access_permission permission ON permission.code = role_permission.permission_code AND permission.active
                WHERE user_role.subject_id = :subjectId
                """).param("subjectId", subjectId).query(String.class).list());
    }

    private Set<String> regions(String subjectId, String workUnitCode) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT scope.region_code
                FROM platform.security_user_region_scope scope
                JOIN platform.work_unit_region_scope unit_scope
                  ON unit_scope.work_unit_code = :workUnitCode
                 AND unit_scope.region_code = scope.region_code
                WHERE scope.subject_id = :subjectId
                """).param("subjectId", subjectId).param("workUnitCode", workUnitCode).query(String.class).list());
    }

    private record SecuritySubject(String id, String workUnitCode) {}
}
