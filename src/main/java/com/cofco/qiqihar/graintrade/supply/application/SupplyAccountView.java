package com.cofco.qiqihar.graintrade.supply.application;
import java.util.List;

public record SupplyAccountView(
        String id,
        String productCode,
        String regionCode,
        String marketingYear,
        int resultVersion,
        long decisionVersion,
        String resultState,
        List<String> validationCodes,
        boolean balanced,
        boolean publishable,
        String balanceReason,
        String totalSupply,
        String totalUse,
        String calculatedEndingInventory,
        String approvedAdjustment,
        String adoptedEndingInventory,
        String surveyedEndingInventory,
        String inventoryReconciliationDifference,
        SupplyAdjustmentAuditView adjustmentAudit,
        SupplyFormulaView formula,
        List<SupplySourceView> sources) {}
