package com.cofco.qiqihar.graintrade.testsupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketTestDatabaseResetTest {

    @Test
    void removesMarketSchemaAndHistoryBeforeEachNewTestSession() throws Exception {
        ProtectedTestDatabase database = ProtectedTestDatabase.shared();
        for (int session = 0; session < 2; session++) {
            try (var connection = database.openConnection();
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA market_intelligence");
                statement.execute("CREATE TABLE market_intelligence.reset_probe(id integer)");
                statement.execute("CREATE TABLE public.flyway_schema_history(reset_probe integer)");
            }

            database.resetForTestSession();

            try (var connection = database.openConnection();
                    var statement = connection.createStatement();
                    var row = statement.executeQuery("""
                            SELECT NOT EXISTS (
                              SELECT 1 FROM pg_namespace WHERE nspname='market_intelligence'),
                              to_regclass('public.flyway_schema_history') IS NULL
                            """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).as("market schema removed for session %s", session).isTrue();
                assertThat(row.getBoolean(2)).as("migration history removed for session %s", session).isTrue();
            }
        }
    }
}
