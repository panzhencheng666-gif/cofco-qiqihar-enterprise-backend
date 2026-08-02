package com.cofco.qiqihar.graintrade.workflow.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
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

    public DefaultWorkItemReader(
            WorkItemRepository repository,
            PageDefinitionQuery pageDefinitions) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
    }

    @Override
    public PagedResult<WorkItem> read(WorkItemQuery query) {
        if (!repository.allowsFilters(query)
                || !pageDefinitions.allowsListQuery(
                        "WORKFLOW", "WORK_ITEMS", null, query.pageSize(), Set.of())) {
            throw new ClientRequestException(
                    "INVALID_WORK_ITEM_QUERY", "Work item query is not allowed by the page definition");
        }
        return repository.findPage(query);
    }
}
