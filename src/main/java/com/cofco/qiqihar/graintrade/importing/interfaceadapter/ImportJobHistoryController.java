package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportJobHistoryService;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportJobHistoryController {
    private final ImportJobHistoryService service;

    public ImportJobHistoryController(ImportJobHistoryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/imports/{domain:production|market|logistics}")
    ApiResponse<PageResponse> list(
            @PathVariable String domain,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize) {
        return new ApiResponse<>(PageResponse.from(service.list(domain, pageNumber, pageSize)));
    }

    record PageResponse(
            List<ImportJobView> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<ImportJobView> page) {
            return new PageResponse(page.items(), page.pageNumber(), page.pageSize(),
                    page.totalElements(), page.totalPages());
        }
    }
}
