package com.cofco.qiqihar.graintrade.identity.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class IdentityInvitationTokenCodec {
    private static final int TOKEN_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;

    public IdentityInvitationTokenCodec(
            @Value("${qiqihar.identity.invitation-encryption-key:}") String configuredKey) {
        byte[] key;
        if (configuredKey == null || configuredKey.isBlank()) {
            key = new byte[32];
            random.nextBytes(key);
        } else {
            try {
                key = Base64.getUrlDecoder().decode(configuredKey);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY must be base64url", invalid);
            }
            if (key.length != 32) {
                throw new IllegalStateException(
                        "QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY must decode to 32 bytes");
            }
        }
        encryptionKey = new SecretKeySpec(key, "AES");
    }

    public String generateToken() {
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public String encryptDeliveryPayload(String deliveryAddress, String token) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] clear = (deliveryAddress + "\n" + token).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(clear);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length)
                            .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Invitation delivery payload encryption failed", failure);
        }
    }

    public DeliveryPayload decryptDeliveryPayload(String encoded) {
        try {
            byte[] stored=Base64.getUrlDecoder().decode(encoded);
            if(stored.length<=NONCE_BYTES+16)throw new GeneralSecurityException("payload too short");
            ByteBuffer bytes=ByteBuffer.wrap(stored);
            byte[] nonce=new byte[NONCE_BYTES];
            bytes.get(nonce);
            byte[] encrypted=new byte[bytes.remaining()];
            bytes.get(encrypted);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,new GCMParameterSpec(128,nonce));
            String clear=new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
            int separator=clear.indexOf('\n');
            if(separator<1||separator==clear.length()-1)throw new GeneralSecurityException("payload malformed");
            return new DeliveryPayload(clear.substring(0,separator),clear.substring(separator+1));
        } catch(IllegalArgumentException|GeneralSecurityException failure) {
            throw new IllegalStateException("Invitation delivery payload decryption failed",failure);
        }
    }

    public record DeliveryPayload(String deliveryAddress,String token) {}
}
