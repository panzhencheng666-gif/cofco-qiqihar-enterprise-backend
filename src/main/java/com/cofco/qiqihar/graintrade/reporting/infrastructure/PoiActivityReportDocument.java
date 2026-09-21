package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.ActivityReport;
import com.cofco.qiqihar.graintrade.reporting.application.ActivityReportDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

@Component
public class PoiActivityReportDocument implements ActivityReportDocument {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    @Override
    public byte[] create(ActivityReport report) {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            title(document, "全系统周期使用总结");
            paragraph(document, "统计周期：" + TIME.format(report.periodStart()) + " 至 "
                    + TIME.format(report.periodEnd()), true);
            paragraph(document, "事件截点：" + TIME.format(report.eventCutoff())
                    + "（截点之后写入的事件不计入本报告）", false);
            heading(document, "一、总体概览");
            summaryTable(document, report);
            heading(document, "二、操作类型汇总");
            countTable(document, "操作类型", report.actions());
            heading(document, "三、业务领域汇总");
            countTable(document, "业务领域", report.domains());
            heading(document, "四、维护单位汇总");
            countTable(document, "维护单位", report.workUnits());
            heading(document, "五、统计口径与追溯说明");
            paragraph(document, report.scopeNotice(), false);
            paragraph(document, "新增、修改、删除、导入、提交、退回、审核、导出和地图标注均由动作代码显式归类；每条不可变审计事件只归入一个操作类型。", false);
            paragraph(document, "样本点新增数与删除数仅统计聚合对象属于正式或设计样本点且动作归类相符的事件。未产生事件的指标以 0 展示。", false);
            paragraph(document, "本报告由系统根据 platform.business_audit_event 自动生成，报告本身不会补造用户活动。", false);
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create activity report document", exception);
        }
    }

    private static void title(XWPFDocument document, String value) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = run(paragraph, value);
        run.setBold(true);
        run.setFontSize(20);
    }

    private static void heading(XWPFDocument document, String value) {
        XWPFRun run = run(document.createParagraph(), value);
        run.setBold(true);
        run.setFontSize(14);
        run.setColor("173F5F");
    }

    private static void paragraph(XWPFDocument document, String value, boolean bold) {
        XWPFRun run = run(document.createParagraph(), value);
        run.setBold(bold);
        run.setFontSize(10);
    }

    private static void summaryTable(XWPFDocument document, ActivityReport report) {
        XWPFTable table = document.createTable(3, 4);
        cell(table, 0, 0, "有效用户数", true);
        cell(table, 0, 1, Long.toString(report.effectiveUserCount()), false);
        cell(table, 0, 2, "操作留痕总数", true);
        cell(table, 0, 3, Long.toString(report.totalEvents()), false);
        cell(table, 1, 0, "新增样本点", true);
        cell(table, 1, 1, Long.toString(report.samplePointsCreated()), false);
        cell(table, 1, 2, "删除或退出使用样本点", true);
        cell(table, 1, 3, Long.toString(report.samplePointsDeleted()), false);
        cell(table, 2, 0, "统计天数", true);
        cell(table, 2, 1, Integer.toString(report.periodDays()), false);
        cell(table, 2, 2, "事件截点", true);
        cell(table, 2, 3, TIME.format(report.eventCutoff()), false);
    }

    private static void countTable(XWPFDocument document, String firstHeader,
            java.util.List<ActivityReport.Count> counts) {
        XWPFTable table = document.createTable(Math.max(1, counts.size()) + 1, 2);
        cell(table, 0, 0, firstHeader, true);
        cell(table, 0, 1, "数量", true);
        if (counts.isEmpty()) {
            cell(table, 1, 0, "无", false);
            cell(table, 1, 1, "0", false);
            return;
        }
        for (int index = 0; index < counts.size(); index++) {
            ActivityReport.Count count = counts.get(index);
            cell(table, index + 1, 0, count.label(), false);
            cell(table, index + 1, 1, Long.toString(count.count()), false);
        }
    }

    private static void cell(XWPFTable table, int row, int column, String value, boolean bold) {
        var cell = table.getRow(row).getCell(column);
        cell.removeParagraph(0);
        XWPFRun run = run(cell.addParagraph(), value);
        run.setBold(bold);
        run.setFontSize(9);
    }

    private static XWPFRun run(XWPFParagraph paragraph, String value) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Microsoft YaHei");
        run.setText(value);
        return run;
    }
}
