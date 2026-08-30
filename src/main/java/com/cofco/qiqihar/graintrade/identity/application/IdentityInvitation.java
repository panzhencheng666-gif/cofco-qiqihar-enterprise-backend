package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.util.UUID;

public record IdentityInvitation(
        UUID invitationId,
        String subjectId,
        String invitationStatus,
        String deliveryStatus,
        Instant expiresAt,
        String requestSha256) {}
