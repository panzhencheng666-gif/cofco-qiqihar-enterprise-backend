package com.cofco.qiqihar.graintrade.risk.application;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="qiqihar.risk.training.enabled",matchIfMissing=true)
public class RiskTrainingWorker {
    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(RiskTrainingWorker.class);
    private final RiskTrainingOrchestrator orchestrator;
    private final RiskModelLifecycleOrchestrator lifecycle;
    private final boolean remoteNodeEnabled;
    private final String workerId=ManagementFactory.getRuntimeMXBean().getName()+":"+UUID.randomUUID();

    public RiskTrainingWorker(RiskTrainingOrchestrator orchestrator,
            RiskModelLifecycleOrchestrator lifecycle,
            @Value("${qiqihar.risk.training.remote-node.enabled:false}")
            boolean remoteNodeEnabled) {
        this.orchestrator=orchestrator;
        this.lifecycle=lifecycle;
        this.remoteNodeEnabled=remoteNodeEnabled;
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
            boolean completed=remoteNodeEnabled
                    ? orchestrator.processNext(workerId,"RISK_CLASSIFIER")
                    : orchestrator.processNext(workerId);
            if (Thread.currentThread().isInterrupted() || !completed) return;
        }
    }

    @Scheduled(initialDelayString="12s",
            fixedDelayString="${qiqihar.risk.training.lifecycle-delay:30s}",
            scheduler="riskTrainingScheduler")
    public void processAutomaticLifecycle() {
        int changed=lifecycle.process();
        if (changed>0) LOG.info("Advanced automatic risk model lifecycle [changes={}]",changed);
    }
}
