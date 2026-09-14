package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public RegionalPublicDataRefreshWorker(RegionalPublicDataRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${qiqihar.regional-public-data.initial-delay:5s}",
            fixedDelayString = "${qiqihar.regional-public-data.poll-delay:30m}")
    public void refreshDueSources() {
        Instant now = Instant.now();
        for (var source : repository.due(now)) {
            try {
                var request = HttpRequest.newBuilder(URI.create(source.url()))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "COFCO-Qiqihar-RegionalData/1.0 (+daily-public-data-sync)")
                        .GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                String body = response.body();
                String excerpt = excerpt(body);
                String hash = sha256(body);
                if ("OPEN_METEO".equals(source.parserKey())) {
                    BigDecimal temperature = number(body, "temperature_2m");
                    BigDecimal precipitation = number(body, "precipitation");
                    BigDecimal soil = number(body, "soil_moisture_0_to_1cm").multiply(new BigDecimal("100"));
                    Instant observed = observedAt(body, now);
                    String risk = risk(temperature, precipitation, soil);
                    String assessment = "气温" + temperature + "℃，降水" + precipitation
                            + "毫米，表层土壤含水率约" + soil.setScale(1) + "%；" + risk;
                    repository.recordWeatherSuccess(source.id(), source.rootRegionCode(), observed,
                            temperature, precipitation, soil, risk, assessment, now, hash, excerpt);
                } else {
                    var metrics = RegionalPublicCropPageParser.parse(source.parserKey(), body);
                    if (!metrics.isEmpty()) repository.recordCropMetrics(source.id(), metrics, now);
                    repository.recordPageSuccess(source.id(), now, hash, excerpt);
                }
            } catch (Exception exception) {
                repository.recordFailure(source.id(), now, exception.getMessage());
            }
        }
    }

    static BigDecimal number(String json, String field) {
        Matcher matcher = Pattern.compile(NUMBER.pattern().formatted(Pattern.quote(field))).matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("weather field missing: " + field);
        return new BigDecimal(matcher.group(1));
    }

    private static Instant observedAt(String json, Instant fallback) {
        Matcher matcher = TIME.matcher(json);
        if (!matcher.find()) return fallback;
        return LocalDateTime.parse(matcher.group(1)).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private static String risk(BigDecimal temperature, BigDecimal precipitation, BigDecimal soil) {
        if (temperature.compareTo(BigDecimal.ZERO) < 0) return "低温霜冻风险，单产修正偏谨慎";
        if (soil.compareTo(new BigDecimal("15")) < 0) return "表层墒情偏低，存在干旱风险";
        if (precipitation.compareTo(new BigDecimal("25")) > 0) return "短时降水偏强，关注渍涝风险";
        return "当前天气指标处于常规模型区间";
    }

    private static String excerpt(String body) {
        String text = body.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
        return text.substring(0, Math.min(600, text.length()));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
