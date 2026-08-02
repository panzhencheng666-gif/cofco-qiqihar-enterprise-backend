package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultMarketRecordReader implements MarketRecordReader {

    private final MarketRecordRepository repository;

    public DefaultMarketRecordReader(MarketRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public PagedResult<MarketRecord> read(MarketRecordQuery query) {
        if (!repository.pageContextExists(query.productCode(), query.pageKind())) {
            throw new ResourceNotFoundException(
                    "PAGE_DEFINITION_NOT_FOUND",
                    "Requested page definition does not exist");
        }
        return repository.findPage(query);
    }
}
