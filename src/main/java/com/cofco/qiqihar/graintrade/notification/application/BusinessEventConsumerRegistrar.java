package com.cofco.qiqihar.graintrade.notification.application;

public interface BusinessEventConsumerRegistrar {
    boolean ensureCheckpoint(
            String consumerId,
            String instanceId,
            long initialSequence,
            String authorizationSubjectId);
}
