package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyFieldContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BusinessImportWorkbookTest {

    @Test
    void removesOnlySpreadsheetFloatingPointResidueWithoutRoundingRealBusinessPrecision() {
        BusinessImportWorkbook.ColumnRule decimalRule = new BusinessImportWorkbook.ColumnRule(
                "预计单产（公斤/亩）", "DECIMAL", "DECIMAL", true, List.of(), 18, 4, null);

        assertThat(BusinessImportWorkbook.normalizeCell("69.40000000000001", decimalRule))
                .isEqualTo("69.4");
        assertThat(BusinessImportWorkbook.normalizeCell("65.09999999999999", decimalRule))
                .isEqualTo("65.1");
        assertThat(BusinessImportWorkbook.normalizeCell("69.40001", decimalRule))
                .isEqualTo("69.40001");
        assertThatThrownBy(() -> BusinessImportWorkbook.validateCell("69.40001", decimalRule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_VALUE_FORMAT");
    }

    @Test
    void acceptsOnlyAnExplicitlyAllowlistedPriorContractAndAppliesTheCurrentBroaderRule() {
        BusinessImportWorkbook.ColumnRule legacyRule = new BusinessImportWorkbook.ColumnRule(
                "地租（元/亩）", "DECIMAL", "DECIMAL", false, List.of(), 18, 0, null);
        BusinessImportWorkbook.ColumnRule currentRule = new BusinessImportWorkbook.ColumnRule(
                "地租（元/亩）", "DECIMAL", "DECIMAL", false, List.of(), 18, 2, null);
        BusinessImportWorkbook.Template legacy = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, "2026.08.17-2", null,
                List.of("地租（元/亩）"), List.of("地租（元/亩）"), List.of(legacyRule));
        BusinessImportWorkbook.Template current = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, "2026.08.17-2", null,
                List.of("地租（元/亩）"), List.of("地租（元/亩）"), List.of(currentRule));
        byte[] priorWorkbook = BusinessImportWorkbook.create(legacy, List.of(List.of("733.33")));

        assertThat(BusinessImportWorkbook.readDraft(
                priorWorkbook, current, List.of(legacy), 5_000).rows())
                .containsExactly(List.of("733.33"));

        BusinessImportWorkbook.Template unrelated = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, "unrelated", null,
                current.headers(), current.labels(), current.rules());
        assertThatThrownBy(() -> BusinessImportWorkbook.readDraft(
                BusinessImportWorkbook.create(unrelated, List.of(List.of("733.33"))),
                current, List.of(legacy), 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_CONTRACT_MISMATCH");
    }

    @Test
    void keepsTheOrdinaryWorkbookProtocolFreeOfCorrectionPurposeMetadata() {
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null,
                List.of("业务字段"), List.of("业务字段"));

        byte[] workbook = BusinessImportWorkbook.create(template, List.of(List.of("原有数据")));
        String workbookXml = zipEntry(workbook, "xl/workbook.xml");

        assertThat(workbookXml)
                .contains("<sheet name=\"市场填报\" sheetId=\"1\" r:id=\"rId1\"/>")
                .contains("<sheet name=\"填报说明\" sheetId=\"2\" r:id=\"rId2\"/>")
                .contains(template.contractDigest())
                .doesNotContain("工作簿用途", "MARKET_RETURNED_CORRECTION", "退回记录修正");
        assertThat(count(workbookXml, "<definedName name="))
                .isEqualTo(5);
        assertThat(XlsxTable.parseWorksheet(workbook, 1, 1))
                .containsExactly(List.of("业务字段"), List.of("原有数据"));
    }

    @Test
    void identifiesTheFirstColumnOutsideTheGovernedTemplate() {
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "SOYBEAN", null,
                List.of("样本点类型"), List.of("样本点类型"));
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(List.of("农户")));
        byte[] extraColumn = replaceZipEntry(workbook, "xl/worksheets/sheet1.xml",
                content -> content.replace("</row></sheetData>",
                        "<c r=\"B2\" t=\"inlineStr\"><is><t>多余照片列</t></is></c></row></sheetData>"));

        assertThatThrownBy(() -> XlsxTable.parseWorksheet(extraColumn, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("XLSX_EXTRA_COLUMN:2");
    }

    @Test
    void alignsRequiredTextValidationWithTheFirstDataRow() {
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "RICE", "FARMER", null,
                List.of("surveyYear"), List.of("数据年份"),
                List.of(new BusinessImportWorkbook.ColumnRule(
                        "surveyYear", "TEXT", "TEXT", true, List.of(), 4, 0,
                        "数据所属年份，1900—2200")));

        assertThat(zipEntry(BusinessImportWorkbook.create(template), "xl/worksheets/sheet1.xml"))
                .contains("type=\"custom\" sqref=\"A2:A5001\"><formula1>LEN(TRIM(A2))&gt;0</formula1>")
                .doesNotContain("LEN(TRIM(A3))");
    }

    @Test
    void createsOneBusinessOnlyProtocolSpecializedForProductionProductAndObjectType() {
        byte[] workbook = BusinessImportWorkbook.create(
                ProductionImportTemplate.workbook("CORN", "FARMER"));

        assertThat(XlsxTable.parseWorksheet(workbook, 1, ProductionImportTemplate.XLSX_HEADERS.size()))
                .containsExactly(ProductionImportTemplate.XLSX_LABELS);
        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2))
                .contains(
                        java.util.List.of("填报类别", "产情"),
                        java.util.List.of("产品品种", "玉米"),
                        java.util.List.of("对象类型", "农户"),
                        java.util.List.of("填报人", "由登录账号自动记录，不得在模板中填写"),
                        java.util.List.of("处理方式", "5000 条以内即时处理；5001 至 50000 条转入后台任务处理"));
        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2).toString())
                .doesNotContain("模板版本", "契约摘要", "sha256:",
                        BusinessImportWorkbook.CONTRACT_VERSION,
                        "PRODUCTION", "CORN", "FARMER", "version", "digest");
        assertThat(ProductionImportTemplate.XLSX_HEADERS)
                .endsWith(BusinessImportWorkbook.PHOTO_FILENAMES_CODE)
                .doesNotContain("productCode", "objectTypeCode", "PROD_REPORTER_NAME", "evidencePhotoId");
        assertThat(ProductionImportTemplate.XLSX_LABELS)
                .endsWith(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
        assertThat(zipEntry(workbook, "xl/worksheets/sheet1.xml"))
                .doesNotContain("hidden=\"1\"", "PROD_", "MKT_", "LOG_", "surveyYear", "regionCode");

        var imported = BusinessImportWorkbook.read(workbook, "PRODUCTION",
                ProductionImportTemplate.XLSX_HEADERS, ProductionImportTemplate.XLSX_LABELS);
        assertThat(imported.productCode()).isEqualTo("CORN");
        assertThat(imported.objectTypeCode()).isEqualTo("FARMER");
        assertThat(imported.rows()).isEmpty();
    }

    @Test
    void keepsThreeSurveyWorkbookContextsBusinessReadableWithoutLosingStrictContextValidation() {
        List<BusinessImportWorkbook.Template> templates = List.of(
                new BusinessImportWorkbook.Template("PRODUCTION", "产情", "CORN", "FARMER",
                        BusinessImportWorkbook.CONTRACT_VERSION,
                        List.of("业务字段"), List.of("业务字段"), List.of()),
                new BusinessImportWorkbook.Template("MARKET", "市场", "SOYBEAN", "TRADER",
                        List.of("业务字段"), List.of("业务字段")),
                new BusinessImportWorkbook.Template("LOGISTICS", "物流", "RICE", "ROUTE_EVENT",
                        List.of("业务字段"), List.of("业务字段")));
        List<List<String>> expected = List.of(
                List.of("产情", "玉米", "农户"),
                List.of("市场", "大豆", "贸易商"),
                List.of("物流", "稻谷", "物流业务记录"));

        for (int index = 0; index < templates.size(); index++) {
            BusinessImportWorkbook.Template template = templates.get(index);
            byte[] workbook = BusinessImportWorkbook.create(template);
            List<List<String>> instructions = XlsxTable.parseWorksheet(workbook, 2, 2);
            assertThat(instructions).contains(
                    List.of("填报类别", expected.get(index).get(0)),
                    List.of("产品品种", expected.get(index).get(1)),
                    List.of("对象类型", expected.get(index).get(2)));
            assertThat(instructions.toString()).doesNotContain(
                    "PRODUCTION", "MARKET", "LOGISTICS", "CORN", "SOYBEAN", "RICE",
                    "FARMER", "TRADER", "ROUTE_EVENT", "production-survey-fields-v1",
                    "模板版本", "契约摘要", "sha256:", "version", "digest");
            BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(
                    workbook, template.domainCode());
            assertThat(context.productCode()).isEqualTo(template.productCode());
            assertThat(context.objectTypeCode()).isEqualTo(template.objectTypeCode());
            assertThat(context.contractVersion()).isEqualTo(template.contractVersion());
            assertThat(context.contractDigest()).isEqualTo(template.contractDigest());
            assertThat(context.contractVersion()).isNotBlank();
            assertThat(context.contractDigest()).startsWith("sha256:");
        }
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
                        "regionCode", "surveyDate",
                        "cultivatedAreaMu", "yieldPerMuKilograms", "PROD_SURVEYOR_NAME", "PROD_SURVEYOR_PHONE",
                        "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
                        "PROD_FUTURE_DETAIL", "PROD_FUTURE_QUALITY",
                        BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
    }

    @Test
    void carriesTheVersionedProductionFieldRulesIntoGenerationAndStrictReading() {
        var fields = ProductionSurveyFieldContract.fields(List.of());
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION, fields, List.of());
        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);

        assertThat(template.contractVersion()).isEqualTo(BusinessImportWorkbook.CONTRACT_VERSION);
        assertThat(ProductionImportTemplate.codes(definition)).containsExactly(
                "surveyYear", "surveyMonth", "PROD_SAMPLE_NAME", "regionCode",
                "PROD_SURVEYOR_NAME", "PROD_SURVEYOR_PHONE", "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE",
                "PROD_SAMPLE_LONGITUDE", "cultivatedAreaMu", "yieldPerMuKilograms",
                BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
        assertThat(template.headers()).containsExactlyElementsOf(template.labels())
                .contains("样本点名称", "数据年份", "数据月份", "播种面积（亩）",
                        BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)
                .allMatch(header -> !header.matches(".*[A-Za-z_].*"));
        assertThat(ProductionImportTemplate.codes(definition))
                .doesNotContain("PROD_REPORTER_NAME", "PROD_SAMPLE_SUBJECT_CODE",
                        "surveyDate", "estimatedOutputKilograms", "evidencePhotoId", "sample_point_id");

        ArrayList<String> row = new ArrayList<>(Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "地区", "230208");
        put(row, template.headers(), "数据年份", "2026");
        put(row, template.headers(), "数据月份", "8");
        put(row, template.headers(), "样本点名称", "权威契约测试主体");
        put(row, template.headers(), "调研人", "王雷");
        put(row, template.headers(), "调研人联系方式", "13800000000");
        put(row, template.headers(), "样本点联系方式", "13900000000");
        put(row, template.headers(), "纬度（度）", "47.3543");
        put(row, template.headers(), "经度（度）", "123.9182");
        put(row, template.headers(), "播种面积（亩）", "100.25");
        put(row, template.headers(), "预计单产（公斤/亩）", "500");
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(row));

        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2).toString())
                .doesNotContain("模板版本", "契约摘要", "sha256:",
                        "production-survey-fields", "version", "digest");
        assertThat(zipEntry(workbook, "xl/worksheets/sheet1.xml"))
                .contains("<dataValidations", "promptTitle=\"必填字段\"", "type=\"decimal\"");
        assertThat(zipEntry(workbook, "xl/styles.xml"))
                .contains("0.##################");
        assertThat(BusinessImportWorkbook.read(workbook, template).rows())
                .containsExactly(row);

        byte[] tamperedContract = replaceZipEntry(workbook, "xl/workbook.xml",
                content -> content.replace(template.contractDigest(), "sha256:tampered"));
        assertThatThrownBy(() -> BusinessImportWorkbook.read(tamperedContract, template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_CONTRACT_MISMATCH");

        ArrayList<String> sparse = new ArrayList<>(Collections.nCopies(template.headers().size(), ""));
        put(sparse, template.headers(), "地区", "230208");
        put(sparse, template.headers(), "数据年份", "2026");
        put(sparse, template.headers(), "样本点名称", "最小可导入样本");
        put(sparse, template.headers(), "样本点联系方式", "13900000000");
        put(sparse, template.headers(), "纬度（度）", "47.3543");
        put(sparse, template.headers(), "经度（度）", "123.9182");
        put(sparse, template.headers(), "播种面积（亩）", "100.25");
        put(sparse, template.headers(), "预计单产（公斤/亩）", "500");
        assertThat(BusinessImportWorkbook.read(
                BusinessImportWorkbook.create(template, List.of(sparse)), template).rows())
                .containsExactly(sparse);

        ArrayList<String> missingRequired = new ArrayList<>(sparse);
        put(missingRequired, template.headers(), "样本点名称", "");
        assertThatThrownBy(() -> BusinessImportWorkbook.read(
                BusinessImportWorkbook.create(template, List.of(missingRequired)), template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_REQUIRED_VALUE");

        ArrayList<String> badDecimal = new ArrayList<>(row);
        put(badDecimal, template.headers(), "播种面积（亩）", "not-a-number");
        assertThatThrownBy(() -> BusinessImportWorkbook.read(
                BusinessImportWorkbook.create(template, List.of(badDecimal)), template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_VALUE_FORMAT");

        ArrayList<String> obsoleteLabels = new ArrayList<>(template.labels());
        obsoleteLabels.set(0, "旧调查日期");
        BusinessImportWorkbook.Template obsoleteBusinessColumns = new BusinessImportWorkbook.Template(
                template.domainCode(), template.domainLabel(), template.productCode(), template.objectTypeCode(),
                template.contractVersion(), template.contractDigest(),
                template.headers(), obsoleteLabels, template.rules());
        assertThatThrownBy(() -> BusinessImportWorkbook.read(workbook, obsoleteBusinessColumns))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_XLSX_TEMPLATE");
    }

    @Test
    void derivesTheAuditedMarketColumnsAndKeepsGovernanceFieldsInternal() {
        MarketImportDefinition.Field objectType = marketField(
                "MKT_OBJECT_TYPE", "对象类型", "SELECT");
        MarketImportDefinition.Field reporter = marketField(
                "MKT_REPORTER_NAME", "填报人", "TEXT");
        MarketImportDefinition.Field packaging = new MarketImportDefinition.Field(
                "MKT_PACKAGING_FORM", "包装形态", "SELECT", null, true, null, null,
                List.of(new MarketImportDefinition.Option("BAGGED", "包粮"),
                        new MarketImportDefinition.Option("BULK", "散粮")));
        MarketImportDefinition.Field sample = marketField(
                "MKT_SAMPLE_NAME", "填报对象", "TEXT");
        MarketImportDefinition.Field surveyor = marketField(
                "MKT_SURVEYOR_NAME", "调研人", "TEXT");
        MarketImportDefinition.Field surveyorPhone = marketField(
                "MKT_SURVEYOR_PHONE", "调研人联系方式", "TEXT");
        MarketImportDefinition.Field sampleContact = marketField(
                "MKT_SAMPLE_CONTACT", "样本点联系方式", "TEXT");
        MarketImportDefinition.Field latitude = marketField(
                "MKT_SAMPLE_LATITUDE", "样本点纬度", "DECIMAL");
        MarketImportDefinition.Field longitude = marketField(
                "MKT_SAMPLE_LONGITUDE", "样本点经度", "DECIMAL");
        MarketImportDefinition.Field region = marketField(
                "MKT_REGION", "行政区划", "REGION_HIERARCHY");
        MarketImportDefinition.Field cultivar = marketField(
                "MKT_CULTIVAR_NAME", "具体品种", "TEXT");
        MarketImportDefinition.Field storage = marketField(
                "MKT_STORAGE_REGION_CODE", "库存存放地区", "REGION_HIERARCHY");
        MarketImportDefinition.Field purchasePrice = marketField(
                "MKT_PURCHASE_BASE_PRICE", "对象采购价格", "DECIMAL");
        MarketImportDefinition.Field salePrice = marketField(
                "MKT_SALE_BASE_PRICE", "对象销售价格", "DECIMAL");
        MarketImportDefinition.Field calculated = marketField(
                "MKT_ACTUAL_TRADE_PRICE", "实际成交价", "READONLY_DECIMAL");
        MarketImportDefinition.Field fact = marketField(
                "ENDING_INVENTORY", "期末库存", "DECIMAL");
        MarketImportDefinition.Field outflow = marketField(
                "STOCK_OUTFLOW", "出库量", "DECIMAL");
        MarketImportDefinition.Field processing = marketField(
                "PROCESSING_INPUT", "加工投入量", "DECIMAL");
        MarketImportDefinition definition = new MarketImportDefinition(
                "CORN", "TRADER",
                List.of(objectType, reporter, region, cultivar, sample, surveyor, surveyorPhone,
                        sampleContact, latitude, longitude, storage,
                        purchasePrice, salePrice, packaging, calculated),
                List.of(fact, outflow, processing));

        BusinessImportWorkbook.Template template = MarketImportTemplate.workbook(definition);
        assertThat(template.headers())
                .containsExactly("surveyYear", "surveyMonth", "MKT_SAMPLE_NAME", "MKT_REGION",
                        "MKT_SURVEYOR_NAME", "MKT_SURVEYOR_PHONE", "MKT_SAMPLE_CONTACT", "MKT_SAMPLE_LATITUDE",
                        "MKT_SAMPLE_LONGITUDE",
                        "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE", "MKT_PACKAGING_FORM",
                        "ENDING_INVENTORY",
                        BusinessImportWorkbook.PHOTO_FILENAMES_CODE)
                .doesNotContain("MKT_REPORTER_NAME", "MKT_CULTIVAR_NAME", "MKT_STORAGE_REGION_CODE", "STOCK_OUTFLOW",
                        "PROCESSING_INPUT", "MKT_ACTUAL_TRADE_PRICE", "evidencePhotoId");
        assertThat(template.labels())
                .containsExactly("数据年份", "数据月份", "样本点名称", "地区",
                        "调研人", "调研人联系方式", "样本点联系方式", "纬度（度）", "经度（度）",
                        "采集对象收购价格（元/吨）", "采集对象销售价格（元/吨）", "包装形态", "现有库存",
                        BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)
                .doesNotContain("填报人", "具体品种", "库存量", "期末库存", "库存存放地", "出库量");

        BusinessImportWorkbook.Template productTemplate = MarketImportTemplate.productWorkbook(
                "CORN", List.of(definition), List.of(new com.cofco.qiqihar.graintrade.importing.application
                        .BusinessImportTemplateCatalog.ObjectTypeOption("TRADER", "贸易商")));
        assertThat(productTemplate.rules().stream()
                .filter(rule -> rule.code().equals("MKT_PACKAGING_FORM")))
                .singleElement().extracting(BusinessImportWorkbook.ColumnRule::options)
                .isEqualTo(List.of("包粮", "散粮"));
        assertThat(zipEntry(BusinessImportWorkbook.create(productTemplate), "xl/worksheets/sheet1.xml"))
                .contains("包粮,散粮");
    }

    @Test
    void derivesTheAuditedLogisticsColumnsAndExcludesLegacyAndInternalFields() {
        LogisticsImportDefinition definition = new LogisticsImportDefinition("CORN", List.of(
                new LogisticsImportDefinition.Field("surveyYear", "数据年份", null, true, false),
                new LogisticsImportDefinition.Field("surveyMonth", "数据月份", null, false, false),
                new LogisticsImportDefinition.Field("LOG_SAMPLE_NAME", "物流样本点名称", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REGION", "地区", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REPORTER", "填报人", "READONLY_TEXT",
                        null, null, null, false, true),
                new LogisticsImportDefinition.Field("LOG_SURVEYOR_NAME", "调研人", null, false, false),
                new LogisticsImportDefinition.Field("LOG_SURVEYOR_PHONE", "调研人联系方式", null, false, false),
                new LogisticsImportDefinition.Field("LOG_SAMPLE_CONTACT", "物流样本点联系方式", null, true, false),
                new LogisticsImportDefinition.Field("LOG_SAMPLE_LATITUDE", "纬度", "度", true, false),
                new LogisticsImportDefinition.Field("LOG_SAMPLE_LONGITUDE", "经度", "度", true, false),
                new LogisticsImportDefinition.Field("LOG_TRANSPORT_MODE", "运输方式", null, true, false),
                new LogisticsImportDefinition.Field("LOG_DIRECTION", "运输方向", null, true, false),
                new LogisticsImportDefinition.Field("LOG_ROUTE_VOLUME", "运输数量", "吨", true, false),
                new LogisticsImportDefinition.Field("LOG_FREIGHT_RATE", "物流运价（不含车板价）", "元/吨", true, false),
                new LogisticsImportDefinition.Field("LOG_BOARD_PRICE", "车板价", "元/吨", true, false),
                new LogisticsImportDefinition.Field("fillingDate", "填报日期", "READONLY_DATE",
                        null, null, null, false, true),
                new LogisticsImportDefinition.Field("LOG_STATUS", "填报状态", "READONLY_STATUS",
                        null, null, null, false, true),
                new LogisticsImportDefinition.Field("LOG_PERIOD", "物流监测期", null, true, false),
                new LogisticsImportDefinition.Field("LOG_COLLECTION_DATE", "物流采集期", null, true, false),
                new LogisticsImportDefinition.Field("LOG_ORIGIN", "物流起运节点", null, true, false),
                new LogisticsImportDefinition.Field("LOG_DESTINATION", "物流到达节点", null, true, false),
                new LogisticsImportDefinition.Field("LOG_TRANSIT_TIME", "物流在途时间", "小时", true, false),
                new LogisticsImportDefinition.Field("LOG_INTERNAL_LOCATION_KEY", "内部位置键", null, false, false)));

        BusinessImportWorkbook.Template template = LogisticsImportTemplate.workbook(definition);
        assertThat(template.headers()).containsExactly(
                "数据年份", "数据月份", "填报日期", "物流样本点名称", "地区",
                "调研人", "调研人联系方式",
                "物流样本点联系方式", "纬度（度）", "经度（度）", "运输方式", "运输方向",
                "运输数量（吨）", "物流运价（不含车板价）（元/吨）", "车板价（元/吨）", "填报状态",
                BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
        assertThat(template.labels()).containsExactly(
                "数据年份", "数据月份", "填报日期", "物流样本点名称", "地区",
                "调研人", "调研人联系方式",
                "物流样本点联系方式", "纬度（度）", "经度（度）", "运输方式", "运输方向",
                "运输数量（吨）", "物流运价（不含车板价）（元/吨）", "车板价（元/吨）", "填报状态",
                BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
        assertThat(template.headers())
                .noneMatch(header -> header.matches(".*[A-Za-z_].*"))
                .doesNotContain("物流监测期", "物流采集期", "物流起运节点", "物流到达节点",
                        "物流在途时间", "内部位置键");
        List<BusinessImportWorkbook.ColumnRule> readOnlyRules = template.rules().stream()
                .filter(rule -> rule.controlType().startsWith("READONLY"))
                .toList();
        List<BusinessImportWorkbook.ColumnRule> editableRules = template.rules().stream()
                .filter(rule -> !rule.controlType().startsWith("READONLY"))
                .toList();
        assertThat(editableRules).hasSize(15);
        assertThat(readOnlyRules).hasSize(2)
                .extracting(BusinessImportWorkbook.ColumnRule::code)
                .containsExactly("填报日期", "填报状态");
        assertThat(readOnlyRules).allMatch(rule -> !rule.required());
        assertThat(template.rules().stream().filter(BusinessImportWorkbook.ColumnRule::required))
                .extracting(BusinessImportWorkbook.ColumnRule::code)
                .containsExactly("物流样本点名称", "地区");
        assertThat(template.rules().getLast().required()).isFalse();
        assertThat(template.rules().stream()
                .filter(rule -> rule.code().equals("运输数量（吨）")))
                .singleElement().extracting(BusinessImportWorkbook.ColumnRule::valueType)
                .isEqualTo("DECIMAL");
    }

    private static MarketImportDefinition.Field marketField(
            String code, String label, String controlType) {
        String unit = switch (code) {
            case "MKT_SAMPLE_LATITUDE", "MKT_SAMPLE_LONGITUDE" -> "度";
            case "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE" -> "元/吨";
            default -> null;
        };
        return new MarketImportDefinition.Field(
                code, label, controlType, unit, false, 18, 4);
    }

    private static void put(List<String> row, List<String> headers, String code, String value) {
        row.set(headers.indexOf(code), value);
    }

    private static String zipEntry(byte[] workbook, String expectedName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (expectedName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new AssertionError("Missing workbook entry " + expectedName);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] replaceZipEntry(byte[] workbook, String expectedName, UnaryOperator<String> replace) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(workbook);
                ZipInputStream zipInput = new ZipInputStream(input, StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = zipInput.readAllBytes();
                if (expectedName.equals(entry.getName())) {
                    content = replace.apply(new String(content, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
                zipOutput.write(content);
                zipOutput.closeEntry();
            }
            zipOutput.finish();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String columnName(int oneBased) {
        StringBuilder value = new StringBuilder();
        int column = oneBased;
        while (column > 0) {
            column--;
            value.append((char) ('A' + column % 26));
            column /= 26;
        }
        return value.reverse().toString();
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

}
