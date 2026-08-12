package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
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
    void reviewerCannotDecideOwnGrant() {
        AccessReviewRepository repository=mock(AccessReviewRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        Instant now=Instant.parse("2026-08-12T00:00:00Z");
        AccessReviewService service=new AccessReviewService(repository,access,audit,
                Clock.fixed(now,ZoneOffset.UTC));
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
}
