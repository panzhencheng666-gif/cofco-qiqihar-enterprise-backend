package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SourceFactIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

    @Test
    void persistsOneImmutableSnapshotPerSourceVersion() {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        AtomicReference<SourceFactSnapshot> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return true;
        });
        SourceFactIngestionService service = service(repository);
        SourceFact fact = fact("v1", Map.of("actionCode", "SUBMITTED"));

        SourceFactReceipt first = service.ingest(fact);
        SourceFactReceipt repeated = service.ingest(fact);

        assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(repeated.payloadSha256()).isEqualTo(first.payloadSha256());
        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        verify(repository, times(1)).insert(any());
    }

    @Test
    void rejectsSameSourceVersionWithDifferentContent() {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        AtomicReference<SourceFactSnapshot> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return true;
        });
        SourceFactIngestionService service = service(repository);

        service.ingest(fact("v1", Map.of("value", 1)));

        assertThatThrownBy(() -> service.ingest(fact("v1", Map.of("value", 2))))
                .isInstanceOf(SourceVersionConflictException.class)
                .hasMessageContaining("enterprise/BUSINESS_AUDIT/event-1/v1");
    }

    @Test
    void hashesNestedMapsIndependentlyOfInsertionOrder() {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        AtomicReference<SourceFactSnapshot> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return true;
        });
        SourceFactIngestionService service = service(repository);

        SourceFactReceipt first = service.ingest(fact("v1", Map.of(
                "outer", Map.of("b", 2, "a", 1), "state", "ACTIVE")));
        SourceFactReceipt second = service.ingest(fact("v1", Map.of(
                "state", "ACTIVE", "outer", Map.of("a", 1, "b", 2))));

        assertThat(second.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(second.payloadSha256()).isEqualTo(first.payloadSha256());
    }

    private static SourceFactIngestionService service(SourceFactRepository repository) {
        return new SourceFactIngestionService(
                repository, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SourceFact fact(String version, Map<String, Object> payload) {
        return new SourceFact(
                "enterprise",
                "BUSINESS_AUDIT",
                "event-1",
                version,
                Instant.parse("2026-09-21T01:00:00Z"),
                payload);
    }
}
