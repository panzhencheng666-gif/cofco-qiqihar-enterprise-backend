package com.cofco.qiqihar.riskintelligence.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RiskBusinessSessionFilter extends OncePerRequestFilter {
    private final RiskBusinessSessionClient client;

    public RiskBusinessSessionFilter(RiskBusinessSessionClient client) {
        this.client = client;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/risk/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RiskBusinessSession session;
        try {
            session = client.authenticate(request.getHeader("Cookie")).orElse(null);
        } catch (RiskSessionValidationUnavailableException exception) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "登录状态校验服务暂时不可用，请稍后重试");
            return;
        }
        if (session == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
            return;
        }
        String permission = HttpMethod.GET.matches(request.getMethod()) ? "BUSINESS_READ" : "BUSINESS_UPDATE";
        if (!session.permits(permission) || !session.scope().hasAccess()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "当前账号没有风险系统所需权限");
            return;
        }
        request.setAttribute(RiskBusinessSession.REQUEST_ATTRIBUTE, session);
        filterChain.doFilter(request, response);
    }
}
