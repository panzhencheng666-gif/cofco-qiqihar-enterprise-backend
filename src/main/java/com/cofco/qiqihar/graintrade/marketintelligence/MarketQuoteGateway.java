package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A fail-closed bridge for a licensed market-data supplier. No browser quote is treated as a feed. */
@RestController
public class MarketQuoteGateway {
    public record Instrument(String id, String name, String group, String market, String unit,
                             String cadence) { }
    public record Quote(String id, BigDecimal last, BigDecimal previousClose, Instant sourceAt,
                        String provider, String state) { }
    public record Board(List<Instrument> instruments, List<Quote> quotes, String gatewayState,
                        Instant lastAttemptAt, Instant lastSuccessAt, String lastError) { }

    private static final List<Instrument> INSTRUMENTS = List.of(
            item("sse-composite", "上证指数", "全球指数", "中国", "点"),
            item("csi-300", "沪深300", "全球指数", "中国", "点"),
            item("szse-component", "深证成指", "全球指数", "中国", "点"),
            item("hang-seng", "恒生指数", "全球指数", "香港", "点"),
            item("sp-500", "标普500", "全球指数", "美国", "点"),
            item("nasdaq-composite", "纳斯达克综合指数", "全球指数", "美国", "点"),
            item("dow-jones", "道琼斯工业指数", "全球指数", "美国", "点"),
            item("stoxx-600", "欧洲斯托克600", "全球指数", "欧洲", "点"),
            item("nikkei-225", "日经225指数", "全球指数", "日本", "点"),
            item("dce-soybean", "大商所黄大豆主力", "油脂油料", "中国", "元/吨"),
            item("dce-soymeal", "大商所豆粕主力", "油脂油料", "中国", "元/吨"),
            item("dce-soyoil", "大商所豆油主力", "油脂油料", "中国", "元/吨"),
            item("dce-palm", "大商所棕榈油主力", "油脂油料", "中国", "元/吨"),
            item("czce-rapemeal", "郑商所菜粕主力", "油脂油料", "中国", "元/吨"),
            item("czce-rapeseed-oil", "郑商所菜籽油主力", "油脂油料", "中国", "元/吨"),
            item("cbot-soybean", "CBOT 大豆期货", "油脂油料", "美国", "美分/蒲式耳"),
            item("cbot-soymeal", "CBOT 豆粕期货", "油脂油料", "美国", "美元/短吨"),
            item("cbot-soyoil", "CBOT 豆油期货", "油脂油料", "美国", "美分/磅"),
            item("ine-crude", "上期能源原油主力", "原油与能源", "中国", "元/桶"),
            item("shfe-fuel", "上期所燃料油主力", "原油与能源", "中国", "元/吨"),
            item("wti-crude", "NYMEX 原油期货", "原油与能源", "美国", "美元/桶"),
            item("brent-crude", "ICE 布伦特原油期货", "原油与能源", "英国", "美元/桶"),
            item("natural-gas", "NYMEX 天然气期货", "原油与能源", "美国", "美元/百万英热"),
            item("dce-corn", "大商所玉米主力", "谷物与农副产品", "中国", "元/吨"),
            item("dce-corn-starch", "大商所玉米淀粉主力", "谷物与农副产品", "中国", "元/吨"),
            item("czce-wheat", "郑商所强麦主力", "谷物与农副产品", "中国", "元/吨"),
            item("czce-rice", "郑商所晚籼稻主力", "谷物与农副产品", "中国", "元/吨"),
            item("czce-sugar", "郑商所白糖主力", "谷物与农副产品", "中国", "元/吨"),
            item("czce-cotton", "郑商所棉花主力", "谷物与农副产品", "中国", "元/吨"),
            item("dce-hog", "大商所生猪主力", "谷物与农副产品", "中国", "元/吨"),
            item("cbot-corn", "CBOT 玉米期货", "谷物与农副产品", "美国", "美分/蒲式耳"),
            item("cbot-wheat", "CBOT 小麦期货", "谷物与农副产品", "美国", "美分/蒲式耳"),
            item("cbot-rice", "CBOT 稻谷期货", "谷物与农副产品", "美国", "美元/英担"),
            item("shfe-gold", "上期所黄金主力", "贵金属与大宗", "中国", "元/克"),
            item("shfe-silver", "上期所白银主力", "贵金属与大宗", "中国", "元/千克"),
            item("shfe-copper", "上期所铜主力", "贵金属与大宗", "中国", "元/吨"),
            item("comex-gold", "COMEX 黄金期货", "贵金属与大宗", "美国", "美元/盎司"),
            item("comex-silver", "COMEX 白银期货", "贵金属与大宗", "美国", "美元/盎司"),
            item("lme-copper", "LME 铜", "贵金属与大宗", "英国", "美元/吨"),
            item("dxy", "美元指数 DXY", "汇率", "国际", "点"),
            item("usd-cny", "美元兑人民币即期汇率", "汇率", "中国", "元/美元"),
            item("eur-usd", "欧元兑美元", "汇率", "国际", "美元/欧元"),
            item("usd-brl", "美元兑巴西雷亚尔", "汇率", "巴西", "雷亚尔/美元"),
            item("cny-brl", "人民币兑巴西雷亚尔", "汇率", "国际", "雷亚尔/元"),
            item("scfi-europe-future", "欧线集运指数期货", "航运与陆运", "中国", "点"),
            item("bdi", "波罗的海干散货指数", "航运与陆运", "国际", "点", "DAILY"),
            item("scfi", "上海出口集装箱运价指数", "航运与陆运", "中国", "点", "WEEKLY"),
            item("china-rail-freight", "中国铁路货运量", "航运与陆运", "中国", "吨", "MONTHLY"),
            item("china-road-freight", "中国公路货运量", "航运与陆运", "中国", "吨", "MONTHLY"),
            item("urea-future", "郑商所尿素主力", "农资与成本", "中国", "元/吨"),
            item("potash", "钾肥基准价格", "农资与成本", "国际", "美元/吨", "MONTHLY")
    );
    private static final Map<String, Instrument> BY_ID = INSTRUMENTS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Instrument::id, item -> item));

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String feedUrl;
    private final String bearerToken;
    private final boolean distributionAuthorized;
    private volatile Map<String, Quote> latest = Map.of();
    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;

    public MarketQuoteGateway(ObjectMapper mapper,
            @Value("${qiqihar.market-intelligence.quote-feed.url:}") String feedUrl,
            @Value("${qiqihar.market-intelligence.quote-feed.bearer-token:}") String bearerToken,
            @Value("${qiqihar.market-intelligence.quote-feed.distribution-authorized:false}") boolean distributionAuthorized) {
        this.mapper = mapper;
        this.feedUrl = feedUrl.trim();
        this.bearerToken = bearerToken.trim();
        this.distributionAuthorized = distributionAuthorized;
    }

    private static Instrument item(String id, String name, String group, String market, String unit) {
        return item(id, name, group, market, unit, "INTRADAY");
    }

    private static Instrument item(String id, String name, String group, String market, String unit,
                                   String cadence) {
        return new Instrument(id, name, group, market, unit, cadence);
    }

    private static Duration maximumAge(String cadence) {
        return switch (cadence) {
            case "DAILY" -> Duration.ofDays(3);
            case "WEEKLY" -> Duration.ofDays(10);
            case "MONTHLY" -> Duration.ofDays(70);
            default -> Duration.ofSeconds(90);
        };
    }

    @Scheduled(initialDelayString = "${qiqihar.market-intelligence.quote-feed.initial-delay:15s}",
            fixedDelayString = "${qiqihar.market-intelligence.quote-feed.poll-interval:10s}")
    public void refresh() {
        if (!distributionAuthorized || feedUrl.isBlank()) return;
        lastAttemptAt = Instant.now();
        try {
            URI uri = URI.create(feedUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) &&
                    !("http".equalsIgnoreCase(uri.getScheme()) &&
                            ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))))
                throw new IllegalArgumentException("QUOTE_FEED_URL_MUST_BE_HTTPS_OR_LOOPBACK");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/json");
            if (!bearerToken.isBlank()) request.header("Authorization", "Bearer " + bearerToken);
            var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("QUOTE_FEED_HTTP_" + response.statusCode());
            JsonNode body = mapper.readTree(response.body());
            if (!body.path("quotes").isArray()) throw new IllegalArgumentException("QUOTE_FEED_QUOTES_REQUIRED");
            var accepted = new HashMap<String, Quote>();
            for (JsonNode row : body.path("quotes")) {
                try {
                    String id = row.path("id").asText("");
                    if (!BY_ID.containsKey(id) || !row.path("last").isNumber()) continue;
                    BigDecimal price = row.path("last").decimalValue();
                    if (price.signum() <= 0) continue;
                    Instant sourceAt = Instant.parse(row.path("sourceAt").asText());
                    if (sourceAt.isAfter(Instant.now().plusSeconds(60))) continue;
                    String provider = row.path("provider").asText("").trim();
                    if (provider.isBlank()) continue;
                    BigDecimal previous = row.path("previousClose").isNumber()
                            ? row.path("previousClose").decimalValue() : null;
                    var quote = new Quote(id, price, previous, sourceAt, provider, "");
                    accepted.merge(id, quote, (earlier, later) ->
                            later.sourceAt().isAfter(earlier.sourceAt()) ? later : earlier);
                } catch (RuntimeException ignored) {
                    // One malformed instrument must not discard other valid supplier observations.
                }
            }
            // Empty or invalid supplier responses must not masquerade as a successful refresh.
            if (accepted.isEmpty()) throw new IllegalArgumentException("QUOTE_FEED_NO_VALID_QUOTES");
            // Supplier batches may contain only changed instruments. Retain the last
            // attributed tick for omitted IDs, and never let delayed ticks rewind a series.
            var merged = new HashMap<>(latest);
            accepted.forEach((id, incoming) -> merged.merge(id, incoming, (previous, next) ->
                    next.sourceAt().isAfter(previous.sourceAt()) ? next : previous));
            latest = Map.copyOf(merged);
            lastSuccessAt = Instant.now();
            lastError = null;
        } catch (Exception ex) {
            // Supplier exceptions can contain a URL or request details. Never expose them through the public board.
            lastError = "QUOTE_FEED_" + ex.getClass().getSimpleName();
        }
    }

    @GetMapping("/api/v1/market-intelligence/quotes/overview")
    public ApiResponse<Board> overview() {
        Instant now = Instant.now();
        var quotes = new ArrayList<Quote>();
        for (Instrument instrument : INSTRUMENTS) {
            Quote quote = latest.get(instrument.id());
            if (quote == null) continue;
            quotes.add(new Quote(quote.id(), quote.last(), quote.previousClose(), quote.sourceAt(),
                    quote.provider(), quote.sourceAt().isBefore(now.minus(maximumAge(instrument.cadence())))
                            ? "STALE" : "CURRENT"));
        }
        String gatewayState = !distributionAuthorized ? "PENDING_AUTHORIZATION"
                : feedUrl.isBlank() ? "PENDING_CONFIGURATION"
                : lastError != null ? "SOURCE_ERROR"
                : lastSuccessAt == null ? "WAITING_FIRST_TICK"
                : quotes.stream().noneMatch(quote -> "CURRENT".equals(quote.state()))
                        ? "STALE_DATA" : "CONNECTED";
        return new ApiResponse<>(new Board(INSTRUMENTS, quotes, gatewayState,
                lastAttemptAt, lastSuccessAt, lastError));
    }
}
