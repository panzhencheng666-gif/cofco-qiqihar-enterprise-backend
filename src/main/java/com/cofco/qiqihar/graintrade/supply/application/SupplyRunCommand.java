package com.cofco.qiqihar.graintrade.supply.application;
import java.math.BigDecimal;
public record SupplyRunCommand(String productCode,String regionCode,String marketingYear,BigDecimal approvedAdjustment,
        String adoptionReason,String adjustmentReason,long expectedDecisionVersion,boolean publish){}
