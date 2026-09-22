package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.infrastructure.MapImageryTileGateway;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OverviewMapImageryController {
    private static final String CACHE_CONTROL =
            "public, max-age=86400, stale-if-error=1209600";
    private static final String IMMUTABLE_CACHE_CONTROL =
            "public, max-age=31536000, immutable";

    private final AccessControl access;
    private final MapImageryTileGateway imagery;

    public OverviewMapImageryController(AccessControl access, MapImageryTileGateway imagery) {
        this.access = access;
        this.imagery = imagery;
    }

    @GetMapping("/api/v1/overview/map-imagery/metadata")
    ApiResponse<MapImageryTileGateway.Metadata> metadata() {
        access.requireOverviewReadScope();
        return new ApiResponse<>(imagery.metadata());
    }

    @GetMapping("/api/v1/overview/map-imagery/tiles/{zoom}/{x}/{y}")
    ResponseEntity<byte[]> tile(
            @PathVariable int zoom,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        access.requireOverviewReadScope();
        try {
            var tile = imagery.tile(zoom, x, y);
            var headers = headers(tile);
            if (tile.etag().equals(ifNoneMatch)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .headers(headers)
                        .build();
            }
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.parseMediaType(tile.contentType()))
                    .body(tile.bytes());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Imagery request interrupted");
        } catch (IOException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Imagery provider is temporarily unavailable");
        }
    }

    @GetMapping("/api/v1/overview/map-imagery/tiles/{version}/{zoom}/{x}/{y}")
    ResponseEntity<byte[]> versionedTile(
            @PathVariable String version,
            @PathVariable int zoom,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        access.requireOverviewReadScope();
        try {
            var tile = imagery.tile(version, zoom, x, y);
            var headers = headers(tile, IMMUTABLE_CACHE_CONTROL);
            if (tile.etag().equals(ifNoneMatch)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
            }
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.parseMediaType(tile.contentType()))
                    .body(tile.bytes());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IOException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Imagery release tile is unavailable");
        }
    }

    private static HttpHeaders headers(MapImageryTileGateway.Tile tile) {
        return headers(tile, CACHE_CONTROL);
    }

    private static HttpHeaders headers(MapImageryTileGateway.Tile tile, String cacheControl) {
        var headers = new HttpHeaders();
        headers.setCacheControl(cacheControl);
        headers.setETag(tile.etag());
        headers.set(HttpHeaders.VARY, "Cookie");
        headers.set("X-Imagery-Period", tile.period());
        if (tile.stale()) headers.set(HttpHeaders.WARNING, "110 - Stale imagery response");
        return headers;
    }
}
