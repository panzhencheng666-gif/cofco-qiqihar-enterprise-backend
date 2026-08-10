package com.cofco.qiqihar.graintrade.workflow.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.workflow.infrastructure.WorkObligationWorkbook;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkObligationReportService {
    private static final Set<String> DOMAINS = Set.of("PRODUCTION", "MARKET", "LOGISTICS");
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final WorkObligationReportRepository repository;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public WorkObligationReportService(
            WorkObligationReportRepository repository,
            AccessControl accessControl,
            BusinessAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkObligationWeeklyReport weekly(WorkObligationReportCommand command) {
        SecurityPrincipal principal = accessControl.require(
                "OBLIGATION_REPORT_READ", command == null ? null : trim(command.regionCode()));
        return repository.findWeekly(authorize(command, principal), clock.instant());
    }

    @Transactional
    public WorkObligationExportView export(WorkObligationReportCommand command) {
        SecurityPrincipal principal = accessControl.require(
                "OBLIGATION_REPORT_EXPORT", command == null ? null : trim(command.regionCode()));
        WorkObligationReportRepository.Query query = authorize(command, principal);
        WorkObligationWeeklyReport report = repository.findWeekly(query, clock.instant());
        byte[] content = WorkObligationWorkbook.create(report);
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        String checksum = sha256(content);
        String scope = safe(report.scopeLabel() == null ? principal.displayName() : report.scopeLabel());
        String filename = "填报履职周报-" + query.weekStart() + "-" + scope + ".xlsx";
        repository.persistExport(new WorkObligationReportRepository.Export(
                id, query, principal.subjectId(), now, filename, XLSX, checksum, content));
        audit.record(principal, "WORK_OBLIGATION_REPORT", id, "WORK_OBLIGATION_REPORT_EXPORTED", now,
                "{\"weekStart\":\"" + query.weekStart() + "\",\"rowCount\":" + report.rows().size() + "}");
        return new WorkObligationExportView(id, filename, XLSX, checksum, now);
    }

    @Transactional
    public WorkObligationReportRepository.ExportContent download(String exportId) {
        if (blank(exportId)) throw invalid();
        SecurityPrincipal principal = accessControl.require("OBLIGATION_REPORT_EXPORT", null);
        WorkObligationReportRepository.ExportContent export = repository.findExport(exportId);
        if (export == null) {
            throw new ResourceNotFoundException(
                    "WORK_OBLIGATION_EXPORT_NOT_FOUND", "Work obligation export was not found");
        }
        if (!export.generatedBy().equals(principal.subjectId())
                && !principal.roleCodes().contains("SYSTEM_ADMIN")) {
            throw denied();
        }
        audit.record(principal, "WORK_OBLIGATION_REPORT", export.id(),
                "WORK_OBLIGATION_REPORT_DOWNLOADED", clock.instant(), "{}");
        return export;
    }

    private WorkObligationReportRepository.Query authorize(
            WorkObligationReportCommand command, SecurityPrincipal principal) {
        validate(command);
        String subjectId = trim(command.subjectId());
        String workUnitCode = trim(command.workUnitCode());
        String domain = trim(command.businessDomain());
        String region = trim(command.regionCode());
        if (subjectId == null && workUnitCode == null) {
            subjectId = principal.subjectId();
            workUnitCode = principal.workUnitCode();
        }
        String employeeUnit = subjectId == null ? null : repository.employeeWorkUnit(subjectId);
        if (subjectId != null && employeeUnit == null) throw denied();
        if (workUnitCode == null) workUnitCode = employeeUnit;
        if (employeeUnit != null && !employeeUnit.equals(workUnitCode)) throw invalid();

        boolean own = subjectId != null && subjectId.equals(principal.subjectId());
        boolean unitSummary = subjectId == null;
        if (!own || unitSummary) {
            if (!principal.permits("OBLIGATION_REPORT_UNIT")) throw denied();
            if (!principal.roleCodes().contains("SYSTEM_ADMIN")
                    && !principal.workUnitCode().equals(workUnitCode)) throw denied();
        }
        return new WorkObligationReportRepository.Query(
                command.weekStart(), subjectId, workUnitCode, domain, region, principal.regionCodes());
    }

    private static void validate(WorkObligationReportCommand command) {
        if (command == null || command.weekStart() == null
                || command.weekStart().getDayOfWeek() != DayOfWeek.MONDAY
                || (trim(command.businessDomain()) != null
                    && !DOMAINS.contains(trim(command.businessDomain())))) {
            throw invalid();
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    }

    private static String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_WORK_OBLIGATION_REPORT", "Work obligation report request is invalid");
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(
                "WORK_OBLIGATION_REPORT_DENIED", "Work obligation report scope is denied");
    }
}
