package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionRecordService {
    private final ProductionRecordRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final CurrentActor currentActor;

    public ProductionRecordService(ProductionRecordRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductionRecord> read(ProductionRecordQuery query) {
        if (!pageDefinitions.allowsListQuery("PRODUCTION", query.pageKind(), query.productCode(),
                query.pageSize(), query.filters().keySet())) {
            throw invalidQuery();
        }
        return repository.findPage(query);
    }

    @Transactional(readOnly = true)
    public ProductionRecord detail(String id) { return requiredRecord(id); }

    @Transactional
    public ProductionRecord create(ProductionDraft draft) { return save(newRecord(draft), draft); }

    @Transactional
    public ProductionRecord saveDraft(String id, ProductionDraft draft) {
        ProductionRecord existing = requiredRecord(id);
        return save(record(id, draft).saveDraft(), draft);
    }

    @Transactional
    public ProductionRecord submit(String id) { return persistTransition(requiredRecord(id).submit()); }

    @Transactional
    public ProductionRecord approve(String id) { return persistTransition(requiredRecord(id).approve()); }

    @Transactional
    public ProductionRecord returnForCorrection(String id, String reason) {
        return persistTransition(requiredRecord(id).returnForCorrection(reason));
    }

    private ProductionRecord newRecord(ProductionDraft draft) { return record(UUID.randomUUID().toString(), draft); }

    private ProductionRecord record(String id, ProductionDraft draft) {
        if (!repository.isApplicableObjectType(draft.productCode(), draft.objectTypeCode())) {
            throw new ClientRequestException("INAPPLICABLE_PRODUCTION_OBJECT_TYPE", "Object type is not applicable to this product");
        }
        return ProductionRecord.draft(id, draft.productCode(), draft.objectTypeCode(), draft.regionCode(),
                draft.cultivarCode(), draft.surveyDate(), draft.reportedAt(), draft.cultivatedAreaMu(),
                draft.yieldPerMuKilograms(), draft.quality());
    }

    private ProductionRecord save(ProductionRecord record, ProductionDraft draft) {
        AuthenticatedActor actor = actor();
        repository.save(record, draft.costs(), draft.insurance(), draft.subsidies(), actor.id());
        return record;
    }

    private ProductionRecord persistTransition(ProductionRecord record) {
        AuthenticatedActor actor = actor();
        repository.save(record, Map.of(), Map.of(), Map.of(), actor.id());
        return record;
    }

    private ProductionRecord requiredRecord(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_RECORD_NOT_FOUND", "Production record does not exist"));
    }

    private AuthenticatedActor actor() {
        return currentActor.currentActor().orElseThrow(() -> new AuthenticationRequiredException());
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException("INVALID_PRODUCTION_RECORD_QUERY", "Production record query context is invalid");
    }
}
