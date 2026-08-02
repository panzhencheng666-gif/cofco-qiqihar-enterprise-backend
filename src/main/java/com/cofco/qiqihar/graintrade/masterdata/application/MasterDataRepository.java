package com.cofco.qiqihar.graintrade.masterdata.application;

import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import java.util.List;
import java.util.Optional;

public interface MasterDataRepository {

    List<Region> findRegions();

    List<Region> findRegionChildren(String parentCode);

    List<Region> findRegionPath(String regionCode);

    List<Product> findProducts();

    List<Product> findProductsWithPageDefinition(String domain, String pageKind);

    List<Cultivar> findCultivarsByProductCode(String productCode);

    List<ObjectType> findObjectTypes(String productCode, String domain);

    List<BusinessPeriod> findBusinessPeriods();

    List<BusinessBatch> findBusinessBatchesByPeriodCode(String businessPeriodCode);

    Optional<PageDefinition> findPageDefinition(String productCode, String domain, String pageKind);
}
