package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import java.util.List;

public final class OperationalFacilityWorkbook {
    public static final String DOMAIN = "OPERATIONAL_FACILITY";
    public static final int MAX_ROWS = 500;
    public static final List<String> HEADERS = List.of(
            "name", "relationType", "regionCode", "address", "longitude", "latitude",
            "operationalStatus", "capacityTonnes", "capacityAsOf", "validFrom", "validTo");
    public static final List<String> LABELS = List.of(
            "库点名称", "库点类型", "所在地区代码", "详细地址", "经度", "纬度",
            "运营状态", "仓容（吨）", "仓容日期", "合作开始日期", "合作结束日期");

    private OperationalFacilityWorkbook() {}

    public static BusinessImportWorkbook.Template template() {
        return new BusinessImportWorkbook.Template(
                DOMAIN, "库点", null, null, BusinessImportWorkbook.CONTRACT_VERSION,
                HEADERS, LABELS, List.of(
                        rule("name", "TEXT", "TEXT", true, List.of(), 0, 0, "2 至 200 个字符"),
                        rule("relationType", "TEXT", "SELECT", true,
                                List.of("自有库点", "租赁库点", "历史租赁库点"), 0, 0, "从下拉选项选择"),
                        rule("regionCode", "TEXT", "TEXT", true, List.of(), 0, 0,
                                "填写四个运营区域内的市、县、乡镇或村级行政区代码"),
                        rule("address", "TEXT", "TEXT", true, List.of(), 0, 0, "填写可核验的详细地址"),
                        rule("longitude", "DECIMAL", "DECIMAL", false, List.of(), 12, 6,
                                "与纬度同时填写；东经为正数"),
                        rule("latitude", "DECIMAL", "DECIMAL", false, List.of(), 12, 6,
                                "与经度同时填写；北纬为正数"),
                        rule("operationalStatus", "TEXT", "SELECT", true,
                                List.of("运营中", "已停用", "尚未核定"), 0, 0, "从下拉选项选择"),
                        rule("capacityTonnes", "DECIMAL", "DECIMAL", false, List.of(), 18, 3,
                                "非负数，最多三位小数"),
                        rule("capacityAsOf", "DATE", "DATE", false, List.of(), 0, 0, "格式：YYYY-MM-DD"),
                        rule("validFrom", "DATE", "DATE", false, List.of(), 0, 0, "格式：YYYY-MM-DD"),
                        rule("validTo", "DATE", "DATE", false, List.of(), 0, 0, "格式：YYYY-MM-DD")));
    }

    private static BusinessImportWorkbook.ColumnRule rule(
            String code, String valueType, String controlType, boolean required,
            List<String> options, int precision, int scale, String description) {
        return new BusinessImportWorkbook.ColumnRule(
                code, valueType, controlType, required, options, precision, scale, description);
    }
}
