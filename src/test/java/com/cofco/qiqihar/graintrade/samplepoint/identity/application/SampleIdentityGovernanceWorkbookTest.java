package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SampleIdentityGovernanceWorkbookTest {
    @Test
    void roundTripsTheVersionBoundFourSheetIdentityContract() throws Exception {
        UUID batchId = UUID.fromString("95400000-0000-0000-0000-000000000001");
        var row = row("95400000-0000-0000-0000-000000000101", 7,
                "95400000-0000-0000-0000-000000000201",
                SampleIdentityGovernanceWorkbook.MERGE,
                "95400000-0000-0000-0000-000000000202");

        byte[] bytes = SampleIdentityGovernanceWorkbook.create(batchId, List.of(row));
        var parsed = SampleIdentityGovernanceWorkbook.read(bytes);

        assertThat(sheetNames(bytes)).containsExactly(
                "填写说明", "身份重复明细", "重复身份组", "导出绑定");
        assertThat(entry(bytes, "xl/workbook.xml"))
                .contains("<sheet name=\"导出绑定\" sheetId=\"4\" state=\"hidden\"");
        assertThat(parsed.batchId()).isEqualTo(batchId);
        assertThat(parsed.rows()).containsExactly(row);
    }

    @Test
    void rejectsInvalidActionsDuplicateRecordsAndMoreThanFiveThousandRows() {
        assertThatThrownBy(() -> row("95400000-0000-0000-0000-000000000111", 1,
                "95400000-0000-0000-0000-000000000211", "直接删除",
                "95400000-0000-0000-0000-000000000212"))
                .isInstanceOf(IllegalArgumentException.class);

        var duplicated = row("95400000-0000-0000-0000-000000000121", 1,
                "95400000-0000-0000-0000-000000000221", "", null);
        assertThatThrownBy(() -> SampleIdentityGovernanceWorkbook.create(
                UUID.randomUUID(), List.of(duplicated, duplicated)))
                .isInstanceOf(IllegalArgumentException.class);

        List<SampleIdentityGovernanceWorkbook.Row> rows = new ArrayList<>();
        for (int index = 0; index < 5_001; index++) {
            rows.add(row(UUID.randomUUID().toString(), index,
                    UUID.randomUUID().toString(), "", null));
        }
        assertThatThrownBy(() -> SampleIdentityGovernanceWorkbook.create(UUID.randomUUID(), rows))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesProfessionalChineseStylesAndOnlyTheBusinessActionList() throws Exception {
        byte[] bytes = SampleIdentityGovernanceWorkbook.create(UUID.randomUUID(), List.of(
                row(UUID.randomUUID().toString(), 2, UUID.randomUUID().toString(), "", null)));
        assertThat(entry(bytes, "xl/styles.xml"))
                .contains("<name val=\"Arial Unicode MS\"/>")
                .contains("wrapText=\"1\"");
        assertThat(entry(bytes, "xl/worksheets/sheet2.xml"))
                .contains("归并至规范样本点,保留为不同身份,暂不处理")
                .contains("showGridLines=\"0\"")
                .doesNotContain("#REF!", "#DIV/0!", "#VALUE!", "#N/A", "#NAME?");
    }

    private static SampleIdentityGovernanceWorkbook.Row row(
            String sourceRecordId, long version, String currentPointId,
            String action, String targetPointId) {
        return new SampleIdentityGovernanceWorkbook.Row(
                sourceRecordId, version, "PRODUCTION", "CORN", "2026-08",
                UUID.fromString(currentPointId), "身份重复样本", "13900000000",
                "230208", "梅里斯达斡尔族区", new BigDecimal("123.800000"),
                new BigDecimal("47.550000"), 3, "identity-group-1", "row-binding-1",
                action, targetPointId == null ? null : UUID.fromString(targetPointId),
                action.isBlank() ? "" : "已核对原始业务材料", "");
    }

    private static List<String> sheetNames(byte[] bytes) throws Exception {
        String workbook = entry(bytes, "xl/workbook.xml");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<sheet name=\"([^\"]+)\"").matcher(workbook);
        List<String> names = new ArrayList<>();
        while (matcher.find()) names.add(matcher.group(1));
        return List.copyOf(names);
    }

    private static String entry(byte[] bytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException(name + " missing");
    }
}
