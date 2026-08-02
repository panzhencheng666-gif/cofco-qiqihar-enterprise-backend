package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionRecordService {
    private static final String DOMAIN = "PRODUCTION";
    private static final String PAGE_KIND = "MONITORING";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final ProductionRecordRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ProductionRecordService(ProductionRecordRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, Clock clock) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductionListItem> read(ProductionRecordQuery query) {
        if (!pageDefinitions.allowsListQueryValues(DOMAIN, query.pageKind(), query.productCode(),
                query.pageSize(), query.filters())) throw invalidQuery();
        String regionCode = query.filters().get("regionCode");
        if (regionCode != null && !repository.isKnownRegion(regionCode)) throw invalidQuery();
        return repository.findPage(query);
    }

    @Transactional(readOnly = true)
    public ProductionRecord detail(String id) { return requiredRecord(id); }

    @Transactional(readOnly = true)
    public List<ProductionFactDefinition> factDefinitions(String productCode, String objectTypeCode) {
        if (productCode == null || productCode.isBlank()
                || (objectTypeCode != null && !repository.isApplicableObjectType(productCode, objectTypeCode))) {
            throw invalidDraft("Invalid production definition context");
        }
        return repository.findFactDefinitions(productCode, objectTypeCode);
    }

    @Transactional
    public ProductionRecord create(ProductionDraft draft) {
        AuthenticatedActor actor = actor();
        validateDraft(draft);
        ProductionRecord record = ProductionRecord.draft(UUID.randomUUID().toString(), draft.productCode(),
                draft.objectTypeCode(), draft.regionCode(), draft.cultivarCode(), draft.surveyDate(), now(),
                draft.cultivatedAreaMu(), draft.yieldPerMuKilograms(), draft.quality(), draft.costs(),
                draft.insurance(), draft.subsidies());
        return repository.insert(record, actor.id());
    }

    @Transactional
    public ProductionRecord saveDraft(String id, long expectedVersion, ProductionDraft draft) {
        AuthenticatedActor actor = actor();
        ProductionRecord existing = requiredRecord(id);
        if (expectedVersion != existing.version()) throw stale();
        if (!existing.productCode().equals(draft.productCode())) throw invalidDraft("Record product cannot be changed");
        validateDraft(draft);
        ProductionRecord revised;
        try {
            revised = existing.revise(draft.productCode(), draft.objectTypeCode(), draft.regionCode(),
                    draft.cultivarCode(), draft.surveyDate(), now(), draft.cultivatedAreaMu(),
                    draft.yieldPerMuKilograms(), draft.quality(), draft.costs(), draft.insurance(), draft.subsidies());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
        return repository.updateFacts(revised, expectedVersion, actor.id());
    }

    @Transactional
    public ProductionRecord submit(String id, long expectedVersion) {
        return transition(id, expectedVersion, ProductionRecord::submit);
    }

    @Transactional
    public ProductionRecord approve(String id, long expectedVersion) {
        return transition(id, expectedVersion, ProductionRecord::approve);
    }

    @Transactional
    public ProductionRecord returnForCorrection(String id, long expectedVersion, String reason) {
        return transition(id, expectedVersion, record -> record.returnForCorrection(reason));
    }

    private ProductionRecord transition(String id, long expectedVersion,
            java.util.function.UnaryOperator<ProductionRecord> command) {
        AuthenticatedActor actor = actor();
        ProductionRecord existing = requiredRecord(id);
        if (expectedVersion != existing.version()) throw stale();
        try {
            return repository.updateState(command.apply(existing), expectedVersion, actor.id());
        } catch (IllegalArgumentException exception) {
            throw invalidDraft(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    private void validateDraft(ProductionDraft draft) {
        try {
            if (draft.surveyDate() == null || draft.surveyDate().isAfter(LocalDate.now(clock.withZone(REPORTING_ZONE)))) {
                throw invalidDraft("Survey date cannot be in the future");
            }
            if (!repository.isKnownRegion(draft.regionCode())) throw invalidDraft("Unknown region");
            if (!repository.isApplicableObjectType(draft.productCode(), draft.objectTypeCode())) {
                throw new ClientRequestException("INAPPLICABLE_PRODUCTION_OBJECT_TYPE",
                        "Object type is not applicable to this product");
            }
            if (draft.cultivarCode() != null && !repository.isApplicableCultivar(draft.productCode(), draft.cultivarCode())) {
                throw new ClientRequestException("INAPPLICABLE_PRODUCTION_CULTIVAR",
                        "Cultivar is not applicable to this product");
            }
            Map<String, Set<String>> facts = new LinkedHashMap<>();
            facts.put("QUALITY", draft.quality().keySet());
            facts.put("COST", draft.costs().keySet());
            facts.put("INSURANCE", draft.insurance().keySet());
            facts.put("SUBSIDY", draft.subsidies().keySet());
            if (!repository.areApplicableFacts(draft.productCode(), draft.objectTypeCode(), facts)) {
                throw new ClientRequestException("INAPPLICABLE_PRODUCTION_FACT",
                        "One or more facts are not applicable to this production context");
            }
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidDraft(exception.getMessage());
        }
    }

    private ProductionRecord requiredRecord(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_RECORD_NOT_FOUND", "Production record does not exist"));
    }

    private AuthenticatedActor actor() {
        return currentActor.currentActor().orElseThrow(AuthenticationRequiredException::new);
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), REPORTING_ZONE); }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException("INVALID_PRODUCTION_RECORD_QUERY", "Production record query context is invalid");
    }
    private static ClientRequestException invalidDraft(String message) {
        return new ClientRequestException("INVALID_PRODUCTION_RECORD", message == null ? "Invalid production record" : message);
    }
    private static ConflictException invalidTransition(IllegalStateException exception) {
        return new ConflictException("INVALID_PRODUCTION_TRANSITION", exception.getMessage());
    }
    private static ConflictException stale() {
        return new ConflictException("PRODUCTION_RECORD_VERSION_CONFLICT", "Production record has changed");
    }
}
