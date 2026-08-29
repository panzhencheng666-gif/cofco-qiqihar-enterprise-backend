package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.DISTINCT;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.MATCHED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleIdentityAssessmentTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reusesOneStrongIdentityAtTheSameGovernedLocation() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "138 0000-0001", "230281", "122.48", "48.07"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.4800", "48.0700")));

        assertThat(result.outcome()).isEqualTo(MATCHED);
        assertThat(result.matchedSamplePointId()).isEqualTo(FIRST);
    }

    @Test
    void permitsARealSameNamePersonOnlyWhenContactAndLocationBothDistinguishThem() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "13900000002", "230281", "122.50", "48.08"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.48", "48.07")));

        assertThat(result.outcome()).isEqualTo(DISTINCT);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_IDENTITY_CLEARLY_DISTINCT");
    }

    @Test
    void sendsDifferentNamesAtTheSameCoordinateToColocationReview() {
        var result = SampleIdentityAssessment.assess(
                input("同址另一经营主体", "13900000002", "230281", "122.48", "48.07"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.4800", "48.0700")));

        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_COORDINATE_SHARED_REVIEW_REQUIRED");
        assertThat(result.candidates()).extracting(SampleIdentityAssessment.Candidate::samplePointId)
                .containsExactly(FIRST);
    }

    @Test
    void sendsChangedContactAtTheSameLocationToReviewInsteadOfCreatingAnotherIdentity() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "13900000002", "230281", "122.48", "48.07"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.48", "48.07")));

        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_IDENTITY_CONTACT_CONFLICT");
    }

    @Test
    void reusesOneStrongIdentityAcrossPeriodsWhenItsLocationChanges() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "13800000001", "230281", "123.00", "47.00"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.48", "48.07")));

        assertThat(result.outcome()).isEqualTo(MATCHED);
        assertThat(result.matchedSamplePointId()).isEqualTo(FIRST);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_IDENTITY_MATCHED");
    }

    @Test
    void sendsMultipleStrongCandidatesToReview() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "13800000001", "230281", "122.48", "48.07"),
                List.of(
                        candidate(FIRST, "王振锋", "13800000001", "230281", "122.48", "48.07"),
                        candidate(SECOND, "王振锋", "13800000001", "230281", "122.48", "48.07")));

        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_IDENTITY_MULTIPLE_MATCHES");
    }

    @Test
    void doesNotUseNameAloneAsAStableIdentity() {
        var result = SampleIdentityAssessment.assess(
                input("王振锋", "", "230281", "122.48", "48.07"),
                List.of(candidate(FIRST, "王振锋", "13800000001", "230281", "122.48", "48.07")));

        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_IDENTITY_EVIDENCE_INCOMPLETE");
    }

    private static SampleIdentityAssessment.SubjectInput input(
            String name, String contact, String region, String longitude, String latitude) {
        return new SampleIdentityAssessment.SubjectInput(
                "PRODUCTION", name, contact, region,
                new BigDecimal(longitude), new BigDecimal(latitude));
    }

    private static SampleIdentityAssessment.Candidate candidate(
            UUID id, String name, String contact, String region, String longitude, String latitude) {
        return new SampleIdentityAssessment.Candidate(
                id, name, contact, region, new BigDecimal(longitude), new BigDecimal(latitude),
                1, LocalDate.of(2024, 1, 1));
    }
}
