package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import java.util.List;
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

    @Test
    void derivesEveryBusinessDetailAndFactColumnFromTheAuthoritativeDefinition() {
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", List.of(
                        new ProductionImportDefinition.Group("DETAIL", "调查明细", List.of(
                                new ProductionImportDefinition.Field(
                                        "PROD_FUTURE_DETAIL", "新增调查项", null, 18, 4))),
                        new ProductionImportDefinition.Group("QUALITY", "质量指标", List.of(
                                new ProductionImportDefinition.Field(
                                        "PROD_FUTURE_QUALITY", "新增质量项", "%", 18, 4)))));

        assertThat(ProductionImportTemplate.workbook(definition).headers())
                .containsExactly(
                        "regionCode", "PROD_CULTIVAR_NAME", "surveyDate",
                        "cultivatedAreaMu", "yieldPerMuKilograms", "PROD_REPORTER_PHONE",
                        "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
                        "PROD_FUTURE_DETAIL", "PROD_FUTURE_QUALITY", "evidencePhotoId");
    }

    @Test
    void derivesMarketColumnsAndExcludesOnlyMenuAccountAndSystemOwnedFields() {
        MarketImportDefinition.Field objectType = marketField(
                "MKT_OBJECT_TYPE", "对象类型", "SELECT");
        MarketImportDefinition.Field reporter = marketField(
                "MKT_REPORTER_NAME", "填报人", "TEXT");
        MarketImportDefinition.Field sample = marketField(
                "MKT_SAMPLE_NAME", "填报对象", "TEXT");
        MarketImportDefinition.Field calculated = marketField(
                "MKT_ACTUAL_TRADE_PRICE", "实际成交价", "READONLY_DECIMAL");
        MarketImportDefinition.Field fact = marketField(
                "ENDING_INVENTORY", "期末库存", "DECIMAL");
        MarketImportDefinition definition = new MarketImportDefinition(
                "CORN", "TRADER", List.of(objectType, reporter, sample, calculated), List.of(fact));

        assertThat(MarketImportTemplate.workbook(definition).headers())
                .containsExactly("MKT_SAMPLE_NAME", "ENDING_INVENTORY", "evidencePhotoId");
    }

    @Test
    void derivesLogisticsColumnsAndExcludesOnlyAccountAndSystemOwnedFields() {
        LogisticsImportDefinition definition = new LogisticsImportDefinition("CORN", List.of(
                new LogisticsImportDefinition.Field("LOG_PERIOD", "物流监测期", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REPORTER", "物流填报人", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REPORTED_AT", "填报时间", null, false, true),
                new LogisticsImportDefinition.Field("LOG_ROUTE_VOLUME", "运输数量", "吨", true, false)));

        assertThat(LogisticsImportTemplate.workbook(definition).headers())
                .containsExactly("LOG_PERIOD", "LOG_ROUTE_VOLUME");
    }

    private static MarketImportDefinition.Field marketField(
            String code, String label, String controlType) {
        return new MarketImportDefinition.Field(
                code, label, controlType, null, false, 18, 4);
    }

}
