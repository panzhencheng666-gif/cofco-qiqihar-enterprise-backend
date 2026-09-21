package com.cofco.qiqihar.riskintelligence.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

public record RiskBusinessSession(String subjectId, Set<String> permissions, boolean rootAdministrator) {
    public static final String REQUEST_ATTRIBUTE = RiskBusinessSession.class.getName();

    public RiskBusinessSession {
        subjectId = RiskRequestIdentity.requireActor(subjectId);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean permits(String permission) {
        return rootAdministrator || permissions.contains(permission);
    }

    public static RiskBusinessSession require(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof RiskBusinessSession session) return session;
        throw new RiskApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "RISK_SESSION_REQUIRED",
                "请先登录后再使用风险研判预警系统");
    }
}
