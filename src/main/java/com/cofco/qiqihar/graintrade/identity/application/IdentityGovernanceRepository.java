package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;
import java.util.Optional;

public interface IdentityGovernanceRepository {
    boolean validAssignment(EmployeeAssignment assignment);
    boolean exists(String subjectId);
    EmployeeProfile create(String subjectId,EmployeeAssignment assignment,String actor);
    Optional<EmployeeProfile> find(String subjectId);
    List<EmployeeProfile> findAll();
    Optional<EmployeeProfile> update(String subjectId,long expectedVersion,EmployeeAssignment assignment,String actor);
    AssignmentOptions assignmentOptions(String workUnitCode);
}
