package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.CurrentSecuritySubject;
import com.cofco.qiqihar.graintrade.shared.security.application.InternalSecuritySubjectScope;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ServletCurrentSecuritySubject implements CurrentSecuritySubject {
    private final String trustedSubjectHeader;
    private final InternalSecuritySubjectScope internalSubject;

    public ServletCurrentSecuritySubject(
            @Value("${qiqihar.security.trusted-subject-header:}") String trustedSubjectHeader,
            InternalSecuritySubjectScope internalSubject) {
        this.trustedSubjectHeader = trustedSubjectHeader;
        this.internalSubject = internalSubject;
    }

    @Override
    public Optional<String> subjectId() {
        Optional<String> requestSubject = Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest)
                .flatMap(request -> {
                    if (request.getUserPrincipal() != null) {
                        return Optional.ofNullable(request.getUserPrincipal().getName());
                    }
                    return trustedSubjectHeader.isBlank()
                            ? Optional.empty()
                            : Optional.ofNullable(request.getHeader(trustedSubjectHeader));
                })
                .map(String::trim)
                .filter(value -> !value.isBlank());
        return requestSubject.isPresent() ? requestSubject : internalSubject.subjectId();
    }
}
