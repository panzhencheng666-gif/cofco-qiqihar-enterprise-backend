package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.market.domain.MarketActionPolicy;
import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.market.domain.MarketTradeDirection;
import com.cofco.qiqihar.graintrade.market.domain.MarketValidationException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketMonitoringService {
    private static final String DOMAIN = "MARKET";
    private static final String PAGE_KIND = "MONITORING";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_TYPED_BINDINGS = Set.of(
            "OBJECT_TYPE", "REGION", "TRADE_DATE", "REPORTED_AT",
            "PURCHASE_BASE_PRICE", "SALE_BASE_PRICE", "CARRIAGE_BOARD_AMOUNT",
            "PACKAGING_FORM", "PACKAGING_AMOUNT", "FREIGHT_AMOUNT");
    private static final Set<String> SYSTEM_MANAGED_OR_RETIRED_INPUT_CODES = Set.of(
            "MKT_CULTIVAR_NAME", "MKT_SAMPLE_SUBJECT_CODE", "MKT_STORAGE_REGION_CODE",
            "MKT_INVENTORY_HOLDER_CODE", "MKT_INVENTORY_OWNERSHIP_TYPE", "MKT_CARGO_OWNER_CODE",
            "MKT_INVENTORY_CUTOFF_DATE", "MKT_INVENTORY_POLICY_ATTRIBUTE");
    private final MarketMonitoringRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final CurrentActor currentActor;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final EvidencePhotoService evidencePhotos;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final Clock clock;

    public MarketMonitoringService(MarketMonitoringRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, Clock clock) {
        this(repository, pageDefinitions, currentActor, null, null, null, null, clock);
    }

    public MarketMonitoringService(MarketMonitoringRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, AccessControl accessControl, BusinessAuditRecorder audit, Clock clock) {
        this(repository, pageDefinitions, currentActor, accessControl, audit, null, null, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MarketMonitoringService(MarketMonitoringRepository repository, PageDefinitionQuery pageDefinitions,
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
    public PagedResult<MarketListItem> list(MarketRecordQuery query) {
        try {
            Math.multiplyExact((long) query.pageNumber(), query.pageSize());
        } catch (ArithmeticException exception) {
            throw invalidQuery();
        }
        if (!pageDefinitions.allowsListQueryValues(
                DOMAIN, query.pageKind(), query.productCode(), query.pageSize(), query.filters())) {
            throw invalidQuery();
        }
        String regionCode = query.filters().get("regionCode");
        if (regionCode != null && !repository.isKnownRegion(regionCode)) throw invalidQuery();
        AuthorizedReadScope scope = readScope();
        if (regionCode != null) scope.requireRegion(regionCode);
        PagedResult<MarketListRow> page = repository.findPage(query.authorizedFor(scope.regionCodes()));
        List<MarketListItem> items = page.items().stream().map(row -> new MarketListItem(
                row.id(), publicValues(row.values()), MarketActionPolicy.allowedActions(row.status()).stream()
                        .filter(row.configuredActions()::contains)
                        .filter(action -> actionAllowed(action, row.id()))
                        .toList(), row.version())).toList();
        return new PagedResult<>(items, page.pageNumber(), page.pageSize(), page.totalElements());
    }

    @Transactional(readOnly = true)
    public MarketRecordView detail(String id) {
        MarketMonitoringRecord record = required(id);
        readScope().requireRegion(record.regionCode());
        return view(record, coreFields(record.productCode()),
                repository.findExtensionCoreValues(id));
    }

    @Transactional(readOnly = true)
    public MarketFormDefinition definition(String productCode, String objectTypeCode) {
        if (!pageDefinitions.hasDefinition(new BusinessPageKey(DOMAIN, PAGE_KIND, productCode))) {
            throw invalid("Invalid market definition context");
        }
        if (objectTypeCode != null
                && !repository.isApplicableObjectType(productCode, objectTypeCode)) {
            throw invalid("Invalid market definition context");
        }
        List<MarketFactCategory> categories = repository.findFactCategories().stream()
                .sorted(Comparator.comparingInt(MarketFactCategory::sortOrder)
                        .thenComparing(MarketFactCategory::code)).toList();
        Map<String, List<MarketFactDefinition>> fields = new LinkedHashMap<>();
        categories.forEach(category -> {
            if (fields.put(category.code(), new ArrayList<>()) != null) {
                throw new IllegalStateException("Duplicate market fact category: " + category.code());
            }
        });
        repository.findFactDefinitions(productCode, objectTypeCode).forEach(field -> {
            List<MarketFactDefinition> group = fields.get(field.category());
            if (group == null) {
                throw new IllegalStateException(
                        "Market fact category is absent from master data: " + field.category());
            }
            group.add(field);
        });
        List<MarketFactGroup> groups = categories.stream().map(category -> new MarketFactGroup(
                category.code(), category.label(), category.sortOrder(),
                fields.get(category.code()).stream()
                        .sorted(Comparator.comparingInt(MarketFactDefinition::sortOrder)
                                .thenComparing(MarketFactDefinition::code)).toList())).toList();
        return new MarketFormDefinition(
                productCode, objectTypeCode, publicCoreFields(coreFields(productCode)), groups);
    }

    /** Used by the write interceptor before request-body conversion. */
    public void requireAuthentication() {
        actor();
    }

    @Transactional
    public MarketRecordView create(MarketMonitoringDraft draft) {
        return create(draft, true);
    }

    private MarketRecordView create(MarketMonitoringDraft draft, boolean requireEvidence) {
        SecurityPrincipal principal = authorize("BUSINESS_CREATE", null);
        MarketMonitoringDraft securedDraft = withReporter(draft, principal.displayName());
        List<MarketCoreFieldDefinition> definitions = coreDefinitions(securedDraft);
        ParsedDraft parsed = parseDraft(securedDraft, definitions);
        validate(parsed);
        principal = authorize("BUSINESS_CREATE", parsed.regionCode());
        if (requireEvidence || !securedDraft.evidencePhotoIds().isEmpty()) validateEvidence(securedDraft, principal);
        try {
            MarketMonitoringRecord record = MarketMonitoringRecord.draft(
                    UUID.randomUUID().toString(), parsed.productCode(), parsed.objectTypeCode(),
                    parsed.regionCode(), parsed.tradeDate(), now(), parsed.direction(),
                    parsed.purchaseBasePrice(), parsed.saleBasePrice(), parsed.carriageBoardAmount(),
                    parsed.packagingAmount(), parsed.freightAmount(), parsed.packagingForm(), parsed.facts());
            MarketMonitoringRecord persisted = repository.insert(record, principal.subjectId(), parsed.extensions());
            repository.reconcileInventoryGovernance(
                    persisted, parsed.extensions(), principal.subjectId(), clock.instant());
            if (evidencePhotos != null && !securedDraft.evidencePhotoIds().isEmpty()) {
                evidencePhotos.attachToMarket(
                        securedDraft.evidencePhotoIds(), persisted.id(), persisted.regionCode(), principal.subjectId());
            }
            audit(principal, persisted, "MARKET_RECORD_CREATED");
            return view(persisted, definitions, repository.findExtensionCoreValues(persisted.id()));
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        }
    }

    /** Validates one import row before the import transaction writes any market record. */
    @Transactional(readOnly = true, noRollbackFor = {
            ClientRequestException.class, ConflictException.class, ResourceNotFoundException.class
    })
    public void validateImportDraft(MarketMonitoringDraft draft) {
        SecurityPrincipal principal = authorize("BUSINESS_IMPORT", null);
        MarketMonitoringDraft securedDraft = withReporter(draft, principal.displayName());
        List<MarketCoreFieldDefinition> definitions = coreDefinitions(securedDraft);
        ParsedDraft parsed = parseDraft(securedDraft, definitions);
        validate(parsed);
        principal = authorize("BUSINESS_IMPORT", parsed.regionCode());
        if (!securedDraft.evidencePhotoIds().isEmpty()) validateEvidence(securedDraft, principal);
    }

    @Transactional
    public String importDraft(MarketMonitoringDraft draft) {
        return create(draft, false).record().id();
    }

    @Transactional
    public MarketRecordView save(String id, long expectedVersion, MarketMonitoringDraft draft) {
        MarketMonitoringRecord existing = required(id);
        SecurityPrincipal principal = authorize("BUSINESS_UPDATE", existing.regionCode());
        if (expectedVersion != existing.version()) throw stale();
        Map<String, String> originalExtensions = repository.findExtensionCoreValues(id);
        String originalReporter = originalExtensions.get("MKT_REPORTER_NAME");
        // Forward-only compatibility: records created before reporter provenance existed
        // are bound to the first authenticated employee who revises them, never to input.
        if (originalReporter == null || originalReporter.isBlank()) originalReporter = principal.displayName();
        MarketMonitoringDraft securedDraft = withReporter(draft, originalReporter);
        List<MarketCoreFieldDefinition> definitions = coreDefinitions(securedDraft);
        ParsedDraft parsed = parseDraft(securedDraft, definitions);
        if (!existing.productCode().equals(parsed.productCode())) {
            throw invalid("Record product cannot change");
        }
        validate(parsed);
        authorize("BUSINESS_UPDATE", parsed.regionCode());
        try {
            MarketMonitoringRecord revised = existing.revise(
                    parsed.objectTypeCode(), parsed.regionCode(), parsed.tradeDate(), now(), parsed.direction(),
                    parsed.purchaseBasePrice(), parsed.saleBasePrice(), parsed.carriageBoardAmount(),
                    parsed.packagingAmount(), parsed.freightAmount(), parsed.packagingForm(), parsed.facts());
            MarketMonitoringRecord persisted = repository.updateFacts(
                    revised, expectedVersion, principal.subjectId(), parsed.extensions());
            repository.reconcileInventoryGovernance(
                    persisted, parsed.extensions(), principal.subjectId(), clock.instant());
            audit(principal, persisted, "MARKET_RECORD_UPDATED");
            return view(persisted, definitions, repository.findExtensionCoreValues(persisted.id()));
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    @Transactional
    public MarketRecordView submit(String id, long expectedVersion) {
        reconcileInventoryGovernance(id, expectedVersion, "BUSINESS_SUBMIT");
        return transition(id, expectedVersion, "BUSINESS_SUBMIT", "MARKET_RECORD_SUBMITTED", MarketMonitoringRecord::submit);
    }

    @Transactional
    public MarketRecordView approve(String id, long expectedVersion) {
        MarketMonitoringRecord existing = reconcileInventoryGovernance(id, expectedVersion, "BUSINESS_APPROVE");
        if (existing.facts().containsKey("ENDING_INVENTORY")
                && !repository.inventoryGovernanceReady(id)) {
            throw new ConflictException(
                    "MARKET_INVENTORY_GOVERNANCE_PENDING",
                    "库存权属尚未核定，暂不能审核通过");
        }
        return transition(id, expectedVersion, "BUSINESS_APPROVE", "MARKET_RECORD_APPROVED",
                MarketMonitoringRecord::approve, (record, principal) -> repository.linkApprovedSamplePoint(
                        record, repository.findExtensionCoreValues(record.id()),
                        principal.subjectId(), clock.instant()));
    }

    @Transactional
    public MarketRecordView returnForCorrection(String id, long expectedVersion, String reason) {
        return transition(id, expectedVersion, "BUSINESS_RETURN", "MARKET_RECORD_RETURNED", record -> record.returnForCorrection(reason));
    }

    @Transactional
    public MarketRecordView voidRecord(String id, long expectedVersion) {
        return transition(id, expectedVersion, "BUSINESS_UPDATE", "MARKET_RECORD_VOIDED",
                MarketMonitoringRecord::voidRecord);
    }

    private MarketRecordView transition(String id, long expectedVersion, String permissionCode, String auditAction,
            java.util.function.UnaryOperator<MarketMonitoringRecord> command) {
        return transition(id, expectedVersion, permissionCode, auditAction, command,
                (record, principal) -> { });
    }

    private MarketRecordView transition(String id, long expectedVersion, String permissionCode, String auditAction,
            java.util.function.UnaryOperator<MarketMonitoringRecord> command,
            BiConsumer<MarketMonitoringRecord, SecurityPrincipal> afterStateUpdate) {
        MarketMonitoringRecord existing = required(id);
        SecurityPrincipal principal = authorize(permissionCode, existing.regionCode());
        if (expectedVersion != existing.version()) throw stale();
        try {
            MarketMonitoringRecord transitioned = command.apply(existing);
            if (separationOfDuties != null && permissionCode.equals("BUSINESS_APPROVE")) {
                separationOfDuties.requireIndependentApprover(
                        "MARKET_RECORD", id, "MARKET_RECORD_SUBMITTED", principal);
            }
            if (separationOfDuties != null && permissionCode.equals("BUSINESS_RETURN")) {
                separationOfDuties.requireIndependentReturner(
                        "MARKET_RECORD", id, "MARKET_RECORD_SUBMITTED", principal);
            }
            MarketMonitoringRecord updated = repository.updateState(
                    transitioned, expectedVersion, principal.subjectId(), clock.instant());
            afterStateUpdate.accept(updated, principal);
            audit(principal, updated, auditAction);
            return view(updated, coreFields(updated.productCode()),
                    repository.findExtensionCoreValues(updated.id()));
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    private void validate(ParsedDraft draft) {
        if (draft.tradeDate().isAfter(LocalDate.now(clock.withZone(REPORTING_ZONE)))) {
            throw invalid("Trade date cannot be in the future");
        }
        if (!repository.isKnownRegion(draft.regionCode())) throw invalid("Unknown region");
        if (!repository.isApplicableObjectType(draft.productCode(), draft.objectTypeCode())) {
            throw new ClientRequestException("INAPPLICABLE_MARKET_OBJECT_TYPE",
                    "Object type is not applicable to this product");
        }
        if (!repository.areApplicableFacts(
                draft.productCode(), draft.objectTypeCode(), draft.facts().keySet())) {
            throw new ClientRequestException("INAPPLICABLE_MARKET_FACT",
                    "One or more facts are not applicable to this market context");
        }
    }

    private MarketMonitoringRecord reconcileInventoryGovernance(
            String id, long expectedVersion, String permissionCode) {
        MarketMonitoringRecord record = required(id);
        SecurityPrincipal principal = authorize(permissionCode, record.regionCode());
        if (expectedVersion != record.version()) throw stale();
        repository.reconcileInventoryGovernance(
                record, repository.findExtensionCoreValues(id), principal.subjectId(), clock.instant());
        return record;
    }

    private List<MarketCoreFieldDefinition> coreDefinitions(MarketMonitoringDraft draft) {
        if (draft == null || draft.productCode() == null || draft.productCode().isBlank()) {
            throw invalid("Product code is required");
        }
        List<MarketCoreFieldDefinition> definitions = coreFields(draft.productCode());
        if (definitions.isEmpty()) throw invalid("Market core field definition is missing");
        return definitions;
    }

    private List<MarketCoreFieldDefinition> coreFields(String productCode) {
        List<MarketCoreFieldDefinition> definitions = repository.findCoreFields(productCode).stream()
                .sorted(Comparator.comparingInt(MarketCoreFieldDefinition::sortOrder)
                        .thenComparing(MarketCoreFieldDefinition::code))
                .toList();
        validateCoreDefinitionContract(definitions);
        return definitions;
    }

    private static void validateCoreDefinitionContract(
            List<MarketCoreFieldDefinition> definitions) {
        Set<String> codes = new LinkedHashSet<>();
        Set<String> typedBindings = new LinkedHashSet<>();
        for (MarketCoreFieldDefinition definition : definitions) {
            if (!codes.add(definition.code())) throw invalidDefinition();
            String binding = definition.domainBinding();
            if (!"EXTENSION".equals(binding) && !typedBindings.add(binding)) {
                throw invalidDefinition();
            }
            boolean supported = switch (binding) {
                case "OBJECT_TYPE" -> matches(
                        definition, "SELECT", "OBJECT_TYPE_CONTEXT", true);
                case "REGION" -> matches(
                        definition, "REGION_HIERARCHY", "GENERIC", true);
                case "TRADE_DATE" -> matches(definition, "DATE", "GENERIC", true);
                case "REPORTED_AT" -> matches(
                        definition, "READONLY_DATETIME", "GENERIC", false);
                case "PURCHASE_BASE_PRICE" -> matches(
                        definition, "DECIMAL", "PURCHASE_BASE_PRICE", true);
                case "SALE_BASE_PRICE" -> matches(
                        definition, "DECIMAL", "SALE_BASE_PRICE", true);
                case "CARRIAGE_BOARD_AMOUNT", "PACKAGING_AMOUNT", "FREIGHT_AMOUNT" ->
                        matches(definition, "DECIMAL", "PRICE_COMPONENT", true);
                case "PACKAGING_FORM" -> matches(
                        definition, "SELECT", "GENERIC", true);
                case "EXTENSION" -> "GENERIC".equals(definition.capability())
                        && Set.of("TEXT", "DECIMAL", "SELECT", "REGION_HIERARCHY", "DATE")
                                .contains(definition.controlType());
                default -> false;
            };
            boolean decimalMetadata = "DECIMAL".equals(definition.controlType())
                    || "READONLY_DECIMAL".equals(definition.controlType());
            if (!supported
                    || decimalMetadata != (definition.precision() != null && definition.scale() != null)) {
                throw invalidDefinition();
            }
        }
        if (!typedBindings.equals(REQUIRED_TYPED_BINDINGS)) throw invalidDefinition();
    }

    private static boolean matches(
            MarketCoreFieldDefinition definition, String controlType,
            String capability, boolean required) {
        return controlType.equals(definition.controlType())
                && capability.equals(definition.capability())
                && definition.required() == required;
    }

    private static ServerContractException invalidDefinition() {
        return new ServerContractException(
                "MARKET_DEFINITION_INVALID", "Market definition is invalid");
    }

    private ParsedDraft parseDraft(
            MarketMonitoringDraft draft, List<MarketCoreFieldDefinition> definitions) {
        Map<String, MarketCoreFieldDefinition> byCode = new LinkedHashMap<>();
        definitions.forEach(definition -> {
            if (byCode.put(definition.code(), definition) != null) {
                throw new IllegalStateException("Duplicate market core field definition: " + definition.code());
            }
        });
        for (String code : draft.coreValues().keySet()) {
            if (!byCode.containsKey(code)) throw invalid("Unknown market core field: " + code);
            if (SYSTEM_MANAGED_OR_RETIRED_INPUT_CODES.contains(code)) {
                throw invalid("System-managed market core field cannot be submitted: " + code);
            }
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        definitions.forEach(definition -> {
            String value = draft.coreValues().get(definition.code());
            if (isReadOnly(definition.controlType())) {
                if (draft.coreValues().containsKey(definition.code())) {
                    throw invalid("Read-only market core field cannot be submitted: " + definition.code());
                }
                return;
            }
            if (value == null || value.isBlank()) {
                if (definition.code().equals("MKT_PACKAGING_AMOUNT")) {
                    normalized.put(definition.code(), "0");
                    return;
                }
                if (SYSTEM_MANAGED_OR_RETIRED_INPUT_CODES.contains(definition.code())) return;
                if (definition.required()) throw invalid(definition.label() + " is required");
                return;
            }
            normalized.put(definition.code(), validateCoreValue(definition, value));
        });

        Map<String, String> byBinding = new LinkedHashMap<>();
        Map<String, String> extensions = new LinkedHashMap<>();
        Set<String> bindings = new LinkedHashSet<>();
        definitions.forEach(definition -> {
            String value = normalized.get(definition.code());
            if ("EXTENSION".equals(definition.domainBinding())) {
                if (value != null) extensions.put(definition.code(), value);
                return;
            }
            if (!bindings.add(definition.domainBinding())) {
                throw new IllegalStateException(
                        "Duplicate market core domain binding: " + definition.domainBinding());
            }
            byBinding.put(definition.domainBinding(), value);
        });

        try {
            return new ParsedDraft(
                    draft.productCode(), requiredBinding(byBinding, "OBJECT_TYPE"),
                    requiredBinding(byBinding, "REGION"),
                    LocalDate.parse(requiredBinding(byBinding, "TRADE_DATE")),
                    MarketTradeDirection.BOTH,
                    requiredDecimal(byBinding, "PURCHASE_BASE_PRICE"),
                    requiredDecimal(byBinding, "SALE_BASE_PRICE"),
                    requiredDecimal(byBinding, "CARRIAGE_BOARD_AMOUNT"),
                    requiredDecimal(byBinding, "PACKAGING_AMOUNT"),
                    requiredDecimal(byBinding, "FREIGHT_AMOUNT"),
                    requiredBinding(byBinding, "PACKAGING_FORM"), draft.facts(), extensions);
        } catch (DateTimeException | NumberFormatException exception) {
            throw invalid("Market core field value is invalid");
        }
    }

    private static String validateCoreValue(MarketCoreFieldDefinition definition, String value) {
        return switch (definition.controlType()) {
            case "SELECT" -> {
                if (definition.options().stream().noneMatch(option -> option.value().equals(value))) {
                    throw invalid("Invalid option for market core field: " + definition.code());
                }
                yield value;
            }
            case "REGION_HIERARCHY" -> value;
            case "DATE" -> {
                try {
                    LocalDate.parse(value);
                } catch (DateTimeException exception) {
                    throw invalid("Invalid date for market core field: " + definition.code());
                }
                yield value;
            }
            case "DECIMAL" -> {
                try {
                    if (definition.precision() == null || definition.scale() == null) {
                        throw new IllegalStateException(
                                "Decimal market core metadata is incomplete: " + definition.code());
                    }
                    BigDecimal parsed = PlainDecimal.parse(value,
                            definition.precision() - definition.scale(), definition.scale(),
                            "INVALID_MARKET_RECORD");
                    BigDecimal coordinateLimit = coordinateLimit(definition.code());
                    if ((coordinateLimit == null && parsed.signum() < 0)
                            || (coordinateLimit != null && parsed.abs().compareTo(coordinateLimit) > 0)) {
                        throw invalid("Decimal is outside range for market core field: " + definition.code());
                    }
                    BigDecimal normalized = parsed.setScale(definition.scale(), RoundingMode.HALF_UP);
                    if (normalized.precision() > definition.precision()) {
                        throw invalid("Decimal is outside range for market core field: " + definition.code());
                    }
                    yield normalized.toPlainString();
                } catch (NumberFormatException | ArithmeticException exception) {
                    throw invalid("Invalid decimal for market core field: " + definition.code());
                }
            }
            case "TEXT" -> {
                BoundedInput.requireText("INVALID_MARKET_RECORD", value);
                if (Set.of("MKT_REPORTER_PHONE", "MKT_SAMPLE_CONTACT").contains(definition.code())
                        && !value.matches("^[0-9+()\\- ]{6,32}$")) {
                    throw invalid("Invalid contact value for market core field: " + definition.code());
                }
                yield value;
            }
            default -> throw invalid("Unsupported market core control type: " + definition.controlType());
        };
    }

    private static BigDecimal coordinateLimit(String code) {
        return switch (code) {
            case "MKT_SAMPLE_LATITUDE" -> new BigDecimal("90");
            case "MKT_SAMPLE_LONGITUDE" -> new BigDecimal("180");
            default -> null;
        };
    }

    private static boolean isReadOnly(String controlType) {
        return "READONLY_DECIMAL".equals(controlType) || "READONLY_DATETIME".equals(controlType);
    }

    private static String requiredBinding(Map<String, String> values, String binding) {
        String value = values.get(binding);
        if (value == null || value.isBlank()) throw invalid("Required market core binding is missing: " + binding);
        return value;
    }

    private static BigDecimal requiredDecimal(Map<String, String> values, String binding) {
        return new BigDecimal(requiredBinding(values, binding));
    }

    private static BigDecimal optionalDecimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private MarketRecordView view(
            MarketMonitoringRecord record, List<MarketCoreFieldDefinition> definitions,
            Map<String, String> extensions) {
        Set<String> extensionCodes = definitions.stream()
                .filter(definition -> "EXTENSION".equals(definition.domainBinding()))
                .map(MarketCoreFieldDefinition::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!extensionCodes.containsAll(extensions.keySet())) {
            throw new ServerContractException(
                    "MARKET_DATA_INTEGRITY", "Market record data is inconsistent");
        }
        Map<String, String> values = new LinkedHashMap<>();
        publicCoreFields(definitions).stream().sorted(Comparator.comparingInt(MarketCoreFieldDefinition::sortOrder)
                        .thenComparing(MarketCoreFieldDefinition::code))
                .forEach(definition -> values.put(definition.code(), switch (definition.domainBinding()) {
                    case "OBJECT_TYPE" -> record.objectTypeCode();
                    case "REGION" -> record.regionCode();
                    case "TRADE_DATE" -> record.tradeDate().toString();
                    case "REPORTED_AT" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(record.reportedAt());
                    case "TRADE_DIRECTION" -> record.direction().name();
                    case "PURCHASE_BASE_PRICE" -> decimal(record.purchaseBasePrice());
                    case "SALE_BASE_PRICE" -> decimal(record.saleBasePrice());
                    case "CARRIAGE_BOARD_AMOUNT" -> decimal(record.carriageBoardAmount());
                    case "PACKAGING_FORM" -> record.packagingForm();
                    case "PACKAGING_AMOUNT" -> decimal(record.packagingAmount());
                    case "FREIGHT_AMOUNT" -> decimal(record.freightAmount());
                    case "ACTUAL_TRADE_PRICE" -> decimal(record.actualTradePrice());
                    case "EXTENSION" -> extensions.get(definition.code());
                    default -> throw new IllegalStateException(
                            "Unsupported market core domain binding: " + definition.domainBinding());
                }));
        return new MarketRecordView(record, values,
                evidencePhotos == null ? List.of() : evidencePhotos.marketPhotos(record.id()),
                MarketActionPolicy.allowedActions(record.status()).stream()
                        .filter(action -> actionAllowed(action, record.id())).toList(),
                repository.inventoryGovernanceStatus(record.id()));
    }

    private static List<MarketCoreFieldDefinition> publicCoreFields(
            List<MarketCoreFieldDefinition> definitions) {
        return definitions.stream()
                .filter(definition -> !SYSTEM_MANAGED_OR_RETIRED_INPUT_CODES.contains(definition.code()))
                .toList();
    }

    private static Map<String, String> publicValues(Map<String, String> values) {
        Map<String, String> visible = new LinkedHashMap<>();
        values.forEach((code, value) -> {
            if (!SYSTEM_MANAGED_OR_RETIRED_INPUT_CODES.contains(code)) visible.put(code, value);
        });
        return visible;
    }

    private boolean actionAllowed(String action, String recordId) {
        if (accessControl == null) return true;
        SecurityPrincipal principal = accessControl.authenticated().orElse(null);
        if (principal == null) return true;
        String permission = switch (action) {
            case "VIEW" -> "BUSINESS_READ";
            case "SAVE" -> "BUSINESS_UPDATE";
            case "SUBMIT" -> "BUSINESS_SUBMIT";
            case "APPROVE" -> "BUSINESS_APPROVE";
            case "RETURN" -> "BUSINESS_RETURN";
            case "VOID" -> "BUSINESS_UPDATE";
            default -> null;
        };
        if (permission == null || !principal.permits(permission)) return false;
        return !Set.of("APPROVE", "RETURN").contains(action) || separationOfDuties == null
                || separationOfDuties.isIndependentReviewer(
                        "MARKET_RECORD", recordId, "MARKET_RECORD_SUBMITTED", principal);
    }

    private void validateEvidence(MarketMonitoringDraft draft, SecurityPrincipal principal) {
        if (evidencePhotos != null) {
            evidencePhotos.validateAvailable(draft.evidencePhotoIds(), principal.subjectId());
        }
    }

    private static MarketMonitoringDraft withReporter(
            MarketMonitoringDraft draft, String authoritativeReporterName) {
        Map<String, String> coreValues = new LinkedHashMap<>(draft.coreValues());
        coreValues.put("MKT_REPORTER_NAME", authoritativeReporterName);
        return new MarketMonitoringDraft(
                draft.productCode(), coreValues, draft.facts(), draft.evidencePhotoIds());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private record ParsedDraft(
            String productCode, String objectTypeCode, String regionCode, LocalDate tradeDate,
            MarketTradeDirection direction, BigDecimal purchaseBasePrice, BigDecimal saleBasePrice,
            BigDecimal carriageBoardAmount, BigDecimal packagingAmount, BigDecimal freightAmount,
            String packagingForm, Map<String, BigDecimal> facts, Map<String, String> extensions) {
        private ParsedDraft {
            facts = Map.copyOf(facts);
            extensions = Map.copyOf(extensions);
        }
    }

    private MarketMonitoringRecord required(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "MARKET_RECORD_NOT_FOUND", "Market record does not exist"));
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

    private void audit(SecurityPrincipal principal, MarketMonitoringRecord record, String actionCode) {
        if (audit != null) {
            audit.record(principal, "MARKET_RECORD", record.id(), actionCode, clock.instant(),
                    "{\"regionCode\":\"" + record.regionCode() + "\",\"productCode\":\""
                            + record.productCode() + "\",\"surveyYear\":"
                            + record.tradeDate().getYear() + "}");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), REPORTING_ZONE);
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException(
                "INVALID_MARKET_RECORD_QUERY", "Market record query context is invalid");
    }

    private static ClientRequestException invalid(String message) {
        return new ClientRequestException(
                "INVALID_MARKET_RECORD", message == null ? "Invalid market record" : message);
    }

    private static ConflictException stale() {
        return new ConflictException(
                "MARKET_RECORD_VERSION_CONFLICT", "Market record has changed");
    }

    private static ConflictException invalidTransition(IllegalStateException exception) {
        return new ConflictException("INVALID_MARKET_TRANSITION", exception.getMessage());
    }
}
