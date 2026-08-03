package com.cofco.qiqihar.graintrade.reporting.application;

import java.util.Optional;

/** Port for the authenticated reporting actor. */
public interface ReportingActor {
    Optional<String> currentActorId();
}
