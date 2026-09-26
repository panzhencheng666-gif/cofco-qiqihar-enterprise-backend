package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Root backend availability boundary, independent of scheduled training and business permissions. */
@Configuration(proxyBeanMethods = false)
public class RiskApiAvailabilityInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final boolean enabled;

    public RiskApiAvailabilityInterceptor(@Value("${qiqihar.risk.api.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/v1/risk", "/api/v1/risk/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (enabled) return true;
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"RISK_API_DISABLED\",\"message\":\"风险功能尚未启用\"}");
        return false;
    }
}
