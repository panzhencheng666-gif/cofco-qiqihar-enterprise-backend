package com.cofco.qiqihar.graintrade.supply.application;
import java.util.List;
public record SupplyFormulaView(String code,int version,String name,int precision,int scale,String roundingMode,String tolerance,
        String differenceCode,String differenceLabel,String differenceExpression,List<Expression> expressions){
 public record Expression(String resultCode,String label,String expression,int sortOrder){}
}
