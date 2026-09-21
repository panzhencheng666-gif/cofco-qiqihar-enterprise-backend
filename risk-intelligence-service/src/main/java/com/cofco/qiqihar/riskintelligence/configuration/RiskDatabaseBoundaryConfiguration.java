package com.cofco.qiqihar.riskintelligence.configuration;

import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class RiskDatabaseBoundaryConfiguration {
    @Bean
    Clock riskClock() {
        return Clock.systemUTC();
    }

    @Bean
    RiskDatabaseBoundary riskDatabaseBoundary(
            JdbcClient jdbc,
            Clock riskClock,
            @Value("${qiqihar.risk.expected-database}") String expectedDatabase) {
        DatabaseIdentity identity = jdbc.sql(
                        "SELECT current_database() AS database_name,current_user AS database_user")
                .query((row, index) -> new DatabaseIdentity(
                        row.getString("database_name"), row.getString("database_user")))
                .single();
        Set<String> writableSchemas = new TreeSet<>(jdbc.sql("""
                SELECT DISTINCT table_schema
                FROM information_schema.role_table_grants
                WHERE grantee=current_user
                  AND privilege_type IN ('INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER')
                  AND table_schema NOT IN ('pg_catalog','information_schema')
                ORDER BY table_schema
                """).query(String.class).list());
        RiskDatabaseBoundary.verify(
                identity.databaseName(), identity.databaseUser(), writableSchemas, expectedDatabase);
        return new RiskDatabaseBoundary(
                identity.databaseName(), identity.databaseUser(), writableSchemas, riskClock.instant());
    }

    private record DatabaseIdentity(String databaseName, String databaseUser) { }
}
