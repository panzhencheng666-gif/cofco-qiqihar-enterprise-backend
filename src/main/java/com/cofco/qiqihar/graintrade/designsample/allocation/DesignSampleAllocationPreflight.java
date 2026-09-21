package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.util.List;

public record DesignSampleAllocationPreflight(
        int townshipCount,long villageCount,long activeDesignSampleCount,
        List<String> globalBlockers,List<DesignSampleTownshipPlan> townships) {
    public boolean ready(){return globalBlockers.isEmpty()&&townships.stream().allMatch(DesignSampleTownshipPlan::ready);}
}
