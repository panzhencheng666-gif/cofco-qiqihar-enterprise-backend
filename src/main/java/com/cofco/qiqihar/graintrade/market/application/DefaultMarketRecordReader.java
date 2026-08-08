package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultMarketRecordReader implements MarketRecordReader {

    private final MarketRecordRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final AccessControl accessControl;

    public DefaultMarketRecordReader(
            MarketRecordRepository repository, PageDefinitionQuery pageDefinitions) {
        this(repository, pageDefinitions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMarketRecordReader(
            MarketRecordRepository repository, PageDefinitionQuery pageDefinitions, AccessControl accessControl) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.accessControl = accessControl;
    }

    @Override
    public PagedResult<MarketRecord> read(MarketRecordQuery query) {
        if (!pageDefinitions.allowsListQuery(
                "MARKET",
                query.pageKind(),
                query.productCode(),
                query.pageSize(),
                query.filters().keySet())) {
            throw new ClientRequestException(
                    "INVALID_MARKET_RECORD_QUERY",
                    "Market record query is not allowed by the page definition");
        }
        AuthorizedReadScope scope = accessControl == null
                ? AuthorizedReadScope.unrestricted() : accessControl.requireReadScope();
        return repository.findPage(query.authorizedFor(scope.regionCodes()));
    }
}
