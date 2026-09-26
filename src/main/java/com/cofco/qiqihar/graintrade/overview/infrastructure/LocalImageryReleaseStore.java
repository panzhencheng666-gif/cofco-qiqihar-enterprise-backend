package com.cofco.qiqihar.graintrade.overview.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalImageryReleaseStore {
    private static final Pattern VERSION = Pattern.compile("[0-9]{4}-(?:W[0-9]{2}|[0-9]{2})(?:-r(?:[2-9]|[1-9][0-9]))?");
    private static final int MAXIMUM_TILE_BYTES = 5 * 1024 * 1024;

    private final Path root;
    private final ObjectMapper json;

    public LocalImageryReleaseStore(
            @Value("${qiqihar.map-imagery.release-root:}") String releaseRoot,
            ObjectMapper json) {
        this.root = releaseRoot.isBlank() ? null : Path.of(releaseRoot).toAbsolutePath().normalize();
        this.json = json;
    }

    public Optional<ReleaseMetadata> currentMetadata() {
        if (root == null) return Optional.empty();
        try {
            Path release = currentRelease();
            var metadata = readMetadata(release);
            if (!release.getFileName().toString().equals(metadata.version())) {
                throw new IOException("Imagery release directory does not match metadata version");
            }
            return Optional.of(metadata);
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    public LocalTile currentTile(int zoom, int x, int y) throws IOException {
        var metadata = currentMetadata().orElseThrow(() -> new IOException("No local imagery release"));
        return tile(metadata.version(), zoom, x, y);
    }

    public LocalTile tile(String version, int zoom, int x, int y) throws IOException {
        if (root == null) throw new IOException("Local imagery releases are disabled");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid imagery version");
        }
        validateCoordinate(zoom, x, y);
        Path releases = root.resolve("releases").normalize();
        Path release = releases.resolve(version).normalize();
        if (!release.startsWith(releases)) throw new IllegalArgumentException("Invalid imagery version");
        Path tile = release.resolve("tiles")
                .resolve(Integer.toString(zoom))
                .resolve(Integer.toString(x))
                .resolve(y + ".webp")
                .normalize();
        if (!tile.startsWith(release) || !Files.isRegularFile(tile)) {
            throw new IOException("Local imagery tile is unavailable");
        }
        byte[] bytes = Files.readAllBytes(tile);
        if (bytes.length == 0 || bytes.length > MAXIMUM_TILE_BYTES) {
            throw new IOException("Local imagery tile has an invalid size");
        }
        return new LocalTile(bytes, "image/webp", etag(bytes), version);
    }

    private Path currentRelease() throws IOException {
        Path current = root.resolve("current");
        if (!Files.exists(current)) throw new IOException("Current imagery release is unavailable");
        Path releases = root.resolve("releases").toRealPath();
        Path release = current.toRealPath();
        if (!release.startsWith(releases) || !Files.isDirectory(release)) {
            throw new IOException("Current imagery release escapes the governed root");
        }
        return release;
    }

    private ReleaseMetadata readMetadata(Path release) throws IOException {
        try {
            return json.readValue(release.resolve("metadata.json").toFile(), ReleaseMetadata.class);
        } catch (RuntimeException invalid) {
            throw new IOException("Imagery release metadata is invalid", invalid);
        }
    }

    private static void validateCoordinate(int zoom, int x, int y) {
        if (zoom < 0 || zoom > 18) throw new IllegalArgumentException("Invalid imagery zoom");
        int limit = 1 << zoom;
        if (x < 0 || y < 0 || x >= limit || y >= limit) {
            throw new IllegalArgumentException("Invalid imagery tile coordinate");
        }
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record LocalTile(byte[] bytes, String contentType, String etag, String version) {
        public LocalTile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ReleaseMetadata(
            String version,
            String provider,
            String attribution,
            String updateCadence,
            String acquisitionFrom,
            String acquisitionTo,
            String syncedAt,
            int spatialResolutionMeters,
            double cloudCoveragePercent,
            String status,
            List<String> coverageRegionCodes,
            List<String> sourceProductIds,
            String truthStatement) {
        public ReleaseMetadata {
            coverageRegionCodes = coverageRegionCodes == null ? List.of() : List.copyOf(coverageRegionCodes);
            sourceProductIds = List.copyOf(sourceProductIds);
        }
    }
}
