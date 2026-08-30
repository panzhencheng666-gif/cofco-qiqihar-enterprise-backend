package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.CurrentActor;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Authenticates production-object writes before Spring resolves or decodes request bodies. */
@Configuration(proxyBeanMethods = false)
public class ProductionObjectWriteAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final CurrentActor currentActor;

    public ProductionObjectWriteAuthenticationInterceptor(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/production-objects/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!request.getMethod().equals("GET") && currentActor.currentActor().isEmpty()) {
            throw new AuthenticationRequiredException();
        }
        return true;
    }
}
