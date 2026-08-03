package com.cofco.qiqihar.graintrade.logistics.application;
import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import java.util.List;
import java.util.Map;
public record LogisticsRecordView(String id, String productCode, Map<String,String> values, Map<String,String> displayValues,
        LogisticsStatus status, String returnReason, List<String> allowedActions, long version) { }
