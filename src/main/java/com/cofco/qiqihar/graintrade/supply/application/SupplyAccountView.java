package com.cofco.qiqihar.graintrade.supply.application;
import java.util.List;

public record SupplyAccountView(
        String id,
        String productCode,
        String regionCode,
        String marketingYear,
        int resultVersion,
        String calculationChecksum,
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
        String inputSetId,
        boolean legacyReadOnly,
        SupplyAdjustmentProposalView adjustmentProposal,
        SupplyAdjustmentAuditView adjustmentAudit,
        SupplyFormulaView formula,
        List<SupplySourceView> sources) {}
