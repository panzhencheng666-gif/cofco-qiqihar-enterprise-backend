package com.cofco.qiqihar.graintrade.supply.application;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
public interface SupplyAccountRepository{
 SupplyTemporalContext findTemporalContext(String periodCode);
 default List<SupplyAccountView> find(String productCode,String regionCode,String periodCode,String resultState,Integer version){
 return find(productCode,regionCode,periodCode,resultState,version,Set.of("*"));
 }
 List<SupplyAccountView> find(String productCode,String regionCode,String periodCode,String resultState,Integer version,
         Set<String> authorizedRegionCodes);
 default PagedResult<SupplyAccountView> findPage(String productCode,String regionCode,String periodCode,
         String resultState,Integer version,int pageNumber,int pageSize,Set<String> authorizedRegionCodes){
  List<SupplyAccountView> rows=find(productCode,regionCode,periodCode,resultState,version,authorizedRegionCodes);
  long offset=(long)pageNumber*pageSize;
  if(offset>=rows.size())return new PagedResult<>(List.of(),pageNumber,pageSize,rows.size());
  int from=(int)offset;
  int to=(int)Math.min((long)rows.size(),offset+pageSize);
  return new PagedResult<>(rows.subList(from,to),pageNumber,pageSize,rows.size());
 }
 default SupplyInputWorkspaceView loadInputWorkspace(String productCode,String regionCode,String periodCode){
  return loadInputWorkspace(productCode,regionCode,periodCode,Set.of("*"));
 }
 SupplyInputWorkspaceView loadInputWorkspace(String productCode,String regionCode,String periodCode,
         Set<String> authorizedRegionCodes);
 void lockCalculationContext(String productCode,String regionCode,String periodCode);
 SupplyCalculationMaterial loadCalculationMaterial(String inputSetId,String productCode,String regionCode,String periodCode);
 void persistFormalDecision(SupplyRunCommand command,SupplyCalculationMaterial material,String actor,Instant now);
 SupplyAccountView persistRun(SupplyRunPersistence run);
 SupplySourceReleaseMaterial loadSourceReleaseMaterial(UpstreamSourceReleaseCommand command);
 SupplyReleaseView persistSourceRelease(SupplySourceReleasePersistence release);
 SupplyManualDecisionMaterial loadManualDecisionMaterial(ManualInputDecisionCommand command);
 SupplyReleaseView persistManualDecision(SupplyManualDecisionPersistence decision);
 SupplyInputSetMaterial loadInputSetMaterial(SupplyInputSetCommand command);
 SupplyInputSetView persistInputSet(SupplyInputSetPersistence inputSet);
}
