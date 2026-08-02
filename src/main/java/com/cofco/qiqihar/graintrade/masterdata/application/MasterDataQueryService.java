package com.cofco.qiqihar.graintrade.masterdata.application;

import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MasterDataQueryService implements MasterDataQuery {

    private final MasterDataRepository repository;

    public MasterDataQueryService(MasterDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Region> regions() {
        return repository.findRegions();
    }

    @Override
    public List<Region> regionChildren(String parentCode) {
        return repository.findRegionChildren(parentCode);
    }

    @Override
    public List<Product> products() {
        return repository.findProducts();
    }

    @Override
    public List<Cultivar> cultivars(String productCode) {
        return repository.findCultivarsByProductCode(productCode);
    }

    @Override
    public List<ObjectType> objectTypes(String productCode, String domain) {
        return repository.findObjectTypes(productCode, domain);
    }

    @Override
    public List<BusinessPeriod> businessPeriods() {
        return repository.findBusinessPeriods();
    }

    @Override
    public List<BusinessBatch> businessBatches(String businessPeriodCode) {
        return repository.findBusinessBatchesByPeriodCode(businessPeriodCode);
    }

    @Override
    public PageDefinition pageDefinition(String productCode, String domain, String pageKind) {
        return repository.findPageDefinition(productCode, domain, pageKind)
                .orElseThrow(() -> new ClientRequestException(
                        "MASTER_DATA_NOT_FOUND",
                        "Requested master data does not exist"));
    }
}
