package com.cofco.qiqihar.riskintelligence.experttraining;

import java.util.Set;

final class ExpertTrainingStateMachine {
    private static final Set<String> TERMINAL = Set.of("CANCELLED", "SUCCEEDED", "FAILED");
    private static final Set<String> PHASES = Set.of(
            "PREPARING", "LOCAL_TRAINING", "PACKAGING", "UPLOADING", "COMPLETING");

    private ExpertTrainingStateMachine() { }

    static String cancel(String status) {
        if ("QUEUED".equals(status)) return "CANCELLED";
        if ("RUNNING".equals(status)) return "CANCEL_REQUESTED";
        if (TERMINAL.contains(status) || "CANCEL_REQUESTED".equals(status)) return status;
        throw new IllegalArgumentException("Unknown expert task status");
    }

    static Expiry expiredLease(String status, int attempt) {
        if ("CANCEL_REQUESTED".equals(status)) return new Expiry("CANCELLED", null, false);
        if (!"RUNNING".equals(status) || attempt < 1) throw new IllegalArgumentException("Invalid expired lease");
        return attempt >= 3 ? new Expiry("FAILED", "OFFLINE_RETRY_EXHAUSTED", false)
                : new Expiry("QUEUED", null, true);
    }

    static int requireProgress(int current, int requested, String phase) {
        if (requested < current || requested < 0 || requested > 100 || !PHASES.contains(phase)) {
            throw new IllegalArgumentException("Invalid expert training progress");
        }
        return requested;
    }

    record Expiry(String status, String failureCode, boolean resetAttemptState) { }
}
