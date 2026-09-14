package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RegionalPublicCropPageParser {
    private RegionalPublicCropPageParser() {}

    static List<RegionalPublicDataRepository.PublicCropMetric> parse(String parserKey, String html) {
        String text = html.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replaceAll("[\\s　]+", " ").trim();
        return switch (parserKey) {
            case "HEIHE_REPORT" -> heihe(text);
            case "DXAL_REPORT" -> dxal(text);
            default -> List.of();
        };
    }

    private static List<RegionalPublicDataRepository.PublicCropMetric> heihe(String text) {
        int year = year(text);
        String outputPart = after(text, "粮食产量");
        return List.of(
                metric(year, "CORN", named(text, "玉米", "万亩"), named(outputPart, "玉米", "亿斤"), "万亩/亿斤"),
                metric(year, "SOYBEAN", named(text, "大豆", "万亩"), named(outputPart, "大豆", "亿斤"), "万亩/亿斤"),
                metric(year, "RICE", named(text, "稻谷", "万亩"), named(outputPart, "稻谷", "亿斤"), "万亩/亿斤"));
    }

    private static List<RegionalPublicDataRepository.PublicCropMetric> dxal(String text) {
        int year = year(text);
        Matcher output = Pattern.compile("小麦、玉米、大豆分别为([0-9.]+)万吨、([0-9.]+)万吨、([0-9.]+)万吨").matcher(text);
        Matcher area = Pattern.compile("小麦、玉米、大豆分别为([0-9.]+)万公顷、([0-9.]+)万公顷、([0-9.]+)万公顷").matcher(text);
        if (!output.find() || !area.find()) return List.of();
        return List.of(
                metricHa(year, "CORN", area.group(2), output.group(2)),
                metricHa(year, "SOYBEAN", area.group(3), output.group(3)));
    }

    private static RegionalPublicDataRepository.PublicCropMetric metric(
            int year, String product, BigDecimal areaWanMu, BigDecimal outputYiJin, String evidenceUnit) {
        BigDecimal area = areaWanMu.multiply(new BigDecimal("10000"));
        BigDecimal output = outputYiJin.multiply(new BigDecimal("50000000"));
        return new RegionalPublicDataRepository.PublicCropMetric(year, product, area,
                output.divide(area, 4, RoundingMode.HALF_UP), output,
                areaWanMu + "万亩；" + outputYiJin + "亿斤（网页自动识别，" + evidenceUnit + "换算）");
    }

    private static RegionalPublicDataRepository.PublicCropMetric metricHa(
            int year, String product, String areaWanHa, String outputWanTon) {
        BigDecimal area = new BigDecimal(areaWanHa).multiply(new BigDecimal("150000"));
        BigDecimal output = new BigDecimal(outputWanTon).multiply(new BigDecimal("10000000"));
        return new RegionalPublicDataRepository.PublicCropMetric(year, product, area,
                output.divide(area, 4, RoundingMode.HALF_UP), output,
                areaWanHa + "万公顷；" + outputWanTon + "万吨（网页自动识别并换算为亩、公斤）");
    }

    private static int year(String text) {
        Matcher matcher = Pattern.compile("(20[0-9]{2})年").matcher(text);
        if (!matcher.find()) throw new IllegalArgumentException("public report year missing");
        return Integer.parseInt(matcher.group(1));
    }

    private static BigDecimal named(String text, String crop, String unit) {
        Matcher matcher = Pattern.compile(Pattern.quote(crop) + "\\s*([0-9.]+)" + Pattern.quote(unit)).matcher(text);
        if (!matcher.find()) throw new IllegalArgumentException(crop + unit + " missing");
        return new BigDecimal(matcher.group(1));
    }

    private static String after(String text, String marker) {
        int index = text.indexOf(marker);
        return index < 0 ? text : text.substring(index);
    }
}
