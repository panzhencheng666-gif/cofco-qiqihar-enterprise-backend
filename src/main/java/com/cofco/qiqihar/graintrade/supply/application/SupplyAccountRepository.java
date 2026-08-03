package com.cofco.qiqihar.graintrade.supply.application;
import java.time.Instant;
import java.util.List;
public interface SupplyAccountRepository{
 List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,Integer version);
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
