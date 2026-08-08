package com.cofco.qiqihar.graintrade.workflow.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultWorkItemReader implements WorkItemReader {

    private final WorkItemRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final AccessControl accessControl;

    public DefaultWorkItemReader(
            WorkItemRepository repository,
            PageDefinitionQuery pageDefinitions) {
        this(repository, pageDefinitions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultWorkItemReader(
            WorkItemRepository repository,
            PageDefinitionQuery pageDefinitions,
            AccessControl accessControl) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.accessControl = accessControl;
    }

    @Override
    public PagedResult<WorkItem> read(WorkItemQuery query) {
        AuthorizedReadScope scope = accessControl == null
                ? AuthorizedReadScope.unrestricted() : accessControl.requireReadScope();
        if (query.regionId() != null) scope.requireRegion(query.regionId());
        WorkItemQuery authorizedQuery = query.authorizedFor(scope.regionCodes());
        if (!repository.allowsFilters(query)
                || !pageDefinitions.allowsListQuery(
                        "WORKFLOW", "WORK_ITEMS", null, query.pageSize(), Set.of())) {
            throw new ClientRequestException(
                    "INVALID_WORK_ITEM_QUERY", "Work item query is not allowed by the page definition");
        }
        return repository.findPage(authorizedQuery);
    }
}
