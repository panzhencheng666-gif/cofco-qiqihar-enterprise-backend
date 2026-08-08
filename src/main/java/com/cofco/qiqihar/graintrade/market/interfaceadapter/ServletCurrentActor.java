package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.AuthenticatedActor;
import com.cofco.qiqihar.graintrade.market.application.CurrentActor;
import java.security.Principal;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** HTTP adapter; it never fabricates an actor when servlet authentication is absent. */
@Component("marketServletCurrentActor")
public class ServletCurrentActor implements CurrentActor {
    private final String trustedSubjectHeader;

    public ServletCurrentActor(@Value("${qiqihar.security.trusted-subject-header:}") String trustedSubjectHeader) {
        this.trustedSubjectHeader = trustedSubjectHeader;
    }

    @Override public Optional<AuthenticatedActor> currentActor() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return Optional.empty();
        Principal principal=attributes.getRequest().getUserPrincipal();
        String subject = principal == null ? null : principal.getName();
        if (subject == null && !trustedSubjectHeader.isBlank()) {
            subject = attributes.getRequest().getHeader(trustedSubjectHeader);
        }
        return subject == null || subject.isBlank() ? Optional.empty() : Optional.of(new AuthenticatedActor(subject.trim()));
    }
}
