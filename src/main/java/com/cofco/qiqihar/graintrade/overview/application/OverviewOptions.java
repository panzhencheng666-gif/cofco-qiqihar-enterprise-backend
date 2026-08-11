package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public record OverviewOptions(
        List<OverviewOption> products,
        List<OverviewPeriodOption> periods,
        List<Integer> years) {}
