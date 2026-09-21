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
                SELECT DISTINCT namespace.nspname
                FROM pg_class relation
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE relation.relkind IN ('r','p','v','m')
                  AND namespace.nspname NOT IN ('pg_catalog','information_schema')
                  AND namespace.nspname NOT LIKE 'pg_toast%'
                  AND namespace.nspname NOT LIKE 'pg_temp_%'
                  AND (has_table_privilege(current_user,relation.oid,'INSERT')
                    OR has_table_privilege(current_user,relation.oid,'UPDATE')
                    OR has_table_privilege(current_user,relation.oid,'DELETE')
                    OR has_table_privilege(current_user,relation.oid,'TRUNCATE')
                    OR has_table_privilege(current_user,relation.oid,'REFERENCES')
                    OR has_table_privilege(current_user,relation.oid,'TRIGGER'))
                ORDER BY namespace.nspname
                """).query(String.class).list());
        RiskDatabaseBoundary.verify(
                identity.databaseName(), identity.databaseUser(), writableSchemas, expectedDatabase);
        return new RiskDatabaseBoundary(
                identity.databaseName(), identity.databaseUser(), writableSchemas, riskClock.instant());
    }

    private record DatabaseIdentity(String databaseName, String databaseUser) { }
}
