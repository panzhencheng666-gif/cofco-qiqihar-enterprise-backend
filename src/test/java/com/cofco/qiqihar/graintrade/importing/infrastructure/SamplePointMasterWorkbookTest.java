package com.cofco.qiqihar.graintrade.importing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SamplePointMasterWorkbookTest {
    private static final SamplePointMasterWorkbook.Template DESIGN =
            new SamplePointMasterWorkbook.Template(
                    SamplePointMasterWorkbook.Kind.DESIGN,
                    "design-v1",
                    "sha256:design",
                    List.of(
                            new SamplePointMasterWorkbook.Column("name", "点位名称", true),
                            new SamplePointMasterWorkbook.Column("region", "行政区代码", true)));

    @Test
    void roundTripsRowsWithStableSpreadsheetRowNumbers() {
        byte[] bytes = SamplePointMasterWorkbook.create(
                DESIGN, List.of(Map.of("name", "第一设计点", "region", "230202")));

        assertThat(SamplePointMasterWorkbook.parse(bytes, DESIGN, 5_000))
                .containsExactly(new SamplePointMasterWorkbook.Row(
                        2, Map.of("name", "第一设计点", "region", "230202")));
    }

    @Test
    void rejectsAnotherSamplePointKindAndContract() {
        byte[] bytes = SamplePointMasterWorkbook.create(DESIGN, List.of(Map.of(
                "name", "第一设计点", "region", "230202")));
        var formal = new SamplePointMasterWorkbook.Template(
                SamplePointMasterWorkbook.Kind.FORMAL,
                "formal-v1",
                "sha256:formal",
                DESIGN.columns());

        assertThatThrownBy(() -> SamplePointMasterWorkbook.parse(bytes, formal, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SAMPLE_POINT_IMPORT_TEMPLATE_MISMATCH");
    }

    @Test
    void writesControlledChoicesIntoTheDataSheet() throws Exception {
        var controlled = new SamplePointMasterWorkbook.Template(
                SamplePointMasterWorkbook.Kind.DESIGN,
                "design-v1",
                "sha256:design",
                List.of(new SamplePointMasterWorkbook.Column(
                        "domain", "业务分类", true, List.of("产情", "市场"))));

        byte[] bytes = SamplePointMasterWorkbook.create(controlled);

        assertThat(zipEntry(bytes, "xl/worksheets/sheet1.xml"))
                .contains("type=\"list\"")
                .contains("&quot;产情,市场&quot;");
    }

    private static String zipEntry(byte[] bytes, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing XLSX entry " + name);
    }
}
