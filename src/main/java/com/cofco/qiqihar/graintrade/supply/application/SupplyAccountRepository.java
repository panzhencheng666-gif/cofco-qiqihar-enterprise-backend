package com.cofco.qiqihar.graintrade.supply.application;
import java.time.Instant;
import java.util.List;
import java.util.Set;
public interface SupplyAccountRepository{
 List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,Integer version);
 default List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,
         Integer version,Set<String> authorizedRegionCodes){
  if(!authorizedRegionCodes.contains("*")&&!authorizedRegionCodes.contains(regionCode))return List.of();
  return find(productCode,regionCode,marketingYear,resultState,version);
 }
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
