package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Controlled boundary owned by the enterprise IdP/invitation delivery service. */
public interface IdentityDeliveryGateway {
    void deliver(DeliveryCommand command);

    record DeliveryCommand(
            UUID eventId,UUID invitationId,String subjectId,String deliveryAddress,
            String activationToken,Instant expiresAt) {}
}
