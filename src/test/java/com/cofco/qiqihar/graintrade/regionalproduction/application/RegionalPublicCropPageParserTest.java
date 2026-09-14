package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegionalPublicCropPageParserTest {
    @Test
    void normalizesHeiheOfficialReportUnitsIntoModelMetrics() {
        var metrics = RegionalPublicCropPageParser.parse("HEIHE_REPORT", """
                2025年 粮食产量。粮食作物播种面积2800.3 万亩，其中，大豆2026.4 万亩、玉米713.9 万亩、稻谷15.5 万亩。
                粮食产量 118.9 亿斤，其中，大豆52.9 亿斤、玉米62.4 亿斤、稻谷1.4 亿斤。
                """);
        assertThat(metrics).hasSize(3);
        assertThat(metrics.get(0).productCode()).isEqualTo("CORN");
        assertThat(metrics.get(0).plantedAreaMu()).isEqualByComparingTo("7139000");
        assertThat(metrics.get(0).totalOutputKg()).isEqualByComparingTo("3120000000");
    }

    @Test
    void normalizesDaxinganlingOfficialReportHectares() {
        var metrics = RegionalPublicCropPageParser.parse("DXAL_REPORT", """
                2025年 小麦、玉米、大豆分别为4.0万吨、2.3万吨、21.2万吨。
                播种面积中小麦、玉米、大豆分别为1.0万公顷、0.4万公顷、15.7万公顷。
                """);
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(1).plantedAreaMu()).isEqualByComparingTo("2355000");
    }
}
