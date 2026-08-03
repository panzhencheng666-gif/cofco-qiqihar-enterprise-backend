package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyManualDecisionMaterial(
        boolean contextExists,
        SupplySourceReleaseMaterial.SourceMapping mapping,
        boolean decisionExists,
        long currentVersion) {}
