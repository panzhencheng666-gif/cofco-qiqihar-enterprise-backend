package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public record EmployeeProfile(String subjectId,String displayName,String workUnitCode,String workUnitName,
        String accountStatus,String employmentStatus,List<Grant> roles,List<Position> positions,
        List<String> regionCodes,long version) {
    public record Grant(String code,String name) {}
    public record Position(String code,String name,boolean primaryPosition) {}
}
