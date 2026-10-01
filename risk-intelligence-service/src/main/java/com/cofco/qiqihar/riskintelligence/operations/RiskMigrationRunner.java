package com.cofco.qiqihar.riskintelligence.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;

/** Explicit, non-web entry point for the risk subsystem's isolated Flyway history. */
public final class RiskMigrationRunner {
    private static final String RISK_HISTORY_TABLE="risk_flyway_schema_history";
    private static final String SHARED_BASELINE_VERSION="213";

    private RiskMigrationRunner() { }

    public static void main(String[] args) throws Exception {
        String url=require("RISK_MIGRATION_URL");
        String username=require("RISK_MIGRATION_USERNAME");
        String password=require("RISK_MIGRATION_PASSWORD");
        String expectedDatabase=require("RISK_EXPECTED_DATABASE");
        Path migrations=Path.of(require("RISK_MIGRATION_PATH")).toAbsolutePath().normalize();
        requireMigrationSet(migrations);

        String sharedLatestVersion;
        boolean compatibilityOutOfOrder=false;
        try (Connection connection=DriverManager.getConnection(url,username,password);
             Statement statement=connection.createStatement()) {
            try (ResultSet result=statement.executeQuery("select current_database()")) {
                result.next();
                String actualDatabase=result.getString(1);
                if (!expectedDatabase.equals(actualDatabase)) {
                    throw new IllegalStateException("Unexpected migration database: "+actualDatabase);
                }
            }
            try (ResultSet result=statement.executeQuery(
                    "select version from public.flyway_schema_history "
                    +"where success order by installed_rank desc limit 1")) {
                if (!result.next()) throw new IllegalStateException("Flyway history is empty");
                sharedLatestVersion=result.getString(1);
            }
            try (ResultSet result=statement.executeQuery(
                    "select count(*) from public.flyway_schema_history where version='213' and success")) {
                result.next();
                if (result.getInt(1)!=1) {
                    throw new IllegalStateException("Shared Flyway baseline 213 is not applied exactly once");
                }
            }
            try (ResultSet result=statement.executeQuery(
                    "select version,script from public.flyway_schema_history where version is not null")) {
                while (result.next()) {
                    requireSharedMigrationOwnership(result.getString(1),result.getString(2));
                }
            }
            boolean riskHistoryExists;
            try (ResultSet result=statement.executeQuery(
                    "select to_regclass('public."+RISK_HISTORY_TABLE+"') is not null")) {
                result.next();
                riskHistoryExists=result.getBoolean(1);
            }
            boolean v218Applied=false;
            if (riskHistoryExists) {
                try (ResultSet result=statement.executeQuery(
                        "select count(*) from public."+RISK_HISTORY_TABLE
                        +" where version='218' and success")) {
                    result.next();
                    v218Applied=result.getInt(1)==1;
                }
            }
            if (v218Applied) {
                java.util.Set<String> applied=new java.util.HashSet<>();
                try (ResultSet result=statement.executeQuery(
                        "select version from public."+RISK_HISTORY_TABLE+" where success")) {
                    while (result.next()) applied.add(result.getString(1));
                }
                // Only the newly introduced compatibility migration may run below
                // the existing tip. Other missing legacy versions remain an error.
                compatibilityOutOfOrder=!applied.contains("217.1");
                if (compatibilityOutOfOrder) {
                    int current=applied.stream().filter(v -> v.matches("[0-9]+"))
                            .mapToInt(Integer::parseInt).max().orElse(213);
                    for (int version=214;version<=Math.min(current,225);version++) {
                        if (!applied.contains(Integer.toString(version))) {
                            throw new IllegalStateException("Missing prior risk migration: "+version);
                        }
                    }
                }
            }
            if (!v218Applied) {
                boolean modelTableExists;
                try (ResultSet result=statement.executeQuery(
                        "select to_regclass('risk.ai_model') is not null")) {
                    result.next();
                    modelTableExists=result.getBoolean(1);
                }
                if (modelTableExists) try (ResultSet result=statement.executeQuery(
                        "select count(*) from risk.ai_model "
                        +"where model_code='qiliang-risk-llm-v1'")) {
                    result.next();
                    if (result.getInt(1)>0) {
                        throw new IllegalStateException(
                                "Unexpected preexisting QL-Risk identity before migration 218; "
                                +"refusing to overwrite database drift");
                    }
                }
            }
        }

        Flyway flyway=Flyway.configure()
                .dataSource(url,username,password)
                .locations("filesystem:"+migrations)
                .defaultSchema("public")
                .table(RISK_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .outOfOrder(compatibilityOutOfOrder)
                .baselineVersion(SHARED_BASELINE_VERSION)
                .baselineDescription("Risk intelligence isolated baseline")
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .load();
        var result=flyway.migrate();
        flyway.validate();
        String finalVersion=flyway.info().current().getVersion().getVersion();
        if (!"226".equals(finalVersion)) {
            throw new IllegalStateException("Risk migration stopped at version "+finalVersion);
        }
        System.out.printf("RISK_FLYWAY_MIGRATION_OK sharedLatest=%s baseline=%s to=%s executed=%d history=%s%n",
                sharedLatestVersion,SHARED_BASELINE_VERSION,finalVersion,
                result.migrationsExecuted,RISK_HISTORY_TABLE);
    }

    private static final java.util.Map<String,String> SHARED_MIGRATIONS=java.util.Map.of(
            "218","V218__world_bank_monthly_benchmarks.sql",
            "219","V219__fao_news_headlines.sql",
            "220","V220__moa_public_market_indices.sql",
            "221","V221__fao_food_price_indices.sql",
            "222","V222__disable_unreviewed_risk_model_auto_activation.sql",
            "223","V223__official_webcast_events.sql");

    static void requireSharedMigrationOwnership(String version,String script) {
        if (java.util.Set.of("214","215","216","217","217.1").contains(version)
                || (SHARED_MIGRATIONS.containsKey(version)
                    && !SHARED_MIGRATIONS.get(version).equals(script))
                || java.util.Set.of("V224__project_formal_market_facts_to_risk.sql",
                    "V225__track_market_rule_assessments.sql",
                    "V226__require_manual_qiliang_model_promotion.sql").contains(script)) {
            throw new IllegalStateException("Shared Flyway history owns an independent risk migration: "+version);
        }
    }

    private static String require(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable is missing: "+name);
        }
        return value;
    }

    private static void requireMigrationSet(Path migrations) {
        if (!Files.isDirectory(migrations)
                || !Files.isRegularFile(migrations.resolve("V214__create_inventory_risk_foundation.sql"))
                || !Files.isRegularFile(migrations.resolve("V217_1__preserve_bootstrap_identity_before_qiliang.sql"))
                || !Files.isRegularFile(migrations.resolve("V219__harden_qiliang_model_lineage.sql"))
                || !Files.isRegularFile(migrations.resolve("V220__create_expert_sft_queue.sql"))
                || !Files.isRegularFile(migrations.resolve("V221__grant_expert_queue_risk_runtime.sql"))
                || !Files.isRegularFile(migrations.resolve("V222__create_qiliang_ai_assistant.sql"))
                || !Files.isRegularFile(migrations.resolve("V223__govern_ai_knowledge_snapshots.sql"))
                || !Files.isRegularFile(migrations.resolve("V224__project_formal_market_facts_to_risk.sql"))
                || !Files.isRegularFile(migrations.resolve("V225__track_market_rule_assessments.sql"))
                || !Files.isRegularFile(migrations.resolve("V226__require_manual_qiliang_model_promotion.sql"))) {
            throw new IllegalArgumentException("Controlled migration directory is incomplete: "+migrations);
        }
    }
}
