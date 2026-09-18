package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;

class RegionalPublicDataRefreshWorkerTest {
    @Test void weatherRefreshClaimsOnlyAControlledBatch() {
        var repository=mock(RegionalPublicDataRepository.class);
        when(repository.claimDueWeather(any(),any(),eq(4))).thenReturn(List.of());

        new RegionalPublicDataRefreshWorker(repository,mock(RegionalSourceDiscovery.class)).refreshDueWeather();

        verify(repository).claimDueWeather(any(),any(),eq(4));
        verify(repository,never()).due(any());
    }
    @Test void detectsChangesBeyondTheShortDisplayExcerpt() throws Exception {
        var body = new AtomicReference<>("农业概况。粮食情况。农田情况。乡村情况。补贴金额10万元。");
        withSource("GENERIC_PAGE", body, (worker, repository) -> {
            worker.retryDueSources();
            body.set("农业概况。粮食情况。农田情况。乡村情况。补贴金额20万元。");
            worker.retryDueSources();
            var hashes = ArgumentCaptor.forClass(String.class);
            verify(repository,times(2)).recordPageSuccess(eq("proof-page"),any(),hashes.capture(),anyString());
            assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
        });
    }

    @Test void doesNotVerifyAnHttp200ChallengePage() throws Exception {
        withSource("GENERIC_PAGE",new AtomicReference<>("<html><body>请完成验证后继续访问</body></html>"), (worker, repository) -> {
            worker.retryDueSources();
            verify(repository).recordFailure(eq("proof-page"),any(),anyString());
            verify(repository,never()).recordPageSuccess(anyString(),any(),anyString(),anyString());
        });
    }

    @Test void recordsMissingCropFactsAsFailureAndDoesNotWriteMetrics() throws Exception {
        withSource("DXAL_REPORT",new AtomicReference<>("2025年大兴安岭地区国民经济和社会发展统计公报。正文暂不可用。"), (worker, repository) -> {
            worker.retryDueSources();
            verify(repository).recordFailure(eq("proof-page"),any(),anyString());
            verify(repository,never()).recordPageSuccess(anyString(),any(),anyString(),anyString());
            verify(repository,never()).recordCropMetrics(anyString(),anyList(),any());
        });
    }

    private void withSource(String parser, AtomicReference<String> body,
            java.util.function.BiConsumer<RegionalPublicDataRefreshWorker,RegionalPublicDataRepository> check) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var repository=mock(RegionalPublicDataRepository.class);
            when(repository.due(any())).thenReturn(List.of(new RegionalPublicDataRepository.DueSource(
                    "proof-page","232700","AGRICULTURE","原文核验",
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/",parser)));
            check.accept(new RegionalPublicDataRefreshWorker(repository,mock(RegionalSourceDiscovery.class)),repository);
        } finally { server.stop(0); }
    }
    @Test
    void readsOpenMeteoCurrentValuesUsedByTheDailyWeatherModel() {
        String json = """
                {"current_units":{"time":"iso8601"},
                "current":{"time":"2026-09-14T10:15","temperature_2m":18.4,
                "precipitation":1.2,"soil_moisture_0_to_1cm":0.27}}
                """;
        assertThat(RegionalPublicDataRefreshWorker.number(json, "temperature_2m"))
                .isEqualByComparingTo("18.4");
        assertThat(RegionalPublicDataRefreshWorker.number(json, "soil_moisture_0_to_1cm"))
                .isEqualByComparingTo("0.27");
        assertThat(RegionalPublicDataRefreshWorker.observedAt(json, Instant.EPOCH))
                .isEqualTo("2026-09-14T02:15:00Z");
    }
}
