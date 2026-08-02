package com.cofco.qiqihar.graintrade.market.application;

import java.util.Optional;

@FunctionalInterface
public interface CurrentActor { Optional<AuthenticatedActor> currentActor(); }
