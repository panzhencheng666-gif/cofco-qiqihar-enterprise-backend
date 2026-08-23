package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.application.BusinessImportPhotoPackage.PhotoPart;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.SubjectInput;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityGovernanceRepository;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Shared row importer used by the nine product workbooks. */
@Service
public class GovernedDraftImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GovernedDraftImportService.class);
    private static final String SOURCE_PREFIX = "GOVERNED-DRAFT-V1:";
    private final ImportJobWriteExecutor jobWrites;
    private final ImportDraftRowExecutor rows;
    private final BusinessImportPhotoPackage photos;
    private final RegionImportResolver regions;
    private final JdbcSampleIdentityGovernanceRepository identities;
    private final AccessControl access;
    private final BusinessImportLimits limits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GovernedDraftImportService(ImportJobWriteExecutor jobWrites, ImportDraftRowExecutor rows,
            BusinessImportPhotoPackage photos,
            RegionImportResolver regions, JdbcSampleIdentityGovernanceRepository identities,
            AccessControl access, BusinessImportLimits limits,
            ObjectMapper objectMapper, Clock clock) {
        this.jobWrites = jobWrites;
        this.rows = rows;
        this.photos = photos;
        this.regions = regions;
        this.identities = identities;
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
        DraftSource source = new DraftSource(domainCode, domainLabel, productCode, sourceRows, null);
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

    DraftSource durableSource(String sourceContent) {
        return decode(sourceContent);
    }

    public void processQueued(ImportJobRepository.StoredImportJob stored, SecurityPrincipal principal) {
        DraftSource source = decode(stored.sourceContent());
        process(stored.job(), source, stored.job().contentSha256(), stored.job().retryOf(), principal);
    }

    public ImportJobView retryFailedRows(
            ImportJobRepository.StoredImportJob prior, SecurityPrincipal principal) {
        if (!prior.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_RETRY_NOT_ALLOWED", "导入任务属于其他账号，不能重试");
        }
        DraftSource original = decode(prior.sourceContent());
        if (!prior.job().domainCode().equals(original.domainCode())) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "导入任务来源无效");
        }
        Set<Integer> failedRows = prior.job().rows().stream()
                .filter(row -> "ERROR".equals(row.outcomeCode()))
                .filter(GovernedDraftImportService::retryable)
                .map(ImportRowOutcome::rowNumber)
                .collect(java.util.stream.Collectors.toSet());
        if (failedRows.isEmpty()) {
            throw new ConflictException("IMPORT_RETRY_NOT_AVAILABLE", "导入任务没有可重试的失败行");
        }
        List<DraftWorkbookRow> retryRows = original.rows().stream()
                .filter(row -> failedRows.contains(row.rowNumber()))
                .toList();
        if (retryRows.size() != failedRows.size()) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "导入任务失败行来源不完整");
        }
        UUID photoJobId = original.photoJobId() == null ? prior.job().id() : original.photoJobId();
        DraftSource retrySource = new DraftSource(original.domainCode(), original.domainLabel(),
                original.productCode(), retryRows, photoJobId);
        String retryKey = "retry-" + UUID.randomUUID();
        var reservation = jobWrites.reserve(principal, original.domainCode(), retryKey,
                prior.job().contentSha256(), clock.instant());
        return ImportJobView.from(process(reservation.stored().job(), retrySource,
                prior.job().contentSha256(), prior.job().id(), principal));
    }

    private ImportJob process(ImportJob reserved, DraftSource source, String digest,
            UUID retryOf, SecurityPrincipal principal) {
        List<ImportRowOutcome> outcomes = new ArrayList<>(source.rows().size());
        Map<BatchIdentityKey, SeenBatchIdentity> batchIdentities = new LinkedHashMap<>();
        UUID photoJobId = source.photoJobId() == null ? reserved.id() : source.photoJobId();
        for (DraftWorkbookRow sourceRow : source.rows()) {
            outcomes.add(processRow(reserved.id(), photoJobId, source, sourceRow, principal, batchIdentities));
        }
        var completedAt = clock.instant();
        String status = outcomes.stream().anyMatch(row -> "ERROR".equals(row.outcomeCode()))
                ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob completed = new ImportJob(reserved.id(), source.domainCode(), reserved.idempotencyKey(),
                digest, principal.subjectId(), principal.workUnitCode(), retryOf, status,
                reserved.createdAt(), completedAt, outcomes, reserved.startedAt(), reserved.attemptCount(),
                null, null, reserved.leaseToken(), reserved.leaseUntil());
        DraftSource durableSource = new DraftSource(source.domainCode(), source.domainLabel(),
                source.productCode(), source.rows(), photoJobId);
        return jobWrites.complete(completed, encode(durableSource), principal);
    }

    private ImportRowOutcome processRow(UUID jobId, UUID photoJobId, DraftSource source,
            DraftWorkbookRow row, SecurityPrincipal principal,
            Map<BatchIdentityKey, SeenBatchIdentity> batchIdentities) {
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
                evidenceIds = photos.resolve(photoJobId, row.photoNames());
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
                    principal.subjectId(), jobId, row.rowNumber(), 0, null, now, now);
            BatchIdentityKey batchKey = batchIdentityKey(source.domainCode(), draft, storedValues);
            if (batchKey != null) {
                SeenBatchIdentity seen = batchIdentities.putIfAbsent(batchKey,
                        new SeenBatchIdentity(row.rowNumber(), new BatchFacts(storedValues)));
                if (seen != null && seen.facts().equals(new BatchFacts(storedValues))) {
                    return ImportRowOutcome.error(row.rowNumber(), "IMPORT_DUPLICATE_ROW",
                            "本行与同一文件第" + seen.rowNumber() + "行的样本身份、月份和业务数据完全相同，未重复导入",
                            row.values());
                }
                if (seen != null) {
                    var pending = rows.createPendingIdentityReview(draft, evidenceIds,
                            "SAMPLE_IDENTITY_RECORD_CONFLICT",
                            "本行与同一文件第" + seen.rowNumber() + "行属于相同样本身份和月份，但业务数据不一致，需核验",
                            principal, json(Map.of(
                                    "draftId", draft.id(), "reasonCode", "SAMPLE_IDENTITY_RECORD_CONFLICT",
                                    "reasonMessage", "同一文件内相同身份和月份的业务数据不一致",
                                    "conflictingRowNumber", seen.rowNumber())));
                    return ImportRowOutcome.draftImported(row.rowNumber(), pending.draft().id(),
                            pending.warningCode(), pending.warningMessage(), row.values());
                }
            }
            SampleIdentityAssessment identity = assessIdentity(source.domainCode(), draft, storedValues);
            if (identity != null && identity.outcome() == SampleIdentityAssessment.Outcome.REVIEW_REQUIRED) {
                var pending = rows.createPendingIdentityReview(draft, evidenceIds,
                        "SAMPLE_IDENTITY_REVIEW_REQUIRED",
                        identity.reasonMessage() + "（" + identity.reasonCode() + "）",
                        principal, json(Map.of(
                                "draftId", draft.id(), "reasonCode", identity.reasonCode(),
                                "reasonMessage", identity.reasonMessage(),
                                "candidateSamplePointIds", identity.candidates().stream()
                                        .map(candidate -> candidate.samplePointId().toString()).toList())));
                return ImportRowOutcome.draftImported(row.rowNumber(), pending.draft().id(),
                        pending.warningCode(), pending.warningMessage(), row.values());
            }
            var submitted = rows.createAndSubmit(draft, evidenceIds);
            if (submitted.warningCode() != null) {
                warningCode = warningCode == null ? submitted.warningCode() : "IMPORT_PHOTO_WARNING";
                warningMessage = warningMessage == null ? submitted.warningMessage()
                        : warningMessage + "；" + submitted.warningMessage();
            }
            return new ImportRowOutcome(row.rowNumber(), "IMPORTED", null, null,
                    submitted.draft().canonicalRecordId(), null,
                    warningCode, warningMessage, row.values());
        } catch (ClientRequestException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(), exception.clientMessage(), row.values());
        } catch (AccessDeniedException exception) {
            String message = "ACCESS_REGION_DENIED".equals(exception.code())
                    ? "该行地区不在当前账号权限范围内，请核对地区或联系管理员调整授权"
                    : "当前账号无权处理该行数据，请联系管理员核对账号权限";
            return ImportRowOutcome.error(row.rowNumber(), exception.code(), message, row.values());
        } catch (RuntimeException exception) {
            LOGGER.warn("Business import draft row failed jobId={} rowNumber={}",
                    jobId, row.rowNumber(), exception);
            return ImportRowOutcome.error(row.rowNumber(), "IMPORT_ROW_WRITE_FAILED",
                    "本行未能保存，其他行不受影响", row.values());
        }
    }

    private SampleIdentityAssessment assessIdentity(String domainCode, ImportDraft draft,
            Map<String, String> storedValues) {
        String contactCode;
        String longitudeCode;
        String latitudeCode;
        if ("PRODUCTION".equals(domainCode)) {
            contactCode = "PROD_SAMPLE_CONTACT";
            longitudeCode = "PROD_SAMPLE_LONGITUDE";
            latitudeCode = "PROD_SAMPLE_LATITUDE";
        } else if ("MARKET".equals(domainCode)) {
            contactCode = "MKT_SAMPLE_CONTACT";
            longitudeCode = "MKT_SAMPLE_LONGITUDE";
            latitudeCode = "MKT_SAMPLE_LATITUDE";
        } else {
            return null;
        }
        String longitude = storedValues.get(longitudeCode);
        String latitude = storedValues.get(latitudeCode);
        if (blank(longitude) || blank(latitude)) return null;
        return identities.assess(new SubjectInput(domainCode, draft.sampleName(),
                storedValues.get(contactCode), draft.regionCode(),
                new BigDecimal(longitude), new BigDecimal(latitude)));
    }

    private static BatchIdentityKey batchIdentityKey(String domainCode, ImportDraft draft,
            Map<String, String> storedValues) {
        String contactCode;
        if ("PRODUCTION".equals(domainCode)) {
            contactCode = "PROD_SAMPLE_CONTACT";
        } else if ("MARKET".equals(domainCode)) {
            contactCode = "MKT_SAMPLE_CONTACT";
        } else {
            return null;
        }
        String contact = SampleIdentityAssessment.normalizedContact(storedValues.get(contactCode));
        if (contact.isEmpty() || blank(draft.surveyPeriod())) return null;
        return new BatchIdentityKey(domainCode, draft.productCode(), draft.objectTypeCode(),
                SampleIdentityAssessment.normalizedName(draft.sampleName()), contact,
                draft.regionCode(), draft.surveyPeriod().strip());
    }

    private static boolean retryable(ImportRowOutcome row) {
        return !"IMPORT_DUPLICATE_ROW".equals(row.errorCode())
                && !"SAMPLE_IDENTITY_RECORD_CONFLICT".equals(row.errorCode())
                && !"SAMPLE_PERIOD_RECORD_CONFLICT".equals(row.errorCode());
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Sample identity review detail cannot be serialized", exception);
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
            List<DraftWorkbookRow> rows, UUID photoJobId) {
        public DraftSource { rows = List.copyOf(rows); }
    }

    private record BatchIdentityKey(String domainCode, String productCode, String objectTypeCode,
            String sampleName, String sampleContact, String regionCode, String surveyPeriod) {}

    private record SeenBatchIdentity(int rowNumber, BatchFacts facts) {}

    private record BatchFacts(Map<String, String> values) {
        private BatchFacts {
            values = Map.copyOf(new TreeMap<>(values));
        }
    }
}
