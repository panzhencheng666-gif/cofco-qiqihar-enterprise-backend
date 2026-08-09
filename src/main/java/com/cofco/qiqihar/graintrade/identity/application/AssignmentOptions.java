package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public record AssignmentOptions(
        List<Option> workUnits,
        List<Option> roles,
        List<Option> positions,
        List<String> regionCodes) {
    public record Option(String code,String name) {}
}
