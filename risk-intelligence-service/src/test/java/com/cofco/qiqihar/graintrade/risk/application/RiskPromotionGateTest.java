package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiskPromotionGateTest {
    private final RiskPromotionGate gate=new RiskPromotionGate();

    @Test
    void waitsUntilEnoughUnseenResolvedCasesExist() {
        var decision=gate.evaluate(List.of(
                outcome(true,true,true),outcome(false,false,false)),3,0.60d,0.02d);

        assertThat(decision.status()).isEqualTo(RiskPromotionDecision.Status.WAITING);
        assertThat(decision.resolvedLabelCount()).isEqualTo(2);
    }

    @Test
    void promotesOnlyWhenCandidatePassesAbsoluteAndIncumbentGates() {
        var decision=gate.evaluate(List.of(
                outcome(true,true,true),outcome(true,true,false),
                outcome(false,false,false),outcome(false,false,true)),4,0.60d,0.02d);

        assertThat(decision.status()).isEqualTo(RiskPromotionDecision.Status.PASSED);
        assertThat(decision.candidateF1()).isEqualTo(1.0d);
        assertThat(decision.incumbentF1()).isLessThan(decision.candidateF1());
    }

    @Test
    void rejectsRegressionAgainstKnownGoodModel() {
        var decision=gate.evaluate(List.of(
                outcome(true,false,true),outcome(true,true,true),
                outcome(false,true,false),outcome(false,false,false)),4,0.60d,0.02d);

        assertThat(decision.status()).isEqualTo(RiskPromotionDecision.Status.REJECTED);
        assertThat(decision.reasonCode()).isEqualTo("F1_BELOW_GATE");
    }

    @Test
    void waitsWhenLiveEvidenceDoesNotContainBothOutcomeClasses() {
        var decision=gate.evaluate(List.of(
                outcome(true,true,true),outcome(true,true,true),
                outcome(true,true,true),outcome(true,true,true)),4,0.60d,0.02d);

        assertThat(decision.status()).isEqualTo(RiskPromotionDecision.Status.WAITING);
        assertThat(decision.reasonCode()).isEqualTo("INSUFFICIENT_CLASS_COVERAGE");
    }

    private static RiskPredictionOutcome outcome(
            boolean actual,boolean candidate,Boolean incumbent) {
        return new RiskPredictionOutcome(actual,candidate,incumbent);
    }
}
