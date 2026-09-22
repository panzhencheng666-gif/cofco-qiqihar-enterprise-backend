package com.cofco.qiqihar.riskintelligence.experttraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExpertTrainingStateMachineTest {
    @Test
    void queuedAndRunningCancellationUseDistinctIdempotentTransitions() {
        assertThat(ExpertTrainingStateMachine.cancel("QUEUED")).isEqualTo("CANCELLED");
        assertThat(ExpertTrainingStateMachine.cancel("RUNNING")).isEqualTo("CANCEL_REQUESTED");
        assertThat(ExpertTrainingStateMachine.cancel("CANCEL_REQUESTED"))
                .isEqualTo("CANCEL_REQUESTED");
        assertThat(ExpertTrainingStateMachine.cancel("SUCCEEDED")).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredLeaseRecoversTwiceThenFailsAndCancellationWins() {
        assertThat(ExpertTrainingStateMachine.expiredLease("RUNNING", 1))
                .isEqualTo(new ExpertTrainingStateMachine.Expiry("QUEUED", null, true));
        assertThat(ExpertTrainingStateMachine.expiredLease("RUNNING", 2))
                .isEqualTo(new ExpertTrainingStateMachine.Expiry("QUEUED", null, true));
        assertThat(ExpertTrainingStateMachine.expiredLease("RUNNING", 3))
                .isEqualTo(new ExpertTrainingStateMachine.Expiry(
                        "FAILED", "OFFLINE_RETRY_EXHAUSTED", false));
        assertThat(ExpertTrainingStateMachine.expiredLease("CANCEL_REQUESTED", 1))
                .isEqualTo(new ExpertTrainingStateMachine.Expiry("CANCELLED", null, false));
    }

    @Test
    void progressIsMonotonicAndUsesAllowlistedPhases() {
        assertThatThrownBy(() -> ExpertTrainingStateMachine.requireProgress(51, 50,
                "LOCAL_TRAINING")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpertTrainingStateMachine.requireProgress(50, 50,
                "CHATTER")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExpertTrainingStateMachine.requireProgress(50, 51, "PACKAGING"))
                .isEqualTo(51);
    }
}
