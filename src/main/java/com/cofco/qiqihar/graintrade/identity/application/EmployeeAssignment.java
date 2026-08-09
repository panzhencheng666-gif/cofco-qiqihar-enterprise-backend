package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public record EmployeeAssignment(String displayName,String workUnitCode,String accountStatus,
        String employmentStatus,List<String> roleCodes,List<String> positionCodes,List<String> regionCodes) {
    public EmployeeAssignment {
        roleCodes=List.copyOf(roleCodes);
        positionCodes=List.copyOf(positionCodes);
        regionCodes=List.copyOf(regionCodes);
    }
}
