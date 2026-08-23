package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportRow;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically converts one complete imported row into a submitted domain record. */
@Service
public class ImportDraftPromotionService {
    private final ImportDraftRepository drafts;
    private final ProductionImportPort production;
    private final MarketImportPort market;
    private final LogisticsImportPort logistics;
    private final BusinessPeriodRecordGuard periodRecords;
    private final EvidencePhotoService evidencePhotos;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public ImportDraftPromotionService(ImportDraftRepository drafts, ProductionImportPort production,
            MarketImportPort market, LogisticsImportPort logistics, BusinessPeriodRecordGuard periodRecords,
            EvidencePhotoService evidencePhotos,
            AccessControl access, BusinessAuditRecorder audit, Clock clock) {
        this.drafts = drafts;
        this.production = production;
        this.market = market;
        this.logistics = logistics;
        this.periodRecords = periodRecords;
        this.evidencePhotos = evidencePhotos;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ImportDraft submit(UUID id) {
        return submit(id, false);
    }

    @Transactional
    public ImportDraft submitAfterIdentityReview(UUID id) {
        return submit(id, true);
    }

    private ImportDraft submit(UUID id, boolean reviewedIdentity) {
        ImportDraft draft = drafts.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(
                "IMPORT_DRAFT_NOT_FOUND", "导入草稿不存在"));
        SecurityPrincipal principal = access.require(
                reviewedIdentity ? "BUSINESS_APPROVE" : "BUSINESS_SUBMIT", draft.regionCode());
        if (!reviewedIdentity && !draft.createdBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_DRAFT_SUBMIT_NOT_ALLOWED", "只能提交本人导入的草稿");
        }
        if ("PROMOTED".equals(draft.stateCode())) return draft;
        if (!"DRAFT".equals(draft.stateCode())) {
            throw new ConflictException("IMPORT_DRAFT_NOT_SUBMITTABLE", "该导入草稿当前不能提交");
        }
        List<UUID> evidenceIds = drafts.evidenceIds(id);
        periodRecords.lockAndRequireAvailable(draft);
        String recordId;
        try {
            recordId = switch (draft.domainCode()) {
                case "PRODUCTION" -> production.importAndSubmit(productionRow(draft, evidenceIds));
                case "MARKET" -> market.importAndSubmit(marketRow(draft, evidenceIds));
                case "LOGISTICS" -> submitLogistics(draft, evidenceIds, principal);
                default -> throw new IllegalStateException("Unsupported import draft domain");
            };
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ClientRequestException("IMPORT_DRAFT_INCOMPLETE",
                    "草稿缺少正式提交所需字段，或字段内容不符合业务规则；请补充后再提交审核");
        }
        ImportDraft promoted = drafts.markPromoted(id, draft.version(), recordId, clock.instant());
        audit.record(principal, "IMPORT_DRAFT", id.toString(), "IMPORT_DRAFT_SUBMITTED",
                clock.instant(), "{\"domainCode\":\"" + draft.domainCode()
                        + "\",\"canonicalRecordId\":\"" + recordId + "\"}");
        return promoted;
    }

    @Transactional(readOnly = true)
    public List<ImportDraft> listByJob(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return drafts.findByJob(importJobId, principal.subjectId());
    }

    @Transactional(readOnly = true)
    public List<ImportDraft> listOwned(String domainCode, String productCode, String stateCode) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return drafts.findByOwnerAndScope(
                principal.subjectId(), domainCode, productCode, stateCode);
    }

    @Transactional
    public BatchSubmission submitJob(UUID importJobId) {
        List<ImportDraft> owned = listByJob(importJobId);
        List<ImportDraft> pending = owned.stream()
                .filter(draft -> "DRAFT".equals(draft.stateCode()))
                .toList();
        int submitted = 0;
        for (ImportDraft draft : pending) {
            submit(draft.id());
            submitted++;
        }
        int remaining = (int) drafts.findByJob(importJobId,
                        access.require("BUSINESS_IMPORT", null).subjectId()).stream()
                .filter(draft -> "DRAFT".equals(draft.stateCode()))
                .count();
        return new BatchSubmission(importJobId, submitted, remaining);
    }

    public record BatchSubmission(UUID importJobId, int submittedRows, int remainingDraftRows) {}

    private ProductionDraft productionRow(ImportDraft draft, List<UUID> evidenceIds) {
        String objectType = required(draft.objectTypeCode());
        ProductionImportDefinition definition = production.importDefinition(draft.productCode(), objectType);
        Map<String, String> values = draft.values();
        SurveyPeriod period = surveyPeriod(values);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("PROD_SAMPLE_NAME", draft.sampleName());
        ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.stream()
                .filter(code -> !"PROD_REPORTER_NAME".equals(code))
                .forEach(code -> putIfPresent(metadata, code, values));
        ProductionImportTemplate.DETAIL_HEADERS.forEach(code -> putIfPresent(metadata, code, values));
        Map<String, BigDecimal> quality = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        Map<String, BigDecimal> insurance = new LinkedHashMap<>();
        Map<String, BigDecimal> subsidies = new LinkedHashMap<>();
        for (ProductionImportDefinition.Group group : definition.groups()) {
            if ("DETAIL".equals(group.code())) continue;
            Map<String, BigDecimal> target = switch (group.code()) {
                case "QUALITY" -> quality;
                case "COST" -> costs;
                case "INSURANCE" -> insurance;
                case "SUBSIDY" -> subsidies;
                default -> throw new IllegalArgumentException("Unsupported production group");
            };
            group.fields().forEach(field -> putDecimalIfPresent(target, field.code(), values));
        }
        return new ProductionDraft(draft.productCode(), objectType, draft.regionCode(), null, period.date(),
                decimal(values.get("cultivatedAreaMu")), decimal(values.get("yieldPerMuKilograms")),
                quality, costs, insurance, subsidies, metadata, evidenceIds, period.year(), period.month());
    }

    private MarketImportRow marketRow(ImportDraft draft, List<UUID> evidenceIds) {
        String objectType = required(draft.objectTypeCode());
        MarketImportDefinition definition = market.definition(draft.productCode(), objectType);
        Map<String, String> core = new LinkedHashMap<>();
        core.put("MKT_OBJECT_TYPE", objectType);
        core.put("MKT_REGION", draft.regionCode());
        core.put("MKT_SAMPLE_NAME", draft.sampleName());
        definition.coreFields().stream().filter(field -> !field.readOnly())
                .filter(field -> !List.of("MKT_OBJECT_TYPE", "MKT_REGION", "MKT_SAMPLE_NAME",
                        "MKT_REPORTER_NAME", "MKT_TRADE_DATE").contains(field.code()))
                .forEach(field -> putIfPresent(core, field.code(), draft.values()));
        SurveyPeriod period = surveyPeriod(draft.values());
        core.put("MKT_TRADE_DATE", period.date().toString());
        Map<String, BigDecimal> facts = new LinkedHashMap<>();
        definition.factFields().forEach(field -> putDecimalIfPresent(facts, field.code(), draft.values()));
        return new MarketImportRow(draft.productCode(), core, facts, evidenceIds);
    }

    private String submitLogistics(
            ImportDraft draft, List<UUID> evidenceIds, SecurityPrincipal principal) {
        Map<String, String> values = new LinkedHashMap<>(draft.values());
        values.put("LOG_SAMPLE_NAME", draft.sampleName());
        values.put("LOG_REGION", draft.regionCode());
        String recordId = logistics.importAndSubmit(new LogisticsImportRow(draft.productCode(), values));
        if (!evidenceIds.isEmpty()) {
            evidencePhotos.attachToLogistics(
                    evidenceIds, recordId, draft.regionCode(), principal.subjectId());
        }
        return recordId;
    }

    private static void putIfPresent(Map<String, String> target, String code, Map<String, String> source) {
        String value = source.get(code);
        if (value != null && !value.isBlank()) target.put(code, value.trim());
    }

    private static void putDecimalIfPresent(
            Map<String, BigDecimal> target, String code, Map<String, String> source) {
        BigDecimal value = decimal(source.get(code));
        if (value != null) target.put(code, value);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static SurveyPeriod surveyPeriod(Map<String, String> values) {
        String yearValue = values.get("surveyYear");
        if (yearValue == null || yearValue.isBlank()) {
            throw new ClientRequestException("INVALID_IMPORT_YEAR", "数据年份必须填写");
        }
        int year;
        try {
            year = Integer.parseInt(yearValue.trim());
        } catch (NumberFormatException exception) {
            throw new ClientRequestException("INVALID_IMPORT_YEAR", "数据年份必须填写 1900—2200 的整数");
        }
        if (year < 1900 || year > 2200) {
            throw new ClientRequestException("INVALID_IMPORT_YEAR", "数据年份必须填写 1900—2200 的整数");
        }
        String monthValue = values.get("surveyMonth");
        Integer month = null;
        if (monthValue != null && !monthValue.isBlank()) {
            try {
                month = Integer.valueOf(monthValue.trim());
            } catch (NumberFormatException exception) {
                throw new ClientRequestException("INVALID_IMPORT_MONTH", "数据月份只能填写 1—12 的整数");
            }
            if (month < 1 || month > 12) {
                throw new ClientRequestException("INVALID_IMPORT_MONTH", "数据月份只能填写 1—12 的整数");
            }
        }
        return new SurveyPeriod(year, month, LocalDate.of(year, month == null ? 1 : month, 1));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required value is missing");
        return value.trim();
    }

    private record SurveyPeriod(int year, Integer month, LocalDate date) {}
}
