package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Public schedule card identity only. A source Live badge is not a playback proof or permission. */
final class UnWebTvProgrammeParser {
    private static final String ORIGIN = "https://webtv.un.org";
    private static final Pattern ASSET = Pattern.compile("/en/asset/(k1[a-z0-9])/(k1[a-z0-9]{8})");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    record Programme(String title, String url, String entryId, boolean sourceReportsLive, Instant startsAt) { }

    static Optional<UnWebTvScheduleParser.Event> match(Programme programme,
            List<UnWebTvScheduleParser.Event> events) {
        var matches = events.stream().filter(event -> !event.cancelled()
                && event.startsAt().equals(programme.startsAt())
                && normalized(event.title()).equals(normalized(programme.title()))).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static String normalized(String title) {
        return title.replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    static List<Programme> parse(String html) throws IOException {
        if (html == null || html.length() > 2_000_000
                || html.getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw invalid();
        var cards = Jsoup.parse(html, ORIGIN).select(".un3-card-row-video");
        if (cards.isEmpty() || cards.size() > 500) throw invalid();
        var programmes = new LinkedHashMap<String, Programme>();
        for (var card : cards) {
            var links = card.select("h4 a[href]");
            if (links.size() != 1) throw invalid();
            var link = links.getFirst();
            String title = link.text().strip();
            if (title.isEmpty() || title.length() > 1000) throw invalid();
            try {
                URI url = URI.create(link.absUrl("href"));
                if (!url.toString().equals(ORIGIN + url.getPath())) throw invalid();
                var asset = ASSET.matcher(url.getPath());
                if (!asset.matches() || !asset.group(2).startsWith(asset.group(1))) throw invalid();
                String entry = "1_" + asset.group(2).substring(2);
                var images = card.select("a.ajax-popup-link img[src]");
                if (images.size() != 1) throw invalid();
                URI image = URI.create(images.getFirst().absUrl("src"));
                if (!"https".equals(image.getScheme()) || !"cfvod.kaltura.com".equals(image.getHost())
                        || image.getRawUserInfo() != null || image.getPort() != -1
                        || !image.getPath().startsWith("/p/2503451/sp/250345100/thumbnail/entry_id/" + entry + "/")) throw invalid();
                var popup = card.select("a.ajax-popup-link[href]");
                if (popup.size() != 1 || !popup.getFirst().absUrl("href").equals(url.toString())) throw invalid();
                boolean live = card.select(".card-img-overlay .badge").stream()
                        .anyMatch(badge -> badge.text().strip().equalsIgnoreCase("Live"));
                var clocks = card.select(".mediaun-timezone");
                if (clocks.size() != 1) throw invalid();
                String clock = clocks.getFirst().text().strip();
                if (!clock.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})")) throw invalid();
                // UN's public timezone script slices(0,19), interprets as UTC, then changes display zone.
                // The serialized suffix is NOT applied by that script; this is not a general ISO parser.
                Instant start = LocalDateTime.parse(clock.substring(0, 19), CLOCK).toInstant(ZoneOffset.UTC);
                var programme = new Programme(title, url.toString(), entry, live, start);
                var previous = programmes.putIfAbsent(programme.url(), programme);
                if (previous != null && !previous.equals(programme)) throw invalid();
            } catch (IllegalArgumentException | java.time.DateTimeException invalidValue) {
                throw invalid();
            }
        }
        return List.copyOf(programmes.values());
    }

    private static IOException invalid() {
        return new IOException("Invalid or unsupported UN Web TV programme cards");
    }
}
