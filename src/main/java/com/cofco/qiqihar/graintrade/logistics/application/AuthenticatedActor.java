package com.cofco.qiqihar.graintrade.logistics.application;
public record AuthenticatedActor(String id) {
    public AuthenticatedActor { if (id == null || id.isBlank()) throw new IllegalArgumentException("actor required"); }
}
