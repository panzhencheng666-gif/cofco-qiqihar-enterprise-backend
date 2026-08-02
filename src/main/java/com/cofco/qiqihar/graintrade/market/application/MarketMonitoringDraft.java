package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketTradeDirection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record MarketMonitoringDraft(String productCode, String objectTypeCode, String regionCode,
        LocalDate tradeDate, MarketTradeDirection direction, BigDecimal purchaseBasePrice,
        BigDecimal saleBasePrice, BigDecimal carriageBoardAmount, BigDecimal freightAmount,
        BigDecimal packagingAmount, String packagingForm, Map<String, BigDecimal> facts) {
    public MarketMonitoringDraft { facts = facts == null ? Map.of() : Map.copyOf(facts); }
}
