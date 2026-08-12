package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentStore;
import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentUnavailableException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qiqihar.evidence.content.mode", havingValue = "filesystem")
public class FilesystemEvidenceContentStore implements EvidenceContentStore {
    private static final int MAX_ENVELOPE_BYTES = 40 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile(
            "evidence/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.evp");
    private final Path root;

    public FilesystemEvidenceContentStore(
            @Value("${qiqihar.evidence.content.filesystem-root:}") String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException("Private evidence content root is unavailable");
        }
        root = Path.of(configuredRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Private evidence content root is unavailable");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Private evidence content root is unavailable", exception);
        }
    }

    @Override
    public void put(String key, byte[] envelope) {
        Path target = resolve(key);
        if (envelope == null || envelope.length < 1 || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Invalid private evidence content");
        }
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, envelope, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (IOException exception) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    exception.addSuppressed(ignored);
                }
            }
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) < 1 || Files.size(target) > MAX_ENVELOPE_BYTES) {
                throw new IOException();
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid private evidence content key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid private evidence content key");
        }
        return resolved;
    }
}
