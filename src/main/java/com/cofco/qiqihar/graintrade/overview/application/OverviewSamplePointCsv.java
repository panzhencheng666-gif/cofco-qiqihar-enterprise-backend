package com.cofco.qiqihar.graintrade.overview.application;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class OverviewSamplePointCsv {
    private OverviewSamplePointCsv() {}

    public static byte[] create(int year, List<OverviewSamplePointExportRow> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("统计年度,").append(year).append("\r\n");
        csv.append("统计类别,样本点数量\r\n");
        append(csv, "唯一正式样本", Long.toString(rows.size()));
        append(csv, "产情样本", Long.toString(count(rows, "产情类")));
        append(csv, "市场样本", Long.toString(count(rows, "市场类")));
        append(csv, "物流样本", Long.toString(count(rows, "物流类")));
        csv.append("\r\n");
        append(csv, "系统稳定标识（只用于重复匹配）", "样本点名称", "所属地区", "地区编码",
                "业务类别", "对象类型", "关联品种", "联系方式", "经度", "纬度");
        rows.forEach(row -> append(csv,
                row.samplePointId().toString(), row.name(), row.regionName(), row.regionCode(),
                String.join("、", row.categories()), String.join("、", row.types()),
                String.join("、", row.products()), String.join("、", row.contacts()),
                Double.toString(row.longitude()), Double.toString(row.latitude())));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long count(List<OverviewSamplePointExportRow> rows, String category) {
        return rows.stream().filter(row -> row.categories().contains(category)).count();
    }

    private static void append(StringBuilder csv, String... values) {
        for (int index = 0; index < values.length; index += 1) {
            if (index > 0) csv.append(',');
            csv.append(escape(values[index]));
        }
        csv.append("\r\n");
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
