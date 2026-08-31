package com.cofco.qiqihar.graintrade.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculation rules shared by market aggregates and database-generated actual prices. */
public final class MarketPricing {
    public static final int SCALE = 4;
    public static final int PRECISION = 18;
    public static final BigDecimal MAX = new BigDecimal("99999999999999.9999");

    private MarketPricing() {}

    public static BigDecimal actualPrice(
            MarketTradeDirection direction,
            BigDecimal purchaseBasePrice,
            BigDecimal saleBasePrice,
            BigDecimal carriageBoardAmount,
            BigDecimal packagingAmount,
            BigDecimal freightAmount) {
        if (direction == null) throw invalid("trade direction must not be null");
        BigDecimal base = switch (direction) {
            case PURCHASE -> amount(purchaseBasePrice, "purchase price");
            case SALE -> amount(saleBasePrice, "sale price");
            case BOTH -> amount(purchaseBasePrice, "purchase price")
                    .add(amount(saleBasePrice, "sale price"))
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
            case OBSERVATION -> {
                if (purchaseBasePrice != null || saleBasePrice != null
                        || carriageBoardAmount != null || packagingAmount != null
                        || freightAmount != null) {
                    throw invalid("observation-only record cannot contain trade prices");
                }
                yield null;
            }
        };
        if (base == null) return null;
        return base
                .add(amount(carriageBoardAmount, "carriage-board amount"))
                .add(amount(packagingAmount, "packaging amount"))
                .add(amount(freightAmount, "freight amount"))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal amount(BigDecimal value, String description) {
        if (value == null) throw invalid(description + " must not be null");
        BigDecimal normalized = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (normalized.signum() < 0 || normalized.compareTo(MAX) > 0
                || normalized.precision() > PRECISION) {
            throw invalid(description + " is outside database range");
        }
        return normalized;
    }

    private static MarketValidationException invalid(String message) {
        return new MarketValidationException(message);
    }
}
