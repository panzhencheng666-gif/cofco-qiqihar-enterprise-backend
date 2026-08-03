package com.cofco.qiqihar.graintrade.supply.application;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class SupplyAccountService{
 private static final Set<String> PRODUCTS=Set.of("CORN","SOYBEAN","RICE");
 private final SupplyAccountRepository repository;private final CurrentActor actor;private final Clock clock;
 public SupplyAccountService(SupplyAccountRepository repository,CurrentActor actor,Clock clock){this.repository=repository;this.actor=actor;this.clock=clock;}
 @Transactional(readOnly=true) public List<SupplyAccountView> list(String product,String region,String year,String state,Integer version){
  if(!PRODUCTS.contains(product)||blank(region)||blank(year)||(state!=null&&!Set.of("TRIAL","FORMAL_CANDIDATE","FORMAL").contains(state))||(version!=null&&version<1))throw invalid();
  return repository.find(product,region,year,state,version);
 }
 @Transactional public SupplyAccountView run(SupplyRunCommand command){
  String current=actor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();
  if(command==null||!PRODUCTS.contains(command.productCode())||blank(command.regionCode())||blank(command.marketingYear())
    ||command.approvedAdjustment()==null||blank(command.adoptionReason())||blank(command.adjustmentReason())||command.expectedDecisionVersion()<0)throw invalid();
  return repository.run(command,current,clock.instant());
 }
 @Transactional public SupplyReleaseView release(UpstreamSourceReleaseCommand command){
  String current=actor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();
  if(command==null||!Set.of("PRODUCTION","MARKET","LOGISTICS").contains(command.sourceDomain())
    ||blank(command.sourceRecordId())||command.sourceVersion()<0||!PRODUCTS.contains(command.productCode())
    ||blank(command.regionCode())||blank(command.marketingYear())||blank(command.roleCode())
    ||blank(command.sourceFieldCode())||blank(command.unitCode())
    ||!Set.of("PASSED","WARNING","BLOCKING").contains(command.qualityState()))throw invalid();
  return repository.release(command,current,clock.instant());
 }
 @Transactional public SupplyReleaseView approveManual(ManualInputDecisionCommand command){
  String current=actor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();
  if(command==null||!PRODUCTS.contains(command.productCode())||blank(command.regionCode())
    ||blank(command.marketingYear())||blank(command.roleCode())||command.value()==null
    ||blank(command.unitCode())||blank(command.reason())||command.expectedVersion()<0)throw invalid();
  return repository.approveManual(command,current,clock.instant());
 }
 private static boolean blank(String v){return v==null||v.isBlank();}
 private static ClientRequestException invalid(){return new ClientRequestException("INVALID_SUPPLY_ACCOUNT_REQUEST","Supply account request is invalid");}
}
