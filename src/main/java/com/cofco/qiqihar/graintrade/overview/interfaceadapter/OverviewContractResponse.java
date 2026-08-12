package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

public record OverviewContractResponse<T>(String contractVersion, T data) {
    public static final String CONTRACT_VERSION = "overview-audit-v2";

    public OverviewContractResponse(T data) {
        this(CONTRACT_VERSION, data);
    }
}
