package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstAdministratorClaimTest {
    private final Instant now=Instant.parse("2026-09-09T00:00:00Z");
    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void acceptsOnlyConfiguredAuthenticatedIdentityAndSecret() throws Exception {
        var claim=new FirstAdministratorClaim("existing-admin","admin@example.test",hash("a".repeat(43)),now.plusSeconds(600));
        assertTrue(claim.accepts("admin@example.test","a".repeat(43),now));
        assertFalse(claim.accepts("other@example.test","a".repeat(43),now));
        assertFalse(claim.accepts("admin@example.test","b".repeat(43),now));
        assertFalse(claim.accepts("admin@example.test","a".repeat(43),now.plusSeconds(600)));
    }
    @Test void failsClosedForMissingOrMalformedConfiguration() {
        assertFalse(new FirstAdministratorClaim("","","",Instant.EPOCH).accepts(null,null,now));
        assertFalse(new FirstAdministratorClaim("admin","a@b.test","bad",now.plusSeconds(1)).accepts("a@b.test","a".repeat(43),now));
    }
    public static void main(String[] args) throws Exception {
        var test=new FirstAdministratorClaimTest();
        test.acceptsOnlyConfiguredAuthenticatedIdentityAndSecret();
        test.failsClosedForMissingOrMalformedConfiguration();
        System.out.println("FirstAdministratorClaimTest: 2 tests passed (no DB session)");
    }
}
