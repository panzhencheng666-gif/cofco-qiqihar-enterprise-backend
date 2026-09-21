package com.cofco.qiqihar.graintrade.messaging.application;

import java.time.Instant;
import java.util.UUID;

public record UserMessageView(
        UUID id,String sender,String senderDisplayName,String recipient,String recipientDisplayName,
        String channel,String title,String body,String deliveryStatus,boolean read,Instant createdAt) {}
