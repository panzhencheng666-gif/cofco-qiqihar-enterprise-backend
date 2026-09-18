package com.cofco.qiqihar.graintrade.overview.application;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qiqihar.public-situation.enabled", matchIfMissing = true)
public class PublicEventRefreshWorker {
    static final String SOURCE = "NASA_EONET";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PublicEventRefreshWorker.class);
    private final PublicEventFeed feed;
    private final OperationalSituationRepository repository;

    public PublicEventRefreshWorker(PublicEventFeed feed, OperationalSituationRepository repository) {
        this.feed = feed;
        this.repository = repository;
    }

    @Scheduled(
            initialDelayString = "${qiqihar.public-situation.initial-delay:8s}",
            fixedDelayString = "${qiqihar.public-situation.refresh-delay:30m}")
    public void refresh() {
        Instant attemptedAt = Instant.now();
        try {
            var events = feed.fetch();
            repository.replaceEvents(SOURCE, events, attemptedAt);
            LOG.info("Public situation refresh completed [source={}, records={}]", SOURCE, events.size());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            repository.recordFailure(SOURCE, attemptedAt, "刷新线程已中断");
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            repository.recordFailure(SOURCE, attemptedAt, message);
            LOG.warn("Public situation refresh failed [source={}, reason={}]", SOURCE, message);
        }
    }
}
