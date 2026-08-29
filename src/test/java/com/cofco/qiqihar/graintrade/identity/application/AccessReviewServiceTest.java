package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessReviewServiceTest {
    @Test
    void reviewerCannotCreateAnEmptySelfOnlyCampaign() {
        AccessReviewRepository repository=mock(AccessReviewRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        Instant now=Instant.parse("2026-08-12T00:00:00Z");
        AccessReviewService service=new AccessReviewService(repository,access,audit,
                Clock.fixed(now,ZoneOffset.UTC),(subject,reason)->{});
        SecurityPrincipal reviewer=new SecurityPrincipal("reviewer","复核员","UNIT-1","测试单位",
                "ACTIVE","ACTIVE",Set.of("ACCESS_REVIEWER"),List.of(),
                Set.of("ACCESS_REVIEW"),Set.of("230202997"));
        when(access.require("ACCESS_REVIEW",null)).thenReturn(reviewer);
        when(repository.workUnitExists("UNIT-1")).thenReturn(true);
        when(repository.create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("年度复核"),
                org.mockito.ArgumentMatchers.eq("UNIT-1"),
                org.mockito.ArgumentMatchers.eq(now.plusSeconds(3600)),
                org.mockito.ArgumentMatchers.eq("reviewer"),
                org.mockito.ArgumentMatchers.eq(now)))
                .thenAnswer(invocation -> new AccessReviewCampaign(
                        invocation.getArgument(0),"年度复核","UNIT-1","OPEN",
                        now.plusSeconds(3600),"reviewer",now,List.of()));

        assertThatThrownBy(() -> service.create(
                "年度复核","UNIT-1",now.plusSeconds(3600)))
                .isInstanceOfSatisfying(ClientRequestException.class,error -> {
                    assertThat(error.code()).isEqualTo("ACCESS_REVIEW_EMPTY");
                    assertThat(error.getMessage()).isEqualTo("当前单位没有可由本人复核的员工授权");
                });
    }

    @Test
    void reviewerCannotDecideOwnGrant() {
        AccessReviewRepository repository=mock(AccessReviewRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        Instant now=Instant.parse("2026-08-12T00:00:00Z");
        AccessReviewService service=new AccessReviewService(repository,access,audit,
                Clock.fixed(now,ZoneOffset.UTC),(subject,reason)->{});
        SecurityPrincipal reviewer=new SecurityPrincipal("reviewer","复核员","UNIT-1","测试单位",
                "ACTIVE","ACTIVE",Set.of("ACCESS_REVIEWER"),List.of(),
                Set.of("ACCESS_REVIEW"),Set.of("230202997"));
        UUID reviewId=UUID.randomUUID();
        AccessReviewCampaign campaign=new AccessReviewCampaign(reviewId,"年度复核","UNIT-1","OPEN",
                now.plusSeconds(3600),"administrator",now,List.of());
        List<AccessReviewDecision> decisions=List.of(new AccessReviewDecision(
                "reviewer","ROLE","ACCESS_REVIEWER","RETAIN","职责仍有效"));
        when(access.require("ACCESS_REVIEW",null)).thenReturn(reviewer);
        when(repository.find(reviewId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.decide(reviewId,decisions))
                .isInstanceOfSatisfying(AccessDeniedException.class,error -> {
                    assertThat(error.code()).isEqualTo("ACCESS_REVIEW_SELF_DECISION_DENIED");
                    assertThat(error.getMessage()).isEqualTo("不能复核本人的权限");
                });
        verify(repository,never()).decide(reviewId,decisions,"reviewer",now);
    }

    @Test
    void ordinaryReviewerCannotDecideBootstrapOnlyGovernanceRoles() {
        AccessReviewRepository repository=mock(AccessReviewRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        Instant now=Instant.parse("2026-08-12T00:00:00Z");
        AccessReviewService service=new AccessReviewService(repository,access,audit,
                Clock.fixed(now,ZoneOffset.UTC),(subject,reason)->{});
        SecurityPrincipal reviewer=new SecurityPrincipal("reviewer","复核员","UNIT-1","测试单位",
                "ACTIVE","ACTIVE",Set.of("ACCESS_REVIEWER"),List.of(),
                Set.of("ACCESS_REVIEW"),Set.of("230202997"));
        UUID reviewId=UUID.randomUUID();
        AccessReviewCampaign campaign=new AccessReviewCampaign(reviewId,"年度复核","UNIT-1","OPEN",
                now.plusSeconds(3600),"administrator",now,List.of());
        List<AccessReviewDecision> decisions=List.of(new AccessReviewDecision(
                "platform-owner","ROLE","ACCOUNT_OWNER","RETAIN","平台所有者职责仍有效"));
        when(access.require("ACCESS_REVIEW",null)).thenReturn(reviewer);
        when(repository.find(reviewId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.decide(reviewId,decisions))
                .isInstanceOfSatisfying(AccessDeniedException.class,error -> {
                    assertThat(error.code()).isEqualTo("ACCESS_REVIEW_PROTECTED_ROLE_DENIED");
                    assertThat(error.getMessage()).isEqualTo("平台治理角色只能由系统管理员复核");
                });
        verify(repository,never()).decide(reviewId,decisions,"reviewer",now);
    }

    @Test
    void revokedAuthorizationInvalidatesEverySessionForTheAffectedSubject() {
        AccessReviewRepository repository=mock(AccessReviewRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        IdentitySessionInvalidator sessions=mock(IdentitySessionInvalidator.class);
        Instant now=Instant.parse("2026-08-12T00:00:00Z");
        AccessReviewService service=new AccessReviewService(repository,access,audit,
                Clock.fixed(now,ZoneOffset.UTC),sessions);
        SecurityPrincipal reviewer=new SecurityPrincipal("reviewer","复核员","UNIT-1","测试单位",
                "ACTIVE","ACTIVE",Set.of("ACCESS_REVIEWER"),List.of(),
                Set.of("ACCESS_REVIEW"),Set.of("230202997"));
        UUID reviewId=UUID.randomUUID();
        AccessReviewCampaign open=new AccessReviewCampaign(reviewId,"年度复核","UNIT-1","OPEN",
                now.plusSeconds(3600),"administrator",now,List.of());
        AccessReviewCampaign closed=new AccessReviewCampaign(reviewId,"年度复核","UNIT-1","CLOSED",
                now.plusSeconds(3600),"administrator",now,List.of());
        List<AccessReviewDecision> decisions=List.of(
                new AccessReviewDecision("employee-a","ROLE","BUSINESS_OPERATOR","REVOKE","职责已调整"),
                new AccessReviewDecision("employee-a","REGION","230202997","REVOKE","责任区已调整"),
                new AccessReviewDecision("employee-b","ROLE","BUSINESS_OPERATOR","RETAIN","职责仍有效"));
        when(access.require("ACCESS_REVIEW",null)).thenReturn(reviewer);
        when(repository.find(reviewId)).thenReturn(Optional.of(open),Optional.of(closed));
        when(repository.decide(reviewId,decisions,"reviewer",now)).thenReturn(true);

        assertThat(service.decide(reviewId,decisions)).isEqualTo(closed);

        verify(sessions).invalidate("employee-a","ACCESS_REVOKED");
        verify(sessions,never()).invalidate("employee-b","ACCESS_REVOKED");
    }
}
