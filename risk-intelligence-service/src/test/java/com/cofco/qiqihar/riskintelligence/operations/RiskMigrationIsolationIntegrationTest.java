package com.cofco.qiqihar.riskintelligence.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Only a named disposable loopback database can run destructive fixture setup. */
@EnabledIfEnvironmentVariable(named="RISK_MIGRATION_INTEGRATION_URL",
        matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/risk_migration_isolated_test")
class RiskMigrationIsolationIntegrationTest {
    private final String url=System.getenv("RISK_MIGRATION_INTEGRATION_URL");
    private final String password=System.getenv().getOrDefault("RISK_MIGRATION_INTEGRATION_PASSWORD","");

    @Test void guardedFreshUpgradePreservesSeedsAndOriginalRiskChecksums() throws Exception {
        try (Connection c=fixture(); Statement s=c.createStatement()) {
            Path base=Files.createTempDirectory("risk-base-");
            copyLegacy(base,217);
            migrate(base,"217");
            applySharedGuard(s);
            Path complete=completeSequence();
            runControlled(complete);
            assertManualPolicies(s);
            assertThat(s.executeQuery("SELECT model_id::text FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'").next()).isTrue();
            var r=s.executeQuery("SELECT model_id::text FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'");r.next();
            assertThat(r.getString(1)).isEqualTo("21800000-0000-0000-0000-000000000001");
            r=s.executeQuery("SELECT model_code FROM risk.ai_model WHERE model_id='21500000-0000-0000-0000-000000000002'");r.next();
            assertThat(r.getString(1)).isEqualTo("risk-reasoning-llm-v1");
            assertThat(Flyway.configure().dataSource(url,"postgres",password).locations("filesystem:"+complete)
                    .table("risk_flyway_schema_history").load().validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test void existing225UpgradeAppliesOnlyNewCompatibilityAndForwardGuard() throws Exception {
        try (Connection c=fixture(); Statement s=c.createStatement()) {
            Path legacy=Files.createTempDirectory("risk-225-");copyLegacy(legacy,225);migrate(legacy,"225");
            applySharedGuard(s);
            var r=s.executeQuery("SELECT model_id::text FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'");r.next();
            String previousId=r.getString(1);
            runControlled(completeSequence());
            assertManualPolicies(s);
            r=s.executeQuery("SELECT model_id::text FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'");r.next();
            assertThat(r.getString(1)).isEqualTo(previousId);
            r=s.executeQuery("SELECT count(*) FROM public.risk_flyway_schema_history WHERE version IN ('217.1','226') AND success");r.next();
            assertThat(r.getInt(1)).isEqualTo(2);
        }
    }

    private Connection fixture() throws Exception {
        Connection c=DriverManager.getConnection(url,"postgres",password);
        try (Statement s=c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS risk CASCADE");
            s.execute("DROP SCHEMA IF EXISTS overview CASCADE");
            s.execute("DROP SCHEMA IF EXISTS platform CASCADE");
            s.execute("DROP SCHEMA IF EXISTS market CASCADE");
            s.execute("DROP TABLE IF EXISTS public.risk_flyway_schema_history");
            s.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_enterprise_runtime') THEN CREATE ROLE qiqihar_enterprise_runtime; END IF; IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime') THEN CREATE ROLE qiqihar_risk_runtime; END IF; END $$");
            s.execute("CREATE SCHEMA platform; CREATE TABLE platform.product(code varchar(40) PRIMARY KEY)");
            s.execute("CREATE SCHEMA overview; CREATE TABLE overview.storage_facility(facility_code varchar(80) PRIMARY KEY)");
            s.execute("CREATE SCHEMA market; CREATE TABLE market.market_record(record_id uuid,version bigint,region_code text,product_code text,object_type_code text,trade_date date,reported_at timestamptz,submitted_at timestamptz,updated_at timestamptz,status_code text,trade_direction text,purchase_base_price numeric,sale_base_price numeric,actual_trade_price numeric,survey_period_governance_state text)");
            s.execute("CREATE TABLE public.flyway_schema_history(installed_rank integer,version text,script text,success boolean)");
            s.execute("INSERT INTO public.flyway_schema_history VALUES (1,'213','V213__make_operational_facilities_user_governed.sql',true),(2,'218','V218__world_bank_monthly_benchmarks.sql',true),(3,'219','V219__fao_news_headlines.sql',true),(4,'220','V220__moa_public_market_indices.sql',true),(5,'221','V221__fao_food_price_indices.sql',true),(6,'222','V222__disable_unreviewed_risk_model_auto_activation.sql',true),(7,'223','V223__official_webcast_events.sql',true)");
        }
        return c;
    }
    private void applySharedGuard(Statement s) throws Exception {
        s.execute(Files.readString(Path.of("../src/main/resources/db/migration/V222__disable_unreviewed_risk_model_auto_activation.sql")));
    }
    private void copyLegacy(Path target,int end) throws Exception {
        for (int v=214;v<=end;v++) {
            Path dir=Path.of(v<218?"../src/main/resources/db/migration":"../ops/risk-intelligence/migrations");
            try (var files=Files.list(dir)) {
                String prefix="V"+v+"__";
                Path source=files.filter(p->p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
                Files.copy(source,target.resolve(source.getFileName()));
            }
        }
    }
    private Path completeSequence() throws Exception {
        Path target=Files.createTempDirectory("risk-complete-");copyLegacy(target,225);
        for (String name:new String[]{"V217_1__preserve_bootstrap_identity_before_qiliang.sql","V226__require_manual_qiliang_model_promotion.sql"}) {
            Files.copy(Path.of("../ops/risk-intelligence/migrations",name),target.resolve(name));
        }
        return target;
    }
    private void migrate(Path migrations,String target) {
        Flyway.configure().dataSource(url,"postgres",password).locations("filesystem:"+migrations)
                .table("risk_flyway_schema_history").baselineOnMigrate(true).baselineVersion("213")
                .target(target).load().migrate();
    }
    private void runControlled(Path migrations) throws Exception {
        String javaCommand=Path.of(System.getProperty("java.home"),"bin","java").toString();
        ProcessBuilder builder=new ProcessBuilder(javaCommand,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),RiskMigrationRunner.class.getName());
        var env=builder.environment();env.put("RISK_MIGRATION_URL",url);env.put("RISK_MIGRATION_USERNAME","postgres");env.put("RISK_MIGRATION_PASSWORD",password.isEmpty()?"isolated-unused":password);env.put("RISK_EXPECTED_DATABASE","risk_migration_isolated_test");env.put("RISK_MIGRATION_PATH",migrations.toString());
        Process process=builder.redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("to=226");
    }
    private void assertManualPolicies(Statement s) throws Exception {
        var r=s.executeQuery("SELECT count(*) FROM risk.ai_training_policy p JOIN risk.ai_model m USING(model_id) WHERE (m.model_code='qiliang-risk-llm-v1' OR p.model_id IN ('21500000-0000-0000-0000-000000000001','21500000-0000-0000-0000-000000000002')) AND p.auto_activation_enabled");r.next();
        assertThat(r.getInt(1)).isZero();
        assertThatThrownBy(()->s.execute("UPDATE risk.ai_training_policy SET auto_activation_enabled=true WHERE model_id=(SELECT model_id FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1')")).hasMessageContaining("manual promotion");
        assertThatThrownBy(()->s.execute("UPDATE risk.ai_training_policy SET auto_activation_enabled=true WHERE model_id='21500000-0000-0000-0000-000000000001'")).hasMessageContaining("constraint");
    }
}
