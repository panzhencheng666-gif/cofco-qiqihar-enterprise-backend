package com.cofco.qiqihar.graintrade.marketintelligence;
import java.io.IOException;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** One pinned HTTPS hop. Caller must check source policy and validate every redirect. */
final class NewsPinnedHttp {
    record Response(int status, String location, String contentType, byte[] body, java.util.Map<String,String> headers) {
        Response { headers=java.util.Map.copyOf(headers); }
        Response(int status,String location,String contentType,byte[] body) {
            this(status,location,contentType,body,java.util.Map.of());
        }
    }
    static Response send(NewsPublicTarget.Target target) throws IOException {
        return send(target, Duration.ofSeconds(15));
    }
    static Response send(NewsPublicTarget.Target target, Duration timeout) throws IOException {
        // Revalidate caller-supplied snapshots, without performing another DNS lookup.
        var checked = NewsPublicTarget.resolve(target.uri(), host -> target.addresses().toArray(InetAddress[]::new));
        try { return transport(checked, SSLContext.getDefault(), timeout, 2_000_000); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IOException("News HTTPS request failed"); }
    }
    /** Trusted internal transport seam; public-source callers must use send(), not bypass snapshot validation. */
    static Response transport(NewsPublicTarget.Target target, SSLContext tls, Duration deadline, int limit) throws IOException {
        if (!"https".equals(target.uri().getScheme()) || deadline.toMillis() < 1
                || deadline.compareTo(Duration.ofSeconds(15)) > 0 || limit < 1 || limit > 2_000_000)
            throw new IllegalArgumentException("Invalid news transport limits");
        var host = target.uri().getHost();
        var resolver = new DnsResolver() {
            public InetAddress[] resolve(String requested) throws UnknownHostException {
                if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Unpinned host");
                return target.addresses().toArray(InetAddress[]::new);
            }
            public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                resolve(requested); return host;
            }
        };
        var timeout = Timeout.ofMilliseconds(deadline.toMillis());
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
            .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(tls).buildClassic())
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
            .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        var client = HttpClients.custom().setConnectionManager(manager)
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
            .disableContentCompression().disableAuthCaching()
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout)
                .setConnectionRequestTimeout(timeout).build()).build();
        var request = new HttpGet(target.uri());
        request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml,text/plain");
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("User-Agent", "QiliangNewsDiscovery/1.0");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> client.execute(request, response -> {
            var encoding = response.getFirstHeader("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue()))
                throw new IOException("Encoded body rejected");
            var entity = response.getEntity();
            byte[] body = new byte[0];
            if (entity != null) {
                if (entity.getContentLength() > limit) throw new IOException("Body too large");
                try (var input = entity.getContent()) {
                    body = input.readNBytes(limit + 1);
                    if (body.length > limit) throw new IOException("Body too large");
                }
            }
            var location = response.getFirstHeader("Location");
            var type = response.getFirstHeader("Content-Type");
            var headers=new java.util.HashMap<String,String>();
            for(String name:java.util.List.of("cache-control","expires","retry-after","age","date")) {
                var values=response.getHeaders(name);
                if(values.length>0) headers.put(name,java.util.Arrays.stream(values)
                    .map(org.apache.hc.core5.http.Header::getValue).collect(java.util.stream.Collectors.joining(",")));
            }
            return new Response(response.getCode(), location == null ? null : location.getValue(),
                type == null ? null : type.getValue(), body, headers);
        }));
        try {
            return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("News HTTPS request failed");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("News HTTPS request failed");
        } finally {
            request.cancel();
            future.cancel(true);
            client.close(CloseMode.IMMEDIATE);
            executor.shutdownNow();
        }
    }
}
