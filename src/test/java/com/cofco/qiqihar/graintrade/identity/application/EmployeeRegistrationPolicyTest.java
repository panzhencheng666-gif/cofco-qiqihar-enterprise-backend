package com.cofco.qiqihar.graintrade.identity.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class EmployeeRegistrationPolicyTest {
 @Test void registrationAcceptsUpToTenDistinctTownships(){
  for(List<String> regions:List.of(List.of("town1","town1"),List.of("1","2","3","4","5","6","7","8","9","10","11"),List.of("")))
   assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateRegions(regions));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateRegions(List.of("town1")));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateRegions(List.of("1","2","3","4","5")));
  for(String level:List.of("PREFECTURE","COUNTY","VILLAGE",""))
   assertThrows(RuntimeException.class,()->EmployeeRegistrationService.validateTownshipLevel(level));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateTownshipLevel("TOWNSHIP"));
 }

 @Test void chineseUsernameAndNoInitialRegionsAreSupported(){
  assertDoesNotThrow(()->EmployeeRegistrationService.validateIdentity("赵长彬"));
  assertDoesNotThrow(()->EmployeeRegistrationService.validateRegions(List.of()));
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
