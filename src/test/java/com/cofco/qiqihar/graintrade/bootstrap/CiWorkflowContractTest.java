package com.cofco.qiqihar.graintrade.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CiWorkflowContractTest {

    @Test
    void provisionsTheApplicationLoginWithInheritedRuntimePrivileges() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));

        assertThat(workflow)
                .contains("CREATE ROLE cofco_app LOGIN INHERIT")
                .doesNotContain("CREATE ROLE cofco_app LOGIN NOINHERIT");
    }

    @Test
    void reservesEnoughTimeForTheFullBackendVerificationSuite() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));

        assertThat(workflow).contains("timeout-minutes: 60");
    }
}
