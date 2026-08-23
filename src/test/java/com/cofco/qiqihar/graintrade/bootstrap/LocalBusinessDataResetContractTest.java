package com.cofco.qiqihar.graintrade.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalBusinessDataResetContractTest {

    @Test
    void protectsNonTargetDraftsWhenTheTargetPredicateIsUnknown() throws Exception {
        String resetSql = Files.readString(Path.of("ops/reset_local_2026_production_market.sql"));

        assertThat(resetSql).contains(") IS NOT TRUE)),\n    ('village'");
    }
}
