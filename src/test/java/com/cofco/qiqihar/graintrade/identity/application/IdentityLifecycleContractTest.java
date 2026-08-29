package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityLifecycleContractTest {
    @Test
    void freezesTheWebFacingLifecycleVocabulary() {
        assertThat(IdentityLifecycleContract.VERSION).isEqualTo("2026-08-30");
        assertThat(IdentityLifecycleContract.IDEMPOTENCY_HEADER).isEqualTo("Idempotency-Key");
        assertThat(IdentityLifecycleContract.INVITATION_STATUSES)
                .containsExactly("PENDING", "ACTIVATED", "REVOKED", "EXPIRED");
        assertThat(IdentityLifecycleContract.DELIVERY_RESULTS)
                .containsExactly("QUEUED", "DELIVERED", "FAILED");
        assertThat(IdentityLifecycleContract.ERROR_CODES).containsExactly(
                "IDENTITY_INVITATION_INVALID",
                "IDENTITY_INVITATION_NOT_FOUND",
                "IDENTITY_INVITATION_STATE_CONFLICT",
                "IDENTITY_INVITATION_IDEMPOTENCY_CONFLICT",
                "INVALID_IDEMPOTENCY_KEY",
                "INVALID_INVITATION_DELIVERY_ADDRESS",
                "IDENTITY_SUBJECT_NOT_FOUND");
        assertThat(IdentityLifecycleContract.AUDIT_EVENTS).containsExactly(
                "SECURITY_USER_INVITED",
                "SECURITY_USER_ACTIVATED",
                "SECURITY_INVITATION_REVOKED",
                "SECURITY_USER_REINVITED");
    }
}
