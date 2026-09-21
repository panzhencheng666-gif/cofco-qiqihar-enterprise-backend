package com.cofco.qiqihar.graintrade.risk.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RiskLlmModelProvisionerTest {
    private static final Instant NOW=Instant.parse("2026-09-21T14:00:00Z");
    private static final String BASE="mlx-community/Qwen3.8-27B-4bit";

    @Test
    void provisionsDedicatedModelWhenRemoteMacNodeIsEnabled() {
        RiskTrainingRepository repository=Mockito.mock(RiskTrainingRepository.class);
        var provisioner=new RiskLlmModelProvisioner(repository,
                Clock.fixed(NOW,ZoneOffset.UTC),"",BASE,true,"x".repeat(32));

        provisioner.run(null);

        verify(repository).configureExternalLlm(BASE,NOW);
    }

    @Test
    void doesNotProvisionWithoutAnyTrainerTransport() {
        RiskTrainingRepository repository=Mockito.mock(RiskTrainingRepository.class);
        var provisioner=new RiskLlmModelProvisioner(repository,
                Clock.fixed(NOW,ZoneOffset.UTC),"",BASE,false,"x".repeat(32));

        provisioner.run(null);

        verify(repository,never()).configureExternalLlm(BASE,NOW);
    }

    @Test
    void doesNotProvisionRemoteNodeWithoutStrongSharedToken() {
        RiskTrainingRepository repository=Mockito.mock(RiskTrainingRepository.class);
        var provisioner=new RiskLlmModelProvisioner(repository,
                Clock.fixed(NOW,ZoneOffset.UTC),"",BASE,true,"short-token");

        provisioner.run(null);

        verify(repository,never()).configureExternalLlm(BASE,NOW);
    }
}
