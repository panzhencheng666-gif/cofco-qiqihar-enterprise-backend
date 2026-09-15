package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qiqihar.regional-public-data.enabled", matchIfMissing = true)
public class RegionalPublicDataRefreshWorker {
    private static final Pattern NUMBER = Pattern.compile("\\\"%s\\\"\\s*:\\s*(-?[0-9.]+)");
    private static final Pattern TIME = Pattern.compile("\\\"time\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final RegionalPublicDataRepository repository;
    private final RegionalSourceDiscovery discovery;
    private final RegionalEstimateBatchService estimateBatches;
    private final RegionalHierarchyRefresh hierarchy;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private final HttpClient discoveredHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public RegionalPublicDataRefreshWorker(RegionalPublicDataRepository repository, RegionalSourceDiscovery discovery) {
        this(repository, discovery, null);
    }

    public RegionalPublicDataRefreshWorker(RegionalPublicDataRepository repository, RegionalSourceDiscovery discovery,
            RegionalEstimateBatchService estimateBatches) {
        this(repository,discovery,estimateBatches,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RegionalPublicDataRefreshWorker(RegionalPublicDataRepository repository, RegionalSourceDiscovery discovery,
            RegionalEstimateBatchService estimateBatches, RegionalHierarchyRefresh hierarchy) {
        this.repository = repository;
        this.discovery = discovery;
        this.estimateBatches = estimateBatches;
        this.hierarchy = hierarchy;
    }

    @Scheduled(cron = "${qiqihar.regional-public-data.daily-cron:0 30 8 * * *}", zone = "Asia/Shanghai",
            scheduler = "regionalPublicDataScheduler")
    public synchronized void refreshDueSources() {
        var now = Instant.now();
        discovery.markDailyDue(now);
        refresh(now);
    }

    @Scheduled(initialDelayString = "${qiqihar.regional-public-data.initial-delay:5s}",
            fixedDelayString = "${qiqihar.regional-public-data.retry-delay:1h}",
            scheduler = "regionalPublicDataScheduler")
    public void retryDueSources() {
        refresh(Instant.now());
    }

    private synchronized void refresh(Instant now) {
        discovery.discover(now);
        for (var source : repository.due(now)) {
            if (Thread.currentThread().isInterrupted()) return;
            if (source.parserKey().equals("WEB_DISCOVERY")) continue;
            try {
                if (source.id().startsWith("search-found-") && !RegionalSourceDiscovery.publicHttps(source.url()))
                    throw new IllegalArgumentException("联网来源地址不符合公开网页访问条件");
                var request = HttpRequest.newBuilder(URI.create(source.url()))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "COFCO-Qiqihar-RegionalData/1.0 (+daily-public-data-sync)")
                        .GET().build();
                var response = RegionalPublicHttp.send(
                        source.id().startsWith("search-found-") ? discoveredHttp : http, request, 20_000_000);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                byte[] bytes = response.body();
                String body;
                if (response.headers().firstValue("content-type").orElse("").contains("application/pdf")
                        || source.url().endsWith(".pdf")) {
                    try (var document = Loader.loadPDF(bytes)) {
                        body = new PDFTextStripper().getText(document);
                    }
                } else {
                    body = new String(bytes, StandardCharsets.UTF_8);
                }
                String excerpt = excerpt(body);
                if ("OPEN_METEO".equals(source.parserKey())) {
                    BigDecimal temperature = number(body, "temperature_2m");
                    BigDecimal precipitation = number(body, "precipitation");
                    BigDecimal soil = number(body, "soil_moisture_0_to_1cm").multiply(new BigDecimal("100"));
                    Instant observed = observedAt(body, now);
                    String hash = sha256(temperature.toPlainString() + "|"
                            + precipitation.toPlainString() + "|" + soil.toPlainString()
                            + "|" + observed);
                    String risk = risk(temperature, precipitation, soil);
                    String assessment = "气温" + temperature + "℃，降水" + precipitation
                            + "毫米，表层土壤含水率约" + soil.setScale(1, java.math.RoundingMode.HALF_UP) + "%；" + risk;
                    repository.recordWeatherSuccess(source.id(), source.rootRegionCode(), observed,
                            temperature, precipitation, soil, risk, assessment, now, hash, excerpt);
                } else {
                    var metrics = RegionalPublicCropPageParser.parse(source.parserKey(), body);
                    var indicators = RegionalPublicIndicatorParser.parse(source.parserKey(), body);
                    if (source.parserKey().startsWith("ANNUAL_") && indicators.isEmpty())
                        throw new IllegalStateException("已获取页面，但未识别出匹配年份的农业指标；保留旧值，不标为核验无变化");
                    String hash = sha256(!indicators.isEmpty() ? indicators.toString()
                            : metrics.isEmpty() ? excerpt : metrics.toString());
                    if (!indicators.isEmpty()) repository.recordIndicators(source.id(), indicators, now);
                    if (!metrics.isEmpty()) repository.recordCropMetrics(source.id(), metrics, now);
                    repository.recordPageSuccess(source.id(), now, hash, indicators.isEmpty() ? excerpt
                            : indicators.stream().limit(6).map(i -> i.year()+"年"+i.label()+i.value()+i.unit())
                                .collect(java.util.stream.Collectors.joining("；")));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                repository.recordFailure(source.id(), now, exception.getMessage());
            }
        }
        if (estimateBatches != null && !Thread.currentThread().isInterrupted()) estimateBatches.refresh(Instant.now());
        if (hierarchy != null && !Thread.currentThread().isInterrupted()) hierarchy.refresh(Instant.now());
    }

    static BigDecimal number(String json, String field) {
        Matcher matcher = Pattern.compile(NUMBER.pattern().formatted(Pattern.quote(field))).matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("weather field missing: " + field);
        return new BigDecimal(matcher.group(1));
    }

    static Instant observedAt(String json, Instant fallback) {
        Matcher matcher = TIME.matcher(json);
        while (matcher.find()) {
            try {
                return LocalDateTime.parse(matcher.group(1))
                        .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            } catch (java.time.format.DateTimeParseException ignored) {
                // The current_units object also contains time="iso8601"; continue to current.time.
            }
        }
        return fallback;
    }

    private static String risk(BigDecimal temperature, BigDecimal precipitation, BigDecimal soil) {
        if (temperature.compareTo(BigDecimal.ZERO) < 0) return "低温提示，需结合当地预警核实";
        if (soil.compareTo(new BigDecimal("15")) < 0) return "表层墒情偏低，存在干旱风险";
        if (precipitation.compareTo(new BigDecimal("25")) > 0) return "短时降水偏强，关注渍涝风险";
        return "未触发系统简易温度、降水和墒情提示阈值";
    }

    private static String excerpt(String body) {
        if (body.stripLeading().startsWith("{")) return "天气接口已读取；结构化气象指标单独展示。";
        String text = body.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
        String focused = java.util.Arrays.stream(text.split("[。；]"))
                .filter(sentence -> sentence.matches(".*(?:农业|粮食|耕地|补贴|补助|农田|种植|畜牧|乡村).*"))
                .filter(sentence -> sentence.length() < 500).limit(4)
                .collect(java.util.stream.Collectors.joining("；"));
        return focused.isBlank() ? "已访问公开网页；正文未提取到适合展示的农业摘要，请查看原文。"
                : focused.substring(0, Math.min(600, focused.length()));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
