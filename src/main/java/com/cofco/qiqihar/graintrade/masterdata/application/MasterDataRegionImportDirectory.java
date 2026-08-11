package com.cofco.qiqihar.graintrade.masterdata.application;

import com.cofco.qiqihar.graintrade.masterdata.importing.RegionImportDirectory;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MasterDataRegionImportDirectory implements RegionImportDirectory {
    private final MasterDataRepository repository;

    public MasterDataRegionImportDirectory(MasterDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RegionEntry> regions() {
        return repository.findRegions().stream()
                .map(region -> new RegionEntry(region.code(), region.name(), region.parentCode()))
                .toList();
    }
}
