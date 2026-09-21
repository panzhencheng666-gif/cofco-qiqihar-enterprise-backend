package com.cofco.qiqihar.riskintelligence.integration;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SourceFactIngestionService {
    private final SourceFactRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public SourceFactIngestionService(SourceFactRepository repository, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public SourceFactReceipt ingest(SourceFact fact) {
        SourceFactKey key = fact.key();
        String hash = hash(fact.payload());
        var existing = repository.find(key);
        if (existing.isPresent()) {
            return receipt(existing.get(), hash, false);
        }

        SourceFactSnapshot candidate = new SourceFactSnapshot(
                UUID.randomUUID(), key, fact.businessOccurredAt(), clock.instant(), hash, fact.payload());
        if (repository.insert(candidate)) {
            return receipt(candidate, hash, true);
        }

        SourceFactSnapshot concurrent = repository.find(key)
                .orElseThrow(() -> new IllegalStateException("Source fact insert conflicted without a stored row"));
        return receipt(concurrent, hash, false);
    }

    private static SourceFactReceipt receipt(
            SourceFactSnapshot snapshot, String requestedHash, boolean created) {
        if (!snapshot.payloadSha256().equals(requestedHash)) {
            throw new SourceVersionConflictException(snapshot.key());
        }
        return new SourceFactReceipt(
                snapshot.snapshotId(), snapshot.payloadSha256(), snapshot.ingestedAt(), created);
    }

    private String hash(Map<String, Object> payload) {
        try {
            String canonicalJson = json.writeValueAsString(canonicalize(payload));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Source payload cannot be serialized", exception);
        }
    }

    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(SourceFactIngestionService::canonicalize).toList();
        }
        if (value != null && value.getClass().isArray()) {
            var items = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                items.add(canonicalize(Array.get(value, index)));
            }
            return items;
        }
        return value;
    }
}
