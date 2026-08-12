package com.cofco.qiqihar.graintrade.evidence.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;

public final class EvidenceContentEnvelope {
    private static final int MAGIC = 0x45565031;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_PART_BYTES = 20 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png");

    private EvidenceContentEnvelope() {}

    public static byte[] encode(String mediaType, byte[] original, byte[] watermarked) {
        if (!MEDIA_TYPES.contains(mediaType) || invalidPart(original) || invalidPart(watermarked)) {
            throw invalid();
        }
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(body)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(mediaType);
                output.writeInt(original.length);
                output.write(original);
                output.writeInt(watermarked.length);
                output.write(watermarked);
            }
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream envelope = new ByteArrayOutputStream(bodyBytes.length + DIGEST_BYTES);
            envelope.write(bodyBytes);
            envelope.write(sha256(bodyBytes));
            return envelope.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static Content decode(byte[] envelope) {
        if (envelope == null || envelope.length <= DIGEST_BYTES) throw invalid();
        int bodyLength = envelope.length - DIGEST_BYTES;
        byte[] body = Arrays.copyOf(envelope, bodyLength);
        byte[] expectedDigest = Arrays.copyOfRange(envelope, bodyLength, envelope.length);
        if (!MessageDigest.isEqual(sha256(body), expectedDigest)) throw invalid();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw invalid();
            String mediaType = input.readUTF();
            if (!MEDIA_TYPES.contains(mediaType)) throw invalid();
            byte[] original = readPart(input);
            byte[] watermarked = readPart(input);
            if (input.available() != 0) throw invalid();
            return new Content(mediaType, original, watermarked);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException invalid) throw invalid;
            throw invalid();
        }
    }

    private static byte[] readPart(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_PART_BYTES || length > input.available()) throw invalid();
        return input.readNBytes(length);
    }

    private static boolean invalidPart(byte[] bytes) {
        return bytes == null || bytes.length == 0 || bytes.length > MAX_PART_BYTES;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid evidence content envelope");
    }

    public record Content(String mediaType, byte[] original, byte[] watermarked) {
        public Content {
            original = original.clone();
            watermarked = watermarked.clone();
        }

        @Override
        public byte[] original() {
            return original.clone();
        }

        @Override
        public byte[] watermarked() {
            return watermarked.clone();
        }
    }
}
