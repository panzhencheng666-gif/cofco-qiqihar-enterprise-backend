package com.cofco.qiqihar.graintrade.masterdata.application;

import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import java.util.List;

public interface MasterDataQuery {

    List<Region> regions();

    List<Region> regionChildren(String parentCode);

    List<Region> regionPath(String regionCode);

    List<Product> products();

    List<Product> productsWithPageDefinition(String domain, String pageKind);

    List<Cultivar> cultivars(String productCode);

    List<ObjectType> objectTypes(String productCode, String domain);

    List<BusinessPeriod> businessPeriods();

    List<BusinessBatch> businessBatches(String businessPeriodCode);

    PageDefinition pageDefinition(String productCode, String domain, String pageKind);
}
