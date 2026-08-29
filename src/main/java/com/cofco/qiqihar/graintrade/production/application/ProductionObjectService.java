package com.cofco.qiqihar.graintrade.production.application;

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
public class ProductionObjectService {
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");
    private static final Set<String> VALID_OBJECT_TYPES =
            Set.of("farmer", "village-committee", "agri-station");
    private final ProductionObjectRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public ProductionObjectService(
            ProductionObjectRepository repository,
            AccessControl access,
            BusinessAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductionObjectView> list() {
        return repository.findAll(access.requireReadScope().regionCodes());
    }

    @Transactional
    public ProductionObjectView create(ProductionObjectDraft draft) {
        draft = validated(draft);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", draft.regionCode());
        if (repository.conflicts(null, draft)) {
            throw conflict("PRODUCTION_OBJECT_CONFLICT", "产情调查对象资料与现有记录冲突");
        }
        try {
            ProductionObjectView created = repository.insert(
                    UUID.randomUUID().toString(), draft,
                    actor.subjectId(), actor.displayName(), clock.instant());
            audit.record(actor, "PRODUCTION_OBJECT", created.objectId(),
                    "PRODUCTION_OBJECT_CREATED", clock.instant(), "{\"version\":0}");
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("PRODUCTION_OBJECT_CONFLICT", "产情调查对象资料与现有记录冲突");
        }
    }

    @Transactional
    public ProductionObjectView update(String objectId, long expectedVersion, ProductionObjectDraft draft) {
        ProductionObjectView current = required(objectId);
        draft = validated(draft);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", current.regionCode());
        access.require("BUSINESS_UPDATE", draft.regionCode());
        if (repository.conflicts(objectId, draft)) {
            throw conflict("PRODUCTION_OBJECT_CONFLICT", "产情调查对象资料与现有记录冲突");
        }
        try {
            ProductionObjectView updated = repository.update(
                            objectId, expectedVersion, draft,
                            current.responsibleUserId(), current.responsiblePerson(),
                            actor.subjectId(), clock.instant())
                    .orElseThrow(() -> conflict(
                            "PRODUCTION_OBJECT_VERSION_CONFLICT", "产情调查对象已发生变化，请刷新后重试"));
            audit.record(actor, "PRODUCTION_OBJECT", updated.objectId(),
                    "PRODUCTION_OBJECT_UPDATED", clock.instant(),
                    "{\"version\":" + updated.version() + "}");
            return updated;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("PRODUCTION_OBJECT_CONFLICT", "产情调查对象资料与现有记录冲突");
        }
    }

    private ProductionObjectView required(String objectId) {
        return repository.find(objectId).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_OBJECT_NOT_FOUND", "产情调查对象不存在"));
    }

    private ProductionObjectDraft validated(ProductionObjectDraft draft) {
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
                || duplicates(draft.roles().stream().map(ProductionObjectRoleDraft::roleId).toList())
                || draft.roles().stream().anyMatch(role -> blank(role.roleId())
                        || blank(role.capabilityTemplateVersionId())
                        || role.effectiveFrom() == null
                        || role.effectiveTo() != null && role.effectiveTo().isBefore(role.effectiveFrom()))
                || !repository.valid(draft)) {
            throw new ClientRequestException(
                    "INVALID_PRODUCTION_OBJECT", "产情调查对象资料不完整或不适用");
        }
        return draft;
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
