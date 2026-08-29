package com.cofco.qiqihar.graintrade.overview.api;

import java.util.UUID;

public record CurrentOverviewSamplePoint(
        UUID samplePointId,
        double longitude,
        double latitude) {}
