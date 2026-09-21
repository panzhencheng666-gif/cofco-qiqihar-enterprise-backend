package com.cofco.qiqihar.riskintelligence.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RiskDatabaseBoundaryTest {
    @Test
    void acceptsExistingDatabaseOnlyWhenRiskIsTheOnlyWritableSchema() {
        assertThatCode(() -> RiskDatabaseBoundary.verify(
                "qiqihar_enterprise_test", "risk_runtime", Set.of("risk"),
                "qiqihar_enterprise_test"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWriteAccessToAnExistingBusinessSchema() {
        assertThatThrownBy(() -> RiskDatabaseBoundary.verify(
                "qiqihar_enterprise_test", "risk_runtime", Set.of("risk", "platform"),
                "qiqihar_enterprise_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform");
    }

    @Test
    void rejectsConnectionToAnUnexpectedDatabase() {
        assertThatThrownBy(() -> RiskDatabaseBoundary.verify(
                "postgres", "risk_runtime", Set.of("risk"),
                "qiqihar_enterprise_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected risk database");
    }

    @Test
    void rejectsMissingRiskWriteAccess() {
        assertThatThrownBy(() -> RiskDatabaseBoundary.verify(
                "qiqihar_enterprise_test", "risk_runtime", Set.of(),
                "qiqihar_enterprise_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writable schema set");
    }
}
