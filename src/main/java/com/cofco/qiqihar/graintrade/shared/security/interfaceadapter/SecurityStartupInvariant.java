package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup when an identity source could escape its intended environment. */
@Component
public final class SecurityStartupInvariant {

    public SecurityStartupInvariant(
            Environment environment,
            @Value("${server.address:}") String serverAddress,
            @Value("${qiqihar.security.trusted-subject-header:}") String trustedSubjectHeader,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        validate(Set.copyOf(Arrays.asList(environment.getActiveProfiles())),
                serverAddress, trustedSubjectHeader, issuerUri);
    }

    static void validate(Set<String> activeProfiles, String serverAddress,
            String trustedSubjectHeader, String issuerUri) {
        Set<String> profiles = activeProfiles.stream()
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        boolean local = profiles.contains("local");
        boolean test = profiles.contains("test");

        if (local && !isLoopback(serverAddress)) {
            throw new IllegalStateException("The local profile must bind server.address to loopback");
        }
        if (!local && !trustedSubjectHeader.isBlank()) {
            throw new IllegalStateException(
                    "qiqihar.security.trusted-subject-header is allowed only in the local profile");
        }
        if (!local && !test && issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "QIQIHAR_OIDC_ISSUER_URI is required outside the local and test profiles");
        }
    }

    private static boolean isLoopback(String address) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1")
                || normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1");
    }
}
