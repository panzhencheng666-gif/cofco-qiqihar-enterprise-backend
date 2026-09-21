package com.cofco.qiqihar.riskintelligence.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RiskRequestIdentityTest {
    @Test
    void acceptsTheValidatedActorForwardedByTheLoopbackGateway() {
        assertThat(RiskRequestIdentity.requireActor("wang-yang")).isEqualTo("wang-yang");
        assertThat(RiskRequestIdentity.requireActor("employee:230200-01"))
                .isEqualTo("employee:230200-01");
    }

    @Test
    void rejectsMissingOrMalformedActors() {
        assertThatThrownBy(() -> RiskRequestIdentity.requireActor(null))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("身份");
        assertThatThrownBy(() -> RiskRequestIdentity.requireActor("../administrator"))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("身份");
    }
}
