package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.CurrentSecuritySubject;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ServletCurrentSecuritySubject implements CurrentSecuritySubject {
    @Override
    public Optional<String> subjectId() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attributes -> attributes.getRequest().getUserPrincipal())
                .map(principal -> principal.getName()).filter(value -> !value.isBlank());
    }
}
