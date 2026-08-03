package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OverviewService {
    private final OverviewRepository repository;
    public OverviewService(OverviewRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<OverviewRegion> regions(String parentCode, String productCode, String periodCode) {
        if (blank(productCode) || blank(periodCode) || !repository.knownProduct(productCode)
                || !repository.knownPeriod(periodCode) || (parentCode != null && !repository.knownRegion(parentCode))) throw invalid();
        return repository.regions(parentCode, productCode, periodCode);
    }

    @Transactional(readOnly = true)
    public List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear) {
        if (blank(productCode) || blank(regionCode) || blank(periodCode) || blank(marketingYear)
                || !repository.knownProduct(productCode) || !repository.knownRegion(regionCode)
                || !repository.knownPeriod(periodCode)) throw invalid();
        return repository.indicators(productCode, regionCode, periodCode, marketingYear);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_OVERVIEW_QUERY", "Overview query context is invalid"); }
}
