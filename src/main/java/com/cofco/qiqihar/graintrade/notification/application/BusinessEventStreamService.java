package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class BusinessEventStreamService implements SmartLifecycle {
    private static final int BATCH_SIZE = 100;
    private static final Duration QUERY_INTERVAL = Duration.ofSeconds(1);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final BusinessNotificationRepository repository;
    private final AccessControl accessControl;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<SseEmitter> activeEmitters = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public BusinessEventStreamService(
            BusinessNotificationRepository repository, AccessControl accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    public SseEmitter stream(long afterSequence) {
        if (afterSequence < 0) {
            throw invalidCursor();
        }
        if (!running) {
            throw new IllegalStateException("Business event stream is not running");
        }
        SecurityPrincipal principal = accessControl.requireAuthenticated();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        activeEmitters.add(emitter);
        emitter.onCompletion(() -> closeConnection(emitter, closed));
        emitter.onTimeout(() -> closeConnection(emitter, closed));
        emitter.onError(ignored -> closeConnection(emitter, closed));
        connections.submit(() -> publish(emitter, closed, principal.subjectId(), scope, afterSequence));
        return emitter;
    }

    private static ClientRequestException invalidCursor() {
        return new ClientRequestException(
                "INVALID_EVENT_CURSOR", "Realtime update cursor is invalid");
    }

    private void publish(
            SseEmitter emitter,
            AtomicBoolean closed,
            String subjectId,
            AuthorizedReadScope scope,
            long initialCursor) {
        long cursor = initialCursor;
        long lastHeartbeatNanos = System.nanoTime();
        try {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                List<BusinessNotification> events =
                        repository.findVisibleAfter(scope, subjectId, cursor, BATCH_SIZE);
                for (BusinessNotification event : events) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name("business-change")
                            .data(event));
                    cursor = event.sequence();
                }
                long now = System.nanoTime();
                if (events.isEmpty()
                        && now - lastHeartbeatNanos >= HEARTBEAT_INTERVAL.toNanos()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeatNanos = now;
                }
                Thread.sleep(QUERY_INTERVAL);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException disconnected) {
            closed.set(true);
        } finally {
            activeEmitters.remove(emitter);
            if (!closed.getAndSet(true)) {
                emitter.complete();
            }
        }
    }

    private void closeConnection(SseEmitter emitter, AtomicBoolean closed) {
        closed.set(true);
        activeEmitters.remove(emitter);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        activeEmitters.forEach(SseEmitter::complete);
        activeEmitters.clear();
        connections.shutdownNow();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @PreDestroy
    void destroy() {
        stop();
    }
}
