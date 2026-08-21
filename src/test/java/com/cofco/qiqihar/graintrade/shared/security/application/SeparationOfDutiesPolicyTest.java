package com.cofco.qiqihar.graintrade.shared.security.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeparationOfDutiesPolicyTest {

    @Test
    void explicitSelfApprovalPermissionAllowsTheSubmitterToApprove() {
        SeparationOfDutiesPolicy policy = new SeparationOfDutiesPolicy(
                (aggregateType, aggregateId, actionCode) -> Optional.of("account-owner"));
        SecurityPrincipal owner = principal(
                "account-owner", Set.of("BUSINESS_APPROVE", "BUSINESS_SELF_APPROVE"), Set.of("ACCOUNT_OWNER"));

        assertThatCode(() -> policy.requireIndependentApprover(
                "PRODUCTION_RECORD", "record-1", "PRODUCTION_RECORD_SUBMITTED", owner))
                .doesNotThrowAnyException();
    }

    @Test
    void ordinaryAccountsRemainUnableToApproveTheirOwnSubmission() {
        SeparationOfDutiesPolicy policy = new SeparationOfDutiesPolicy(
                (aggregateType, aggregateId, actionCode) -> Optional.of("ordinary-account"));
        SecurityPrincipal ordinary = principal("ordinary-account", Set.of("BUSINESS_APPROVE"), Set.of());

        assertThatThrownBy(() -> policy.requireIndependentApprover(
                "PRODUCTION_RECORD", "record-2", "PRODUCTION_RECORD_SUBMITTED", ordinary))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void selfApprovalPermissionWithoutOwnerRoleRemainsBlocked() {
        SeparationOfDutiesPolicy policy = new SeparationOfDutiesPolicy(
                (aggregateType, aggregateId, actionCode) -> Optional.of("other-administrator"));
        SecurityPrincipal administrator = principal(
                "other-administrator", Set.of("BUSINESS_APPROVE", "BUSINESS_SELF_APPROVE"),
                Set.of("SYSTEM_ADMIN"));

        assertThatThrownBy(() -> policy.requireIndependentApprover(
                "PRODUCTION_RECORD", "record-3", "PRODUCTION_RECORD_SUBMITTED", administrator))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void explicitSelfApprovalPermissionAllowsTheAccountOwnerToReturnOwnSubmission() {
        SeparationOfDutiesPolicy policy = new SeparationOfDutiesPolicy(
                (aggregateType, aggregateId, actionCode) -> Optional.of("account-owner"));
        SecurityPrincipal owner = principal(
                "account-owner", Set.of("BUSINESS_RETURN", "BUSINESS_SELF_APPROVE"), Set.of("ACCOUNT_OWNER"));

        assertThatCode(() -> policy.requireIndependentReturner(
                "PRODUCTION_RECORD", "record-4", "PRODUCTION_RECORD_SUBMITTED", owner))
                .doesNotThrowAnyException();
    }

    @Test
    void ordinaryAccountsRemainUnableToReturnTheirOwnSubmission() {
        SeparationOfDutiesPolicy policy = new SeparationOfDutiesPolicy(
                (aggregateType, aggregateId, actionCode) -> Optional.of("ordinary-account"));
        SecurityPrincipal ordinary = principal("ordinary-account", Set.of("BUSINESS_RETURN"), Set.of());

        assertThatThrownBy(() -> policy.requireIndependentReturner(
                "PRODUCTION_RECORD", "record-5", "PRODUCTION_RECORD_SUBMITTED", ordinary))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("SELF_RETURN_FORBIDDEN"));
    }

    private static SecurityPrincipal principal(
            String subjectId, Set<String> permissions, Set<String> roles) {
        return new SecurityPrincipal(
                subjectId, "本地账号", "LOCAL_DEV", "平台运营管理部", "ACTIVE", "ACTIVE",
                roles, List.of(), permissions, Set.of("230200"));
    }
}
