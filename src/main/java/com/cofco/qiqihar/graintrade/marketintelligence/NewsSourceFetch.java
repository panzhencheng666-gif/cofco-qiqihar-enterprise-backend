package com.cofco.qiqihar.graintrade.marketintelligence;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Predicate;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

/** Bounded fetch of approved targets only. Does not grant rights or verify article content. */
final class NewsSourceFetch {
    interface Sender { NewsPinnedHttp.Response send(NewsPublicTarget.Target target, Duration remaining) throws IOException; }
    record Result(URI finalUri, byte[] body, String reason, int status, String contentType, Map<String,String> headers) {
        Result { headers=Map.copyOf(headers); }
        Result(URI finalUri,byte[] body,String reason,int status,String contentType) {
            this(finalUri,body,reason,status,contentType,Map.of());
        }
        Result(URI finalUri, byte[] body, String reason) { this(finalUri,body,reason,0,null); }
    }
    Result fetchRobots(URI article) {
        var normalized=article==null?null:NewsSearchResults.candidateUri(article.toString());
        if(normalized==null) return failed(article,"INVALID_URL");
        return fetch(normalized.resolve("/robots.txt"),true);
    }
    // No queue: stuck native DNS cannot create unlimited workers or queued jobs.
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,
        new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("news-source-fetch-",0).factory());
    private final Predicate<URI> permitted;
    private final NewsPublicTarget.Resolver resolver;
    private final Sender sender;
    private final Duration timeout;
    private final boolean followRedirects;

    NewsSourceFetch(Predicate<URI> permitted) {
        this(permitted,InetAddress::getAllByName,NewsPinnedHttp::send,Duration.ofSeconds(15));
    }
    NewsSourceFetch(Predicate<URI> permitted, NewsPublicTarget.Resolver resolver, Sender sender, Duration timeout) {
        this(permitted,resolver,sender,timeout,true);
    }
    NewsSourceFetch(Predicate<URI> permitted, NewsPublicTarget.Resolver resolver, Sender sender,
                    Duration timeout, boolean followRedirects) {
        this.permitted=Objects.requireNonNull(permitted);
        this.resolver=Objects.requireNonNull(resolver);
        this.sender=Objects.requireNonNull(sender);
        this.timeout=Objects.requireNonNull(timeout);
        this.followRedirects=followRedirects;
        if(timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(15))>0)
            throw new IllegalArgumentException("Invalid fetch timeout");
    }
    Result fetch(URI uri) {
        return fetch(uri,false);
    }
    private Result fetch(URI uri,boolean robots) {
        long expires=System.nanoTime()+timeout.toNanos();
        Future<Result> future;
        try { future=WORKERS.submit(() -> fetchWithinDeadline(uri,expires,robots)); }
        catch(RejectedExecutionException busy) { return failed(uri,"FETCH_BUSY"); }
        try { return future.get(timeout.toNanos(),TimeUnit.NANOSECONDS); }
        catch(TimeoutException failure) { return failed(uri,"FETCH_TIMEOUT"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt();return failed(uri,"FETCH_INTERRUPTED"); }
        catch(ExecutionException failure) { return failed(uri,"FETCH_FAILED"); }
        finally { future.cancel(true); }
    }
    private Result fetchWithinDeadline(URI original,long expires,boolean robots) throws IOException {
        URI current=original==null?null:NewsSearchResults.candidateUri(original.toString());
        if(current==null) return failed(original,"INVALID_URL");
        Set<URI> visited=new HashSet<>();
        for(int redirects=0; ; redirects++) {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>=expires)
                return failed(current,"FETCH_TIMEOUT");
            if(!permitted.test(current)) return failed(current,"SOURCE_NOT_APPROVED");
            visited.add(current);
            var target=NewsPublicTarget.resolve(current,resolver);
            long remaining=expires-System.nanoTime();
            if(Thread.currentThread().isInterrupted() || remaining<1_000_000)
                return failed(current,"FETCH_TIMEOUT");
            var response=sender.send(target,Duration.ofNanos(remaining));
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>=expires)
                return failed(current,"FETCH_TIMEOUT");
            int status=response.status();
            if(Set.of(301,302,303,307,308).contains(status)) {
                if(!followRedirects) return new Result(current,new byte[0],
                    robots?"ROBOTS_FETCHED":"HTTP_"+status,status,response.contentType(),response.headers());
                if(redirects>=5) return failed(current,"REDIRECT_LIMIT");
                try {
                    String location=response.location();
                    if(location==null || location.isBlank() || location.length()>2048)
                        return failed(current,"REDIRECT_REJECTED");
                    URI next=NewsSearchResults.candidateUri(current.resolve(location).toString());
                    if(next==null || visited.contains(next)) return failed(current,"REDIRECT_REJECTED");
                    current=next;
                    continue; // Policy AND DNS checked again before next network call.
                } catch(IllegalArgumentException invalid) { return failed(current,"REDIRECT_REJECTED"); }
            }
            if(robots) {
                if(response.body()==null || response.body().length>512000)
                    return failed(current,"UNSUPPORTED_CONTENT");
                return new Result(current,status==200?response.body():new byte[0],
                    "ROBOTS_FETCHED",status,response.contentType(),response.headers());
            }
            if(status!=200) return new Result(current,new byte[0],"HTTP_"+status,
                status,response.contentType(),response.headers());
            String type=response.contentType()==null?"":response.contentType().split(";",2)[0].strip().toLowerCase(Locale.ROOT);
            if(!Set.of("text/html","application/xhtml+xml","application/xml","text/xml",
                "application/rss+xml","application/atom+xml","text/plain").contains(type)
                || response.body()==null || response.body().length==0 || response.body().length>2_000_000)
                return failed(current,"UNSUPPORTED_CONTENT");
            return new Result(current,response.body(),"FETCHED",status,response.contentType(),response.headers());
        }
    }
    private static Result failed(URI uri,String reason) { return new Result(uri,new byte[0],reason); }
}
