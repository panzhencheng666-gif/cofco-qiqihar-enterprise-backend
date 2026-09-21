package com.cofco.qiqihar.graintrade.risk.application;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="qiqihar.risk.training.enabled",matchIfMissing=true)
public class RiskTrainingWorker {
    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(RiskTrainingWorker.class);
    private final RiskTrainingOrchestrator orchestrator;
    private final String workerId=ManagementFactory.getRuntimeMXBean().getName()+":"+UUID.randomUUID();

    public RiskTrainingWorker(RiskTrainingOrchestrator orchestrator) {
        this.orchestrator=orchestrator;
    }

    @Scheduled(initialDelayString="5s",
            fixedDelayString="${qiqihar.risk.training.schedule-reconcile-delay:1m}",
            scheduler="riskTrainingScheduler")
    public void enqueueDueDailyExecutions() {
        int enqueued=orchestrator.enqueueDueDailyExecutions();
        if (enqueued>0) LOG.info("Queued due daily risk model training [count={}]",enqueued);
    }

    @Scheduled(initialDelayString="8s",
            fixedDelayString="${qiqihar.risk.training.poll-delay:10s}",
            scheduler="riskTrainingScheduler")
    public void processPendingExecutions() {
        for (int processed=0;processed<4;processed++) {
            if (Thread.currentThread().isInterrupted() || !orchestrator.processNext(workerId)) return;
        }
    }
}
