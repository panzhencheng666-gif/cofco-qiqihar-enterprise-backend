package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public record OverviewSamplePointSnapshot(
        OverviewSamplePointList list,
        List<OverviewSamplePointIcon> icons) {
    public OverviewSamplePointSnapshot {
        icons = List.copyOf(icons);
    }
}
