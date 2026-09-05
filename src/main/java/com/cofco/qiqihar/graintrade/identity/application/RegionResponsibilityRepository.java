package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public interface RegionResponsibilityRepository {
    void lockChange();
    List<String> ownedRegions(String subjectId);
    List<RegionResponsibility.Region> regions(List<String> codes);
    List<RegionResponsibility.Sample> samples(List<String> affected,List<String> selected,String subjectId,String name);
    void save(String subjectId,List<String> selected,List<String> affected,String actor,String reason);
}
