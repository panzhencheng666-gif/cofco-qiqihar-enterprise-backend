package com.cofco.qiqihar.graintrade.overview.infrastructure;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ClientQueryCancellation {
    private final JdbcClient sessionJdbc;

    public ClientQueryCancellation(@Qualifier("sessionJdbcClient") JdbcClient sessionJdbc) {
        this.sessionJdbc = sessionJdbc;
    }

    public static ClientQueryCancellation inactive() {
        return new ClientQueryCancellation(null);
    }

    public void arm(JdbcClient requestJdbc) {
        if (sessionJdbc == null || requestJdbc == null) return;
        ServletRequestAttributes attributes =
                RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                        ? servlet : null;
        if (attributes == null || !attributes.getRequest().isAsyncStarted()) return;
        Integer backendPid = requestJdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
        AtomicBoolean cancelled = new AtomicBoolean();
        attributes.getRequest().getAsyncContext().addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) {}
            @Override public void onTimeout(AsyncEvent event) { cancel(backendPid, cancelled); }
            @Override public void onError(AsyncEvent event) { cancel(backendPid, cancelled); }
            @Override public void onStartAsync(AsyncEvent event) {}
        });
    }

    private void cancel(int backendPid, AtomicBoolean cancelled) {
        if (!cancelled.compareAndSet(false, true)) return;
        try {
            sessionJdbc.sql("SELECT pg_cancel_backend(:pid)").param("pid", backendPid)
                    .query(Boolean.class).optional();
        } catch (RuntimeException ignored) {
            // The request is already gone. A failed cancel must not replace the original error.
        }
    }
}
