package com.cofco.qiqihar.graintrade.importing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
}
