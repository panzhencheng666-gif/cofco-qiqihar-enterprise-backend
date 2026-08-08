package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Fails startup when an identity source could escape its intended environment. */
@Component
public final class SecurityStartupInvariant {
    private static final String TEST_CLASSPATH_MARKER =
            "com.cofco.qiqihar.graintrade.testsupport.TestSecurityConfiguration";

    public SecurityStartupInvariant(
            Environment environment,
            ResourceLoader resourceLoader,
            @Value("${server.address:}") String serverAddress,
            @Value("${qiqihar.security.trusted-subject-header:}") String trustedSubjectHeader,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        validate(Set.copyOf(Arrays.asList(environment.getActiveProfiles())),
                serverAddress, trustedSubjectHeader, issuerUri,
                ClassUtils.isPresent(TEST_CLASSPATH_MARKER, resourceLoader.getClassLoader()));
    }

    static void validate(Set<String> activeProfiles, String serverAddress,
            String trustedSubjectHeader, String issuerUri) {
        validate(activeProfiles, serverAddress, trustedSubjectHeader, issuerUri, true);
    }

    static void validate(Set<String> activeProfiles, String serverAddress,
            String trustedSubjectHeader, String issuerUri, boolean testClasspathMarkerPresent) {
        Set<String> profiles = activeProfiles.stream()
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        boolean local = profiles.contains("local");
        boolean test = profiles.contains("test");

        if (local && !isLoopback(serverAddress)) {
            throw new IllegalStateException("The local profile must bind server.address to numeric loopback");
        }
        if (test && !testClasspathMarkerPresent) {
            throw new IllegalStateException(
                    "The test profile is forbidden in a production artifact without the test classpath marker");
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
        String[] ipv4Parts = normalized.split("\\.", -1);
        if (ipv4Parts.length == 4) {
            try {
                for (String part : ipv4Parts) {
                    int octet = Integer.parseInt(part);
                    if (octet < 0 || octet > 255) {
                        return false;
                    }
                }
                return Integer.parseInt(ipv4Parts[0]) == 127;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (!normalized.contains(":") || !normalized.matches("[0-9a-f:]+")) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(normalized);
            return parsed instanceof Inet6Address && parsed.isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
