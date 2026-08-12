package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
        this.required = true;
    }

    @Autowired
    public BusinessReadAuthenticationInterceptor(
            AccessControl accessControl,
            @Value("${qiqihar.security.require-read-authentication:true}") boolean configuredRequired,
            Environment environment) {
        this.accessControl = accessControl;
        this.required = configuredRequired || !environment.acceptsProfiles(Profiles.of("test"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (required && "GET".equals(request.getMethod()) && !"/api/v1/session/login".equals(path)) {
            accessControl.require("BUSINESS_READ", null);
        }
        return true;
    }
}
