package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

public record StoredFormalSampleObservation(
        String requestSha256,
        FormalSampleObservationResult result) {
}
