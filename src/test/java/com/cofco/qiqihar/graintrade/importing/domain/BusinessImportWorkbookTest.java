package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import org.junit.jupiter.api.Test;

class BusinessImportWorkbookTest {

    @Test
    void createsOneVersionedProtocolSpecializedForProductionProductAndObjectType() {
        byte[] workbook = BusinessImportWorkbook.create(
                ProductionImportTemplate.workbook("CORN", "FARMER"));

        assertThat(XlsxTable.parseWorksheet(workbook, 1, ProductionImportTemplate.XLSX_HEADERS.size()))
                .containsExactly(ProductionImportTemplate.XLSX_LABELS, ProductionImportTemplate.XLSX_HEADERS);
        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2))
                .contains(
                        java.util.List.of("模板版本", "1"),
                        java.util.List.of("业务类型", "PRODUCTION"),
                        java.util.List.of("产品品种", "CORN"),
                        java.util.List.of("对象类型", "FARMER"),
                        java.util.List.of("填报人", "由登录账号自动记录，不得在模板中填写"),
                        java.util.List.of("单批数量", "每次最多导入 5000 条；更多记录请分批导入"));
        assertThat(ProductionImportTemplate.XLSX_HEADERS)
                .doesNotContain("productCode", "objectTypeCode", "PROD_REPORTER_NAME");

        var imported = BusinessImportWorkbook.read(workbook, "PRODUCTION",
                ProductionImportTemplate.XLSX_HEADERS, ProductionImportTemplate.XLSX_LABELS);
        assertThat(imported.productCode()).isEqualTo("CORN");
        assertThat(imported.objectTypeCode()).isEqualTo("FARMER");
        assertThat(imported.rows()).isEmpty();
    }

}
