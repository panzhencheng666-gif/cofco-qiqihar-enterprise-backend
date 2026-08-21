package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class BusinessEventStreamService implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessEventStreamService.class);
    private static final int BATCH_SIZE = 100;
    private static final Duration QUERY_INTERVAL = Duration.ofSeconds(1);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final BusinessEventDeliveryService deliveries;
    private final AccessControl accessControl;
    private final SecurityPrincipalRepository principals;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<SseEmitter, StreamConnection> activeConnections =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final String instanceId = "sse-" + UUID.randomUUID();
    private volatile boolean running;

    public BusinessEventStreamService(
            BusinessEventDeliveryService deliveries,
            AccessControl accessControl,
            SecurityPrincipalRepository principals) {
        this.deliveries = deliveries;
        this.accessControl = accessControl;
        this.principals = principals;
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
        String consumerId = streamConsumerId(principal.subjectId());
        StreamConnection connection = new StreamConnection(emitter, consumerId, afterSequence);
        activeConnections.put(emitter, connection);
        emitter.onCompletion(() -> closeConnection(connection, ConsumerRetirementReason.CLIENT_COMPLETED));
        emitter.onTimeout(() -> closeConnection(connection, ConsumerRetirementReason.CLIENT_TIMEOUT));
        emitter.onError(ignored -> closeConnection(connection, ConsumerRetirementReason.CLIENT_ERROR));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException unableToOpen) {
            closeConnection(connection, ConsumerRetirementReason.CLIENT_ERROR);
            emitter.completeWithError(unableToOpen);
            throw new IllegalStateException("Unable to open business event stream", unableToOpen);
        }
        connections.submit(() -> publish(connection, principal.subjectId(), scope));
        return emitter;
    }

    private static ClientRequestException invalidCursor() {
        return new ClientRequestException(
                "INVALID_EVENT_CURSOR", "Realtime update cursor is invalid");
    }

    private void publish(
            StreamConnection connection,
            String subjectId,
            AuthorizedReadScope initialScope) {
        long lastHeartbeatNanos = System.nanoTime();
        AuthorizedReadScope scope = initialScope;
        try {
            while (!connection.closed().get() && !Thread.currentThread().isInterrupted()) {
                SecurityPrincipal current = principals.findEnabled(subjectId).orElse(null);
                if (current == null || !current.permits("BUSINESS_READ")) {
                    connection.reason().compareAndSet(
                            ConsumerRetirementReason.STREAM_ENDED,
                            ConsumerRetirementReason.AUTHORIZATION_REVOKED);
                    break;
                }
                scope = new AuthorizedReadScope(subjectId, current.regionCodes());
                var result = deliveries.drain(connection.consumerId(), instanceId, scope, subjectId,
                        connection.cursor().get(), BATCH_SIZE,
                        event -> connection.emitter().send(SseEmitter.event()
                                .id(Long.toString(event.sequence()))
                                .name("business-change")
                                .data(event)));
                connection.cursor().set(result.resumeSequence());
                long now = System.nanoTime();
                if (result.deliveredCount() == 0 && result.failedCount() == 0
                        && now - lastHeartbeatNanos >= HEARTBEAT_INTERVAL.toNanos()) {
                    connection.emitter().send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeatNanos = now;
                }
                Duration delay = result.retryAfter().compareTo(QUERY_INTERVAL) > 0
                        ? result.retryAfter() : QUERY_INTERVAL;
                Thread.sleep(delay);
            }
        } catch (InterruptedException interrupted) {
            connection.reason().compareAndSet(
                    ConsumerRetirementReason.STREAM_ENDED,
                    ConsumerRetirementReason.APPLICATION_STOP);
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException disconnected) {
            connection.reason().compareAndSet(
                    ConsumerRetirementReason.STREAM_ENDED,
                    ConsumerRetirementReason.DELIVERY_DISCONNECTED);
            connection.closed().set(true);
        } finally {
            activeConnections.remove(connection.emitter(), connection);
            try {
                deliveries.retireConsumer(
                        connection.consumerId(), instanceId, connection.cursor().get(),
                        connection.reason().get());
            } catch (RuntimeException retirementFailure) {
                LOGGER.warn("Unable to persist business event consumer retirement for {}",
                        connection.consumerId(), retirementFailure);
            }
            if (!connection.closed().getAndSet(true)) {
                connection.emitter().complete();
            }
        }
    }

    private static String streamConsumerId(String subjectId) {
        return "sse:" + subjectId + ":" + UUID.randomUUID();
    }

    private void closeConnection(
            StreamConnection connection,
            ConsumerRetirementReason reason) {
        connection.reason().compareAndSet(ConsumerRetirementReason.STREAM_ENDED, reason);
        connection.closed().set(true);
        activeConnections.remove(connection.emitter(), connection);
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
        activeConnections.values().forEach(connection -> {
            connection.reason().set(ConsumerRetirementReason.APPLICATION_STOP);
            connection.closed().set(true);
            connection.emitter().complete();
        });
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

    private record StreamConnection(
            SseEmitter emitter,
            String consumerId,
            AtomicBoolean closed,
            AtomicReference<ConsumerRetirementReason> reason,
            AtomicLong cursor) {

        private StreamConnection(SseEmitter emitter, String consumerId, long initialCursor) {
            this(emitter, consumerId, new AtomicBoolean(false),
                    new AtomicReference<>(ConsumerRetirementReason.STREAM_ENDED),
                    new AtomicLong(initialCursor));
        }
    }
}
