package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;

public interface MarketRecordRepository {

    boolean pageContextExists(String productCode, String pageKind);

    PagedResult<MarketRecord> findPage(MarketRecordQuery query);
}
