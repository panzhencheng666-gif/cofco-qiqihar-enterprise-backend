package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.util.UUID;

public record FormalSampleMaintainerView(
        UUID id,
        String kindCode,
        String canonicalName,
        String regionCode,
        String maintainerSubjectId,
        String maintainerDisplayName,
        long version) {}
