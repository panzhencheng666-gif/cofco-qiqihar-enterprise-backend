package com.cofco.qiqihar.graintrade.identity.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class EmployeeRegistrationPolicyTest {
 @Test void preventsReservedOrCallerChosenAdministratorIdentity(){
  assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateIdentity("admin"));
  assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateIdentity("ADMIN"));
  assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateIdentity("x y"));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateIdentity("new.employee"));
 }
 @Test void rejectsEveryNonOrdinaryRole(){
  for(String role:List.of("SYSTEM_ADMIN","ACCOUNT_OWNER","BUSINESS_REVIEWER","IDENTITY_ADMIN"))
   assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateRoles(List.of(role)));
  assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateRoles(List.of("BUSINESS_OPERATOR","SYSTEM_ADMIN")));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateRoles(List.of("BUSINESS_OPERATOR")));
 }
}
