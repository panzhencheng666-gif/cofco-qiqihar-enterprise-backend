package com.cofco.qiqihar.graintrade.identity.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class EmployeeRegistrationPolicyTest {
 @Test void registrationRequiresExactlyOneTownship(){
  for(List<String> regions:List.of(List.<String>of(),List.of("town1","town2"),List.of("")))
   assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateSingleRegion(regions));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateSingleRegion(List.of("town1")));
  for(String level:List.of("PREFECTURE","COUNTY","VILLAGE",""))
   assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateTownshipLevel(level));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateTownshipLevel("TOWNSHIP"));
 }

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
