package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.Assert;

/** Bounded, session-local OIDC attempts with independent one-shot state and landing targets. */
final class ConcurrentAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    static final String CALLBACK_RETURN_TO_ATTRIBUTE = "COFCO_OIDC_CALLBACK_RETURN_TO";
    private static final String SESSION_ATTRIBUTE = ConcurrentAuthorizationRequestRepository.class.getName() + ".PENDING";
    private static final int MAX_PENDING = 8;
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private final Clock clock;

    ConcurrentAuthorizationRequestRepository() {
        this(Clock.systemUTC());
    }

    ConcurrentAuthorizationRequestRepository(Clock clock) {
        Assert.notNull(clock, "clock cannot be null");
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Assert.notNull(request, "request cannot be null");
        String state = callbackState(request);
        Pending pending = pending(request.getSession(false), false);
        if (state == null || pending == null) return null;
        synchronized (pending) {
            prune(pending);
            Entry entry = pending.entries.get(state);
            return entry == null ? null : entry.authorization();
        }
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,
            HttpServletRequest request, HttpServletResponse response) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(response, "response cannot be null");
        if (authorization == null) {
            consume(request, false);
            return;
        }
        Assert.isTrue(validState(authorization.getState()), "authorizationRequest.state is invalid");
        Assert.isTrue(request.getParameterValues("state") == null || callbackState(request) != null,
                "state parameter is invalid or ambiguous");
        Pending pending = pending(request.getSession(), true);
        synchronized (pending) {
            prune(pending);
            Assert.isTrue(!pending.entries.containsKey(authorization.getState()), "authorizationRequest.state already pending");
            if (pending.entries.size() >= MAX_PENDING) {
                pending.entries.pollFirstEntry();
            }
            String[] targets = request.getParameterValues("returnTo");
            String target = targets != null && targets.length == 1 && "/risk/".equals(targets[0]) ? "/risk/" : null;
            pending.entries.put(authorization.getState(), new Entry(authorization, clock.instant().plus(LIFETIME), target));
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(response, "response cannot be null");
        return consume(request, true);
    }

    private OAuth2AuthorizationRequest consume(HttpServletRequest request, boolean publishLanding) {
        request.removeAttribute(CALLBACK_RETURN_TO_ATTRIBUTE);
        String state = callbackState(request);
        Pending pending = pending(request.getSession(false), false);
        if (state == null || pending == null) return null;
        synchronized (pending) {
            prune(pending);
            Entry entry = pending.entries.remove(state);
            if (entry == null) return null;
            if (publishLanding && entry.returnTo() != null) {
                request.setAttribute(CALLBACK_RETURN_TO_ATTRIBUTE, entry.returnTo());
            }
            return entry.authorization();
        }
    }

    private void prune(Pending pending) {
        Instant now = clock.instant();
        pending.entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }

    private static String callbackState(HttpServletRequest request) {
        String[] states = request.getParameterValues("state");
        return states != null && states.length == 1 && validState(states[0]) ? states[0] : null;
    }

    private static boolean validState(String state) {
        if (state == null || state.isEmpty() || state.length() > 1024) return false;
        for (int i = 0; i < state.length(); i++) {
            if (state.charAt(i) < 0x21 || state.charAt(i) > 0x7e) return false;
        }
        return true;
    }

    private static Pending pending(HttpSession session, boolean create) {
        if (session == null) return null;
        // Initialize once; subsequent operations lock the holder that survives session migration.
        synchronized (session) {
            Pending pending = (Pending) session.getAttribute(SESSION_ATTRIBUTE);
            if (pending == null && create) {
                pending = new Pending();
                session.setAttribute(SESSION_ATTRIBUTE, pending);
            }
            return pending;
        }
    }

    private static final class Pending implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    }

    private record Entry(OAuth2AuthorizationRequest authorization, Instant expiresAt, String returnTo)
            implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
}
