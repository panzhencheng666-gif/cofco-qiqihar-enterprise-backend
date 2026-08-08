package com.cofco.qiqihar.graintrade.supply.application;
import java.time.Instant;
import java.util.List;
import java.util.Set;
public interface SupplyAccountRepository{
 default List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,Integer version){
  return find(productCode,regionCode,marketingYear,resultState,version,Set.of("*"));
 }
 List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,Integer version,
         Set<String> authorizedRegionCodes);
 default SupplyInputWorkspaceView loadInputWorkspace(String productCode,String regionCode,String marketingYear){
  return loadInputWorkspace(productCode,regionCode,marketingYear,Set.of("*"));
 }
 SupplyInputWorkspaceView loadInputWorkspace(String productCode,String regionCode,String marketingYear,
         Set<String> authorizedRegionCodes);
 void lockCalculationContext(String productCode,String regionCode,String marketingYear);
 SupplyCalculationMaterial loadCalculationMaterial(String inputSetId,String productCode,String regionCode,String marketingYear);
 void persistFormalDecision(SupplyRunCommand command,SupplyCalculationMaterial material,String actor,Instant now);
 SupplyAccountView persistRun(SupplyRunPersistence run);
 SupplySourceReleaseMaterial loadSourceReleaseMaterial(UpstreamSourceReleaseCommand command);
 SupplyReleaseView persistSourceRelease(SupplySourceReleasePersistence release);
 SupplyManualDecisionMaterial loadManualDecisionMaterial(ManualInputDecisionCommand command);
 SupplyReleaseView persistManualDecision(SupplyManualDecisionPersistence decision);
 SupplyInputSetMaterial loadInputSetMaterial(SupplyInputSetCommand command);
 SupplyInputSetView persistInputSet(SupplyInputSetPersistence inputSet);
}
