package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public record AssignmentOptions(
        List<Option> workUnits,
        List<Option> roles,
        List<Option> positions,
        List<String> regionCodes,
        List<RegionOption> regions) {
    public record Option(String code,String name) {}
    public record RegionOption(String code,String name,String administrativeLevel,String parentCode) {}
}
