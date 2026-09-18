package com.cofco.qiqihar.graintrade.overview.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalSituationService {
    private final OperationalSituationRepository repository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OperationalSituationService(OperationalSituationRepository repository) {
        this(repository, Clock.systemUTC());
    }

    OperationalSituationService(OperationalSituationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperationalSituationCatalogue current() {
        Instant now = clock.instant();
        var snapshot = repository.snapshot();
        var sources = new ArrayList<OperationalSituationCatalogue.SourceStatus>();
        snapshot.refreshStates().forEach(state -> sources.add(
                new OperationalSituationCatalogue.SourceStatus(
                        state.sourceCode(), state.sourceName(), status(state.lastSuccessAt(), now, Duration.ofHours(6)),
                        state.lastAttemptAt(), state.lastSuccessAt(), state.sourceUrl(),
                        state.lastError() == null
                                ? "仅展示 NASA EONET 最近30天开放事件；地图范围内无事件时明确显示为零。"
                                : "本次刷新失败，继续使用上次成功快照：" + state.lastError())));
        Instant latestWeather = snapshot.weather().stream()
                .map(OperationalSituationCatalogue.WeatherObservation::fetchedAt)
                .max(Instant::compareTo).orElse(null);
        sources.add(new OperationalSituationCatalogue.SourceStatus(
                "OPEN_METEO", "Open-Meteo 区域天气", status(latestWeather, now, Duration.ofMinutes(45)),
                latestWeather, latestWeather, "https://open-meteo.com/",
                "后端每15分钟限频同步区域代表坐标，页面读取本地快照；失败时保留最近一次成功观测。"));
        return new OperationalSituationCatalogue(
                now, snapshot.weather(), snapshot.events(), snapshot.policies(), List.copyOf(sources));
    }

    private static String status(Instant lastSuccess, Instant now, Duration readyFor) {
        if (lastSuccess == null) return "UNAVAILABLE";
        return lastSuccess.plus(readyFor).isAfter(now) ? "READY" : "STALE";
    }
}
