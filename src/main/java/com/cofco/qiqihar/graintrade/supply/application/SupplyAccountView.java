package com.cofco.qiqihar.graintrade.supply.application;
import java.util.List;
public record SupplyAccountView(String id,String productCode,String regionCode,String marketingYear,int version,
        String resultState,List<String> validationCodes,String totalSupply,String totalUse,String calculatedEndingInventory,
        String approvedAdjustment,String adoptedEndingInventory,String surveyedEndingInventory,
        String inventoryReconciliationDifference,boolean balanced,SupplyFormulaView formula,List<SupplySourceView> sources){}
