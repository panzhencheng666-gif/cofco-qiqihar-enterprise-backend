package com.cofco.qiqihar.graintrade.workflow.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationExportView;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationReportCommand;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationReportRepository;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationReportService;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationWeeklyReport;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/work-obligation-reports")
public class WorkObligationReportController {
    private static final Set<String> PARAMETERS = Set.of(
            "weekStart", "subjectId", "workUnitCode", "businessDomain", "regionCode");
    private final WorkObligationReportService service;

    public WorkObligationReportController(WorkObligationReportService service) {
        this.service = service;
    }

    @GetMapping("/weekly")
    ApiResponse<WorkObligationWeeklyReport> weekly(
            @RequestParam MultiValueMap<String, String> parameters) {
        return new ApiResponse<>(service.weekly(command(parameters)));
    }

    @PostMapping("/weekly/exports")
    ApiResponse<WorkObligationExportView> export(@RequestBody ReportRequest request) {
        return new ApiResponse<>(service.export(request == null ? null : request.command()));
    }

    @GetMapping("/exports/{exportId}/content")
    ResponseEntity<byte[]> download(@PathVariable String exportId) {
        WorkObligationReportRepository.ExportContent export = service.download(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }

    private static WorkObligationReportCommand command(MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters, PARAMETERS::contains, WorkObligationReportController::invalid);
        try {
            return new WorkObligationReportCommand(
                    LocalDate.parse(parsed.required("weekStart")),
                    parsed.optional("subjectId"), parsed.optional("workUnitCode"),
                    parsed.optional("businessDomain"), parsed.optional("regionCode"));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    record ReportRequest(
            LocalDate weekStart,
            String subjectId,
            String workUnitCode,
            String businessDomain,
            String regionCode) {
        WorkObligationReportCommand command() {
            return new WorkObligationReportCommand(
                    weekStart, subjectId, workUnitCode, businessDomain, regionCode);
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_WORK_OBLIGATION_REPORT", "Work obligation report request is invalid");
    }
}
