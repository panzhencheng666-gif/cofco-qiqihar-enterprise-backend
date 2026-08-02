package com.cofco.qiqihar.graintrade.workflow.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;

public interface WorkItemReader {
    PagedResult<WorkItem> read(WorkItemQuery query);
}
