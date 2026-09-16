package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.util.*;

public record DesignSampleTownshipPlan(
        String townshipCode,String townshipName,int villageCount,int adjacencyEdgeCount,
        SortedSet<String> selectedVillageCodes,int preservedExisting,
        int projectedMoved,int projectedCreated,int projectedExpired,
        SortedMap<String,String> coverageProof,List<String> blockers) {
    public boolean ready(){return blockers.isEmpty();}
}
