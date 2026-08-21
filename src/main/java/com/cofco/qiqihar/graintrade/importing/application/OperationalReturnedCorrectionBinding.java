package com.cofco.qiqihar.graintrade.importing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Signs immutable controls in production and logistics returned-record workbooks. */
@Component
public final class OperationalReturnedCorrectionBinding {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String NON_PRODUCTION_KEY =
            "local-test-only-operational-returned-correction-binding-key";
    private final byte[] key;

    OperationalReturnedCorrectionBinding(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("RETURNED_CORRECTION_BINDING_KEY_REQUIRED");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Autowired
    OperationalReturnedCorrectionBinding(
            @Value("${qiqihar.import.returned-correction-binding-key:}") String configuredKey,
            Environment environment) {
        this(resolveKey(configuredKey, environment));
    }

    String sign(String purpose, String productCode, String originalRecordId, long originalVersion) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            String payload = purpose + '\n' + productCode + '\n'
                    + originalRecordId + '\n' + originalVersion;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Returned correction row cannot be signed", exception);
        }
    }

    boolean matches(String signature, String purpose, String productCode,
            String originalRecordId, long originalVersion) {
        if (signature == null || signature.isBlank()) return false;
        return MessageDigest.isEqual(
                sign(purpose, productCode, originalRecordId, originalVersion)
                        .getBytes(StandardCharsets.US_ASCII),
                signature.trim().getBytes(StandardCharsets.US_ASCII));
    }

    private static String resolveKey(String configuredKey, Environment environment) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            if (configuredKey.length() < 32) {
                throw new IllegalStateException(
                        "QIQIHAR_RETURNED_CORRECTION_BINDING_KEY must contain at least 32 characters");
            }
            return configuredKey;
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return NON_PRODUCTION_KEY;
        }
        throw new IllegalStateException(
                "QIQIHAR_RETURNED_CORRECTION_BINDING_KEY is required outside local and test profiles");
    }
}
