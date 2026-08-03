package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import com.cofco.qiqihar.graintrade.reporting.application.ReportingActor;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class ServletReportingActor implements ReportingActor {
    private final HttpServletRequest request;

    public ServletReportingActor(HttpServletRequest request) { this.request = request; }

    @Override
    public Optional<String> currentActorId() {
        Principal principal = request.getUserPrincipal();
        return principal == null ? Optional.empty() : Optional.of(principal.getName());
    }
}
