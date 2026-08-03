package com.cofco.qiqihar.graintrade.reporting.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.reporting.domain.ReportExportContent;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditWriter;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReportingService {
    private final ReportingRepository repository;
    private final AccessControl accessControl;
    private final BusinessAuditWriter audit;
    private final Clock clock;
    private final ObjectMapper json;

    public ReportingService(ReportingRepository repository, AccessControl accessControl, BusinessAuditWriter audit, Clock clock, ObjectMapper json) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ReportParameterOptionsView options() {
        return repository.options();
    }

    @Transactional
    public ReportPreviewView preview(ReportPreviewCommand command) {
        validate(command);
        SecurityPrincipal principal = authorize("REPORT_PREVIEW", command.regionCode());
        ReportingRepository.ReportPreviewMaterial material = repository.loadPreviewMaterial(command);
        if (material == null || material.definition() == null || material.approvedRecordCount() < 1) {
            throw new ClientRequestException("REPORT_APPROVED_DATA_REQUIRED", "Report preview requires approved data");
        }
        Instant now = clock.instant();
        String datasetId = UUID.randomUUID().toString();
        String datasetDigest = digest(material.approvedSummaryJson());
        String contentJson = content(command, material, now);
        ReportPreviewView preview = repository.persistPreview(new ReportingRepository.ReportPreviewPersistence(
                command, material, principal.subjectId(), now, now.plus(30, ChronoUnit.MINUTES), datasetId, datasetDigest,
                contentJson, digest(contentJson)));
        audit(principal, "REPORT_PREVIEW", preview.id(), "REPORT_PREVIEW_CREATED", now,
                "{\"regionCode\":\"" + command.regionCode() + "\"}");
        return preview;
    }

    @Transactional
    public ReportExportView export(String previewId, String formatCode) {
        if (blank(previewId) || blank(formatCode)) throw invalid();
        ReportPreviewView preview = repository.findPreview(previewId);
        if (preview == null) throw new ResourceNotFoundException("REPORT_PREVIEW_NOT_FOUND", "Report preview was not found");
        SecurityPrincipal principal = authorize("REPORT_EXPORT", repository.findPreviewRegion(previewId));
        if (!preview.expiresAt().isAfter(clock.instant())) {
            throw new ClientRequestException("REPORT_PREVIEW_EXPIRED", "Report preview has expired");
        }
        String format = formatCode.trim().toUpperCase();
        if (!format.equals("CSV")) {
            throw new ClientRequestException("REPORT_EXPORT_FORMAT_UNAVAILABLE", "Requested report export format is unavailable");
        }
        byte[] content = csv(preview).getBytes(StandardCharsets.UTF_8);
        Instant now = clock.instant();
        ReportExportView export = repository.persistExport(new ReportingRepository.ReportExportPersistence(
                previewId, format, principal.subjectId(), now, safeFilename(preview) + ".csv", "text/csv;charset=utf-8",
                digest(content), content));
        audit(principal, "REPORT_EXPORT", export.id(), "REPORT_EXPORT_CREATED", now,
                "{\"previewId\":\"" + previewId + "\"}");
        return export;
    }

    @Transactional
    public ReportExportContent download(String exportTaskId) {
        if (blank(exportTaskId)) throw invalid();
        SecurityPrincipal principal = authorize("REPORT_EXPORT", repository.findExportRegion(exportTaskId));
        ReportExportContent export = repository.findExportContent(exportTaskId);
        if (export == null) {
            throw new ResourceNotFoundException("REPORT_EXPORT_NOT_FOUND", "Report export was not found");
        }
        audit(principal, "REPORT_EXPORT", export.id(), "REPORT_EXPORT_DOWNLOADED", clock.instant(), "{}");
        return export;
    }

    @Transactional
    public ReportPublicationView publish(String previewId, String exportTaskId, long expectedVersion) {
        if (blank(previewId) || blank(exportTaskId) || expectedVersion < 0) throw invalid();
        try {
            Instant now = clock.instant();
            SecurityPrincipal principal = authorize("REPORT_PUBLISH", repository.findPreviewRegion(previewId));
            ReportPublicationView publication = repository.persistPublication(new ReportingRepository.ReportPublicationPersistence(
                    previewId, exportTaskId, principal.subjectId(), now, expectedVersion));
            audit(principal, "REPORT_PUBLICATION", publication.id(), "REPORT_PUBLICATION_CREATED", now,
                    "{\"previewId\":\"" + previewId + "\",\"exportTaskId\":\"" + exportTaskId + "\"}");
            return publication;
        } catch (IllegalStateException exception) {
            throw new ConflictException("REPORT_PUBLICATION_CONFLICT", "Report publication has changed");
        }
    }

    private String content(ReportPreviewCommand command, ReportingRepository.ReportPreviewMaterial material, Instant now) {
        ObjectNode root = json.createObjectNode();
        root.put("title", material.regionLabel() + material.productLabel() + material.definition().name());
        root.put("dataCutoffLabel", command.periodCode());
        root.put("approvedRecordCount", material.approvedRecordCount());
        root.set("approvedSummary", json.readTree(material.approvedSummaryJson()));
        ArrayNode sections = root.putArray("sections");
        for (ReportDefinitionView.Section section : material.definition().sections()) {
            ObjectNode item = sections.addObject();
            item.put("code", section.code());
            item.put("title", section.title());
            item.put("body", section.title() + "：已采用 " + material.approvedRecordCount() + " 条核定数据。");
        }
        root.put("generatedAt", now.toString());
        return root.toString();
    }

    private String csv(ReportPreviewView preview) {
        StringBuilder rows = new StringBuilder("\uFEFF报告名称,数据截止,指标,数值,说明\r\n");
        for (ReportPreviewView.Line line : preview.lines()) {
            rows.append(csv(preview.title())).append(',').append(csv(preview.dataCutoffLabel())).append(',')
                    .append(csv(line.label())).append(',').append(csv(line.value())).append(',')
                    .append(csv(line.note())).append("\r\n");
        }
        return rows.toString();
    }

    private static String csv(String value) {
        String normalized = value == null ? "" : value;
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    private static String safeFilename(ReportPreviewView preview) {
        return preview.title().replaceAll("[\\\\/:*?\"<>|\\s]+", "-") + "-" + preview.id();
    }

    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private SecurityPrincipal authorize(String permission, String regionCode) { return accessControl.require(permission, regionCode); }
    private void audit(SecurityPrincipal principal, String aggregateType, String aggregateId, String action, Instant occurredAt, String detailJson) {
        audit.append(new BusinessAuditEvent(UUID.randomUUID(), aggregateType, aggregateId, action,
                principal.subjectId(), principal.workUnitCode(), occurredAt, detailJson));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validate(ReportPreviewCommand command) {
        if (command == null || blank(command.definitionCode()) || blank(command.productCode())
                || blank(command.regionLevel()) || blank(command.regionCode()) || blank(command.periodCode())) throw invalid();
    }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_REPORT_REQUEST", "Report request is invalid"); }
}
