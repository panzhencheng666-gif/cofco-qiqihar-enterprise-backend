package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.application.BusinessImportPhotoPackage.PhotoPart;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Shared non-atomic row importer used by the nine product workbooks. */
@Service
public class GovernedDraftImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GovernedDraftImportService.class);
    private static final String SOURCE_PREFIX = "GOVERNED-DRAFT-V1:";
    private final ImportJobWriteExecutor jobWrites;
    private final ImportDraftRowExecutor rows;
    private final BusinessImportPhotoPackage photos;
    private final RegionImportResolver regions;
    private final AccessControl access;
    private final BusinessImportLimits limits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GovernedDraftImportService(ImportJobWriteExecutor jobWrites, ImportDraftRowExecutor rows,
            BusinessImportPhotoPackage photos, RegionImportResolver regions, AccessControl access,
            BusinessImportLimits limits, ObjectMapper objectMapper, Clock clock) {
        this.jobWrites = jobWrites;
        this.rows = rows;
        this.photos = photos;
        this.regions = regions;
        this.access = access;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ImportJobView submit(String key, String domainCode, String domainLabel, String productCode,
            String filename, String mediaType, byte[] workbookBytes, List<PhotoPart> photoParts,
            List<DraftWorkbookRow> sourceRows) {
        validateRequest(key, filename, mediaType, workbookBytes, sourceRows);
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        String digest = digest(workbookBytes, photoParts);
        DraftSource source = new DraftSource(domainCode, domainLabel, productCode, sourceRows);
        String sourceContent = encode(source);
        var now = clock.instant();
        if (limits.queued(sourceRows.size())) {
            var reservation = jobWrites.queue(principal, domainCode, key, digest, null, sourceContent, now);
            if (reservation.owner()) {
                photos.stage(reservation.stored().job().id(), photoParts, domainLabel + "批量填报");
            }
            return ImportJobView.from(reservation.stored().job());
        }
        var reservation = jobWrites.reserve(principal, domainCode, key, digest, now);
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        photos.stage(reservation.stored().job().id(), photoParts, domainLabel + "批量填报");
        return ImportJobView.from(process(reservation.stored().job(), source, digest, null, principal));
    }

    public boolean supports(String sourceContent) {
        return sourceContent != null && sourceContent.startsWith(SOURCE_PREFIX);
    }

    public void processQueued(ImportJobRepository.StoredImportJob stored, SecurityPrincipal principal) {
        DraftSource source = decode(stored.sourceContent());
        process(stored.job(), source, stored.job().contentSha256(), stored.job().retryOf(), principal);
    }

    private ImportJob process(ImportJob reserved, DraftSource source, String digest,
            UUID retryOf, SecurityPrincipal principal) {
        List<ImportRowOutcome> outcomes = new ArrayList<>(source.rows().size());
        for (DraftWorkbookRow sourceRow : source.rows()) {
            outcomes.add(processRow(reserved.id(), source, sourceRow, principal));
        }
        var completedAt = clock.instant();
        String status = outcomes.stream().anyMatch(row -> "ERROR".equals(row.outcomeCode()))
                ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob completed = new ImportJob(reserved.id(), source.domainCode(), reserved.idempotencyKey(),
                digest, principal.subjectId(), principal.workUnitCode(), retryOf, status,
                reserved.createdAt(), completedAt, outcomes, reserved.startedAt(), reserved.attemptCount(),
                null, null, reserved.leaseToken(), reserved.leaseUntil());
        return jobWrites.complete(completed, encode(source), principal);
    }

    private ImportRowOutcome processRow(UUID jobId, DraftSource source,
            DraftWorkbookRow row, SecurityPrincipal principal) {
        if (row.errorCode() != null) {
            return ImportRowOutcome.error(row.rowNumber(), row.errorCode(), row.errorMessage(), row.values());
        }
        try {
            if (blank(row.sampleName()) || blank(row.regionInput())) {
                return ImportRowOutcome.error(row.rowNumber(), "IMPORT_ROW_REQUIRED_VALUE",
                        "样本点名称和地区必须填写", row.values());
            }
            String regionCode = regions.resolve(row.regionInput());
            access.require("BUSINESS_IMPORT", regionCode);
            String warningCode = null;
            String warningMessage = null;
            List<UUID> evidenceIds;
            try {
                evidenceIds = photos.resolve(jobId, row.photoNames());
            } catch (ClientRequestException warning) {
                evidenceIds = List.of();
                warningCode = warning.code();
                warningMessage = warning.clientMessage();
            }
            Map<String, String> storedValues = new LinkedHashMap<>();
            row.normalizedValues().forEach((code, value) -> {
                if (value != null && !value.isBlank() && !code.equals(row.sampleCode())
                        && !code.equals(row.regionCode()) && !code.equals(row.photoCode())
                        && !code.equals(row.objectTypeCodeField())) storedValues.put(code, value.trim());
            });
            var now = clock.instant();
            ImportDraft draft = new ImportDraft(UUID.randomUUID(), source.domainCode(), source.productCode(),
                    blank(row.objectTypeCode()) ? null : row.objectTypeCode(), row.sampleName().trim(), regionCode,
                    row.surveyPeriod(), storedValues, row.missingFields(), row.completenessPercent(), "DRAFT",
                    principal.subjectId(), jobId, row.rowNumber(), 0, now, now);
            var created = rows.create(draft, evidenceIds);
            if (created.warningCode() != null) {
                warningCode = warningCode == null ? created.warningCode() : "IMPORT_PHOTO_WARNING";
                warningMessage = warningMessage == null ? created.warningMessage()
                        : warningMessage + "；" + created.warningMessage();
            }
            return ImportRowOutcome.draftImported(row.rowNumber(), created.draft().id(),
                    warningCode, warningMessage, row.values());
        } catch (ClientRequestException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(), exception.clientMessage(), row.values());
        } catch (RuntimeException exception) {
            LOGGER.warn("Business import draft row failed jobId={} rowNumber={}",
                    jobId, row.rowNumber(), exception);
            return ImportRowOutcome.error(row.rowNumber(), "IMPORT_ROW_WRITE_FAILED",
                    "本行未能保存，其他行不受影响", row.values());
        }
    }

    private void validateRequest(String key, String filename, String mediaType, byte[] bytes,
            List<DraftWorkbookRow> sourceRows) {
        if (key == null || key.isBlank() || key.length() > 128 || filename == null || filename.isBlank()
                || filename.length() > 255 || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()
                || sourceRows == null || sourceRows.isEmpty() || sourceRows.size() > limits.maximumRows()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "导入请求无效");
        }
    }

    private String encode(DraftSource source) {
        try {
            return SOURCE_PREFIX + java.util.Base64.getEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(source));
        } catch (Exception exception) {
            throw new IllegalStateException("Draft import source cannot be serialized", exception);
        }
    }

    private DraftSource decode(String sourceContent) {
        if (!supports(sourceContent)) throw new IllegalArgumentException("INVALID_DRAFT_IMPORT_SOURCE");
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(sourceContent.substring(SOURCE_PREFIX.length()));
            return objectMapper.readValue(bytes, DraftSource.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_DRAFT_IMPORT_SOURCE", exception);
        }
    }

    private static String digest(byte[] workbook, List<PhotoPart> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(workbook);
            for (PhotoPart part : parts == null ? List.<PhotoPart>of() : parts) {
                if (part == null) continue;
                update(digest, part.filename());
                update(digest, part.mediaType());
                byte[] bytes = part.bytes();
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record DraftWorkbookRow(int rowNumber, String objectTypeCode, String sampleName,
            String regionInput, String surveyPeriod, Map<String, String> values,
            Map<String, String> normalizedValues, List<String> missingFields, int completenessPercent,
            String photoNames, String sampleCode, String regionCode, String photoCode,
            String objectTypeCodeField, String errorCode, String errorMessage) {
        public DraftWorkbookRow {
            values = Map.copyOf(values == null ? Map.of() : values);
            normalizedValues = Map.copyOf(normalizedValues == null ? Map.of() : normalizedValues);
            missingFields = List.copyOf(missingFields == null ? List.of() : missingFields);
        }
    }

    public record DraftSource(String domainCode, String domainLabel, String productCode,
            List<DraftWorkbookRow> rows) {
        public DraftSource { rows = List.copyOf(rows); }
    }
}
