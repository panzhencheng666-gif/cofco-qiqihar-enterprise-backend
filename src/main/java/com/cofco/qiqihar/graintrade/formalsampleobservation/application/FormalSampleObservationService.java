package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.formalsamplepoint.FormalSampleLocationWriter;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.FormalSampleIdentity;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordView;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringDraft;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRecordView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FormalSampleObservationService {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final FormalSampleObservationRepository repository;
    private final AccessControl accessControl;
    private final ProductionRecordService productionRecords;
    private final MarketMonitoringService marketRecords;
    private final LogisticsService logisticsRecords;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final FormalSampleLocationWriter samplePoints;

    public FormalSampleObservationService(
            FormalSampleObservationRepository repository,
            AccessControl accessControl,
            ProductionRecordService productionRecords,
            MarketMonitoringService marketRecords,
            LogisticsService logisticsRecords,
            BusinessAuditRecorder audit,
            ObjectMapper objectMapper,
            Clock clock,
            FormalSampleLocationWriter samplePoints) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.productionRecords = productionRecords;
        this.marketRecords = marketRecords;
        this.logisticsRecords = logisticsRecords;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.samplePoints = samplePoints;
    }

    @Transactional(readOnly = true)
    public List<EligibleFormalSample> eligibleSamples(
            FormalSampleObservationDomain domain,
            String productCode,
            String regionCode,
            String objectTypeCode,
            String keyword,
            Integer year,
            OffsetDateTime observedAt) {
        if (domain == null || productCode == null || productCode.isBlank() || observedAt == null) {
            throw invalid("领域、产品和实际观测时间不能为空");
        }
        int observedYear = observedAt.atZoneSameInstant(REPORTING_ZONE).getYear();
        if (year != null && year != observedYear) {
            throw invalid("数据年份必须与实际观测时间一致");
        }
        String normalizedProduct = productCode.strip().toUpperCase();
        String normalizedObjectType = normalizeOptional(objectTypeCode);
        String normalizedKeyword = keyword == null ? null : keyword.strip();
        if (normalizedKeyword != null && normalizedKeyword.length() > 100) {
            throw invalid("搜索关键字不能超过100个字符");
        }
        if (normalizedKeyword != null && normalizedKeyword.isEmpty()) normalizedKeyword = null;
        if (normalizedObjectType != null && repository
                .findObjectTypeName(domain, normalizedProduct, normalizedObjectType).isEmpty()) {
            throw invalid("对象类型不适用于当前领域和产品");
        }
        SecurityPrincipal principal = accessControl.require("BUSINESS_CREATE", regionCode);
        return repository.findEligibleSamples(domain, normalizedProduct, regionCode, normalizedObjectType,
                keywordPattern(normalizedKeyword),
                observedAt.atZoneSameInstant(REPORTING_ZONE).toLocalDate(), principal.regionCodes(),
                principal.subjectId(), principal.permits("FORMAL_SAMPLE_MANAGE"));
    }

    @Transactional(readOnly = true)
    public FormalSampleObservationHistoryPage history(
            FormalSampleObservationDomain domain,
            UUID samplePointId,
            String productCode,
            Integer year,
            Integer pageNumber,
            Integer pageSize) {
        if (domain == null || samplePointId == null || productCode == null || productCode.isBlank()
                || year == null || year < 2000 || year > 2100) {
            throw invalid("领域、正式样本、产品和有效年份不能为空");
        }
        int normalizedPage = pageNumber == null ? 0 : pageNumber;
        int normalizedSize = pageSize == null ? 20 : pageSize;
        if (normalizedPage < 0 || normalizedPage > 100_000 || normalizedSize < 1 || normalizedSize > 100) {
            throw invalid("历史记录分页参数不正确");
        }
        SecurityPrincipal principal = accessControl.require("BUSINESS_READ", null);
        return repository.findHistory(domain, samplePointId, productCode.strip().toUpperCase(),
                year, normalizedPage, normalizedSize, principal.regionCodes());
    }

    @Transactional
    public FormalSampleObservationResult save(
            String idempotencyKey,
            FormalSampleObservationCommand command) {
        validateCommand(idempotencyKey, command);
        SecurityPrincipal principal = accessControl.require("BUSINESS_CREATE", null);
        String normalizedProduct = command.productCode().strip().toUpperCase();
        String requestSha256 = requestSha256(command, normalizedProduct);
        repository.lockIdempotencyScope(principal.subjectId(), command.domain(), idempotencyKey);
        StoredFormalSampleObservation stored = repository
                .findStored(principal.subjectId(), command.domain(), idempotencyKey).orElse(null);
        if (stored != null) {
            if (!stored.requestSha256().equals(requestSha256)) {
                throw new ConflictException("FORMAL_SAMPLE_OBSERVATION_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的正式样本观测，请更换后重试");
            }
            return stored.result();
        }

        Instant savedAt = clock.instant();
        if (command.observedAt().toInstant().isAfter(savedAt)) {
            throw invalidCommand("实际观测时间不能晚于当前时间");
        }
        var observedOn = command.observedAt().atZoneSameInstant(REPORTING_ZONE).toLocalDate();
        // Keep coordinate-lock -> point-row-lock order consistent with master-data updates.
        // All master, observation, audit and outbox writes participate in this transaction.
        if (command.sampleLocation() != null) {
            samplePoints.updateLocation(command.samplePointId(), command.sampleLocation());
        }
        FormalSampleIdentity lockedIdentity = repository.lockEligibleSample(
                command.domain(), command.samplePointId(), normalizedProduct,
                observedOn, principal.regionCodes());
        accessControl.require("BUSINESS_CREATE", lockedIdentity.regionCode());
        FormalSampleIdentity identity;
        if (lockedIdentity.maintainerSubjectId() == null || lockedIdentity.maintainerSubjectId().isBlank()) {
            repository.claimMaintainer(lockedIdentity.samplePointId(), principal.subjectId());
            identity = withMaintainer(lockedIdentity, principal.subjectId());
        } else identity = lockedIdentity;
        boolean administratorOverride = requireMaintainer(principal, identity);

        String sourceRecordId;
        switch (command.domain()) {
            case PRODUCTION -> {
                ProductionDraft draft = readPayload(command.payload(), ProductionDraft.class);
                ProductionRecordView saved = productionRecords.saveOfficialObservation(
                        identity, command.observedAt(), draft, savedAt);
                sourceRecordId = saved.record().id();
            }
            case MARKET -> {
                MarketMonitoringDraft draft = readPayload(command.payload(), MarketMonitoringDraft.class);
                sourceRecordId = marketRecords.saveOfficialObservation(
                        identity, command.observedAt(), draft, savedAt);
            }
            case LOGISTICS -> {
                LogisticsDraft draft = readPayload(command.payload(), LogisticsDraft.class);
                LogisticsRecordView saved = logisticsRecords.saveOfficialObservation(
                        identity, command.observedAt(), draft, savedAt);
                sourceRecordId = saved.id();
            }
            default -> throw invalidCommand("不支持的业务领域");
        }

        EligibleFormalSample refreshed = repository.findEligibleSamples(
                        command.domain(), normalizedProduct, identity.regionCode(), null, null, observedOn,
                        principal.regionCodes(), principal.subjectId(),
                        principal.permits("FORMAL_SAMPLE_MANAGE")).stream()
                .filter(sample -> sample.samplePointId().equals(identity.samplePointId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Saved formal sample observation is missing from the effective projection"));
        UUID observationId = UUID.randomUUID();
        OffsetDateTime normalizedObservedAt = command.observedAt().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime normalizedSavedAt = OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC);
        FormalSampleObservationResult result = new FormalSampleObservationResult(
                observationId, identity.samplePointId(), command.domain(), normalizedProduct,
                normalizedObservedAt, normalizedSavedAt, "sha256:" + requestSha256,
                synchronizedModules(command.domain()), refreshed.latestValues());
        audit.record(principal, "FORMAL_SAMPLE_OBSERVATION", observationId.toString(),
                "FORMAL_SAMPLE_OBSERVATION_SAVED", savedAt,
                auditDetail(command.domain(), identity, sourceRecordId,
                        principal.subjectId(), administratorOverride));
        repository.store(principal.subjectId(), idempotencyKey, requestSha256, sourceRecordId, result);
        return result;
    }

    private void validateCommand(String idempotencyKey, FormalSampleObservationCommand command) {
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$")) {
            throw invalidCommand("幂等键格式不正确");
        }
        if (command == null || command.domain() == null || command.samplePointId() == null
                || command.productCode() == null || command.productCode().isBlank()
                || command.observedAt() == null || command.payload() == null
                || !command.payload().isObject()) {
            throw invalidCommand("领域、正式样本、产品、实际观测时间和业务数据不能为空");
        }
        validatePayload(command.payload(), 0, new int[] {0});
    }

    private void validatePayload(JsonNode node, int depth, int[] nodes) {
        if (depth > 12 || ++nodes[0] > 500) throw invalidCommand("业务数据层级或字段数量超出限制");
        if (node.isTextual() && node.asText().length() > 2_000) {
            throw invalidCommand("业务数据文本超出长度限制");
        }
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getKey().length() > 120) throw invalidCommand("业务字段名称超出长度限制");
                validatePayload(field.getValue(), depth + 1, nodes);
            });
        } else if (node.isArray()) {
            node.forEach(value -> validatePayload(value, depth + 1, nodes));
        }
    }

    private String requestSha256(FormalSampleObservationCommand command, String normalizedProduct) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("domain", command.domain().name());
        canonical.put("samplePointId", command.samplePointId().toString());
        canonical.put("productCode", normalizedProduct);
        canonical.put("observedAt", command.observedAt().toInstant().toString());
        canonical.set("payload", sorted(command.payload()));
        if (command.sampleLocation() != null) {
            canonical.set("sampleLocation", sorted(objectMapper.valueToTree(command.sampleLocation())));
        }
        return sha256(write(canonical));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new java.util.ArrayList<>();
            node.properties().forEach(fields::add);
            fields.stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(field -> result.set(field.getKey(), sorted(field.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node.deepCopy();
    }

    private <T> T readPayload(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (Exception exception) {
            throw invalidCommand("业务数据格式不正确");
        }
    }

    private static boolean requireMaintainer(
            SecurityPrincipal principal, FormalSampleIdentity identity) {
        if (identity.maintainerSubjectId().equals(principal.subjectId())) return false;
        if (principal.permits("FORMAL_SAMPLE_MANAGE")) return true;
        throw new AccessDeniedException(
                "FORMAL_SAMPLE_MAINTAINER_DENIED",
                "当前账号不是该正式样本的维护人，不能更新期间数据");
    }

    private static FormalSampleIdentity withMaintainer(
            FormalSampleIdentity identity, String maintainerSubjectId) {
        return new FormalSampleIdentity(identity.samplePointId(), identity.sampleName(),
                identity.productCode(), identity.regionCode(), maintainerSubjectId,
                identity.latitude(), identity.longitude(), identity.effectiveFrom(),
                identity.effectiveTo(), identity.lockedValues());
    }

    private String auditDetail(FormalSampleObservationDomain domain,
            FormalSampleIdentity identity, String sourceRecordId,
            String actorSubjectId, boolean administratorOverride) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("domain", domain.name());
        detail.put("samplePointId", identity.samplePointId().toString());
        detail.put("regionCode", identity.regionCode());
        detail.put("productCode", identity.productCode());
        detail.put("sourceRecordId", sourceRecordId);
        detail.put("maintainerSubjectId", identity.maintainerSubjectId());
        detail.put("actorSubjectId", actorSubjectId);
        detail.put("administratorOverride", administratorOverride);
        return write(detail);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Formal sample observation cannot be serialized", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<String> synchronizedModules(FormalSampleObservationDomain domain) {
        return switch (domain) {
            case PRODUCTION -> List.of("OVERVIEW", "PRODUCTION_ANALYSIS", "REPORTS");
            case MARKET -> List.of("OVERVIEW", "MARKET_ANALYSIS", "REPORTS");
            case LOGISTICS -> List.of("OVERVIEW", "LOGISTICS_ANALYSIS", "REPORTS");
        };
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().toUpperCase();
    }

    private static String keywordPattern(String keyword) {
        if (keyword == null) return null;
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static ClientRequestException invalid(String message) {
        return new ClientRequestException("INVALID_FORMAL_SAMPLE_OBSERVATION_QUERY", message);
    }

    private static ClientRequestException invalidCommand(String message) {
        return new ClientRequestException("INVALID_FORMAL_SAMPLE_OBSERVATION", message);
    }
}
