package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OfficialVideoPlaybackControllerTest {
    @Test
    void readsTheOfficialDetailPlayerRatherThanNavigationOrTrackingFrames() {
        var html = """
                <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-123"></iframe>
                <a href="https://www.youtube.com/watch?v=0EY87thoLuo">Channel</a>
                <div class="col-12 detail-page detail-video">
                  <iframe src='https://www.youtube.com/embed/X4tcMkJQnfI' title='YouTube video player'></iframe>
                </div>
                """;
        assertThat(OfficialVideoPlaybackController.parsePlayer(html)).isEqualTo("X4tcMkJQnfI");
    }

    @Test
    void acceptsTheOfficialPrivacyEnhancedPlayer() {
        assertThat(OfficialVideoPlaybackController.parsePlayer("""
                <div class="detail-video"><iframe src="https://www.youtube-nocookie.com/embed/X4tcMkJQnfI?rel=0"></iframe></div>
                """)).isEqualTo("X4tcMkJQnfI");
    }

    @Test
    void rejectsSpoofedHostsCredentialsAndUnrelatedFrames() {
        for (var src : new String[] { "https://www.youtube.com.evil.example/embed/X4tcMkJQnfI",
                "https://user@www.youtube.com/embed/X4tcMkJQnfI", "http://www.youtube.com/embed/X4tcMkJQnfI",
                "https://www.youtube.com:8443/embed/X4tcMkJQnfI", "https://www.youtube.com/embed/invalid" }) {
            assertThatThrownBy(() -> OfficialVideoPlaybackController.parsePlayer(
                    "<div class='detail-video'><iframe src='" + src + "'></iframe></div>"))
                    .hasMessageContaining("404");
        }
        assertThatThrownBy(() -> OfficialVideoPlaybackController.parsePlayer(
                "<iframe src='https://www.youtube.com/embed/X4tcMkJQnfI'></iframe>"))
                .hasMessageContaining("404");
    }

    @Test
    void onlyAllowsAnExactOfficialDetailPageAsTheFetchTarget() {
        assertThat(OfficialVideoPlaybackController.officialPage(
                "https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/soco/en").getHost())
                .isEqualTo("www.fao.org");
        for (var url : new String[] { "https://www.fao.org.evil.example/markets-and-trade/news-and-events/multimedia/video-detail/x/en",
                "https://user@www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/x/en",
                "https://www.fao.org:8443/markets-and-trade/news-and-events/multimedia/video-detail/x/en",
                "https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/../other/en",
                "https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/%2e%2e/other/en",
                "https://www.fao.org/webcast/", "https://127.0.0.1/", "malformed" }) {
            assertThatThrownBy(() -> OfficialVideoPlaybackController.officialPage(url)).hasMessageContaining("400");
        }
    }

    @Test
    void doesNotFetchAnOfficialPageAbsentFromTheSavedCatalogue() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).param(eq("url"), any()).query(Boolean.class).single()).thenReturn(false);
        var controller = new OfficialVideoPlaybackController(jdbc);
        assertThatThrownBy(() -> controller.resolve(
                "https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/unknown/en"))
                .hasMessageContaining("404").hasMessageContaining("not in official catalogue");
    }

    @Test
    void readsTheOriginalOfficialWebcastRecording() {
        var result = OfficialVideoPlaybackController.parsePlayback("""
                <div class="detail-webcast"><video id="webcastPlayer" controls>
                  <source src="https://vod.fao.org/video/20261001-COFO28-Day4-Plenary-EV-floor.mp4" type="video/mp4">
                </video></div>
                <a data-src="https://evil.example/video.mp4">Other</a>
                """);
        assertThat(result.mediaUrl()).isEqualTo("https://vod.fao.org/video/20261001-COFO28-Day4-Plenary-EV-floor.mp4");
        assertThat(result.id()).isNull();
    }

    @Test
    void rejectsAnUnofficialMediaHostAndAPageWithoutAPlayer() {
        assertThatThrownBy(() -> OfficialVideoPlaybackController.parsePlayback("""
                <div class="detail-webcast"><video id="webcastPlayer"><source src="https://vod.fao.org.evil.example/video/recording.mp4"></video></div>
                """)).hasMessageContaining("404");
        assertThatThrownBy(() -> OfficialVideoPlaybackController.parsePlayback("<div class='detail-webcast'>Not started</div>"))
                .hasMessageContaining("404");
    }
}
