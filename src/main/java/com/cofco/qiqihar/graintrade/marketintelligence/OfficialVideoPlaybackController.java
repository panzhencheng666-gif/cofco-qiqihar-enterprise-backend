package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Resolves only saved FAO programmes; media stays on the official video host. */
@RestController
public class OfficialVideoPlaybackController {
    private final JdbcClient jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    OfficialVideoPlaybackController(JdbcClient jdbc) { this.jdbc = jdbc; }
    public record Playback(String id, String mediaUrl) { }

    @GetMapping("/api/v1/market-intelligence/news/videos/playback")
    public ResponseEntity<ApiResponse<Playback>> resolve(@RequestParam String url) {
        var page = officialPage(url);
        var saved = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM market_intelligence.news_headline
                    WHERE source_code='fao-market-video' AND article_url=:url
                    UNION ALL
                    SELECT 1 FROM market_intelligence.webcast_event
                    WHERE source_code='fao-webcast' AND event_url=:url
                )
                """).param("url", url).query(Boolean.class).single();
        if (!saved) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Programme not in official catalogue");
        try {
            var response = http.send(HttpRequest.newBuilder(page).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official player resolution)")
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200)
                    throw new IOException("Official page unavailable");
                var bytes = body.readNBytes(1_000_001);
                if (bytes.length > 1_000_000) throw new IOException("Official page too large");
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new ApiResponse<>(parsePlayback(new String(bytes, StandardCharsets.UTF_8))));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Official player unavailable");
        } catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Official player unavailable");
        }
    }

    static String parsePlayer(String html) {
        for (var frame : Jsoup.parse(html).select(".detail-video iframe[src], .detail-webcast iframe[src]")) {
            try {
                var src = URI.create(frame.attr("src"));
                if (secure(src) && ("www.youtube.com".equals(src.getHost()) || "www.youtube-nocookie.com".equals(src.getHost()))
                        && src.getPath().matches("/embed/[A-Za-z0-9_-]{11}"))
                    return src.getPath().substring("/embed/".length());
            } catch (IllegalArgumentException malformed) { /* Ignore malformed frames. */ }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    static Playback parsePlayback(String html) {
        try { return new Playback(parsePlayer(html), null); }
        catch (ResponseStatusException noYoutube) { /* FAO also publishes native recordings. */ }
        for (var source : Jsoup.parse(html).select(".detail-webcast #webcastPlayer source[src], .detail-video video source[src]")) {
            try {
                var src = URI.create(source.attr("src"));
                if (secure(src) && "vod.fao.org".equals(src.getHost())
                        && src.getPath().startsWith("/video/") && src.getPath().endsWith(".mp4"))
                    return new Playback(null, src.toString());
            } catch (IllegalArgumentException malformed) { /* Ignore malformed media. */ }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Official player not yet available");
    }

    private static boolean secure(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    static URI officialPage(String url) {
        try {
            var uri = URI.create(url);
            if (url.length() <= 2048 && secure(uri) && "www.fao.org".equals(uri.getHost())
                    && uri.getQuery() == null && uri.getFragment() == null && uri.normalize().equals(uri)
                    && !uri.getPath().contains("/../") && !uri.getPath().contains("/./")
                    && (uri.getPath().startsWith("/markets-and-trade/news-and-events/multimedia/video-detail/")
                        || uri.getPath().startsWith("/webcast/detail/"))) return uri;
        } catch (IllegalArgumentException malformed) { /* Reject before any database or network access. */ }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
