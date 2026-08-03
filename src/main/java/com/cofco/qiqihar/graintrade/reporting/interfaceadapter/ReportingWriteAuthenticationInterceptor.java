package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import com.cofco.qiqihar.graintrade.reporting.application.ReportingActor;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class ReportingWriteAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final ReportingActor actor;
    public ReportingWriteAuthenticationInterceptor(ReportingActor actor) { this.actor = actor; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/reports/**");
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean parameterOptions = request.getRequestURI().endsWith("/parameter-options");
        if (!parameterOptions && actor.currentActorId().isEmpty()) throw new AuthenticationRequiredException();
        return true;
    }
}
