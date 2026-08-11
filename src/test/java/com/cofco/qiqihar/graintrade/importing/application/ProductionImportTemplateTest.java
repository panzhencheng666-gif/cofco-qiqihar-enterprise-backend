package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionImportTemplateTest {

    @Test
    void acceptsThePreviousRegionCodeLabelWithoutChangingItsCanonicalField() {
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", List.of());
        BusinessImportWorkbook.Template current = ProductionImportTemplate.workbook(definition);
        List<String> legacyLabels = new ArrayList<>(current.labels());
        legacyLabels.set(0, "所在地区代码");
        BusinessImportWorkbook.Template legacy = new BusinessImportWorkbook.Template(
                current.domainCode(), current.domainLabel(), current.productCode(),
                current.objectTypeCode(), current.headers(), legacyLabels);
        List<String> row = new ArrayList<>(Collections.nCopies(legacy.headers().size(), ""));
        row.set(legacy.headers().indexOf("regionCode"), "230200");

        List<List<String>> canonical = ProductionImportTemplate.canonicalXlsx(
                BusinessImportWorkbook.create(legacy, List.of(row)), definition);

        assertThat(canonical.getFirst()).contains("regionCode");
        assertThat(canonical.get(1).get(canonical.getFirst().indexOf("regionCode")))
                .isEqualTo("230200");
    }
}
