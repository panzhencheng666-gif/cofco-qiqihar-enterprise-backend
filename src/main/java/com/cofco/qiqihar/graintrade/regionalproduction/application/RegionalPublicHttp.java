package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds both the response body and the complete network operation, including chunked bodies. */
final class RegionalPublicHttp {
    private RegionalPublicHttp() {}

    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maximumBytes)
            throws IOException, InterruptedException {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        var response = client.sendAsync(request, info -> new LimitedBodySubscriber(maximumBytes));
        try {
            return response.get(request.timeout().orElse(Duration.ofSeconds(20)).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            response.cancel(true);
            throw new HttpTimeoutException("来源下载超时，已停止读取");
        } catch (InterruptedException exception) {
            response.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException failure) throw failure;
            throw new IOException("来源下载失败", exception.getCause());
        }
    }

    static HttpResponse<byte[]> sendFollowingPublicHttps(HttpClient client, HttpRequest request,
            int maximumBytes, Predicate<URI> publicUri) throws IOException, InterruptedException {
        URI original = request.uri();
        URI current = original;
        Set<URI> visited = new LinkedHashSet<>();
        visited.add(current);
        for (int redirects = 0; ; redirects++) {
            var currentRequest = redirectedRequest(request, current);
            var response = send(client, currentRequest, maximumBytes);
            if (!isRedirect(response.statusCode())) {
                requireUsableRedirectResult(original, current, response.body());
                return response;
            }
            if (redirects >= 5) throw new IOException("来源重定向超过5跳");
            current = redirectTarget(current, response.statusCode(), response.headers(), visited, publicUri);
            visited.add(current);
        }
    }

    static URI redirectTarget(URI current, int status, HttpHeaders headers, Set<URI> visited,
            Predicate<URI> publicUri) throws IOException {
        if (!isRedirect(status)) throw new IOException("非重定向响应不能解析Location");
        String location = headers.firstValue("Location").orElseThrow(() -> new IOException("来源重定向缺少Location"));
        URI target;
        try { target = current.resolve(location).normalize(); }
        catch (IllegalArgumentException exception) { throw new IOException("来源重定向Location无效", exception); }
        if (!"https".equalsIgnoreCase(target.getScheme())) throw new IOException("来源重定向只允许HTTPS");
        if (visited.contains(target)) throw new IOException("来源重定向形成循环");
        if (!publicUri.test(target)) throw new IOException("来源重定向目标不是可访问的HTTPS公网地址");
        return target;
    }

    static void requireUsableRedirectResult(URI original, URI target, byte[] body) throws IOException {
        if (!original.equals(target)) {
            String path = target.getPath() == null ? "" : target.getPath();
            String originalPath = original.getPath() == null ? "" : original.getPath();
            if ((path.isBlank() || "/".equals(path)) && !(originalPath.isBlank() || "/".equals(originalPath)))
                throw new IOException("来源重定向至站点首页，不能作为原文核验成功");
            String destination = (path + "?" + target.getQuery()).toLowerCase(Locale.ROOT);
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (destination.matches(".*(?:captcha|challenge|verification|verify|waf).*")
                    || text.matches("(?s).*(?:人机验证|访问验证|安全验证|完成验证后继续访问|humanmachineverification).*"))
                throw new IOException("来源重定向进入访问验证页面，不能作为原文核验成功");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static HttpRequest redirectedRequest(HttpRequest original, URI target) {
        var builder = HttpRequest.newBuilder(target).GET();
        original.timeout().ifPresent(builder::timeout);
        for (String name : List.of("Accept", "User-Agent"))
            original.headers().allValues(name).forEach(value -> builder.header(name, value));
        return builder.build();
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maximumBytes;
        private Flow.Subscription subscription;
        private long received;
        private boolean completed;

        LimitedBodySubscriber(int maximumBytes) { this.maximumBytes = maximumBytes; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (completed) return;
            for (var buffer : buffers) {
                received += buffer.remaining();
                if (received > maximumBytes) {
                    completed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("来源内容超过读取上限（" + maximumBytes + "字节），已停止下载"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) {
            if (!completed) { completed = true; delegate.onError(failure); }
        }
        @Override public void onComplete() {
            if (!completed) { completed = true; delegate.onComplete(); }
        }
    }
}
