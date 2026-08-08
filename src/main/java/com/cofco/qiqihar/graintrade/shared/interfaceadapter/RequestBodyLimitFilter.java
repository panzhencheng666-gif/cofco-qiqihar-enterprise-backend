package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Enforces a raw-byte limit while MVC streams a JSON request body. */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1)
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final String ERROR_CODE = "REQUEST_BODY_TOO_LARGE";

    private final long maximumBytes;
    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(
            @Value("${qiqihar.request-limits.json-body-bytes:1048576}") long maximumBytes,
            ObjectMapper objectMapper) {
        if (maximumBytes < 1) throw new IllegalArgumentException("JSON body limit must be positive");
        this.maximumBytes = maximumBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!applicationPath(request).startsWith("/api/")) return true;
        String contentType = request.getContentType();
        if (contentType == null) return true;
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
            return !"application".equalsIgnoreCase(mediaType.getType())
                    || !("json".equals(subtype) || subtype.endsWith("+json"));
        } catch (InvalidMediaTypeException exception) {
            return true;
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) return servletPath;
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length()) : requestUri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (declaredLength(request) > maximumBytes) {
            writeTooLarge(response, request);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, response, maximumBytes), response);
        } catch (RequestBodyTooLargeException exception) {
            if (response.isCommitted()) throw exception;
            writeTooLarge(response, request);
        }
    }

    private static long declaredLength(HttpServletRequest request) {
        long servletLength = request.getContentLengthLong();
        String header = request.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (header == null) return servletLength;
        try {
            return Math.max(servletLength, Long.parseLong(header));
        } catch (NumberFormatException exception) {
            return servletLength;
        }
    }

    private void writeTooLarge(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(
                ERROR_CODE, "JSON request body exceeds the allowed byte limit", Map.of(), traceId(request)));
    }

    private void writeAsyncTooLarge(HttpServletResponse response, HttpServletRequest request) {
        synchronized (response) {
            if (response.isCommitted()) return;
            try {
                writeTooLarge(response, request);
            } catch (IOException | IllegalStateException ignored) {
                // The stream exception still aborts the async reader if the client disconnected or committed first.
            }
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value && !value.isBlank() ? value : UUID.randomUUID().toString();
    }

    public static final class RequestBodyTooLargeException extends IOException {
        RequestBodyTooLargeException() {
            super("JSON request body exceeds the allowed byte limit");
        }
    }

    private final class LimitedRequest extends HttpServletRequestWrapper {
        private final HttpServletResponse response;
        private final long maximumBytes;
        private final AtomicBoolean asyncLimitHandled = new AtomicBoolean();
        private ServletInputStream inputStream;

        private LimitedRequest(HttpServletRequest request, HttpServletResponse response, long maximumBytes) {
            super(request);
            this.response = response;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedInputStream(super.getInputStream(), maximumBytes, this::handleAsyncLimit);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public AsyncContext startAsync() throws IllegalStateException {
            return super.startAsync(this, response);
        }

        @Override
        public AsyncContext startAsync(ServletRequest request, ServletResponse response) throws IllegalStateException {
            return super.startAsync(this, this.response);
        }

        private void handleAsyncLimit() {
            if (!isAsyncStarted() || !asyncLimitHandled.compareAndSet(false, true)) return;
            AsyncContext asyncContext;
            try {
                asyncContext = getAsyncContext();
            } catch (IllegalStateException exception) {
                return;
            }
            writeAsyncTooLarge(response, this);
            try {
                if (isAsyncStarted()) asyncContext.complete();
            } catch (IllegalStateException ignored) {
                // Another async participant already completed the request.
            }
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maximumBytes;
        private final Runnable limitHandler;
        private long bytesRead;

        private LimitedInputStream(ServletInputStream delegate, long maximumBytes, Runnable limitHandler) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
            this.limitHandler = limitHandler;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) add(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) add(count);
            return count;
        }

        private void add(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                limitHandler.run();
                throw new RequestBodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
