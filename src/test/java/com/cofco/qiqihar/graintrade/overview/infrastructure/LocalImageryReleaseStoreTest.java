package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LocalImageryReleaseStoreTest {
    @TempDir
    Path temporary;

    @Test
    void readsCurrentMetadataAndAnImmutableVersionedTile() throws Exception {
        var release = release("2026-W38");
        Files.createSymbolicLink(temporary.resolve("current"), release);
        var store = new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper());

        var metadata = store.currentMetadata().orElseThrow();
        var tile = store.tile("2026-W38", 14, 13871, 5612);

        assertThat(metadata.version()).isEqualTo("2026-W38");
        assertThat(metadata.spatialResolutionMeters()).isEqualTo(10);
        assertThat(metadata.status()).isEqualTo("CURRENT");
        assertThat(tile.bytes()).isEqualTo("weekly-tile".getBytes(StandardCharsets.UTF_8));
        assertThat(tile.contentType()).isEqualTo("image/webp");
        assertThat(tile.etag()).startsWith("\"").endsWith("\"");
    }

    @Test
    void rejectsAnInvalidVersionBeforeResolvingAPath() throws Exception {
        var store = new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper());

        assertThatThrownBy(() -> store.tile("../../etc", 14, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void readsMonthlyReleaseWithoutDroppingLegacyWeeklyReleases() throws Exception {
        var release = release("2026-09");
        Files.createSymbolicLink(temporary.resolve("current"), release);
        var store = new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper());

        assertThat(store.currentMetadata().orElseThrow().version()).isEqualTo("2026-09");
        assertThat(store.tile("2026-09", 14, 13871, 5612).bytes()).isNotEmpty();
    }

    @Test
    void readsRevisedMonthlyReleaseWithIndependentCacheVersion() throws Exception {
        var release = release("2026-09-r2");
        Files.createSymbolicLink(temporary.resolve("current"), release);
        var store = new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper());

        assertThat(store.currentMetadata().orElseThrow().version()).isEqualTo("2026-09-r2");
        assertThat(store.tile("2026-09-r2", 14, 13871, 5612).bytes()).isNotEmpty();
    }

    @Test
    void isDisabledWhenTheReleaseRootIsBlank() {
        var store = new LocalImageryReleaseStore("", new ObjectMapper());

        assertThat(store.currentMetadata()).isEmpty();
    }

    private Path release(String version) throws Exception {
        var release = temporary.resolve("releases").resolve(version);
        var tile = release.resolve("tiles/14/13871/5612.webp");
        Files.createDirectories(tile.getParent());
        Files.write(tile, "weekly-tile".getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                release.resolve("metadata.json"),
                """
                {
                  "version": "%s",
                  "provider": "Copernicus Sentinel-2 L2A",
                  "attribution": "European Union, Copernicus Sentinel-2 imagery",
                  "updateCadence": "WEEKLY",
                  "acquisitionFrom": "2026-09-18T02:00:00Z",
                  "acquisitionTo": "2026-09-20T02:00:00Z",
                  "syncedAt": "2026-09-21T03:10:00Z",
                  "spatialResolutionMeters": 10,
                  "cloudCoveragePercent": 8.5,
                  "status": "CURRENT",
                  "sourceProductIds": ["S2-test"],
                  "truthStatement": "Latest available observation; not live video."
                }
                """.formatted(version));
        return release;
    }
}
