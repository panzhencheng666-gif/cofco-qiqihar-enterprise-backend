package com.cofco.qiqihar.graintrade.masterdata.domain;

import java.time.LocalDate;

public record BusinessPeriod(String code, String name, LocalDate startsOn, LocalDate endsOn) {
}
