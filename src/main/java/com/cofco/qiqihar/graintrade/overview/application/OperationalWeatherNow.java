package com.cofco.qiqihar.graintrade.overview.application;

import java.util.Optional;

public interface OperationalWeatherNow {
    Optional<OperationalSituationCatalogue.WeatherObservation> forRegion(String regionCode);
}
