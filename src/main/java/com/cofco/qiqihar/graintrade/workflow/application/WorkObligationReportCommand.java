package com.cofco.qiqihar.graintrade.workflow.application;

import java.time.LocalDate;

public record WorkObligationReportCommand(
        LocalDate weekStart,
        String subjectId,
        String workUnitCode,
        String businessDomain,
        String regionCode) {}
