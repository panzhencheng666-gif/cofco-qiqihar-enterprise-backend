package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketActionPolicy;
import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.market.domain.MarketValidationException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketMonitoringService {
    private static final String DOMAIN = "MARKET";
    private static final String PAGE_KIND = "MONITORING";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
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
        return view(required(id));
    }

    @Transactional(readOnly = true)
    public MarketFormDefinition definition(String productCode, String objectTypeCode) {
        if (productCode == null || productCode.isBlank()
                || (objectTypeCode != null
                    && !repository.isApplicableObjectType(productCode, objectTypeCode))) {
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
        return new MarketFormDefinition(productCode, objectTypeCode,
                repository.findCoreFields(productCode).stream()
                        .sorted(Comparator.comparingInt(MarketCoreFieldDefinition::sortOrder)
                                .thenComparing(MarketCoreFieldDefinition::code)).toList(), groups);
    }

    /** Used by the write interceptor before request-body conversion. */
    public void requireAuthentication() {
        actor();
    }

    @Transactional
    public MarketRecordView create(MarketMonitoringDraft draft) {
        AuthenticatedActor actor = actor();
        validate(draft);
        try {
            MarketMonitoringRecord record = MarketMonitoringRecord.draft(
                    UUID.randomUUID().toString(), draft.productCode(), draft.objectTypeCode(),
                    draft.regionCode(), draft.tradeDate(), now(), draft.direction(),
                    draft.purchaseBasePrice(), draft.saleBasePrice(), draft.carriageBoardAmount(),
                    draft.packagingAmount(), draft.freightAmount(), draft.packagingForm(), draft.facts());
            return view(repository.insert(record, actor.id()));
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        }
    }

    @Transactional
    public MarketRecordView save(String id, long expectedVersion, MarketMonitoringDraft draft) {
        AuthenticatedActor actor = actor();
        MarketMonitoringRecord existing = required(id);
        if (expectedVersion != existing.version()) throw stale();
        if (!existing.productCode().equals(draft.productCode())) {
            throw invalid("Record product cannot change");
        }
        validate(draft);
        try {
            MarketMonitoringRecord revised = existing.revise(
                    draft.objectTypeCode(), draft.regionCode(), draft.tradeDate(), now(), draft.direction(),
                    draft.purchaseBasePrice(), draft.saleBasePrice(), draft.carriageBoardAmount(),
                    draft.packagingAmount(), draft.freightAmount(), draft.packagingForm(), draft.facts());
            return view(repository.updateFacts(revised, expectedVersion, actor.id()));
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
            return view(repository.updateState(command.apply(existing), expectedVersion, actor.id()));
        } catch (MarketValidationException exception) {
            throw invalid(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw invalidTransition(exception);
        }
    }

    private void validate(MarketMonitoringDraft draft) {
        if (draft == null || draft.tradeDate() == null
                || draft.tradeDate().isAfter(LocalDate.now(clock.withZone(REPORTING_ZONE)))) {
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

    private MarketMonitoringRecord required(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "MARKET_RECORD_NOT_FOUND", "Market record does not exist"));
    }

    private static MarketRecordView view(MarketMonitoringRecord record) {
        return new MarketRecordView(record, MarketActionPolicy.allowedActions(record.status()));
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
