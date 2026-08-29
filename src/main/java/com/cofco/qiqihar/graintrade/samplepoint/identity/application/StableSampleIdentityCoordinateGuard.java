package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import java.math.BigDecimal;

/**
 * Protects the immutable coordinate of a visible stable sample identity.
 */
public interface StableSampleIdentityCoordinateGuard {
    void requireCompatible(
            String canonicalName, String contact, BigDecimal longitude, BigDecimal latitude);
}
