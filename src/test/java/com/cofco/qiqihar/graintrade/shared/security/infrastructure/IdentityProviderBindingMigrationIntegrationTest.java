package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class IdentityProviderBindingMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired SecurityPrincipalRepository principals;

    @BeforeEach
    void removeTestBindings() {
        JdbcClient.create(dataSource).sql("""
                DELETE FROM platform.identity_provider_binding
                WHERE issuer_uri LIKE 'https://identity-binding-test.example/%'
                """).update();
    }

    @Test
    void migrationCreatesStableEmployeeIdentityAndProviderBindingStorage() {
        JdbcClient jdbc=JdbcClient.create(dataSource);

        assertThat(jdbc.sql("SELECT to_regclass('platform.identity_provider_binding')::text")
                .query(String.class).optional()).contains("platform.identity_provider_binding");
        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM platform.security_user
                WHERE employee_number IS NULL OR btrim(employee_number)=''
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT has_table_privilege(
                    'qiqihar_enterprise_runtime','platform.identity_provider_binding','SELECT')
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT has_table_privilege(
                    'qiqihar_enterprise_runtime','platform.identity_provider_binding','INSERT')
                """).query(Boolean.class).single()).isFalse();
    }

    @Test
    void exactIssuerAndSubjectResolveToStableBusinessIdentityAndRevocationFailsClosed() {
        JdbcClient jdbc=JdbcClient.create(dataSource);
        insertBinding(jdbc,"https://identity-binding-test.example/keycloak","shared-provider-subject",
                "production-tester");
        insertBinding(jdbc,"https://identity-binding-test.example/eiam","shared-provider-subject",
                "market-tester");

        assertThat(principals.findEnabledByOidcIdentity(
                "https://identity-binding-test.example/keycloak","shared-provider-subject"))
                .get().extracting(principal -> principal.subjectId()).isEqualTo("production-tester");
        assertThat(principals.findEnabledByOidcIdentity(
                "https://identity-binding-test.example/eiam","shared-provider-subject"))
                .get().extracting(principal -> principal.subjectId()).isEqualTo("market-tester");

        jdbc.sql("""
                UPDATE platform.identity_provider_binding
                SET state='REVOKED',updated_at=now(),version=version+1
                WHERE issuer_uri='https://identity-binding-test.example/keycloak'
                  AND provider_subject='shared-provider-subject'
                """).update();

        assertThat(principals.findEnabledByOidcIdentity(
                "https://identity-binding-test.example/keycloak","shared-provider-subject")).isEmpty();
    }

    @Test
    void expiredAndDuplicateExactProviderIdentitiesFailClosed() {
        JdbcClient jdbc=JdbcClient.create(dataSource);
        insertBinding(jdbc,"https://identity-binding-test.example/expired","expired-subject",
                "production-tester");
        jdbc.sql("""
                UPDATE platform.identity_provider_binding
                SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day'
                WHERE issuer_uri='https://identity-binding-test.example/expired'
                """).update();

        assertThat(principals.findEnabledByOidcIdentity(
                "https://identity-binding-test.example/expired","expired-subject")).isEmpty();
        assertThatThrownBy(() -> insertBinding(jdbc,
                "https://identity-binding-test.example/expired","expired-subject","market-tester"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyRuntimeEmployeeInsertRemainsCompatibleWithTheAdditiveMigration() throws SQLException {
        JdbcClient jdbc=JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='legacy-runtime-employee'").update();
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var role=connection.createStatement()) {
                role.execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
            }
            try(var insert=connection.prepareStatement("""
                    INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                    VALUES ('legacy-runtime-employee','旧版运行时员工','TEST')
                    """)) {
                insert.executeUpdate();
            }
            connection.commit();
        }

        assertThat(jdbc.sql("""
                SELECT employee_number FROM platform.security_user
                WHERE subject_id='legacy-runtime-employee'
                """).query(String.class).single()).isEqualTo("legacy-runtime-employee");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='legacy-runtime-employee'").update();
    }

    private static void insertBinding(JdbcClient jdbc,String issuer,String providerSubject,
            String securitySubjectId) {
        jdbc.sql("""
                INSERT INTO platform.identity_provider_binding(
                    binding_id,provider_code,issuer_uri,provider_subject,security_subject_id,approved_by)
                VALUES(:id,'KEYCLOAK',:issuer,:providerSubject,:securitySubjectId,'production-tester')
                """).param("id",UUID.randomUUID()).param("issuer",issuer)
                .param("providerSubject",providerSubject).param("securitySubjectId",securitySubjectId).update();
    }
}
