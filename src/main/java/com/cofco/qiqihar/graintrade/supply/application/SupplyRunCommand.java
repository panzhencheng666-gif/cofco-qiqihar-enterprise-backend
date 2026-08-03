package com.cofco.qiqihar.graintrade.supply.application;
import java.math.BigDecimal;
public record SupplyRunCommand(String productCode,String regionCode,String marketingYear,String inputSetId,
        BigDecimal adjustmentProposalValue,String adjustmentProposalReason,long expectedDecisionVersion,boolean publish){}
