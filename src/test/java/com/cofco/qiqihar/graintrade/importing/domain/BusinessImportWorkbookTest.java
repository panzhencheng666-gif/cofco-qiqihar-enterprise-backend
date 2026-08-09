package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
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
                .containsExactly(
                        java.util.List.of("模板版本", "1"),
                        java.util.List.of("业务类型", "PRODUCTION"),
                        java.util.List.of("产品品种", "CORN"),
                        java.util.List.of("对象类型", "FARMER"),
                        java.util.List.of("填报人", "由登录账号自动记录，不得在模板中填写"));
        assertThat(ProductionImportTemplate.XLSX_HEADERS)
                .doesNotContain("productCode", "objectTypeCode", "PROD_REPORTER_NAME");

        var imported = BusinessImportWorkbook.read(workbook, "PRODUCTION",
                ProductionImportTemplate.XLSX_HEADERS, ProductionImportTemplate.XLSX_LABELS);
        assertThat(imported.productCode()).isEqualTo("CORN");
        assertThat(imported.objectTypeCode()).isEqualTo("FARMER");
        assertThat(imported.rows()).isEmpty();
    }

    @Test
    void appliesTheSameProtocolToMarketWithoutMixingItsColumnsWithProduction() {
        byte[] workbook = BusinessImportWorkbook.create(
                MarketImportTemplate.workbook("SOYBEAN", "TRADER"));

        assertThat(XlsxTable.parseWorksheet(workbook, 1, MarketImportTemplate.XLSX_HEADERS.size()))
                .containsExactly(MarketImportTemplate.XLSX_LABELS, MarketImportTemplate.XLSX_HEADERS);
        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2))
                .contains(java.util.List.of("业务类型", "MARKET"), java.util.List.of("产品品种", "SOYBEAN"));
        assertThat(MarketImportTemplate.XLSX_HEADERS)
                .doesNotContain("productCode", "objectTypeCode", "reporterName")
                .doesNotContainAnyElementsOf(ProductionImportTemplate.XLSX_HEADERS.stream()
                        .filter(header -> header.startsWith("cultivated") || header.startsWith("yield"))
                        .toList());
    }
}
