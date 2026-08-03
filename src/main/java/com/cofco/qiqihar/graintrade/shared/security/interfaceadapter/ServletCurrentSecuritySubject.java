package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.CurrentSecuritySubject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ServletCurrentSecuritySubject implements CurrentSecuritySubject {
    private final HttpServletRequest request;

    public ServletCurrentSecuritySubject(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public Optional<String> subjectId() {
        return Optional.ofNullable(request.getUserPrincipal()).map(principal -> principal.getName())
                .filter(value -> !value.isBlank());
    }
}
