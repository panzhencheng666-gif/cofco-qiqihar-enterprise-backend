package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.production.application.AuthenticatedActor;
import com.cofco.qiqihar.graintrade.production.application.CurrentActor;
import java.security.Principal;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** HTTP authentication adapter; Task 9 replaces the servlet principal source with full authorization. */
@Component
public class ServletCurrentActor implements CurrentActor {
    @Override
    public Optional<AuthenticatedActor> currentActor() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        Principal principal = attributes.getRequest().getUserPrincipal();
        return principal == null || principal.getName() == null || principal.getName().isBlank()
                ? Optional.empty() : Optional.of(new AuthenticatedActor(principal.getName()));
    }
}
