package com.cofco.qiqihar.graintrade.supply.application;
import java.time.Instant;
import java.util.List;
public interface SupplyAccountRepository{
 List<SupplyAccountView> find(String productCode,String regionCode,String marketingYear,String resultState,Integer version);
 SupplyAccountView run(SupplyRunCommand command,String actor,Instant now);
 SupplyReleaseView release(UpstreamSourceReleaseCommand command,String actor,Instant now);
 SupplyReleaseView approveManual(ManualInputDecisionCommand command,String actor,Instant now);
}
