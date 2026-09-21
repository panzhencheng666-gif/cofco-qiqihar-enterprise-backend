package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
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

class RiskWorkbenchServiceTest {
    private static final Instant NOW=Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void readsOnlyThroughTheAuthorizedBusinessScope() {
        RiskWorkbenchRepository repository=mock(RiskWorkbenchRepository.class);
        AccessControl access=mock(AccessControl.class);
        RiskWorkbenchService service=service(repository,access,mock(BusinessAuditRecorder.class));
        when(repository.findAssessments(new RiskAssessmentQuery("INVENTORY","HIGH","OPEN","仓库",50)))
                .thenReturn(List.of());

        assertThat(service.assessments("INVENTORY","HIGH","OPEN","仓库",50)).isEmpty();

        verify(access).requireBusinessReadScope();
        verify(repository).findAssessments(new RiskAssessmentQuery("INVENTORY","HIGH","OPEN","仓库",50));
    }

    @Test
    void rejectsInvalidFeedbackBeforeWriting() {
        RiskWorkbenchRepository repository=mock(RiskWorkbenchRepository.class);
        AccessControl access=mock(AccessControl.class);
        RiskWorkbenchService service=service(repository,access,mock(BusinessAuditRecorder.class));

        assertThatThrownBy(() -> service.submitFeedback(
                UUID.randomUUID(),"CONFIRMED","","已核实"))
                .isInstanceOfSatisfying(ClientRequestException.class,error ->
                        assertThat(error.code()).isEqualTo("INVALID_RISK_FEEDBACK"));
    }

    @Test
    void persistsAuditedHumanFeedbackExactlyOnce() {
        RiskWorkbenchRepository repository=mock(RiskWorkbenchRepository.class);
        AccessControl access=mock(AccessControl.class);
        BusinessAuditRecorder audit=mock(BusinessAuditRecorder.class);
        RiskWorkbenchService service=service(repository,access,audit);
        UUID assessmentId=UUID.randomUUID();
        SecurityPrincipal actor=new SecurityPrincipal(
                "reviewer","复核员","UNIT-1",Set.of("BUSINESS_UPDATE"),Set.of());
        RiskFeedback feedback=new RiskFeedback(
                UUID.randomUUID(),assessmentId,"CONFIRMED","SOURCE_VERIFIED","已核对原始凭证",
                "reviewer",NOW);
        when(access.require("BUSINESS_UPDATE",null)).thenReturn(actor);
        when(repository.assessmentExists(assessmentId)).thenReturn(true);
        when(repository.createFeedback(assessmentId,"CONFIRMED","SOURCE_VERIFIED",
                "已核对原始凭证","reviewer",NOW)).thenReturn(Optional.of(feedback));

        assertThat(service.submitFeedback(assessmentId,"CONFIRMED","SOURCE_VERIFIED","已核对原始凭证"))
                .isEqualTo(feedback);

        verify(audit).record(actor,"RISK_ASSESSMENT",assessmentId.toString(),
                "RISK_FEEDBACK_RECORDED",NOW,"{\"conclusionCode\":\"CONFIRMED\"}");

        when(repository.createFeedback(assessmentId,"CONFIRMED","SOURCE_VERIFIED",
                "已核对原始凭证","reviewer",NOW)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitFeedback(
                assessmentId,"CONFIRMED","SOURCE_VERIFIED","已核对原始凭证"))
                .isInstanceOfSatisfying(ConflictException.class,error ->
                        assertThat(error.code()).isEqualTo("RISK_FEEDBACK_ALREADY_EXISTS"));
    }

    private static RiskWorkbenchService service(
            RiskWorkbenchRepository repository,AccessControl access,BusinessAuditRecorder audit) {
        return new RiskWorkbenchService(repository,access,audit,Clock.fixed(NOW,ZoneOffset.UTC));
    }
}
