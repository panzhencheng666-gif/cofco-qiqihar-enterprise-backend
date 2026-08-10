package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketObjectService {
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");
    private static final Set<String> VALID_OBJECT_TYPES = Set.of("business-party");
    private final MarketObjectRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public MarketObjectService(
            MarketObjectRepository repository,
            AccessControl access,
            BusinessAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MarketObjectView> list() {
        return repository.findAll(access.requireReadScope().regionCodes());
    }

    @Transactional
    public MarketObjectView create(MarketObjectDraft draft) {
        validate(draft);
        SecurityPrincipal actor = access.require("MARKET_OBJECT_MANAGE", draft.regionCode());
        try {
            MarketObjectView created = repository.insert(
                    UUID.randomUUID().toString(), draft, actor.subjectId(), actor.displayName(), clock.instant());
            audit.record(actor, "MARKET_OBJECT", created.objectId(), "MARKET_OBJECT_CREATED", clock.instant(),
                    "{\"version\":0}");
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("MARKET_OBJECT_ALREADY_EXISTS", "同一地区已存在同名市场监测对象");
        }
    }

    @Transactional
    public MarketObjectView update(String objectId, long expectedVersion, MarketObjectDraft draft) {
        validate(draft);
        MarketObjectView current = required(objectId);
        SecurityPrincipal actor = access.require("MARKET_OBJECT_MANAGE", current.regionCode());
        access.require("MARKET_OBJECT_MANAGE", draft.regionCode());
        try {
            MarketObjectView updated = repository.update(
                            objectId, expectedVersion, draft,
                            current.responsibleUserId(), current.responsiblePerson(),
                            actor.subjectId(), clock.instant())
                    .orElseThrow(() -> conflict(
                            "MARKET_OBJECT_VERSION_CONFLICT", "市场监测对象已发生变化，请刷新后重试"));
            audit.record(actor, "MARKET_OBJECT", updated.objectId(), "MARKET_OBJECT_UPDATED", clock.instant(),
                    "{\"version\":" + updated.version() + "}");
            return updated;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("MARKET_OBJECT_ALREADY_EXISTS", "同一地区已存在同名市场监测对象");
        }
    }

    private MarketObjectView required(String objectId) {
        return repository.find(objectId).orElseThrow(() -> new ResourceNotFoundException(
                "MARKET_OBJECT_NOT_FOUND", "市场监测对象不存在"));
    }

    private void validate(MarketObjectDraft draft) {
        if (draft == null
                || blank(draft.objectName())
                || blank(draft.objectTypeId())
                || blank(draft.regionCode())
                || blank(draft.sourceChannelId())
                || draft.effectiveFrom() == null
                || !VALID_OBJECT_TYPES.contains(draft.objectTypeId())
                || !VALID_STATUSES.contains(draft.validityStatus())
                || draft.productIds().isEmpty()
                || draft.roles().isEmpty()
                || draft.effectiveTo() != null && draft.effectiveTo().isBefore(draft.effectiveFrom())
                || draft.objectName().codePointCount(0, draft.objectName().length()) > 200
                || duplicates(draft.productIds())
                || duplicates(draft.cultivarIds())
                || duplicates(draft.roles().stream().map(MarketObjectRoleDraft::roleId).toList())
                || draft.roles().stream().anyMatch(role -> blank(role.roleId())
                        || blank(role.capabilityTemplateVersionId())
                        || role.effectiveFrom() == null
                        || role.effectiveTo() != null && role.effectiveTo().isBefore(role.effectiveFrom()))
                || !repository.valid(draft)) {
            throw new ClientRequestException("INVALID_MARKET_OBJECT", "市场监测对象资料不完整或不适用");
        }
    }

    private static boolean duplicates(List<String> values) {
        return new HashSet<>(values).size() != values.size();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ConflictException conflict(String code, String message) {
        return new ConflictException(code, message);
    }
}
