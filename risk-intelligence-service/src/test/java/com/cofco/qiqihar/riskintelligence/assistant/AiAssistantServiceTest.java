package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiAssistantServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T14:30:00Z");
    private final AiAssistantRepository repository = mock(AiAssistantRepository.class);
    private final AiAssistantService service = new AiAssistantService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final RiskBusinessSession employee = new RiskBusinessSession(
            "employee-1", Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), false, Set.of("230200"));

    @Test
    void createsAnOwnedQueuedQuestionWithTrimmedText() {
        var expected = request("employee-1", "玉米水分标准是什么？", "QUEUED");
        when(repository.create("employee-1", "assistant-11111111-1111-1111-1111-111111111111",
                "玉米水分标准是什么？", false, NOW)).thenReturn(expected);

        assertThat(service.ask("  玉米水分标准是什么？  ",
                "assistant-11111111-1111-1111-1111-111111111111", employee)).isEqualTo(expected);

        verify(repository).create("employee-1", "assistant-11111111-1111-1111-1111-111111111111",
                "玉米水分标准是什么？", false, NOW);
    }

    @Test
    void introducesQiliangFromAuditedProductIdentityWithoutClaimingExpertTraining() {
        var queued = request("employee-1", "你好，介绍一下自己", "QUEUED");
        var answered = request("employee-1", "你好，介绍一下自己", "ANSWERED");
        when(repository.create("employee-1", "assistant-identity-1",
                "你好，介绍一下自己", false, NOW)).thenReturn(queued);
        when(repository.completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                any(), eq(NOW))).thenReturn(answered);

        assertThat(service.ask(" 你好，介绍一下自己 ", "assistant-identity-1", employee))
                .isEqualTo(answered);
        verify(repository).completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                argThat(answer ->
                        answer.contains("我是齐粮 AI") && answer.contains("当前回答不代表已晋级专家模型")), eq(NOW));
    }

    @Test
    void answersTheActualGreetingWithoutRequiringBusinessEvidence() {
        var queued = request("employee-1", "你好请介绍一下自己", "QUEUED");
        var answered = request("employee-1", "你好请介绍一下自己", "ANSWERED");
        when(repository.create("employee-1", "assistant-identity-2",
                "你好请介绍一下自己", false, NOW)).thenReturn(queued);
        when(repository.completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                any(), eq(NOW))).thenReturn(answered);

        assertThat(service.ask("你好请介绍一下自己", "assistant-identity-2", employee))
                .isEqualTo(answered);
        verify(repository).completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                argThat(answer -> answer.contains("我是齐粮 AI")), eq(NOW));
    }

    @Test
    void answersNaturalIdentityAndCapabilityQuestionImmediately() {
        String question = "你好，请介绍一下你自己，并说明你能帮助我做什么。";
        var queued = request("employee-1", question, "QUEUED");
        var answered = request("employee-1", question, "ANSWERED");
        when(repository.create("employee-1", "assistant-identity-3", question, false, NOW))
                .thenReturn(queued);
        when(repository.completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                any(), eq(NOW))).thenReturn(answered);

        assertThat(service.ask(question, "assistant-identity-3", employee)).isEqualTo(answered);
        verify(repository).completeProductIdentity(eq(queued.requestId()), eq("employee-1"),
                argThat(answer -> answer.contains("粮食质量") && answer.contains("条件情景")), eq(NOW));
    }

    @Test
    void rejectsBlankAndOversizedQuestionsBeforePersistence() {
        assertThatThrownBy(() -> service.ask("   ", "assistant-key", employee))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("问题不能为空");
        assertThatThrownBy(() -> service.ask("问".repeat(2001), "assistant-key", employee))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("2000");
        assertThatThrownBy(() -> service.ask("问题", "invalid key with spaces", employee))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("幂等");
    }

    @Test
    void keepsEmployeeQuestionsPrivateButAllowsRootAuditRead() {
        UUID id = UUID.randomUUID();
        when(repository.find(id)).thenReturn(Optional.of(request("employee-2", "问题", "ANSWERED")));

        assertThatThrownBy(() -> service.get(id, employee))
                .isInstanceOf(RiskApiException.class)
                .hasMessageContaining("无权查看");

        var root = new RiskBusinessSession("root", Set.of(), true, Set.of());
        assertThat(service.get(id, root).subjectId()).isEqualTo("employee-2");
    }

    private static AiAssistantRepository.RequestView request(
            String subject, String question, String status) {
        return new AiAssistantRepository.RequestView(
                UUID.randomUUID(), subject, status, question, null, null, null, null,
                null, null, null, NOW, null);
    }
}
