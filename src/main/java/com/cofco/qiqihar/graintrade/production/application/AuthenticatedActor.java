package com.cofco.qiqihar.graintrade.production.application;

public record AuthenticatedActor(String id) {
    public AuthenticatedActor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("actor id must not be blank");
    }
}
