package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemEvidenceContentStoreTest {
    @TempDir Path root;

    @Test
    void atomicallyRoundTripsAndDeletesOneGeneratedPrivateObject() {
        var store = new FilesystemEvidenceContentStore(root.toString());
        String key = "evidence/12/123e4567-e89b-12d3-a456-426614174000.evp";
        byte[] content = {1, 2, 3, 4};

        store.put(key, content);
        content[0] = 99;

        assertThat(store.get(key)).containsExactly(1, 2, 3, 4);
        store.delete(key);
        assertThatThrownBy(() -> store.get(key))
                .isInstanceOf(EvidenceContentUnavailableException.class)
                .hasMessage("Private evidence content is temporarily unavailable");
    }

    @Test
    void rejectsTraversalAbsoluteAndNonCanonicalKeys() throws Exception {
        var store = new FilesystemEvidenceContentStore(root.toString());

        for (String key : new String[] {
                "../outside.evp", "/tmp/outside.evp", "evidence/12/../outside.evp",
                "evidence/12/not-a-uuid.evp", "evidence/FF/123e4567-e89b-12d3-a456-426614174000.evp"
        }) {
            assertThatThrownBy(() -> store.put(key, new byte[] {1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid private evidence content key");
        }
        try (var files = Files.list(root)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void failsClosedWhenConfiguredRootIsNotADirectory() throws Exception {
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "occupied");

        assertThatThrownBy(() -> new FilesystemEvidenceContentStore(file.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Private evidence content root is unavailable");
    }
}
