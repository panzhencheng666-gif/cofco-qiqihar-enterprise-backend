package com.cofco.qiqihar.graintrade.risk.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RiskTrainingWorkerTest {

    @Test
    void remoteNodeModeLeavesDomainLlmJobsForTheMacWorker() {
        RiskTrainingOrchestrator orchestrator=mock(RiskTrainingOrchestrator.class);
        RiskModelLifecycleOrchestrator lifecycle=mock(RiskModelLifecycleOrchestrator.class);
        when(orchestrator.processNext(anyString(),eq("RISK_CLASSIFIER"))).thenReturn(false);
        var worker=new RiskTrainingWorker(orchestrator,lifecycle,true);

        worker.processPendingExecutions();

        verify(orchestrator).processNext(anyString(),eq("RISK_CLASSIFIER"));
        verify(orchestrator,never()).processNext(anyString());
    }

    @Test
    void embeddedModeKeepsTheExistingAllModelBehavior() {
        RiskTrainingOrchestrator orchestrator=mock(RiskTrainingOrchestrator.class);
        RiskModelLifecycleOrchestrator lifecycle=mock(RiskModelLifecycleOrchestrator.class);
        when(orchestrator.processNext(anyString())).thenReturn(false);
        var worker=new RiskTrainingWorker(orchestrator,lifecycle,false);

        worker.processPendingExecutions();

        verify(orchestrator).processNext(anyString());
        verify(orchestrator,never()).processNext(anyString(),anyString());
    }
}
