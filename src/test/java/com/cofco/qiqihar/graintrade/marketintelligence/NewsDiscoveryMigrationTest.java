package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

class NewsDiscoveryMigrationTest {
    static final String LOCATION = "db/news-discovery-release";
    static final String MIGRATION = LOCATION + "/V224__news_discovery.sql";
    JdbcTemplate sql;
    Flyway flyway;

    @BeforeEach void setup() {
        var ds = ProtectedTestDatabase.shared().dataSource();
        sql = new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        for (String name : new String[]{"candidate", "source_admission", "host_schedule", "search_schedule"})
            sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_" + name);
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_test_history");
        sql.execute("""
            DO $$ BEGIN
              IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='qiqihar_enterprise_runtime') THEN
                CREATE ROLE qiqihar_enterprise_runtime NOLOGIN;
              END IF;
            END $$;
            """);
        flyway = Flyway.configure().dataSource(ds).locations("classpath:" + LOCATION)
            .defaultSchema("market_intelligence").table("news_discovery_test_history")
            .baselineVersion("223").baselineOnMigrate(false).target("224")
            .cleanDisabled(true).group(true).load();
        // Isolated local fixture, NOT the production baseline/history.
        flyway.baseline();
    }

    @Test void onlyNewMigrationIsPendingAndSecondRunIsNoop() {
        assertThat(new ClassPathResource(MIGRATION).exists()).isTrue();
        assertThat(flyway.info().pending()).extracting(m -> m.getVersion().getVersion())
            .containsExactly("224");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(sql.queryForObject("SELECT to_regclass('public.news_discovery_test_history') IS NULL", Boolean.class)).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM market_intelligence.news_discovery_source_admission", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT last_state FROM market_intelligence.news_discovery_search_schedule", String.class)).isEqualTo("NOT_RUN");
    }

    @Test void deploymentContainsExactReviewedDefinitionsOutsideDefaultScan() throws Exception {
        var resource = new ClassPathResource(MIGRATION);
        assertThat(resource.exists()).isTrue();
        String migration = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String name : new String[]{"candidate", "admission", "schedule", "search-schedule"}) {
            String definition = new ClassPathResource("db/news-discovery/" + name + "-schema.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(migration).contains(definition);
        }
        assertThat(new ClassPathResource("db/migration/V224__news_discovery.sql").exists()).isFalse();
    }

    @Test void grantsOnlyRequiredRuntimeTablePrivileges() {
        assertThat(new ClassPathResource(MIGRATION).exists()).isTrue();
        flyway.migrate();
        for (String name : new String[]{"candidate", "host_schedule", "search_schedule", "source_admission"}) {
            for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                boolean expected = privilege.equals("SELECT") ||
                    (!name.equals("source_admission") && (privilege.equals("INSERT") || privilege.equals("UPDATE")));
                assertThat(sql.queryForObject("SELECT has_table_privilege('qiqihar_enterprise_runtime', ?, ?)",
                    Boolean.class, "market_intelligence.news_discovery_" + name, privilege))
                    .as(name + ":" + privilege).isEqualTo(expected);
            }
        }
        assertThat(sql.queryForObject("""
            SELECT count(*) FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
            WHERE c.relnamespace='market_intelligence'::regnamespace
              AND c.relname LIKE 'news_discovery_%' AND (a.grantee=0 OR a.is_grantable AND
              a.grantee='qiqihar_enterprise_runtime'::regrole)
            """, Integer.class)).isZero();
    }

    @Test void refusesExistingUntrackedTableInsteadOfMaskingConflict() {
        assertThat(new ClassPathResource(MIGRATION).exists()).isTrue();
        sql.execute("CREATE TABLE market_intelligence.news_discovery_candidate(sentinel text)");
        sql.update("INSERT INTO market_intelligence.news_discovery_candidate VALUES ('retain')");
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT sentinel FROM market_intelligence.news_discovery_candidate", String.class)).isEqualTo("retain");
    }
}
