package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketActionPolicy;
import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.market.domain.MarketTradeDirection;
import com.cofco.qiqihar.graintrade.market.domain.MarketValidationException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
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
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketMonitoringService {
    private static final String DOMAIN = "MARKET";
    private static final String PAGE_KIND = "MONITORING";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern PLAIN_DECIMAL = Pattern.compile(
            "^-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$");
    private static final Set<String> REQUIRED_TYPED_BINDINGS = Set.of(
            "OBJECT_TYPE", "REGION", "TRADE_DATE", "REPORTED_AT", "TRADE_DIRECTION",
            "PURCHASE_BASE_PRICE", "SALE_BASE_PRICE", "CARRIAGE_BOARD_AMOUNT",
            "PACKAGING_FORM", "PACKAGING_AMOUNT", "FREIGHT_AMOUNT", "ACTUAL_TRADE_PRICE");
    private final MarketMonitoringRepository repository;
    private final PageDefinitionQuery pageDefinitions;
    private final CurrentActor currentActor;
    private final Clock clock;

    public MarketMonitoringService(MarketMonitoringRepository repository, PageDefinitionQuery pageDefinitions,
            CurrentActor currentActor, Clock clock) {
        this.repository = repository;
        this.pageDefinitions = pageDefinitions;
        this.currentActor = currentActor;
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
        PagedResult<MarketListRow> page = repository.findPage(query);
        List<MarketListItem> items = page.items().stream().map(row -> new MarketListItem(
                row.id(), row.values(), MarketActionPolicy.allowedActions(row.status()).stream()
                        .filter(row.configuredActions()::contains).toList(), row.version())).toList();
        return new PagedResult<>(items, page.pageNumber(), page.pageSize(), page.totalElements());
    }

    @Transactional(readOnly = true)
    public MarketRecordView detail(String id) {
        MarketMonitoringRecord record = required(id);
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
                productCode, objectTypeCode, coreFields(productCode), groups);
    }

    /** Used by the write interceptor before request-body conversion. */
    public void requireAuthentication() {
        actor();
    }

    @Transactional
    public MarketRecordView create(MarketMonitoringDraft draft) {
        AuthenticatedActor actor = actor();
        List<MarketCoreFieldDefinition> definitions = coreDefinitions(draft);
        ParsedDraft parsed = parseDraft(draft, definitions);
        validate(parsed);
        try {
            MarketMonitoringRecord record = MarketMonitoringRecord.draft(
                    UUID.randomUUID().toString(), parsed.productCode(), parsed.objectTypeCode(),
                    parsed.regionCode(), parsed.tradeDate(), now(), parsed.direction(),
                    parsed.purchaseBasePrice(), parsed.saleBasePrice(), parsed.carriageBoardAmount(),
                    parsed.packagingAmount(), parsed.freightAmount(), parsed.packagingForm(), parsed.facts());
            return view(repository.insert(record, actor.id(), parsed.extensions()),
                    definitions, parsed.extensions());
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        }
    }

    @Transactional
    public MarketRecordView save(String id, long expectedVersion, MarketMonitoringDraft draft) {
        AuthenticatedActor actor = actor();
        MarketMonitoringRecord existing = required(id);
        if (expectedVersion != existing.version()) throw stale();
        List<MarketCoreFieldDefinition> definitions = coreDefinitions(draft);
        ParsedDraft parsed = parseDraft(draft, definitions);
        if (!existing.productCode().equals(parsed.productCode())) {
            throw invalid("Record product cannot change");
        }
        validate(parsed);
        try {
            MarketMonitoringRecord revised = existing.revise(
                    parsed.objectTypeCode(), parsed.regionCode(), parsed.tradeDate(), now(), parsed.direction(),
                    parsed.purchaseBasePrice(), parsed.saleBasePrice(), parsed.carriageBoardAmount(),
                    parsed.packagingAmount(), parsed.freightAmount(), parsed.packagingForm(), parsed.facts());
            return view(repository.updateFacts(
                    revised, expectedVersion, actor.id(), parsed.extensions()),
                    definitions, parsed.extensions());
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    @Transactional
    public MarketRecordView submit(String id, long expectedVersion) {
        return transition(id, expectedVersion, MarketMonitoringRecord::submit);
    }

    @Transactional
    public MarketRecordView approve(String id, long expectedVersion) {
        return transition(id, expectedVersion, MarketMonitoringRecord::approve);
    }

    @Transactional
    public MarketRecordView returnForCorrection(String id, long expectedVersion, String reason) {
        return transition(id, expectedVersion, record -> record.returnForCorrection(reason));
    }

    private MarketRecordView transition(String id, long expectedVersion,
            java.util.function.UnaryOperator<MarketMonitoringRecord> command) {
        AuthenticatedActor actor = actor();
        MarketMonitoringRecord existing = required(id);
        if (expectedVersion != existing.version()) throw stale();
        try {
            MarketMonitoringRecord updated = repository.updateState(
                    command.apply(existing), expectedVersion, actor.id(), clock.instant());
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
                case "TRADE_DIRECTION" -> matches(
                        definition, "SELECT", "PRICE_DIRECTION", true);
                case "PURCHASE_BASE_PRICE" -> matches(
                        definition, "DECIMAL", "PURCHASE_BASE_PRICE", false);
                case "SALE_BASE_PRICE" -> matches(
                        definition, "DECIMAL", "SALE_BASE_PRICE", false);
                case "CARRIAGE_BOARD_AMOUNT", "PACKAGING_AMOUNT", "FREIGHT_AMOUNT" ->
                        matches(definition, "DECIMAL", "PRICE_COMPONENT", true);
                case "PACKAGING_FORM" -> matches(
                        definition, "SELECT", "GENERIC", true);
                case "ACTUAL_TRADE_PRICE" -> matches(
                        definition, "READONLY_DECIMAL", "ACTUAL_TRADE_PRICE", false);
                case "EXTENSION" -> "GENERIC".equals(definition.capability())
                        && ("TEXT".equals(definition.controlType())
                            || "DECIMAL".equals(definition.controlType()));
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
                    MarketTradeDirection.valueOf(requiredBinding(byBinding, "TRADE_DIRECTION")),
                    optionalDecimal(byBinding.get("PURCHASE_BASE_PRICE")),
                    optionalDecimal(byBinding.get("SALE_BASE_PRICE")),
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
                    if (!PLAIN_DECIMAL.matcher(value).matches()) {
                        throw invalid("Invalid decimal for market core field: " + definition.code());
                    }
                    if (definition.precision() == null || definition.scale() == null) {
                        throw new IllegalStateException(
                                "Decimal market core metadata is incomplete: " + definition.code());
                    }
                    BigDecimal normalized = new BigDecimal(value).setScale(
                            definition.scale(), RoundingMode.HALF_UP);
                    if (normalized.signum() < 0 || normalized.precision() > definition.precision()) {
                        throw invalid("Decimal is outside range for market core field: " + definition.code());
                    }
                    yield normalized.toPlainString();
                } catch (NumberFormatException | ArithmeticException exception) {
                    throw invalid("Invalid decimal for market core field: " + definition.code());
                }
            }
            case "TEXT" -> {
                if (value.length() > 500) throw invalid("Market core text value is too long: " + definition.code());
                yield value;
            }
            default -> throw invalid("Unsupported market core control type: " + definition.controlType());
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

    private static MarketRecordView view(
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
        definitions.stream().sorted(Comparator.comparingInt(MarketCoreFieldDefinition::sortOrder)
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
        return new MarketRecordView(record, values, MarketActionPolicy.allowedActions(record.status()));
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
