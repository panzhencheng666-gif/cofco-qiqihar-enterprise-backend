package com.cofco.qiqihar.riskintelligence.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Explicit, non-web entry point for the shared database's controlled Flyway history. */
public final class RiskMigrationRunner {
    private static final Set<String> ALLOWED_START_VERSIONS=Set.of("213","214","215","216","217");

    private RiskMigrationRunner() { }

    public static void main(String[] args) throws Exception {
        String url=require("RISK_MIGRATION_URL");
        String username=require("RISK_MIGRATION_USERNAME");
        String password=require("RISK_MIGRATION_PASSWORD");
        String expectedDatabase=require("RISK_EXPECTED_DATABASE");
        Path migrations=Path.of(require("RISK_MIGRATION_PATH")).toAbsolutePath().normalize();
        requireMigrationSet(migrations);

        String currentVersion;
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
                currentVersion=result.getString(1);
            }
        }
        if (!ALLOWED_START_VERSIONS.contains(currentVersion)) {
            throw new IllegalStateException("Refusing migration from unexpected version "+currentVersion);
        }

        Flyway flyway=Flyway.configure()
                .dataSource(url,username,password)
                .locations("filesystem:"+migrations)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .load();
        flyway.validate();
        var result=flyway.migrate();
        String finalVersion=flyway.info().current().getVersion().getVersion();
        if (!"217".equals(finalVersion)) {
            throw new IllegalStateException("Risk migration stopped at version "+finalVersion);
        }
        System.out.printf("RISK_FLYWAY_MIGRATION_OK from=%s to=%s executed=%d%n",
                currentVersion,finalVersion,result.migrationsExecuted);
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
                || !Files.isRegularFile(migrations.resolve("V217__isolate_risk_schema_runtime.sql"))) {
            throw new IllegalArgumentException("Controlled migration directory is incomplete: "+migrations);
        }
    }
}
