package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.*;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class ConcurrentAuthorizationRequestRepositoryTest {
    private final MutableClock clock = new MutableClock();
    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository =
            new ConcurrentAuthorizationRequestRepository(clock);

    @Test
    void expirationAtTenMinutesCannotConsumeOrSetTarget() {
        var session = new MockHttpSession();
        var callback = request(session, "expiring");
        callback.setParameter("returnTo", "/risk/");
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization("expiring"), callback, response);
        clock.now = clock.now.plusSeconds(599);
        assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
        assertThat(callback.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
        clock.now = clock.now.plusSeconds(1);
        assertThat(repository.removeAuthorizationRequest(callback, response)).isNull();
        assertThat(callback.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
    }

    @Test
    void capacityEvictsOnlyTheOldestOfEightPendingRequests() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        for (int i = 0; i < 9; i++) {
            repository.saveAuthorizationRequest(authorization("state-" + i), request(session, "state-" + i), response);
        }
        assertThat(repository.loadAuthorizationRequest(request(session, "state-0"))).isNull();
        for (int i = 1; i < 9; i++) {
            assertThat(repository.removeAuthorizationRequest(request(session, "state-" + i), response)).isNotNull();
        }
    }

    @Test
    void nullSaveRemovesOnlyMatchingRequestWithoutPublishingALanding() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        var first = request(session, "first");
        first.setParameter("returnTo", "/risk/");
        repository.saveAuthorizationRequest(authorization("first"), first, response);
        repository.saveAuthorizationRequest(authorization("second"), request(session, "second"), response);
        repository.saveAuthorizationRequest(null, first, response);
        assertThat(repository.loadAuthorizationRequest(first)).isNull();
        assertThat(first.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
        assertThat(repository.loadAuthorizationRequest(request(session, "second"))).isNotNull();
    }

    @Test
    void duplicateMissingAndInvalidCallbackStateCannotConsumeRequest() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization("valid"), request(session, "valid"), response);
        var duplicate = request(session, "valid");
        duplicate.setParameter("state", "valid", "other");
        assertThat(repository.removeAuthorizationRequest(duplicate, response)).isNull();
        duplicate.setParameter("state", "valid", "valid");
        assertThat(repository.loadAuthorizationRequest(duplicate)).isNull();
        var missing = new MockHttpServletRequest();
        missing.setSession(session);
        assertThat(repository.removeAuthorizationRequest(missing, response)).isNull();
        for (String invalid : new String[] { "", " ", "valid\n", "x".repeat(1025) }) {
            assertThat(repository.removeAuthorizationRequest(request(session, invalid), response)).isNull();
        }
        assertThat(repository.removeAuthorizationRequest(request(session, "valid"), response)).isNotNull();
    }

    @Test
    void duplicateSavedStateCannotReplaceOriginalRequestOrTarget() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        var original = authorization("same");
        repository.saveAuthorizationRequest(original, request(session, "same"), response);
        var replacement = request(session, "same");
        replacement.setParameter("returnTo", "/risk/");
        assertThatThrownBy(() -> repository.saveAuthorizationRequest(authorization("same"), replacement, response))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.removeAuthorizationRequest(replacement, response)).isSameAs(original);
        assertThat(replacement.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
    }

    @Test
    void riskAndNormalTargetsAreBoundToTheirOwnStateInEitherCallbackOrder() {
        for (boolean riskFirst : new boolean[] { true, false }) {
            var session = new MockHttpSession();
            var response = new MockHttpServletResponse();
            var riskStart = request(session, "risk");
            riskStart.setParameter("returnTo", "/risk/");
            var original = OAuth2AuthorizationRequest.from(authorization("risk"))
                    .attributes(attributes -> attributes.put("code_verifier", "original-verifier"))
                    .additionalParameters(parameters -> parameters.put("nonce", "original-nonce")).build();
            repository.saveAuthorizationRequest(original, riskStart, response);
            repository.saveAuthorizationRequest(authorization("normal"), request(session, "normal"), response);
            var risk = request(session, "risk");
            var normal = request(session, "normal");
            normal.setParameter("returnTo", "/risk/");
            var wrong = request(session, "wrong");
            wrong.setParameter("returnTo", "/risk/");
            assertThat(repository.removeAuthorizationRequest(wrong, response)).isNull();
            assertThat(wrong.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
            if (riskFirst) {
                assertThat(repository.removeAuthorizationRequest(risk, response)).isSameAs(original);
                assertThat(repository.removeAuthorizationRequest(normal, response)).isNotNull();
            } else {
                assertThat(repository.removeAuthorizationRequest(normal, response)).isNotNull();
                assertThat(repository.removeAuthorizationRequest(risk, response)).isSameAs(original);
            }
            assertThat(risk.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isEqualTo("/risk/");
            assertThat(normal.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
            assertThat(repository.removeAuthorizationRequest(risk, response)).isNull();
            assertThat(risk.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
        }
    }

    @Test
    void externalAndAmbiguousReturnTargetsAreNotStored() {
        for (String[] target : new String[][] { {"https://evil.example"}, {"//evil.example"}, {"/risk/", "/risk/"} }) {
            var session = new MockHttpSession();
            var response = new MockHttpServletResponse();
            var start = request(session, "state");
            start.setParameter("returnTo", target);
            repository.saveAuthorizationRequest(authorization("state"), start, response);
            var callback = request(session, "state");
            assertThat(repository.removeAuthorizationRequest(callback, response)).isNotNull();
            assertThat(callback.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isNull();
        }
    }

    @Test
    void concurrentSavesDoNotLoseAnyOfEightRequestsAndConcurrentConsumptionHasOneWinner() throws Exception {
        var session = new MockHttpSession();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var barrier = new CyclicBarrier(8);
            var saves = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                String state = "state-" + i;
                saves.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    repository.saveAuthorizationRequest(authorization(state), request(session, state), new MockHttpServletResponse());
                    return null;
                }));
            }
            for (var save : saves) save.get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 8; i++) {
                assertThat(repository.loadAuthorizationRequest(request(session, "state-" + i))).isNotNull();
            }
            var consumes = new ArrayList<Future<OAuth2AuthorizationRequest>>();
            for (int i = 0; i < 8; i++) {
                consumes.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return repository.removeAuthorizationRequest(request(session, "state-0"), new MockHttpServletResponse());
                }));
            }
            int winners = 0;
            for (var consume : consumes) if (consume.get(10, TimeUnit.SECONDS) != null) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void pendingRequestsAndTargetsSurviveSessionAttributeSerialization() throws Exception {
        var session = new MockHttpSession();
        var start = request(session, "state");
        start.setParameter("returnTo", "/risk/");
        repository.saveAuthorizationRequest(authorization("state"), start, new MockHttpServletResponse());
        var migrated = new MockHttpSession();
        var names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            var bytes = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(bytes)) { output.writeObject(session.getAttribute(name)); }
            try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                migrated.setAttribute(name, input.readObject());
            }
        }
        var callback = request(migrated, "state");
        assertThat(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()).getState()).isEqualTo("state");
        assertThat(callback.getAttribute("COFCO_OIDC_CALLBACK_RETURN_TO")).isEqualTo("/risk/");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void eachLoginTabRetainsItsOwnRequestAndCallbackConsumesOnlyItsState() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        var first = request(session, "first");
        var second = request(session, "second");
        repository.saveAuthorizationRequest(authorization("first"), first, response);
        repository.saveAuthorizationRequest(authorization("second"), second, response);
        assertThat(repository.removeAuthorizationRequest(first, response)).isNotNull();
        assertThat(repository.removeAuthorizationRequest(first, response)).isNull();
        assertThat(repository.removeAuthorizationRequest(second, response)).isNotNull();
    }

    @Test
    void wrongStateOrOtherSessionCannotConsumeTheLegitimateRequest() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        var legitimate = request(session, "legitimate");
        repository.saveAuthorizationRequest(authorization("legitimate"), legitimate, response);
        assertThat(repository.removeAuthorizationRequest(request(session, "wrong"), response)).isNull();
        assertThat(repository.removeAuthorizationRequest(request(new MockHttpSession(), "legitimate"), response)).isNull();
        assertThat(repository.removeAuthorizationRequest(legitimate, response)).isNotNull();
    }

    static MockHttpServletRequest request(MockHttpSession session, String state) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        request.setParameter("state", state);
        return request;
    }

    static OAuth2AuthorizationRequest authorization(String state) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://idp.test/auth")
                .clientId("enterprise").redirectUri("https://app.test/login/oauth2/code/enterprise")
                .state(state).build();
    }
}
