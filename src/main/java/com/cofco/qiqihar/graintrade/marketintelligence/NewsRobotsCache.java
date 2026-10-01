package com.cofco.qiqihar.graintrade.marketintelligence;
import java.net.URI;
import java.time.Clock;
import java.util.function.Function;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local bounded rules cache. Refresh outside fetch callbacks; decisions never perform I/O. */
final class NewsRobotsCache {
    private record Entry(NewsSourceFetch.Result response, Instant retrievedAt, Instant expires) {}
    private final Function<URI,NewsSourceFetch.Result> loader;
    private final Clock clock;
    private final ConcurrentHashMap<URI,Entry> entries=new ConcurrentHashMap<>();
    NewsRobotsCache(Function<URI,NewsSourceFetch.Result> loader, Clock clock) {
        this.loader=Objects.requireNonNull(loader);
        this.clock=Objects.requireNonNull(clock);
    }
    /** Only a coordinator may refresh; never invoke this from a fetch policy callback. */
    synchronized void refresh(URI article) {
        var robots=key(article);
        if(robots==null) return;
        var now=clock.instant();
        var requestStarted=now;
        var previous=entries.get(robots);
        if(previous!=null && fresh(previous,now)) return;
        NewsSourceFetch.Result result;
        try {
            result=loader.apply(article);
            if(result==null || !"ROBOTS_FETCHED".equals(result.reason())
                    || result.body()==null || result.body().length>512000)
                throw new IllegalStateException("Invalid rules result");
            result=new NewsSourceFetch.Result(result.finalUri(),result.body().clone(),result.reason(),
                result.status(),result.contentType(),result.headers());
        } catch(RuntimeException failure) {
            result=new NewsSourceFetch.Result(robots,new byte[0],"FETCH_FAILED");
        }
        now=clock.instant();
        boolean success=result.status()==200 || result.status()==404 || result.status()==410;
        if(!entries.containsKey(robots) && entries.size()>=32) {
            entries.entrySet().stream().min(java.util.Comparator.comparing(entry -> entry.getValue().retrievedAt()))
                .ifPresent(oldest -> entries.remove(oldest.getKey(),oldest.getValue()));
        }
        var expires=success?freshUntil(result.headers(),requestStarted,now):retryUntil(result.headers(),now);
        if(!expires.isAfter(now)) {
            // No-store/no-cache: retain no response body, only a short local negative scheduling marker.
            result=new NewsSourceFetch.Result(robots,new byte[0],"REVALIDATION_REQUIRED");
            expires=now.plusSeconds(60);
        }
        entries.put(robots,new Entry(result,now,expires));
    }
    NewsRobotsPolicy.Decision check(URI article) {
        var robots=key(article);
        var entry=robots==null?null:entries.get(robots);
        var now=clock.instant();
        if(entry==null || !fresh(entry,now))
            return new NewsRobotsPolicy.Decision(false,"ROBOTS_NOT_CACHED",0);
        var result=entry.response();
        return NewsRobotsPolicy.evaluate(article,robots,result.status(),result.body(),result.contentType(),
            entry.retrievedAt(),now);
    }
    Instant retryNotBefore(URI article) {
        var key=key(article);
        var entry=key==null?null:entries.get(key);
        return entry!=null && !check(article).allowed() ? entry.expires() : clock.instant();
    }
    private static boolean fresh(Entry entry,Instant now) {
        return !entry.retrievedAt().isAfter(now) && entry.expires().isAfter(now);
    }
    private static URI key(URI article) {
        var normalized=article==null?null:NewsSearchResults.candidateUri(article.toString());
        return normalized==null?null:normalized.resolve("/robots.txt");
    }
    private static Instant freshUntil(java.util.Map<String,String> headers,Instant started,Instant now) {
        try {
            long lifetime=3600;
            var seen=new java.util.HashSet<String>();
            for(String directive:headers.getOrDefault("cache-control","").toLowerCase(java.util.Locale.ROOT).split(",")) {
                var parts=directive.strip().split("=",2);
                String name=parts[0].strip();
                if(java.util.Set.of("no-store","no-cache","private").contains(name)) return now;
                if(name.equals("max-age") || name.equals("s-maxage")) {
                    if(parts.length!=2 || !seen.add(name)) return now;
                    String value=parts[1].strip().replaceAll("^\"|\"$","");
                    lifetime=Math.min(lifetime,seconds(value));
                }
            }
            long delay=Math.max(0,java.time.Duration.between(started,now).toMillis()+999)/1000;
            long age=seconds(headers.getOrDefault("age","0"));
            if(headers.containsKey("date")) age=Math.max(age,Math.max(0,
                java.time.Duration.between(httpDate(headers.get("date")),now).getSeconds()));
            lifetime=Math.max(0,lifetime-age-delay);
            if(headers.containsKey("expires")) lifetime=Math.min(lifetime,Math.max(0,
                java.time.Duration.between(now,httpDate(headers.get("expires"))).getSeconds()));
            return now.plusSeconds(lifetime);
        } catch(RuntimeException invalid) { return now; }
    }
    static Instant retryUntil(java.util.Map<String,String> headers,Instant now) {
        Instant fallback=now.plusSeconds(60);
        String value=headers.get("retry-after");
        if(value==null) return fallback;
        try {
            Instant requested=value.matches("[0-9]+")?now.plusSeconds(seconds(value)):httpDate(value);
            return requested.isAfter(fallback)?requested:fallback;
        } catch(RuntimeException invalid) {
            // An overflowing all-digit delay must not accidentally shorten the requested wait.
            return value.matches("[0-9]+")?Instant.MAX:fallback;
        }
    }
    private static long seconds(String value) {
        if(!value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid seconds");
        return Long.parseLong(value);
    }
    private static Instant httpDate(String value) {
        return java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }
}
