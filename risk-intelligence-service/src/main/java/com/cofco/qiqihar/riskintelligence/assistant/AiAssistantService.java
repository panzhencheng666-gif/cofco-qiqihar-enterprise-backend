package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAssistantService {
    private static final Set<String> IDENTITY_QUESTIONS = Set.of(
            "你好", "您好", "你好介绍一下自己", "你好请介绍一下自己", "请介绍一下自己",
            "介绍一下自己", "你是谁", "你是什么", "齐粮AI是什么", "介绍一下齐粮AI");
    private static final String PRODUCT_IDENTITY = "我是齐粮 AI 模型服务，由齐粮风险研判系统提供。"
            + "当前问答使用本地基础模型与受控知识检索；专家训练和模型晋级状态以训练中心台账为准，当前回答不代表已晋级专家模型。"
            + "我可以解释粮食质量、仓储和市场问题，梳理风险因素与条件情景，并提供公开资料线索。"
            + "涉及具体业务数值时，我会标明来源及适用范围。";
    private final AiAssistantRepository repository;
    private final Clock clock;

    public AiAssistantService(AiAssistantRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AiAssistantRepository.RequestView ask(String rawQuestion, String rawIdempotencyKey,
            RiskBusinessSession session) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        String idempotencyKey = rawIdempotencyKey == null ? "" : rawIdempotencyKey.strip();
        if (question.isEmpty()) {
            throw new RiskApiException(HttpStatus.BAD_REQUEST, "AI_QUESTION_REQUIRED", "问题不能为空");
        }
        if (question.length() > 2000) {
            throw new RiskApiException(HttpStatus.BAD_REQUEST, "AI_QUESTION_TOO_LONG", "问题不能超过2000个字符");
        }
        if (!idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,79}")) {
            throw new RiskApiException(HttpStatus.BAD_REQUEST, "AI_IDEMPOTENCY_KEY_INVALID",
                    "AI问答幂等键不合法");
        }
        var created = repository.create(session.subjectId(), idempotencyKey, question,
                session.rootAdministrator(), clock.instant());
        if ("QUEUED".equals(created.status()) && isProductIdentityQuestion(question)) {
            return repository.completeProductIdentity(created.requestId(), session.subjectId(),
                    PRODUCT_IDENTITY, clock.instant());
        }
        return created;
    }

    private static boolean isProductIdentityQuestion(String question) {
        String normalized = question.replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .toUpperCase(Locale.ROOT);
        return IDENTITY_QUESTIONS.contains(normalized)
                || normalized.matches("(?:你好|您好)?(?:请)?介绍一下(?:你|您)自己(?:并说明(?:你|您)能帮助我做什么)?")
                || Set.of("你能做什么", "您能做什么", "你可以帮我做什么", "能帮助我做什么")
                        .contains(normalized);
    }

    @Transactional(readOnly = true)
    public AiAssistantRepository.RequestView get(UUID requestId, RiskBusinessSession session) {
        var view = repository.find(requestId).orElseThrow(() -> new RiskApiException(
                HttpStatus.NOT_FOUND, "AI_REQUEST_NOT_FOUND", "未找到该问答请求"));
        if (!session.rootAdministrator() && !session.subjectId().equals(view.subjectId())) {
            throw new RiskApiException(HttpStatus.FORBIDDEN, "AI_REQUEST_FORBIDDEN", "无权查看该问答请求");
        }
        return view;
    }
}
