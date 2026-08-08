package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionClasspathTestProfileTest {

    private static final String TEST_CLASSPATH_MARKER =
            "com.cofco.qiqihar.graintrade.testsupport.TestSecurityConfiguration";

    @Test
    void productionClasspathCannotUseTestProfileToBypassOidc() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(TEST_CLASSPATH_MARKER))
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(SecurityStartupInvariant.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("test profile")
                            .hasMessageContaining("production artifact");
                });
    }
}
