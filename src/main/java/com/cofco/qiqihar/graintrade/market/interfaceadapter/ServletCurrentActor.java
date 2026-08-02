package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.AuthenticatedActor;
import com.cofco.qiqihar.graintrade.market.application.CurrentActor;
import java.security.Principal;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** HTTP adapter; it never fabricates an actor when servlet authentication is absent. */
@Component("marketServletCurrentActor")
public class ServletCurrentActor implements CurrentActor {
    @Override public Optional<AuthenticatedActor> currentActor() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return Optional.empty();
        Principal principal=attributes.getRequest().getUserPrincipal();
        return principal==null || principal.getName()==null || principal.getName().isBlank() ? Optional.empty() : Optional.of(new AuthenticatedActor(principal.getName()));
    }
}
