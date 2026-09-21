package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteArtifactStoreTest {
    @TempDir
    java.nio.file.Path root;

    @Test
    void writesOnceAndRereadsTheExactBundle() throws Exception {
        byte[] content="adapter-bundle".getBytes(StandardCharsets.UTF_8);
        byte[] bundle=bundle("adapters.safetensors",content);
        String bundleHash=sha256(bundle);
        String contentHash=canonicalHash("adapters.safetensors",content);
        UUID runId=UUID.randomUUID();
        RemoteArtifactStore store=new RemoteArtifactStore(root.toString(),1024);

        StoredRemoteArtifact first=store.store(
                runId,bundleHash,contentHash,bundle);
        StoredRemoteArtifact repeated=store.store(
                runId,bundleHash,contentHash,bundle);

        assertThat(first).isEqualTo(repeated);
        assertThat(Files.readAllBytes(root.resolve(bundleHash+".artifact/artifact.bundle")))
                .isEqualTo(bundle);
        assertThat(store.matches(runId,first.artifactReference(),bundleHash)).isTrue();
        assertThat(store.matches(UUID.randomUUID(),first.artifactReference(),bundleHash)).isFalse();
    }

    @Test
    void rejectsBytesThatDoNotMatchTheDeclaredHash() throws Exception {
        RemoteArtifactStore store=new RemoteArtifactStore(root.toString(),1024);

        byte[] content="tampered".getBytes(StandardCharsets.UTF_8);
        byte[] bundle=bundle("adapters.safetensors",content);
        assertThatThrownBy(() -> store.store(UUID.randomUUID(),"b".repeat(64),
                canonicalHash("adapters.safetensors",content),bundle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void streamsWithABoundedLimitAndRejectsOversizedBodies() {
        RemoteArtifactStore store=new RemoteArtifactStore(root.toString(),4);
        byte[] bundle="12345".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.store(UUID.randomUUID(),sha256(bundle),
                "a".repeat(64),new ByteArrayInputStream(bundle),-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小");
    }

    @Test
    void detectsTamperingBeforeCompletionMatching() throws Exception {
        byte[] content="adapter-bundle".getBytes(StandardCharsets.UTF_8);
        byte[] bundle=bundle("adapters.safetensors",content);
        String bundleHash=sha256(bundle);
        UUID runId=UUID.randomUUID();
        RemoteArtifactStore store=new RemoteArtifactStore(root.toString(),1024);
        StoredRemoteArtifact artifact=store.store(runId,bundleHash,
                canonicalHash("adapters.safetensors",content),bundle);
        Files.writeString(root.resolve(bundleHash+".artifact/artifact.bundle"),"tampered");

        assertThat(store.matches(runId,artifact.artifactReference(),bundleHash)).isFalse();
    }

    private static byte[] bundle(String name,byte[] content) throws Exception {
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try (var gzip=new GzipCompressorOutputStream(compressed);
             var tar=new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry=new TarArchiveEntry(name);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return compressed.toByteArray();
    }

    private static String canonicalHash(String name,byte[] content) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
        digest.update(content);
        digest.update((byte)0);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
