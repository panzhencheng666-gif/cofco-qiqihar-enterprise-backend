package com.cofco.qiqihar.graintrade.workflow.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import com.cofco.qiqihar.graintrade.workflow.application.WorkItemReader;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemScope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkItemController {

    private static final Set<String> PARAMETERS = Set.of(
            "scope", "status", "domain", "regionId", "productCode", "page", "pageSize");

    private final WorkItemReader reader;

    public WorkItemController(WorkItemReader reader) {
        this.reader = reader;
    }

    @GetMapping("/api/v1/work-items")
    ApiResponse<PageResponse> workItems(@RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters, PARAMETERS::contains, WorkItemController::invalidQuery);
        try {
            WorkItemQuery query = WorkItemQuery.of(
                    WorkItemScope.valueOf(parsed.required("scope")),
                    parsed.optional("status"),
                    parsed.optional("domain"),
                    parsed.optional("regionId"),
                    parsed.optional("productCode"),
                    parsed.integer("page", 0),
                    parsed.integer("pageSize", 20));
            return new ApiResponse<>(PageResponse.from(reader.read(query)));
        } catch (IllegalArgumentException exception) {
            throw invalidQuery();
        }
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException(
                "INVALID_WORK_ITEM_QUERY", "Work item query context is invalid");
    }

    record WorkItemResponse(
            String id,
            String task,
            String domain,
            String regionCode,
            String region,
            String product,
            String businessPeriod,
            OffsetDateTime dueAt,
            String workflowNode,
            String statusCode,
            String status,
            String responsiblePartyCode,
            String responsibleParty) {
        static WorkItemResponse from(WorkItem item) {
            return new WorkItemResponse(
                item.id(), item.task(), item.domain(), item.regionCode(), item.region(),
                item.product(),
                item.businessPeriod(), item.dueAt(), item.workflowNode(), item.statusCode(),
                item.status(), item.responsiblePartyCode(), item.responsibleParty());
        }
    }

    record PageResponse(
            List<WorkItemResponse> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<WorkItem> page) {
            return new PageResponse(
                    page.items().stream().map(WorkItemResponse::from).toList(),
                    page.pageNumber(), page.pageSize(), page.totalElements(), page.totalPages());
        }
    }
}
