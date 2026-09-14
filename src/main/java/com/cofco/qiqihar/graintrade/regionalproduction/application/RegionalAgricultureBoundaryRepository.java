package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.util.Optional;

public interface RegionalAgricultureBoundaryRepository {
    Optional<BigDecimal> areaSquareMetres(String regionCode);
}
