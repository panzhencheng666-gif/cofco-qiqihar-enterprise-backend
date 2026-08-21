package com.cofco.qiqihar.graintrade.analysis.application;

public record AnalysisCoverage(
        int recordCount,
        int uniqueSubjectCount,
        int coveredRegionCount,
        int excludedRecordCount,
        int pendingReviewRecordCount) {

    public AnalysisCoverage {
        if (recordCount < 0 || uniqueSubjectCount < 0
                || coveredRegionCount < 0 || excludedRecordCount < 0
                || pendingReviewRecordCount < 0) {
            throw new IllegalArgumentException("Analysis coverage counts cannot be negative");
        }
    }
}
