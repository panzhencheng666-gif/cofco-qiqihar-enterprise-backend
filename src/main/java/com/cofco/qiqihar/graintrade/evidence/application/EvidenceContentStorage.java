package com.cofco.qiqihar.graintrade.evidence.application;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EvidenceContentStorage {
    private static final Pattern KEY = Pattern.compile(
            "evidence/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.evp");
    private final String mode;
    private final EvidenceContentStore store;

    public EvidenceContentStorage(
            @Value("${qiqihar.evidence.content.mode:database}") String mode,
            ObjectProvider<EvidenceContentStore> stores) {
        this.mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!this.mode.equals("database") && !this.mode.equals("filesystem") && !this.mode.equals("oss")) {
            throw new IllegalStateException("Unsupported private evidence content mode");
        }
        this.store = stores.getIfAvailable();
        if (!this.mode.equals("database") && this.store == null) {
            throw new IllegalStateException("Private evidence content store is not configured");
        }
    }

    public boolean external() {
        return !mode.equals("database");
    }

    public String key(UUID id) {
        String value = id.toString();
        return "evidence/" + value.substring(0, 2) + "/" + value + ".evp";
    }

    public void put(String key, byte[] envelope) {
        requireKey(key);
        store.put(key, envelope.clone());
    }

    public byte[] get(String key) {
        requireKey(key);
        return store.get(key).clone();
    }

    public void delete(String key) {
        requireKey(key);
        store.delete(key);
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid private evidence content key");
        }
    }
}
