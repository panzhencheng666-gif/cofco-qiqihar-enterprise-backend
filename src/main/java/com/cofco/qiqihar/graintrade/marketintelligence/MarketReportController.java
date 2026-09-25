package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Source-attributed DOCX reports built only from persisted public observations. */
@RestController
public class MarketReportController {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Map<String, String> CHINA_LABELS = Map.of(
            "grain", "粮食价格指数", "grain-oil", "粮油产品批发价格指数",
            "edible-oil", "食用油价格指数", "agri-200", "农产品批发价格200指数",
            "basket", "菜篮子产品批发价格指数", "livestock", "畜产品价格指数",
            "aquatic", "水产品价格指数", "vegetable", "蔬菜价格指数",
            "fruit", "水果价格指数");
    private final JdbcClient jdbc;

    public MarketReportController(JdbcClient jdbc) { this.jdbc = jdbc; }

    public enum Period { DAY, WEEK, MONTH, QUARTER, YEAR }
    record Range(LocalDate start, LocalDate endExclusive) { }
    record News(String source, String title, String url, Instant publishedAt) { }
    record Observation(String series, LocalDate period, BigDecimal value, String unit, String url,
                       Instant fetchedAt, int position) { }
    record Source(String name, Instant lastSuccessAt, LocalDate latestPeriod, String lastError) { }

    static Range range(Period period, LocalDate anchor) {
        return switch (period) {
            case DAY -> new Range(anchor, anchor.plusDays(1));
            case WEEK -> {
                var start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Range(start, start.plusWeeks(1));
            }
            case MONTH -> {
                var start = anchor.withDayOfMonth(1);
                yield new Range(start, start.plusMonths(1));
            }
            case QUARTER -> {
                var start = anchor.withMonth(((anchor.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
                yield new Range(start, start.plusMonths(3));
            }
            case YEAR -> {
                var start = anchor.withDayOfYear(1);
                yield new Range(start, start.plusYears(1));
            }
        };
    }

    @GetMapping("/api/v1/market-intelligence/reports/docx")
    public ResponseEntity<byte[]> download(@RequestParam Period period,
                                           @RequestParam(required = false) LocalDate date) throws IOException {
        var now = Instant.now();
        var anchor = date == null ? LocalDate.now(REPORT_ZONE) : date;
        if (anchor.isAfter(LocalDate.now(REPORT_ZONE)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Future report date is unavailable");
        var range = range(period, anchor);
        var start = range.start().atStartOfDay(REPORT_ZONE).toOffsetDateTime();
        var end = range.endExclusive().atStartOfDay(REPORT_ZONE).toOffsetDateTime();
        var cutoff = end.toInstant().isAfter(now) ? OffsetDateTime.ofInstant(now, REPORT_ZONE) : end;
        var news = news(start, cutoff);
        var china = observations("china_daily_index", cutoff);
        var world = observations("monthly_benchmark_price", cutoff);
        var sources = sources();
        var bytes = render(period, range, now, news, china, world, sources);
        var filename = "grain-market-" + period.name().toLowerCase() + "-" + anchor + ".docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    private List<News> news(OffsetDateTime start, OffsetDateTime cutoff) {
        return jdbc.sql("""
                WITH ranked AS (
                    SELECT source_code,title,article_url,published_at,
                           ROW_NUMBER() OVER (PARTITION BY CASE WHEN source_code LIKE 'moa-%'
                               THEN 'domestic' ELSE 'international' END
                               ORDER BY published_at DESC,article_url) AS position
                    FROM market_intelligence.news_headline
                    WHERE published_at >= :start AND published_at < :cutoff
                      AND source_code IN ('moa-public-monitor','moa-department-news','fao-newsroom-rss')
                )
                SELECT source_code,title,article_url,published_at FROM ranked
                WHERE position <= 25 ORDER BY published_at DESC,article_url
                """).param("start", start).param("cutoff", cutoff)
                .query((rs, row) -> new News(switch (rs.getString("source_code")) {
                    case "moa-public-monitor" -> "农业农村部监测";
                    case "moa-department-news" -> "农业农村部动态";
                    default -> "FAO 新闻";
                }, rs.getString("title"), rs.getString("article_url"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant())).list();
    }

    private List<Observation> observations(String table, OffsetDateTime cutoff) {
        // Both names are fixed internal choices, never request parameters.
        if (!table.equals("china_daily_index") && !table.equals("monthly_benchmark_price"))
            throw new IllegalArgumentException("Unsupported report series");
        var sql = table.equals("china_daily_index") ? """
                WITH ranked AS (
                    SELECT series_code,period,value,source_url,fetched_at,
                           ROW_NUMBER() OVER (PARTITION BY series_code ORDER BY period DESC) AS position
                    FROM market_intelligence.china_daily_index WHERE period <= :cutoff
                ) SELECT series_code,period,value,'指数点' AS unit,source_url,fetched_at,position
                  FROM ranked WHERE position <= 2 ORDER BY series_code,position
                """ : """
                WITH ranked AS (
                    SELECT series_code,period,price AS value,unit,source_url,fetched_at,
                           ROW_NUMBER() OVER (PARTITION BY series_code ORDER BY period DESC) AS position
                    FROM market_intelligence.monthly_benchmark_price WHERE period <= :cutoff
                ) SELECT series_code,period,value,unit,source_url,fetched_at,position
                  FROM ranked WHERE position <= 2 ORDER BY series_code,position
                """;
        var lastIncludedDay = cutoff.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? cutoff.toLocalDate().minusDays(1) : cutoff.toLocalDate();
        return jdbc.sql(sql).param("cutoff", lastIncludedDay)
                .query((rs, row) -> new Observation(rs.getString("series_code"),
                        rs.getDate("period").toLocalDate(), rs.getBigDecimal("value"),
                        rs.getString("unit"), rs.getString("source_url"),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("position"))).list();
    }

    private List<Source> sources() {
        return jdbc.sql("""
                SELECT source_code,last_success_at,latest_period,last_error
                FROM market_intelligence.source_sync_state
                WHERE source_code IN ('moa-public-monitor','moa-department-news',
                                      'fao-newsroom-rss','world-bank-pink-sheet')
                ORDER BY source_code
                """).query((rs, row) -> new Source(rs.getString("source_code"),
                rs.getObject("last_success_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("last_success_at", OffsetDateTime.class).toInstant(),
                rs.getDate("latest_period") == null ? null : rs.getDate("latest_period").toLocalDate(),
                rs.getString("last_error"))).list();
    }

    static byte[] render(Period period, Range range, Instant generatedAt, List<News> news,
                         List<Observation> china, List<Observation> world, List<Source> sources) throws IOException {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            heading(document, "全球粮食商情" + switch (period) {
                case DAY -> "日报"; case WEEK -> "周报"; case MONTH -> "月报";
                case QUARTER -> "季报"; case YEAR -> "年报";
            }, 18);
            line(document, "统计区间（北京时间）：" + range.start() + " 至 " + range.endExclusive().minusDays(1));
            line(document, "生成时间：" + generatedAt.atZone(REPORT_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
            if (generatedAt.isBefore(range.endExclusive().atStartOfDay(REPORT_ZONE).toInstant()))
                line(document, "本期尚未结束，资讯与指标仅覆盖生成时已采集的记录。");
            line(document, "本报告按已接入来源的发布节奏生成；不表示全网覆盖或交易所实时行情。");
            heading(document, "一、来源状态", 13);
            var sourceTable = table(document, "来源代码", "最近成功采集", "源最新统计日", "状态");
            if (sources.isEmpty()) line(document, "尚无来源状态记录。");
            for (var source : sources) row(sourceTable, source.name(),
                    source.lastSuccessAt() == null ? "待接入" : source.lastSuccessAt().atZone(REPORT_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    source.latestPeriod() == null ? "--" : source.latestPeriod().toString(),
                    source.lastError() == null ? "最近采集成功" : "最近采集失败：" + source.lastError());

            heading(document, "二、国内官方批发价格指数", 13);
            observationTable(document, china, true);
            heading(document, "三、国际月度公开基准", 13);
            observationTable(document, world, false);
            heading(document, "四、区间内官方资讯", 13);
            line(document, "以下为来源标题与原文链接；最多列出国内、国际各 25 条。标题不是已核验的事件影响结论。");
            if (news.isEmpty()) line(document, "本区间暂无已采集资讯。");
            for (var item : news) {
                line(document, item.publishedAt().atZone(REPORT_ZONE).toLocalDate() + " | " + item.source() + " | " + item.title());
                line(document, item.url());
            }
            heading(document, "五、口径与缺口", 13);
            line(document, "历史区间按生成时数据库内的记录回看；来源状态也以生成时为准，并非历史时点的数据快照。");
            line(document, "国内指数的变化值为本系统对最近两次官方发布数值的差；国际基准价格取世界银行已发布月度值。表内标注原始统计日，可早于报告区间。");
            line(document, "交易所实时行情、持仓、运价、直播视频及未经授权的付费来源未接入，本报告不填充模拟值或预测结论。");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static void observationTable(XWPFDocument document, List<Observation> rows, boolean domestic) {
        var table = table(document, "指标", "统计日", "最新", "较前次", "原始来源");
        if (rows.isEmpty()) { row(table, "待接入", "--", "--", "--", "--"); return; }
        for (var latest : rows) {
            if (latest.position() != 1) continue;
            var previous = rows.stream().filter(item -> item.series().equals(latest.series()) && item.position() == 2)
                    .findFirst().orElse(null);
            var label = domestic ? CHINA_LABELS.getOrDefault(latest.series(), latest.series())
                    : WorldBankMonthlySeries.fromCode(latest.series()).title;
            var delta = previous == null ? "--" : latest.value().subtract(previous.value()).stripTrailingZeros().toPlainString();
            row(table, label, latest.period().format(DATE), latest.value().stripTrailingZeros().toPlainString()
                    + " " + latest.unit(), delta, latest.url());
        }
    }

    private static void heading(XWPFDocument document, String value, int size) {
        var paragraph = document.createParagraph();
        var run = paragraph.createRun();
        run.setBold(true); run.setFontFamily("Microsoft YaHei"); run.setFontSize(size); run.setText(value);
    }
    private static void line(XWPFDocument document, String value) {
        var run = document.createParagraph().createRun();
        run.setFontFamily("Microsoft YaHei"); run.setFontSize(9); run.setText(value);
    }
    private static XWPFTable table(XWPFDocument document, String... headers) {
        var table = document.createTable(1, headers.length);
        for (int i = 0; i < headers.length; i++) table.getRow(0).getCell(i).setText(headers[i]);
        return table;
    }
    private static void row(XWPFTable table, String... values) {
        var row = table.createRow();
        for (int i = 0; i < values.length; i++) row.getCell(i).setText(values[i]);
    }
}
