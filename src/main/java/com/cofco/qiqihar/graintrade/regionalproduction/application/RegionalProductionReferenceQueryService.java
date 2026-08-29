package com.cofco.qiqihar.graintrade.regionalproduction.application;

import com.cofco.qiqihar.graintrade.regionalproduction.api.RegionalProductionReferenceQuery;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegionalProductionReferenceQueryService implements RegionalProductionReferenceQuery {
    private final RegionalCropAnnualStatRepository repository;

    public RegionalProductionReferenceQueryService(RegionalCropAnnualStatRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegionReference> findRegion(String regionCode) {
        return repository.region(regionCode).map(region -> new RegionReference(
                region.code(), region.name(), region.parentCode(), region.administrativeLevel()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean supportsProduct(String productCode) {
        return repository.knownProduct(productCode);
    }
}
