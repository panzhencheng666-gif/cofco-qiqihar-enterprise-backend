package com.cofco.qiqihar.graintrade.production.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductionActionPolicyTest {

    @ParameterizedTest
    @MethodSource("statusActions")
    void ownsTheCompleteAllowedActionPolicyForEveryStatus(ProductionStatus status, List<String> expected) {
        assertThat(ProductionActionPolicy.allowedActions(status)).containsExactlyElementsOf(expected);
    }

    private static List<Arguments> statusActions() {
        return List.of(
                Arguments.of(ProductionStatus.DRAFT, List.of("VIEW", "SAVE", "SUBMIT")),
                Arguments.of(ProductionStatus.RETURNED, List.of("VIEW", "SAVE", "SUBMIT")),
                Arguments.of(ProductionStatus.PENDING_REVIEW, List.of("VIEW", "APPROVE", "RETURN")),
                Arguments.of(ProductionStatus.APPROVED, List.of("VIEW")));
    }
}
