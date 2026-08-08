package com.cofco.qiqihar.graintrade.supply.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.supply.application.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SupplyWriteAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final CurrentActor actor;

    public SupplyWriteAuthenticationInterceptor(CurrentActor actor) {
        this.actor = actor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(
                "/api/v1/supply-accounts/**",
                "/api/v1/supply-sources/**",
                "/api/v1/supply-inputs/**",
                "/api/v1/supply-input-sets/**");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod
                && !request.getMethod().equals("GET")
                && actor.currentActor().isEmpty()) {
            throw new AuthenticationRequiredException();
        }
        return true;
    }
}
