package com.cofco.qiqihar.graintrade.designsample.point.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleMetadataService;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.ValidatedDesignSampleValues;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DesignSamplePointService {
    private static final String AGGREGATE = "DESIGN_SAMPLE_POINT";

    private final DesignSamplePointRepository repository;
    private final DesignSampleMetadataService metadata;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    public DesignSamplePointService(
            DesignSamplePointRepository repository,
            DesignSampleMetadataService metadata,
            AccessControl access,
            BusinessAuditRecorder audit,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.metadata = metadata;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<DesignSamplePointView> list(
            String domainCode,
            String productCode,
            String objectTypeCode,
            String regionCode,
            String keyword,
            int pageNumber,
            int pageSize) {
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw invalidRequest();
        try {
            Math.multiplyExact((long) pageNumber, pageSize);
        } catch (ArithmeticException exception) {
            throw invalidRequest();
        }
        String domain = optionalCode(domainCode, 40);
        String product = optionalCode(productCode, 40);
        String objectType = optionalCode(objectTypeCode, 80);
        String region = optionalText(regionCode, 12);
        String search = optionalText(keyword, 200);
        AuthorizedReadScope scope = access.requireReadScope();
        if (region != null) scope.requireRegion(region);
        return repository.findPage(new DesignSamplePointQuery(
                domain, product, objectType, region, search,
                pageNumber, pageSize, scope.regionCodes()));
    }

    @Transactional(readOnly = true)
    public DesignSamplePointView get(UUID id) {
        AuthorizedReadScope scope = access.requireReadScope();
        DesignSamplePointView point = required(id);
        scope.requireRegion(point.regionCode());
        return point;
    }

    @Transactional
    public DesignSamplePointRepository.CreateResult create(
            String idempotencyKey, DesignSamplePointDraft submitted) {
        String key = requiredText(idempotencyKey, 200, "INVALID_IDEMPOTENCY_KEY");
        ValidatedDraft validated = validate(submitted);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", validated.regionCode());
        Instant now = clock.instant();
        try {
            DesignSamplePointRepository.CreateResult result = repository.insert(
                    UUID.randomUUID(), validated.draft(), validated.values(),
                    validated.sampleName(), validated.regionCode(),
                    validated.longitude(), validated.latitude(), key,
                    fingerprint(validated.draft(), validated.values()), actor.subjectId(), now)
                    .orElseThrow(() -> conflict(
                            "DESIGN_SAMPLE_POINT_IDEMPOTENCY_CONFLICT",
                            "幂等键已用于不同的设计样本点请求"));
            if (!result.replayed()) {
                record(actor, result.point(), "DESIGN_SAMPLE_POINT_CREATED", now,
                        java.util.List.of(result.point().regionCode()));
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("DESIGN_SAMPLE_POINT_CONFLICT", "设计样本点与现有记录冲突");
        }
    }

    @Transactional
    public DesignSamplePointView update(
            UUID id, long expectedVersion, DesignSamplePointDraft submitted) {
        if (expectedVersion < 0) throw invalidRequest();
        DesignSamplePointView current = required(id);
        ValidatedDraft validated = validate(submitted);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", current.regionCode());
        access.require("BUSINESS_UPDATE", validated.regionCode());
        Instant now = clock.instant();
        try {
            DesignSamplePointView updated = repository.update(
                            id, expectedVersion, validated.draft(), validated.values(),
                            validated.sampleName(), validated.regionCode(),
                            validated.longitude(), validated.latitude(), actor.subjectId(), now)
                    .orElseThrow(() -> conflict(
                            "DESIGN_SAMPLE_POINT_VERSION_CONFLICT",
                            "设计样本点已发生变化，请刷新后重试"));
            LinkedHashSet<String> regions = new LinkedHashSet<>();
            regions.add(current.regionCode());
            regions.add(updated.regionCode());
            record(actor, updated, "DESIGN_SAMPLE_POINT_UPDATED", now,
                    java.util.List.copyOf(regions));
            return updated;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("DESIGN_SAMPLE_POINT_CONFLICT", "设计样本点与现有记录冲突");
        }
    }

    @Transactional
    public void delete(UUID id, long expectedVersion) {
        if (expectedVersion < 0) throw invalidRequest();
        DesignSamplePointView current = required(id);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", current.regionCode());
        Instant now = clock.instant();
        try {
            if (!repository.delete(id, expectedVersion)) {
                throw conflict("DESIGN_SAMPLE_POINT_VERSION_CONFLICT",
                        "设计样本点已发生变化，请刷新后重试");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("DESIGN_SAMPLE_POINT_REFERENCED",
                    "设计样本点已被引用，不能删除");
        }
        record(actor, current, "DESIGN_SAMPLE_POINT_DELETED", now,
                java.util.List.of(current.regionCode()));
    }

    private ValidatedDraft validate(DesignSamplePointDraft submitted) {
        if (submitted == null || blank(submitted.contractVersion())
                || blank(submitted.contractDigest()) || submitted.context() == null
                || submitted.values() == null) {
            throw invalidRequest();
        }
        DesignSampleContext context;
        try {
            context = new DesignSampleContext(
                    code(submitted.context().domainCode(), 40),
                    code(submitted.context().productCode(), 40),
                    code(submitted.context().objectTypeCode(), 80));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidRequest();
        }
        BoundedInput.requireAggregateSize("INVALID_DESIGN_SAMPLE_POINT", submitted.values());
        TreeMap<String, JsonNode> values = new TreeMap<>(submitted.values());
        values.forEach((fieldCode, value) -> {
            BoundedInput.requireText("INVALID_DESIGN_SAMPLE_POINT", fieldCode);
            if (value != null && value.isTextual()) {
                BoundedInput.requireText("INVALID_DESIGN_SAMPLE_POINT", value.asText());
            }
        });
        DesignSamplePointDraft submittedDraft = new DesignSamplePointDraft(
                submitted.contractVersion().trim(), submitted.contractDigest().trim(),
                context, values);
        ValidatedDesignSampleValues validated = metadata.validateForPersistence(
                submittedDraft.contractVersion(), submittedDraft.contractDigest(), context, values);
        Map<String, JsonNode> normalizedValues = validated.values();
        DesignSamplePointDraft draft = new DesignSamplePointDraft(
                validated.contractVersion(), validated.contractDigest(), context, normalizedValues);

        String name = requiredValue(normalizedValues, "DSP_NAME", 200);
        String region = requiredValue(normalizedValues, "DSP_REGION_CODE", 12);
        BigDecimal longitude = decimal(normalizedValues.get("DSP_LONGITUDE"));
        BigDecimal latitude = decimal(normalizedValues.get("DSP_LATITUDE"));
        DesignSamplePointRepository.BoundaryContainment containment = repository
                .coordinateBoundaryState(region, longitude, latitude)
                .orElseThrow(DesignSamplePointService::invalidRequest);
        switch (containment) {
            case UNAVAILABLE -> throw new ServiceUnavailableException(
                    "ADMIN_BOUNDARY_UNAVAILABLE", "所选行政区边界数据暂不可用");
            case OUTSIDE -> throw new ClientRequestException(
                    "COORDINATE_OUTSIDE_REGION", "设计样本点坐标不在所选行政区范围内");
            case INSIDE -> { }
        }
        return new ValidatedDraft(
                draft, normalizedValues, name, region, longitude, latitude);
    }

    private static BigDecimal decimal(JsonNode value) {
        try {
            if (value == null || value.isNull()) throw new NumberFormatException();
            return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private static String requiredValue(Map<String, JsonNode> values, String code, int maxLength) {
        JsonNode value = values.get(code);
        if (value == null || !value.isTextual()) throw invalidRequest();
        return requiredText(value.asText(), maxLength, "INVALID_DESIGN_SAMPLE_POINT");
    }

    private String fingerprint(
            DesignSamplePointDraft draft, Map<String, JsonNode> normalizedValues) {
        try {
            String canonical = json.writeValueAsString(new Fingerprint(
                    draft.contractVersion(), draft.contractDigest(), draft.context(), normalizedValues));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint design sample point request", exception);
        }
    }

    private void record(
            SecurityPrincipal actor,
            DesignSamplePointView point,
            String action,
            Instant occurredAt,
            java.util.List<String> regionCodes) {
        try {
            String detail = json.writeValueAsString(new EventDetail(
                    point.regionCode(), regionCodes, point.context().domainCode(),
                    point.context().productCode(), point.context().objectTypeCode(),
                    point.contractVersion(), point.version()));
            audit.record(actor, AGGREGATE, point.id().toString(), action, occurredAt, detail);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize design sample point event", exception);
        }
    }

    private DesignSamplePointView required(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException(
                "DESIGN_SAMPLE_POINT_NOT_FOUND", "设计样本点不存在"));
    }

    private static String code(String value, int maxLength) {
        return requiredText(value, maxLength, "INVALID_DESIGN_SAMPLE_POINT")
                .toUpperCase(Locale.ROOT);
    }

    private static String optionalCode(String value, int maxLength) {
        String normalized = optionalText(value, maxLength);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null) return null;
        return requiredText(value, maxLength, "INVALID_DESIGN_SAMPLE_POINT_QUERY");
    }

    private static String requiredText(String value, int maxLength, String code) {
        if (value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > maxLength) {
            throw new ClientRequestException(code, "设计样本点请求参数无效");
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ClientRequestException invalidRequest() {
        return new ClientRequestException(
                "INVALID_DESIGN_SAMPLE_POINT", "设计样本点请求参数无效");
    }

    private static ConflictException conflict(String code, String message) {
        return new ConflictException(code, message);
    }

    private record ValidatedDraft(
            DesignSamplePointDraft draft,
            Map<String, JsonNode> values,
            String sampleName,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude) {}

    private record Fingerprint(
            String contractVersion,
            String contractDigest,
            DesignSampleContext context,
            Map<String, JsonNode> values) {}

    private record EventDetail(
            String regionCode,
            java.util.List<String> regionCodes,
            String domainCode,
            String productCode,
            String objectTypeCode,
            String contractVersion,
            long version) {}
}
