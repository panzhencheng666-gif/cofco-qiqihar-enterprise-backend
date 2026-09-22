package com.cofco.qiqihar.riskintelligence.experttraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpertTrainingNodeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private final ExpertTrainingRepository repository = mock(ExpertTrainingRepository.class);
    private final ExpertTrainingNodeService service = new ExpertTrainingNodeService(repository,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(35));

    @Test
    void claimUsesConfiguredLeaseAndHeartbeatReturnsCancellationSignal() {
        when(repository.claim("node-a", NOW, Duration.ofMinutes(35))).thenReturn(Optional.empty());
        assertThat(service.claim("node-a")).isEmpty();

        UUID taskId = UUID.randomUUID();
        var heartbeat = new ExpertTrainingRepository.Heartbeat(true, NOW.plusSeconds(2100));
        when(repository.heartbeat(taskId, "node-a", NOW, Duration.ofMinutes(35)))
                .thenReturn(Optional.of(heartbeat));
        assertThat(service.heartbeat(taskId, "node-a")).contains(heartbeat);
    }

    @Test
    void rejectsDecreasingProgressBeforeRepositoryMutation() {
        UUID taskId = UUID.randomUUID();
        when(repository.progress(taskId, "node-a", 61, "PACKAGING", NOW)).thenReturn(true);
        assertThat(service.progress(taskId, "node-a", 61, "PACKAGING")).isTrue();
        verify(repository).progress(taskId, "node-a", 61, "PACKAGING", NOW);
    }
}
