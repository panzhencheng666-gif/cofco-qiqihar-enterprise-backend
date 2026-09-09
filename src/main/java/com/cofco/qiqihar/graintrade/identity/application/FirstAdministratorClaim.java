package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;

/** Operator-controlled administrator; never a public registration grant. */
public record FirstAdministratorClaim(String subjectId,String providerSubject,String tokenSha256,Instant expiresAt) {
    public boolean accepts(String authenticatedSubject,String token,Instant now) {
        if(subjectId==null||subjectId.isBlank()||providerSubject==null||providerSubject.isBlank()
                ||!providerSubject.equals(authenticatedSubject)||expiresAt==null
                ||!now.isBefore(expiresAt)||token==null||!token.matches("[A-Za-z0-9_-]{43,128}")
                ||tokenSha256==null||!tokenSha256.matches("[a-f0-9]{64}"))return false;
        try {
            return java.security.MessageDigest.isEqual(
                    java.util.HexFormat.of().parseHex(tokenSha256),
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
