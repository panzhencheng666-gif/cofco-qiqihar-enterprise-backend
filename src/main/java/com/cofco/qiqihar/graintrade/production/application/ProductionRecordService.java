package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.production.domain.ProductionActionPolicy;
import com.cofco.qiqihar.graintrade.production.domain.ProductionSubmissionMetadata;
import com.cofco.qiqihar.graintrade.production.domain.ProductionValidationException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionRecordService implements ProductionImportPort {
    private static final String DOMAIN = "PRODUCTION";
    private static final String PAGE_KIND = "MONITORING";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final ProductionRecordRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final CurrentActor currentActor;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final EvidencePhotoService evidencePhotos;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final Clock clock;

    public ProductionRecordService(ProductionRecordRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, Clock clock) {
        this(repository, pageDefinitions, currentActor, null, null, null, null, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionRecordService(ProductionRecordRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, AccessControl accessControl, BusinessAuditRecorder audit,
            EvidencePhotoService evidencePhotos, SeparationOfDutiesPolicy separationOfDuties, Clock clock) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.currentActor = currentActor;
        this.accessControl = accessControl;
        this.audit = audit;
        this.evidencePhotos = evidencePhotos;
        this.separationOfDuties = separationOfDuties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductionListItem> read(ProductionRecordQuery query) {
        if (!pageDefinitions.allowsListQueryValues(DOMAIN, query.pageKind(), query.productCode(),
                query.pageSize(), query.filters())) throw invalidQuery();
        String regionCode = query.filters().get("regionCode");
        if (regionCode != null && !repository.isKnownRegion(regionCode)) throw invalidQuery();
        AuthorizedReadScope scope = readScope();
        if (regionCode != null) scope.requireRegion(regionCode);
        PagedResult<ProductionListRow> page = repository.findPage(query.authorizedFor(scope.regionCodes()));
        List<ProductionListItem> items = page.items().stream().map(row -> new ProductionListItem(
                row.id(), row.values(), ProductionActionPolicy.allowedActions(row.status()).stream()
                        .filter(row.configuredActions()::contains)
                        .filter(action -> actionAllowed(action, "PRODUCTION_RECORD", row.id(),
                                "PRODUCTION_RECORD_SUBMITTED"))
                        .toList(), row.version())).toList();
        return new PagedResult<>(items, page.pageNumber(), page.pageSize(), page.totalElements());
    }

    @Transactional(readOnly = true)
    public ProductionRecordView detail(String id) {
        ProductionRecord record = requiredRecord(id);
        readScope().requireRegion(record.regionCode());
        return view(record);
    }

    @Transactional(readOnly = true)
    public ProductionFormDefinition factDefinition(String productCode, String objectTypeCode) {
        if (productCode == null || productCode.isBlank()
                || (objectTypeCode != null && !repository.isApplicableObjectType(productCode, objectTypeCode))) {
            throw invalidDraft("Invalid production definition context");
        }
        List<ProductionFactCategory> categories = repository.findFactCategories().stream()
                .sorted(Comparator.comparingInt(ProductionFactCategory::sortOrder)
                        .thenComparing(ProductionFactCategory::code)).toList();
        Map<String, List<ProductionFactDefinition>> definitionsByCategory = new LinkedHashMap<>();
        categories.forEach(category -> {
            if (definitionsByCategory.put(category.code(), new java.util.ArrayList<>()) != null) {
                throw new IllegalStateException("Duplicate production fact category: " + category.code());
            }
        });
        repository.findFactDefinitions(productCode, objectTypeCode).forEach(definition -> {
            List<ProductionFactDefinition> definitions = definitionsByCategory.get(definition.category());
            if (definitions == null) {
                throw new IllegalStateException(
                        "Production fact category is absent from master data: " + definition.category());
            }
            definitions.add(definition);
        });
        List<ProductionFactGroup> groups = categories.stream().map(category -> new ProductionFactGroup(
                category.code(), category.label(), category.sortOrder(),
                definitionsByCategory.get(category.code()).stream()
                        .sorted(Comparator.comparingInt(ProductionFactDefinition::sortOrder)
                                .thenComparing(ProductionFactDefinition::code)).toList())).toList();
        return new ProductionFormDefinition(productCode, objectTypeCode, groups);
    }

    @Override
    public ProductionImportDefinition importDefinition(String productCode, String objectTypeCode) {
        ProductionFormDefinition definition = factDefinition(productCode, objectTypeCode);
        return new ProductionImportDefinition(definition.productCode(), definition.objectTypeCode(),
                definition.groups().stream().map(group -> new ProductionImportDefinition.Group(
                        group.category(), group.label(), group.fields().stream()
                                .map(field -> new ProductionImportDefinition.Field(
                                        field.code(), field.label(), field.unit(),
                                        field.precision(), field.scale()))
                                .toList())).toList());
    }

    @Transactional
    public ProductionRecordView create(ProductionDraft draft) {
        SecurityPrincipal principal = authorize("BUSINESS_CREATE", draft.regionCode());
        Map<String, String> submissionMetadata = canonicalSubmissionMetadata(
                draft.submissionMetadata(), principal.displayName());
        validateDraft(draft, submissionMetadata);
        validateEvidence(draft, principal);
        ProductionRecord record;
        try {
            record = ProductionRecord.draft(UUID.randomUUID().toString(), draft.productCode(),
                    draft.objectTypeCode(), draft.regionCode(), draft.cultivarCode(), draft.surveyDate(), now(),
                    draft.cultivatedAreaMu(), draft.yieldPerMuKilograms(), draft.quality(), draft.costs(),
                    draft.insurance(), draft.subsidies(), submissionMetadata);
        } catch (ProductionValidationException exception) {
            throw invalidDraft(exception.getMessage());
        }
        ProductionRecord persisted = repository.insert(record, principal.subjectId());
        if (evidencePhotos != null) {
            evidencePhotos.attachToProduction(
                    draft.evidencePhotoIds(), persisted.id(), persisted.regionCode(), principal.subjectId());
        }
        audit(principal, persisted, "PRODUCTION_RECORD_CREATED");
        return view(persisted);
    }

    @Override
    public String importDraft(ProductionDraft draft) {
        return create(draft).record().id();
    }

    @Override
    @Transactional(readOnly = true)
    public void validateImportDraft(ProductionDraft draft) {
        SecurityPrincipal principal = authorize("BUSINESS_IMPORT", draft.regionCode());
        validateDraft(draft, canonicalSubmissionMetadata(
                draft.submissionMetadata(), principal.displayName()));
        validateEvidence(draft, principal);
    }

    @Transactional
    public ProductionRecordView saveDraft(String id, long expectedVersion, ProductionDraft draft) {
        ProductionRecord existing = requiredRecord(id);
        SecurityPrincipal principal = authorize("BUSINESS_UPDATE", existing.regionCode());
        if (expectedVersion != existing.version()) throw stale();
        if (!existing.productCode().equals(draft.productCode())) throw invalidDraft("Record product cannot be changed");
        Map<String, String> submissionMetadata = canonicalSubmissionMetadata(
                draft.submissionMetadata(), existing.submissionMetadata().get("PROD_REPORTER_NAME"));
        validateDraft(draft, submissionMetadata);
        authorize("BUSINESS_UPDATE", draft.regionCode());
        ProductionRecord revised;
        try {
            revised = existing.revise(draft.productCode(), draft.objectTypeCode(), draft.regionCode(),
                    draft.cultivarCode(), draft.surveyDate(), now(), draft.cultivatedAreaMu(),
                    draft.yieldPerMuKilograms(), draft.quality(), draft.costs(), draft.insurance(), draft.subsidies(),
                    submissionMetadata);
        } catch (ProductionValidationException exception) {
            throw invalidDraft(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
        ProductionRecord persisted = repository.updateFacts(revised, expectedVersion, principal.subjectId());
        audit(principal, persisted, "PRODUCTION_RECORD_UPDATED");
        return view(persisted);
    }

    @Transactional
    public ProductionRecordView submit(String id, long expectedVersion) {
        return transition(id, expectedVersion, "BUSINESS_SUBMIT", "PRODUCTION_RECORD_SUBMITTED", ProductionRecord::submit);
    }

    @Transactional
    public ProductionRecordView approve(String id, long expectedVersion) {
        return transition(id, expectedVersion, "BUSINESS_APPROVE", "PRODUCTION_RECORD_APPROVED", ProductionRecord::approve);
    }

    @Transactional
    public ProductionRecordView returnForCorrection(String id, long expectedVersion, String reason) {
        return transition(id, expectedVersion, "BUSINESS_RETURN", "PRODUCTION_RECORD_RETURNED", record -> record.returnForCorrection(reason));
    }

    private ProductionRecordView transition(String id, long expectedVersion, String permission, String auditAction,
            java.util.function.UnaryOperator<ProductionRecord> command) {
        ProductionRecord existing = requiredRecord(id);
        SecurityPrincipal principal = authorize(permission, existing.regionCode());
        if (expectedVersion != existing.version()) throw stale();
        try {
            ProductionRecord transitioned = command.apply(existing);
            if (separationOfDuties != null && permission.equals("BUSINESS_APPROVE")) {
                separationOfDuties.requireIndependentApprover(
                        "PRODUCTION_RECORD", id, "PRODUCTION_RECORD_SUBMITTED", principal);
            }
            if (separationOfDuties != null && permission.equals("BUSINESS_RETURN")) {
                separationOfDuties.requireIndependentReturner(
                        "PRODUCTION_RECORD", id, "PRODUCTION_RECORD_SUBMITTED", principal);
            }
            ProductionRecord persisted = repository.updateState(transitioned, expectedVersion, principal.subjectId());
            audit(principal, persisted, auditAction);
            return view(persisted);
        } catch (ProductionValidationException exception) {
            throw invalidDraft(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    private void validateDraft(ProductionDraft draft, Map<String, String> submissionMetadata) {
        if (draft.surveyDate() == null || draft.surveyDate().isAfter(LocalDate.now(clock.withZone(REPORTING_ZONE)))) {
            throw invalidDraft("Survey date cannot be in the future");
        }
        try {
            ProductionSubmissionMetadata.from(submissionMetadata);
        } catch (IllegalArgumentException exception) {
            throw invalidDraft(exception.getMessage());
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
    }

    private Map<String, String> canonicalSubmissionMetadata(
            Map<String, String> submittedMetadata, String authoritativeReporterName) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (submittedMetadata != null) metadata.putAll(submittedMetadata);
        metadata.put("PROD_REPORTER_NAME", authoritativeReporterName);
        try {
            return ProductionSubmissionMetadata.from(metadata).asMap();
        } catch (IllegalArgumentException exception) {
            throw invalidDraft(exception.getMessage());
        }
    }

    private ProductionRecord requiredRecord(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_RECORD_NOT_FOUND", "Production record does not exist"));
    }

    private ProductionRecordView view(ProductionRecord record) {
        return new ProductionRecordView(record, ProductionActionPolicy.allowedActions(record.status()).stream()
                .filter(action -> actionAllowed(action, "PRODUCTION_RECORD", record.id(),
                        "PRODUCTION_RECORD_SUBMITTED"))
                .toList(),
                evidencePhotos == null ? List.of() : evidencePhotos.productionPhotos(record.id()));
    }

    private boolean actionAllowed(String action, String aggregateType, String aggregateId, String submittedAction) {
        if (accessControl == null) return true;
        SecurityPrincipal principal = accessControl.authenticated().orElse(null);
        if (principal == null) return true; // Explicit unrestricted read identity exists only in test support.
        String permission = switch (action) {
            case "VIEW" -> "BUSINESS_READ";
            case "SAVE" -> "BUSINESS_UPDATE";
            case "SUBMIT" -> "BUSINESS_SUBMIT";
            case "APPROVE" -> "BUSINESS_APPROVE";
            case "RETURN" -> "BUSINESS_RETURN";
            default -> null;
        };
        if (permission == null || !principal.permits(permission)) return false;
        return !Set.of("APPROVE", "RETURN").contains(action) || separationOfDuties == null
                || separationOfDuties.isIndependentReviewer(
                        aggregateType, aggregateId, submittedAction, principal);
    }

    private void validateEvidence(ProductionDraft draft, SecurityPrincipal principal) {
        if (evidencePhotos != null) evidencePhotos.validateAvailable(draft.evidencePhotoIds(), principal.subjectId());
    }

    private AuthenticatedActor actor() {
        return currentActor.currentActor().orElseThrow(AuthenticationRequiredException::new);
    }

    private SecurityPrincipal authorize(String permissionCode, String regionCode) {
        if (accessControl != null) return accessControl.require(permissionCode, regionCode);
        return new SecurityPrincipal(actor().id(), "UNIT_TEST", Set.of(), Set.of());
    }

    private AuthorizedReadScope readScope() {
        return accessControl == null ? AuthorizedReadScope.unrestricted() : accessControl.requireReadScope();
    }

    private void audit(SecurityPrincipal principal, ProductionRecord record, String actionCode) {
        if (audit != null) {
            audit.record(principal, "PRODUCTION_RECORD", record.id(), actionCode, clock.instant(),
                    "{\"regionCode\":\"" + record.regionCode() + "\",\"productCode\":\""
                            + record.productCode() + "\"}");
        }
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
