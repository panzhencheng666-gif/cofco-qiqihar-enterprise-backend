package com.cofco.qiqihar.graintrade.shared.audit.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditQueryService;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditView;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
public class BusinessAuditController {
    private final BusinessAuditQueryService service;

    public BusinessAuditController(BusinessAuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResponse> events(
            @RequestParam(required = false) String workUnitCode,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String actorSubjectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return new ApiResponse<>(PageResponse.from(service.events(
                workUnitCode, aggregateType, actorSubjectId, occurredFrom, occurredTo, page, pageSize)));
    }

    record PageResponse(
            List<BusinessAuditView> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<BusinessAuditView> result) {
            return new PageResponse(result.items(), result.pageNumber(), result.pageSize(),
                    result.totalElements(), result.totalPages());
        }
    }
}
