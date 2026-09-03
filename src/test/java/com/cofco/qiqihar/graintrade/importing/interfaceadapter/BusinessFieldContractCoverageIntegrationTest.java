package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class BusinessFieldContractCoverageIntegrationTest {
    private static final Set<String> MARKET_OPTIONAL_PRICE_FIELDS = Set.of(
            "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE", "MKT_CARRIAGE_BOARD_AMOUNT",
            "MKT_PACKAGING_FORM", "MKT_PACKAGING_AMOUNT", "MKT_FREIGHT_AMOUNT");
    private static final Set<String> MARKET_CONTEXT_OR_SERVER_OWNED = Set.of(
            "MKT_OBJECT_TYPE", "MKT_TRADE_DATE", "MKT_REPORTED_AT", "MKT_FILLING_AT",
            "MKT_STATUS", "MKT_REPORTER_NAME");

    @Autowired DataSource dataSource;
    @Autowired MarketImportPort market;
    @Autowired ProductionImportPort production;

    @TestFactory
    Stream<DynamicTest> everyApplicableMarketFieldIsPresentInItsObjectWorksheet() {
        return contexts("MARKET").stream().map(context -> DynamicTest.dynamicTest(context.key(), () -> {
            var definition = market.definition(context.productCode(), context.objectTypeCode());
            Set<String> expected = new LinkedHashSet<>();
            Stream.concat(definition.coreFields().stream(), definition.factFields().stream())
                    .filter(field -> !field.readOnly())
                    .map(field -> field.code())
                    .filter(code -> !MARKET_CONTEXT_OR_SERVER_OWNED.contains(code))
                    .forEach(expected::add);

            assertThat(MarketImportTemplate.workbook(definition).headers())
                    .as("all applicable market fields for " + context.key())
                    .containsAll(expected);
        }));
    }

    @TestFactory
    Stream<DynamicTest> accountOwnedReporterIsNeverAnEditableWorkbookColumn() {
        Stream<DynamicTest> productionTests = contexts("PRODUCTION").stream()
                .map(context -> DynamicTest.dynamicTest("PRODUCTION-" + context.key(), () ->
                        assertThat(ProductionImportTemplate.workbook(production.importDefinition(
                                context.productCode(), context.objectTypeCode())).headers())
                                .doesNotContain("PROD_REPORTER_NAME", "填报人")));
        Stream<DynamicTest> marketTests = contexts("MARKET").stream()
                .map(context -> DynamicTest.dynamicTest("MARKET-" + context.key(), () ->
                        assertThat(MarketImportTemplate.workbook(market.definition(
                                context.productCode(), context.objectTypeCode())).headers())
                                .doesNotContain("MKT_REPORTER_NAME", "填报人")));
        return Stream.concat(productionTests, marketTests);
    }

    @TestFactory
    Stream<DynamicTest> individuallyBlankBusinessFieldsArePublishedAsOptional() {
        Stream<DynamicTest> productionTests = contexts("PRODUCTION").stream()
                .map(context -> DynamicTest.dynamicTest("PRODUCTION-OPTIONAL-" + context.key(), () -> {
                    var fields = production.importDefinition(
                            context.productCode(), context.objectTypeCode()).fields();
                    assertThat(fields).filteredOn(field -> Set.of(
                                    "cultivatedAreaMu", "yieldPerMuKilograms").contains(field.code()))
                            .allMatch(field -> !field.required());
                    assertThat(fields).filteredOn(field -> Set.of(
                                    "QUALITY", "COST", "INSURANCE", "SUBSIDY").contains(field.groupCode()))
                            .allMatch(field -> !field.required());
                }));
        Stream<DynamicTest> marketTests = contexts("MARKET").stream()
                .map(context -> DynamicTest.dynamicTest("MARKET-OPTIONAL-" + context.key(), () -> {
                    var definition = market.definition(context.productCode(), context.objectTypeCode());
                    assertThat(definition.coreFields())
                                .filteredOn(field -> MARKET_OPTIONAL_PRICE_FIELDS.contains(field.code()))
                                .allMatch(field -> !field.required());
                    assertThat(definition.factFields()).allMatch(field -> !field.required());
                }));
        return Stream.concat(productionTests, marketTests);
    }

    private List<Context> contexts(String domain) {
        return JdbcClient.create(dataSource).sql("""
                SELECT applicability.product_code, applicability.object_type_code
                FROM platform.product_object_type applicability
                JOIN platform.product product ON product.code=applicability.product_code
                JOIN platform.object_type object_type ON object_type.code=applicability.object_type_code
                WHERE object_type.business_domain=:domain
                ORDER BY product.sort_order,object_type.sort_order,object_type.code
                """).param("domain", domain).query((row, ignored) ->
                new Context(row.getString(1), row.getString(2))).list();
    }

    private record Context(String productCode, String objectTypeCode) {
        String key() {
            return productCode + "-" + objectTypeCode;
        }
    }
}
