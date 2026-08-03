package com.cofco.qiqihar.graintrade.logistics.application;
import java.math.BigDecimal;
import java.time.LocalDate;
public record LogisticsDraft(
        String productCode, String monitoringPeriodCode, LocalDate collectionDate,
        long originNodeId, long destinationNodeId, String transportModeCode, String directionCode,
        BigDecimal routeVolume, String volumeUnit, BigDecimal freightRate, String freightUnit,
        BigDecimal transitTime, String transitUnit, String sourceOrganization, String reporter) { }
