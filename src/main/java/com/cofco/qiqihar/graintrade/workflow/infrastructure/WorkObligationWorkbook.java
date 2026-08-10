package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.shared.spreadsheet.BusinessWorkbook;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationWeeklyReport;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WorkObligationWorkbook {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private WorkObligationWorkbook() {}

    public static byte[] create(WorkObligationWeeklyReport report) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(
                "统计周", "单位", "填报人员", "业务领域", "地区", "品种",
                "业务期间", "截止时间", "完成时间", "单据状态", "履职状态", "单据编号"));
        for (WorkObligationWeeklyReport.Row row : report.rows()) {
            rows.add(List.of(
                    report.weekStart() + " 至 " + report.weekEnd(),
                    row.workUnitName(), row.employeeName(), row.businessDomainLabel(),
                    row.regionName(), row.productName(), row.businessPeriod(),
                    DATE_TIME.format(row.dueAt()),
                    row.completedAt() == null ? "—" : DATE_TIME.format(row.completedAt()),
                    row.statusLabel(), row.complianceLabel(), row.sourceId()));
        }
        return BusinessWorkbook.create("填报履职周报", rows);
    }
}
