package com.cofco.qiqihar.graintrade.messaging.application;

public record SendUserMessageCommand(
        String recipient,
        String channel,
        String title,
        String body,
        String idempotencyKey) {}
