package com.cofco.qiqihar.graintrade.reporting.application;

import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
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
    private final ReportingActor actor;
    private final Clock clock;
    private final ObjectMapper json;

    public ReportingService(ReportingRepository repository, ReportingActor actor, Clock clock, ObjectMapper json) {
        this.repository = repository;
        this.actor = actor;
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
        ReportingRepository.ReportPreviewMaterial material = repository.loadPreviewMaterial(command);
        if (material == null || material.definition() == null || material.approvedRecordCount() < 1) {
            throw new ClientRequestException("REPORT_APPROVED_DATA_REQUIRED", "Report preview requires approved data");
        }
        Instant now = clock.instant();
        String actorId = actor();
        String datasetId = UUID.randomUUID().toString();
        String datasetDigest = digest(material.approvedSummaryJson());
        String contentJson = content(command, material, now);
        return repository.persistPreview(new ReportingRepository.ReportPreviewPersistence(
                command, material, actorId, now, now.plus(30, ChronoUnit.MINUTES), datasetId, datasetDigest,
                contentJson, digest(contentJson)));
    }

    @Transactional
    public ReportExportView export(String previewId, String formatCode) {
        if (blank(previewId) || blank(formatCode)) throw invalid();
        ReportPreviewView preview = repository.findPreview(previewId);
        if (preview == null) throw new ResourceNotFoundException("REPORT_PREVIEW_NOT_FOUND", "Report preview was not found");
        if (!preview.expiresAt().isAfter(clock.instant())) {
            throw new ClientRequestException("REPORT_PREVIEW_EXPIRED", "Report preview has expired");
        }
        String format = formatCode.trim().toUpperCase();
        if (!format.equals("CSV")) {
            throw new ClientRequestException("REPORT_EXPORT_FORMAT_UNAVAILABLE", "Requested report export format is unavailable");
        }
        byte[] content = csv(preview).getBytes(StandardCharsets.UTF_8);
        Instant now = clock.instant();
        return repository.persistExport(new ReportingRepository.ReportExportPersistence(
                previewId, format, actor(), now, safeFilename(preview) + ".csv", "text/csv;charset=utf-8",
                digest(content), content));
    }

    @Transactional
    public ReportPublicationView publish(String previewId, String exportTaskId, long expectedVersion) {
        if (blank(previewId) || blank(exportTaskId) || expectedVersion < 0) throw invalid();
        try {
            return repository.persistPublication(new ReportingRepository.ReportPublicationPersistence(
                    previewId, exportTaskId, actor(), clock.instant(), expectedVersion));
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
    private String actor() { return actor.currentActorId().orElseThrow(AuthenticationRequiredException::new); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validate(ReportPreviewCommand command) {
        if (command == null || blank(command.definitionCode()) || blank(command.productCode())
                || blank(command.regionLevel()) || blank(command.regionCode()) || blank(command.periodCode())) throw invalid();
    }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_REPORT_REQUEST", "Report request is invalid"); }
}
