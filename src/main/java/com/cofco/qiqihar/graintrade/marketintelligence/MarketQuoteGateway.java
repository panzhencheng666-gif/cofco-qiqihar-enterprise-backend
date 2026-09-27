package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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
                        String provider, String contractId, String state) { }
    public record Board(List<Instrument> instruments, List<Quote> quotes, String gatewayState,
                        Instant lastAttemptAt, Instant lastSuccessAt, String lastError,
                        String feedState, Instant feedPublishedAt, Long feedAgeSeconds) { }
    private record Feed(Map<String, Quote> quotes, String state, Instant publishedAt,
                        Instant lastSuccessAt, String error) { }
    private static final Set<String> FEED_STATES = Set.of("NEW", "STARTING", "WAITING_DATA",
            "PENDING_AUTHORIZATION", "ENTITLEMENT_ERROR", "SESSION_LOST", "SOURCE_ERROR", "CLOSED",
            "RECONNECTING", "RECOVERY_REQUIRED", "RECONCILED", "STALE_DATA");
    private static final Set<String> HIDE_QUOTES = Set.of("NEW", "STARTING", "WAITING_DATA",
            "PENDING_AUTHORIZATION", "ENTITLEMENT_ERROR", "SESSION_LOST", "SOURCE_ERROR", "CLOSED");

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
            item("dce-soybean", "大商所黄大豆1号主力", "油脂油料", "中国", "元/吨"),
            item("dce-soybean-2", "大商所黄大豆2号主力", "油脂油料", "中国", "元/吨"),
            item("dce-soymeal", "大商所豆粕主力", "油脂油料", "中国", "元/吨"),
            item("dce-soyoil", "大商所豆油主力", "油脂油料", "中国", "元/吨"),
            item("dce-palm", "大商所棕榈油主力", "油脂油料", "中国", "元/吨"),
            item("czce-rapemeal", "郑商所菜粕主力", "油脂油料", "中国", "元/吨"),
            item("czce-rapeseed-oil", "郑商所菜籽油主力", "油脂油料", "中国", "元/吨"),
            item("czce-rapeseed", "郑商所油菜籽主力", "油脂油料", "中国", "元/吨"),
            item("czce-peanut", "郑商所花生主力", "油脂油料", "中国", "元/吨"),
            item("cbot-soybean", "CBOT 大豆期货", "油脂油料", "美国", "美分/蒲式耳"),
            item("cbot-soymeal", "CBOT 豆粕期货", "油脂油料", "美国", "美元/短吨"),
            item("cbot-soyoil", "CBOT 豆油期货", "油脂油料", "美国", "美分/磅"),
            item("ine-crude", "上期能源原油主力", "原油与能源", "中国", "元/桶"),
            item("shfe-fuel", "上期所燃料油主力", "原油与能源", "中国", "元/吨"),
            item("wti-crude", "NYMEX 原油期货", "原油与能源", "美国", "美元/桶"),
            item("brent-crude", "ICE 布伦特原油期货", "原油与能源", "英国", "美元/桶"),
            item("natural-gas", "NYMEX 天然气期货", "原油与能源", "美国", "美元/百万英热"),
            item("dce-corn", "大商所玉米主力", "谷物", "中国", "元/吨"),
            item("dce-corn-starch", "大商所玉米淀粉主力", "谷物", "中国", "元/吨"),
            item("dce-japonica-rice", "大商所粳米主力", "谷物", "中国", "元/吨"),
            item("czce-wheat", "郑商所强麦主力", "谷物", "中国", "元/吨"),
            item("czce-common-wheat", "郑商所普麦主力", "谷物", "中国", "元/吨"),
            item("czce-rice", "郑商所晚籼稻主力", "谷物", "中国", "元/吨"),
            item("czce-early-indica-rice", "郑商所早籼稻主力", "谷物", "中国", "元/吨"),
            item("czce-japonica-rice", "郑商所粳稻主力", "谷物", "中国", "元/吨"),
            item("cbot-corn", "CBOT 玉米期货", "谷物", "美国", "美分/蒲式耳"),
            item("cbot-wheat", "CBOT 小麦期货", "谷物", "美国", "美分/蒲式耳"),
            item("cbot-rice", "CBOT 稻谷期货", "谷物", "美国", "美元/英担"),
            item("czce-sugar", "郑商所白糖主力", "农副产品", "中国", "元/吨"),
            item("czce-cotton", "郑商所棉花主力", "农副产品", "中国", "元/吨"),
            item("czce-cotton-yarn", "郑商所棉纱主力", "农副产品", "中国", "元/吨"),
            item("czce-apple", "郑商所苹果主力", "农副产品", "中国", "元/吨"),
            item("czce-red-date", "郑商所红枣主力", "农副产品", "中国", "元/吨"),
            item("dce-hog", "大商所生猪主力", "农副产品", "中国", "元/吨"),
            item("dce-egg", "大商所鸡蛋主力", "农副产品", "中国", "元/500千克"),
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
    // The supplier adapter must provide the actual dated contract, not just a
    // continuous-contract alias. These are the exchange products verified for
    // the first grain mapping gate; other catalogue items remain unverified.
    private record GrainContract(String exchangeId, String productId, String unit,
                                 String contractPattern) { }
    private static final Map<String, GrainContract> GATED_GRAIN_PRODUCTS = Map.of(
            "dce-soybean", new GrainContract("DCE", "a", "元/吨", "DCE\\.a[0-9]{2}(0[1-9]|1[0-2])"),
            "dce-corn", new GrainContract("DCE", "c", "元/吨", "DCE\\.c[0-9]{2}(0[1-9]|1[0-2])"),
            "czce-wheat", new GrainContract("CZCE", "WH", "元/吨", "CZCE\\.WH[0-9](0[1-9]|1[0-2])"),
            "czce-common-wheat", new GrainContract("CZCE", "PM", "元/吨", "CZCE\\.PM[0-9](0[1-9]|1[0-2])"),
            "czce-rice", new GrainContract("CZCE", "LR", "元/吨", "CZCE\\.LR[0-9](0[1-9]|1[0-2])"));

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String feedUrl;
    private final String bearerToken;
    private final boolean distributionAuthorized;
    private final Set<String> displayAllowedInstrumentIds;
    private final Clock clock;
    private volatile Feed feed = new Feed(Map.of(), "NOT_OBSERVED", null, null, null);
    private volatile Instant lastAttemptAt;

    @Autowired
    public MarketQuoteGateway(ObjectMapper mapper,
            @Value("${qiqihar.market-intelligence.quote-feed.url:}") String feedUrl,
            @Value("${qiqihar.market-intelligence.quote-feed.bearer-token:}") String bearerToken,
            @Value("${qiqihar.market-intelligence.quote-feed.distribution-authorized:false}") boolean distributionAuthorized,
            @Value("${qiqihar.market-intelligence.quote-feed.display-allowed-instrument-ids:}") String displayAllowedInstrumentIds) {
        this(mapper, feedUrl, bearerToken, distributionAuthorized,
                parseDisplayAllowedInstrumentIds(displayAllowedInstrumentIds), Clock.systemUTC());
    }

    MarketQuoteGateway(ObjectMapper mapper, String feedUrl, String bearerToken,
                       boolean distributionAuthorized, Clock clock) {
        this(mapper, feedUrl, bearerToken, distributionAuthorized, Set.of(), clock);
    }

    MarketQuoteGateway(ObjectMapper mapper, String feedUrl, String bearerToken,
                       boolean distributionAuthorized, Set<String> displayAllowedInstrumentIds, Clock clock) {
        this.mapper = mapper;
        this.feedUrl = feedUrl.trim();
        this.bearerToken = bearerToken.trim();
        this.distributionAuthorized = distributionAuthorized;
        if (!BY_ID.keySet().containsAll(displayAllowedInstrumentIds))
            throw new IllegalArgumentException("QUOTE_FEED_DISPLAY_SCOPE_UNKNOWN_INSTRUMENT");
        this.displayAllowedInstrumentIds = Set.copyOf(displayAllowedInstrumentIds);
        this.clock = clock;
    }

    MarketQuoteGateway(ObjectMapper mapper, String feedUrl, String bearerToken,
                       boolean distributionAuthorized, Set<String> displayAllowedInstrumentIds) {
        this(mapper, feedUrl, bearerToken, distributionAuthorized,
                displayAllowedInstrumentIds, Clock.systemUTC());
    }

    MarketQuoteGateway(ObjectMapper mapper, String feedUrl, String bearerToken,
                       boolean distributionAuthorized) {
        this(mapper, feedUrl, bearerToken, distributionAuthorized, Set.of(), Clock.systemUTC());
    }

    private static Set<String> parseDisplayAllowedInstrumentIds(String value) {
        if (value.isBlank()) return Set.of();
        var ids = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (ids.stream().anyMatch(String::isBlank) || ids.size() != Set.copyOf(ids).size())
            throw new IllegalArgumentException("QUOTE_FEED_DISPLAY_SCOPE_INVALID");
        return Set.copyOf(ids);
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
    public synchronized void refresh() {
        if (!distributionAuthorized || feedUrl.isBlank() || displayAllowedInstrumentIds.isEmpty()) return;
        lastAttemptAt = clock.instant();
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
            if (!body.path("schemaVersion").isIntegralNumber()
                    || !"1".equals(body.path("schemaVersion").asText()))
                throw new IllegalArgumentException("QUOTE_FEED_SCHEMA_REQUIRED");
            String state = body.path("state").asText("");
            if (!FEED_STATES.contains(state)) throw new IllegalArgumentException("QUOTE_FEED_STATE_INVALID");
            Instant publishedAt = Instant.parse(body.path("publishedAt").asText(""));
            if (!freshPublication(publishedAt, clock.instant()))
                throw new IllegalArgumentException("QUOTE_FEED_HEARTBEAT_INVALID");
            if (feed.publishedAt() != null && (publishedAt.isBefore(feed.publishedAt())
                    || (publishedAt.equals(feed.publishedAt()) && !state.equals(feed.state())))) {
                feed = new Feed(feed.quotes(), feed.state(), feed.publishedAt(),
                        feed.lastSuccessAt(), "QUOTE_FEED_OUT_OF_ORDER_HEALTH");
                return;
            }
            if (!Set.of("RECONCILED", "STALE_DATA").contains(state)) {
                feed = new Feed(HIDE_QUOTES.contains(state) ? Map.of() : feed.quotes(), state,
                        publishedAt, feed.lastSuccessAt(), "QUOTE_FEED_" + state);
                return;
            }
            var accepted = new HashMap<String, Quote>();
            boolean timestampConflict = false;
            for (JsonNode row : body.path("quotes")) {
                try {
                    String id = row.path("id").asText("");
                    if (!displayAllowedInstrumentIds.contains(id) || !row.path("last").isNumber()) continue;
                    BigDecimal price = row.path("last").decimalValue();
                    if (price.signum() <= 0) continue;
                    Instant sourceAt = Instant.parse(row.path("sourceAt").asText());
                    if (sourceAt.isAfter(clock.instant().plusSeconds(60))) continue;
                    String provider = row.path("provider").asText("").trim();
                    if (provider.isBlank()) continue;
                    if (!matchesVerifiedGrain(row, id)) continue;
                    String contractId = row.path("contractId").asText("");
                    BigDecimal previous = row.path("previousClose").isNumber()
                            ? row.path("previousClose").decimalValue() : null;
                    var quote = new Quote(id, price, previous, sourceAt, provider, contractId, "");
                    Quote earlier = accepted.get(id);
                    if (earlier == null || sourceAt.isAfter(earlier.sourceAt())) {
                        accepted.put(id, quote);
                    } else if (sourceAt.equals(earlier.sourceAt()) && !sameObservation(earlier, quote)) {
                        timestampConflict = true;
                    }
                } catch (RuntimeException ignored) {
                    // One malformed instrument must not discard other valid supplier observations.
                }
            }
            for (var entry : accepted.entrySet()) {
                Quote cached = feed.quotes().get(entry.getKey());
                Quote incoming = entry.getValue();
                if (cached != null && cached.sourceAt().equals(incoming.sourceAt())
                        && !sameObservation(cached, incoming)) {
                    timestampConflict = true;
                }
            }
            if (timestampConflict) {
                feed = new Feed(feed.quotes(), "SOURCE_ERROR", feed.publishedAt(),
                        feed.lastSuccessAt(), "QUOTE_FEED_TIMESTAMP_CONFLICT");
                return;
            }
            // Empty or invalid supplier responses must not masquerade as a successful refresh.
            if (accepted.isEmpty()) throw new IllegalArgumentException("QUOTE_FEED_NO_VALID_QUOTES");
            // Supplier batches may contain only changed instruments. Retain the last
            // attributed tick for omitted IDs, and never let delayed ticks rewind a series.
            var merged = new HashMap<>(feed.quotes());
            accepted.forEach((id, incoming) -> merged.merge(id, incoming, (previous, next) ->
                    next.sourceAt().isAfter(previous.sourceAt()) ? next : previous));
            feed = new Feed(Map.copyOf(merged), state, publishedAt, clock.instant(), null);
        } catch (Exception ex) {
            // Supplier exceptions can contain a URL or request details. Never expose them through the public board.
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            feed = new Feed(feed.quotes(), "INVALID_OR_UNREACHABLE", feed.publishedAt(),
                    feed.lastSuccessAt(), "QUOTE_FEED_" + ex.getClass().getSimpleName());
        }
    }

    private static boolean freshPublication(Instant publishedAt, Instant now) {
        return !publishedAt.isBefore(now.minusSeconds(30)) && !publishedAt.isAfter(now.plusSeconds(5));
    }

    private static boolean sameObservation(Quote first, Quote second) {
        return first.last().compareTo(second.last()) == 0
                && samePrice(first.previousClose(), second.previousClose())
                && first.provider().equals(second.provider())
                && first.contractId().equals(second.contractId());
    }

    private static boolean samePrice(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static boolean matchesVerifiedGrain(JsonNode row, String id) {
        GrainContract expected = GATED_GRAIN_PRODUCTS.get(id);
        if (expected == null) return true;
        return expected.exchangeId().equals(row.path("exchangeId").asText(""))
                && expected.productId().equals(row.path("productId").asText(""))
                && expected.unit().equals(row.path("unit").asText(""))
                && row.path("contractId").asText("").matches(expected.contractPattern());
    }

    @GetMapping("/api/v1/market-intelligence/quotes/overview")
    public ApiResponse<Board> overview() {
        Instant now = clock.instant();
        Feed snapshot = feed;
        var quotes = new ArrayList<Quote>();
        for (Instrument instrument : INSTRUMENTS) {
            Quote quote = snapshot.quotes().get(instrument.id());
            if (quote == null || !displayAllowedInstrumentIds.contains(instrument.id())) continue;
            quotes.add(new Quote(quote.id(), quote.last(), quote.previousClose(), quote.sourceAt(),
                    quote.provider(), quote.contractId(),
                    quote.sourceAt().isBefore(now.minus(maximumAge(instrument.cadence())))
                            ? "STALE" : "CURRENT"));
        }
        String error = snapshot.publishedAt() != null && !freshPublication(snapshot.publishedAt(), now)
                ? "QUOTE_FEED_HEARTBEAT_STALE" : snapshot.error();
        String gatewayState = !distributionAuthorized ? "PENDING_AUTHORIZATION"
                : feedUrl.isBlank() || displayAllowedInstrumentIds.isEmpty() ? "PENDING_CONFIGURATION"
                : Set.of("PENDING_AUTHORIZATION", "ENTITLEMENT_ERROR").contains(snapshot.state())
                        ? "PENDING_AUTHORIZATION"
                : error != null ? "SOURCE_ERROR"
                : snapshot.lastSuccessAt() == null ? "WAITING_FIRST_TICK"
                : "STALE_DATA".equals(snapshot.state())
                        || quotes.stream().noneMatch(quote -> "CURRENT".equals(quote.state()))
                        ? "STALE_DATA" : "CONNECTED";
        return new ApiResponse<>(new Board(INSTRUMENTS, quotes, gatewayState,
                lastAttemptAt, snapshot.lastSuccessAt(), error, snapshot.state(), snapshot.publishedAt(),
                snapshot.publishedAt() == null ? null : Math.max(0, Duration.between(snapshot.publishedAt(), now).getSeconds())));
    }
}
