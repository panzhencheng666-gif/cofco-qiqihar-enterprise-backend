package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;

public record SupplyAccountCalculation(
        BigDecimal totalSupply, BigDecimal totalUse, BigDecimal calculatedEndingInventory,
        BigDecimal approvedAdjustment, BigDecimal adoptedEndingInventory,
        BigDecimal surveyedEndingInventory, BigDecimal inventoryReconciliationDifference,
        boolean balanced, SupplyResultState resultState) { }
