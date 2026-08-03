package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enforces an authenticated business-read permission before any business API controller executes. */
@Configuration(proxyBeanMethods = false)
public class BusinessReadAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final AccessControl accessControl;
    private final boolean required;

    public BusinessReadAuthenticationInterceptor(
            AccessControl accessControl,
            @Value("${qiqihar.security.require-read-authentication:true}") boolean required) {
        this.accessControl = accessControl;
        this.required = required;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (required && "GET".equals(request.getMethod())) accessControl.require("BUSINESS_READ", null);
        return true;
    }
}
