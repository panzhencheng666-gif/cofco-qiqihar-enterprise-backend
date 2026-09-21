package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RiskPromotionGate {
    public RiskPromotionDecision evaluate(List<RiskPredictionOutcome> outcomes,int minimumLabels,
            double minimumF1,double maximumF1Regression) {
        if (outcomes.size()<minimumLabels) {
            return new RiskPromotionDecision(RiskPromotionDecision.Status.WAITING,outcomes.size(),
                    f1(outcomes,true),incumbentF1(outcomes),"INSUFFICIENT_SHADOW_LABELS");
        }
        if (outcomes.stream().allMatch(RiskPredictionOutcome::actualPositive)
                || outcomes.stream().noneMatch(RiskPredictionOutcome::actualPositive)) {
            return new RiskPromotionDecision(RiskPromotionDecision.Status.WAITING,outcomes.size(),
                    f1(outcomes,true),incumbentF1(outcomes),"INSUFFICIENT_CLASS_COVERAGE");
        }
        double candidate=f1(outcomes,true);
        Double incumbent=incumbentF1(outcomes);
        if (candidate<minimumF1) {
            return new RiskPromotionDecision(RiskPromotionDecision.Status.REJECTED,outcomes.size(),
                    candidate,incumbent,"F1_BELOW_GATE");
        }
        if (incumbent!=null && candidate+maximumF1Regression<incumbent) {
            return new RiskPromotionDecision(RiskPromotionDecision.Status.REJECTED,outcomes.size(),
                    candidate,incumbent,"F1_REGRESSION");
        }
        return new RiskPromotionDecision(RiskPromotionDecision.Status.PASSED,outcomes.size(),
                candidate,incumbent,"PROMOTION_GATES_PASSED");
    }

    private static Double incumbentF1(List<RiskPredictionOutcome> outcomes) {
        if (outcomes.stream().anyMatch(value -> value.incumbentPredictedPositive()==null)) return null;
        return f1(outcomes,false);
    }

    private static double f1(List<RiskPredictionOutcome> outcomes,boolean candidate) {
        long truePositive=0,falsePositive=0,falseNegative=0;
        for (RiskPredictionOutcome outcome:outcomes) {
            boolean prediction=candidate?outcome.candidatePredictedPositive()
                    :Boolean.TRUE.equals(outcome.incumbentPredictedPositive());
            if (prediction && outcome.actualPositive()) truePositive++;
            else if (prediction) falsePositive++;
            else if (outcome.actualPositive()) falseNegative++;
        }
        double precision=ratio(truePositive,truePositive+falsePositive);
        double recall=ratio(truePositive,truePositive+falseNegative);
        return precision+recall==0?0d:round(2d*precision*recall/(precision+recall));
    }

    private static double ratio(long numerator,long denominator) {
        return denominator==0?0d:(double)numerator/denominator;
    }

    private static double round(double value) {
        return Math.round(value*1_000_000d)/1_000_000d;
    }
}
