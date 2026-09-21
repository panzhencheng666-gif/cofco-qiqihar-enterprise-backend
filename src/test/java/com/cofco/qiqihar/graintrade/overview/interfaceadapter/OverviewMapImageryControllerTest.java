package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.overview.infrastructure.MapImageryTileGateway;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class OverviewMapImageryControllerTest {
    @Test
    void returnsAReusableSameOriginTileAfterEnforcingOverviewAccess() throws Exception {
        var access = mock(AccessControl.class);
        var gateway = mock(MapImageryTileGateway.class);
        var tile = new MapImageryTileGateway.Tile(
                "image".getBytes(StandardCharsets.UTF_8),
                "image/webp",
                "\"digest\"",
                "2026-08",
                false);
        when(gateway.tile(8, 201, 93)).thenReturn(tile);
        var controller = new OverviewMapImageryController(access, gateway);

        var response = controller.tile(8, 201, 93, null);

        verify(access).requireOverviewReadScope();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("public, max-age=86400, stale-if-error=604800");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"digest\"");
        assertThat(response.getHeaders().getFirst("X-Imagery-Period")).isEqualTo("2026-08");
        assertThat(response.getBody()).containsExactly("image".getBytes(StandardCharsets.UTF_8));

        var notModified = controller.tile(8, 201, 93, "\"digest\"");
        assertThat(notModified.getStatusCode().value()).isEqualTo(304);
        assertThat(notModified.getBody()).isNull();
        assertThat(notModified.getHeaders().getFirst(HttpHeaders.VARY)).isEqualTo("Cookie");
    }
}
