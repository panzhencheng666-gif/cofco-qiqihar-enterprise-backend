package com.cofco.qiqihar.riskintelligence.operations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class RiskMigrationOwnershipTest {
    @Test void acceptsExactIndependentMarketAndSeedHistory() {
        String[] scripts={"V218__world_bank_monthly_benchmarks.sql", "V219__fao_news_headlines.sql",
                "V220__moa_public_market_indices.sql", "V221__fao_food_price_indices.sql",
                "V222__disable_unreviewed_risk_model_auto_activation.sql", "V223__official_webcast_events.sql"};
        for (int i=0;i<scripts.length;i++) {
            String version=Integer.toString(218+i),script=scripts[i];
            assertThatCode(() -> RiskMigrationRunner.requireSharedMigrationOwnership(version,script))
                    .doesNotThrowAnyException();
        }
    }
    @Test void rejectsSharedRiskHistoryAndUnexpectedSameNumberScript() {
        assertThatThrownBy(() -> RiskMigrationRunner.requireSharedMigrationOwnership("214",
                "V214__create_inventory_risk_foundation.sql")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RiskMigrationRunner.requireSharedMigrationOwnership("218",
                "V218__establish_qiliang_risk_model_identity.sql")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RiskMigrationRunner.requireSharedMigrationOwnership("223",
                "V223__unknown.sql")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RiskMigrationRunner.requireSharedMigrationOwnership("224",
                "V224__project_formal_market_facts_to_risk.sql")).isInstanceOf(IllegalStateException.class);
    }
    @Test void permitsUnrelatedSharedVersions() {
        assertThatCode(() -> RiskMigrationRunner.requireSharedMigrationOwnership("213",
                "V213__make_operational_facilities_user_governed.sql")).doesNotThrowAnyException();
    }
}
