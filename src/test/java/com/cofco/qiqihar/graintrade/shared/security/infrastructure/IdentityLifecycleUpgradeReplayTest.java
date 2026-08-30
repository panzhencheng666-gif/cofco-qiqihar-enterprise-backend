package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdentityLifecycleUpgradeReplayTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSharedSchemaAndSecurityFixtures() {
        DATABASE.flyway().migrate();
        JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
        jdbc.sql("""
                DELETE FROM platform.identity_provider_binding
                WHERE security_subject_id='identity-v152-upgrade'
                """).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-v152-upgrade'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='IDENTITY_V152_UPGRADE'").update();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test
    void upgradesAnInstalledV152IdentityWithoutChangingItsStableBinding() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("152").migrate().migrationsExecuted)
                .isEqualTo(152);
        execute("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('IDENTITY_V152_UPGRADE','V152 身份升级夹具单位',9152)
                """);
        execute("""
                INSERT INTO platform.security_user(
                    subject_id,employee_number,display_name,work_unit_code,enabled)
                VALUES('identity-v152-upgrade','EMP-V152-001','升级保留员工','IDENTITY_V152_UPGRADE',true)
                """);
        execute("""
                INSERT INTO platform.identity_provider_binding(
                    binding_id,provider_code,issuer_uri,provider_subject,
                    security_subject_id,approved_by)
                VALUES('%s','KEYCLOAK','https://idp.example.test/realms/cofco',
                    'pre-v153-provider-subject','identity-v152-upgrade','identity-v152-upgrade')
                """.formatted(UUID.randomUUID()));

        assertThat(queryString("""
                SELECT version FROM public.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """)).isEqualTo("152");
        assertThat(queryString("""
                SELECT to_regclass('platform.identity_invitation')::text
                """)).isNull();

        assertThat(DATABASE.flywayToVersion("153").migrate().migrationsExecuted).isOne();

        assertThat(queryString("""
                SELECT string_agg(version,',' ORDER BY installed_rank)
                FROM public.flyway_schema_history
                WHERE success AND version IN ('152','153')
                """)).isEqualTo("152,153");
        assertThat(queryString("""
                SELECT employee_number || ':' || display_name || ':' || enabled || ':' || session_version
                FROM platform.security_user WHERE subject_id='identity-v152-upgrade'
                """)).isEqualTo("EMP-V152-001:升级保留员工:true:0");
        assertThat(queryString("""
                SELECT issuer_uri || ':' || provider_subject || ':' || state || ':' || version
                FROM platform.identity_provider_binding
                WHERE security_subject_id='identity-v152-upgrade'
                """)).isEqualTo(
                        "https://idp.example.test/realms/cofco:pre-v153-provider-subject:ACTIVE:0");
        assertThat(queryLong("""
                SELECT (SELECT count(*) FROM platform.identity_invitation)
                     + (SELECT count(*) FROM platform.identity_delivery_outbox)
                     + (SELECT count(*) FROM platform.oidc_session_registry)
                     + (SELECT count(*) FROM platform.http_session)
                """)).isZero();
        assertThat(queryString("""
                SELECT has_table_privilege(
                    'qiqihar_enterprise_runtime','platform.identity_invitation','SELECT')::text
                """)).isEqualTo("false");
    }

    @Test
    void upgradesInstalledV153WithoutChangingItsChecksumAndGrantsCurrentInvitationRead() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("153").migrate().migrationsExecuted)
                .isEqualTo(153);
        String v153Checksum = queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='153' AND success
                """);
        assertThat(queryString("""
                SELECT has_column_privilege(
                    'qiqihar_enterprise_runtime','platform.identity_invitation','created_at','SELECT')::text
                """)).isEqualTo("false");

        assertThat(DATABASE.flywayToVersion("154").migrate().migrationsExecuted).isOne();

        assertThat(queryString("""
                SELECT string_agg(version,',' ORDER BY installed_rank)
                FROM public.flyway_schema_history
                WHERE success AND version IN ('153','154')
                """)).isEqualTo("153,154");
        assertThat(queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='153' AND success
                """)).isEqualTo(v153Checksum);
        assertThat(queryString("""
                SELECT has_column_privilege(
                    'qiqihar_enterprise_runtime','platform.identity_invitation','created_at','SELECT')::text
                """)).isEqualTo("true");
    }

    @Test
    void upgradesInstalledV154WithoutChangingPublishedChecksumsAndAddsProductionObjects()
            throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("154").migrate().migrationsExecuted)
                .isEqualTo(154);
        String v153Checksum = queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='153' AND success
                """);
        String v154Checksum = queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='154' AND success
                """);
        assertThat(queryString("""
                SELECT to_regclass('production.monitoring_object')::text
                """)).isNull();

        assertThat(DATABASE.flywayToVersion("155").migrate().migrationsExecuted).isOne();

        assertThat(queryString("""
                SELECT string_agg(version,',' ORDER BY installed_rank)
                FROM public.flyway_schema_history
                WHERE success AND version IN ('153','154','155')
                """)).isEqualTo("153,154,155");
        assertThat(queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='153' AND success
                """)).isEqualTo(v153Checksum);
        assertThat(queryString("""
                SELECT checksum::text FROM public.flyway_schema_history
                WHERE version='154' AND success
                """)).isEqualTo(v154Checksum);
        assertThat(queryString("""
                SELECT to_regclass('production.monitoring_object')::text
                """)).isEqualTo("production.monitoring_object");
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }
}
