package com.cofco.qiqihar.graintrade.production.application;

import java.util.Optional;

@FunctionalInterface
public interface CurrentActor {
    Optional<AuthenticatedActor> currentActor();
}
