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
    void missingRegionIsRejectedBeforeRepositoryAccess() {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        assertThatThrownBy(() -> service(repository).ingest(fact("v1", Map.of("value", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regionCode");
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"*", "230221%", "", " 230221", "abc"})
    void malformedRegionsAreRejected(String region) {
        assertThatThrownBy(() -> fact("v1", Map.of("regionCode", region)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regionCode");
    }

    @Test void numericRegionIsNotCoercedToString() {
        assertThatThrownBy(() -> fact("v1", Map.of("regionCode", 230221)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regionCode");
    }

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
        SourceFact fact = fact("v1", Map.of("regionCode", "230221", "actionCode", "SUBMITTED"));

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

        service.ingest(fact("v1", Map.of("regionCode", "230221", "value", 1)));

        assertThatThrownBy(() -> service.ingest(fact("v1", Map.of("regionCode", "230221", "value", 2))))
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
                "regionCode", "230221", "outer", Map.of("b", 2, "a", 1), "state", "ACTIVE")));
        SourceFactReceipt second = service.ingest(fact("v1", Map.of(
                "regionCode", "230221", "state", "ACTIVE", "outer", Map.of("a", 1, "b", 2))));

        assertThat(second.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(second.payloadSha256()).isEqualTo(first.payloadSha256());
    }

    private static SourceFactIngestionService service(SourceFactRepository repository) {
        return configuredService(repository, "230221,230222");
    }

    @Test void defaultDenyAndExactRegionAllowlistRunBeforeAnyStorageAccess() {
        for (String configured : new String[]{"", "230222", "230221001"}) {
            SourceFactRepository repository = mock(SourceFactRepository.class);
            assertThatThrownBy(() -> configuredService(repository, configured)
                    .ingest(fact("v1", Map.of("regionCode", "230221"))))
                    .isInstanceOf(com.cofco.qiqihar.riskintelligence.security.RiskApiException.class)
                    .hasMessageContaining("区域");
            org.mockito.Mockito.verifyNoInteractions(repository);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"*", "230221,*", "230221,", "230221,abc"})
    void malformedAllowlistCannotStart(String configured) {
        assertThatThrownBy(() -> configuredService(mock(SourceFactRepository.class), configured))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void changingRegionChangesImmutableHash() {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        AtomicReference<SourceFactSnapshot> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return true;
        });
        var service = service(repository);
        service.ingest(fact("v1", Map.of("regionCode", "230221")));
        assertThatThrownBy(() -> service.ingest(fact("v1", Map.of("regionCode", "230222"))))
                .isInstanceOf(SourceVersionConflictException.class);
    }

    private static SourceFactIngestionService configuredService(SourceFactRepository repository, String regions) {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "isolated-test", Map.of("qiqihar.risk.ingestion-allowed-regions", regions)));
            context.registerBean(SourceFactRepository.class, () -> repository);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
            context.registerBean(SourceFactIngestionService.class);
            context.refresh();
            return context.getBean(SourceFactIngestionService.class);
        }
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
