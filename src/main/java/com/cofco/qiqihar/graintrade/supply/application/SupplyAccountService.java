package com.cofco.qiqihar.graintrade.supply.application;

import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.supply.domain.ApprovalState;
import com.cofco.qiqihar.graintrade.supply.domain.QualityState;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyAccountCalculation;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyAccountCalculator;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyFormula;
import com.cofco.qiqihar.graintrade.supply.domain.SupplySource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class SupplyAccountService {
    private static final Set<String> PRODUCTS = Set.of("CORN", "SOYBEAN", "RICE");

    private final SupplyAccountRepository repository;
    private final CurrentActor actor;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SupplyAccountService(
            SupplyAccountRepository repository,
            CurrentActor actor,
            Clock clock,
            ObjectMapper objectMapper) {
        this(repository, actor, null, null, clock, objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SupplyAccountService(
            SupplyAccountRepository repository,
            CurrentActor actor,
            AccessControl accessControl,
            BusinessAuditRecorder audit,
            Clock clock,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.actor = actor;
        this.accessControl = accessControl;
        this.audit = audit;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<SupplyAccountView> list(
            String product, String region, String period, String state, Integer version,
            int pageNumber, int pageSize) {
        if (!PRODUCTS.contains(product) || blank(region) || blank(period)
                || (state != null && !Set.of("DRAFT", "CONFIRMED", "PUBLISHED").contains(state))
                || (version != null && version < 1)
                || pageNumber < 0 || pageSize < 1 || pageSize > 50) throw invalid();
        requireTemporalContext(period);
        AuthorizedReadScope scope=readScope(); scope.requireRegion(region);
        return repository.findPage(
                product, region, period, state, version, pageNumber, pageSize, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public SupplyInputWorkspaceView inputWorkspace(String product, String region, String period) {
        if (!PRODUCTS.contains(product) || blank(region) || blank(period)) throw invalid();
        requireTemporalContext(period);
        AuthorizedReadScope scope=readScope(); scope.requireRegion(region);
        SupplyInputWorkspaceView workspace = repository.loadInputWorkspace(product, region, period, scope.regionCodes());
        if (workspace == null) throw invalid();
        return workspace;
    }

    @Transactional(readOnly = true)
    public String resolvePeriodCode(String periodCodeOrMarketingYear) {
        if (blank(periodCodeOrMarketingYear)) throw invalid();
        return requireTemporalContext(periodCodeOrMarketingYear).periodCode();
    }

    @Transactional
    public SupplyAccountView run(SupplyRunCommand command) {
        if (command == null || !PRODUCTS.contains(command.productCode()) || blank(command.regionCode())
                || blank(command.periodCode()) || blank(command.inputSetId())
                || command.adjustmentProposalValue() == null || blank(command.adjustmentProposalReason())
                || command.expectedDecisionVersion() < 0) throw invalid();
        SupplyTemporalContext temporalContext = requireTemporalContext(command.periodCode());
        SecurityPrincipal principal = authorize("BUSINESS_UPDATE", command.regionCode());
        if (command.publish()) authorize("BUSINESS_APPROVE", command.regionCode());
        String current = principal.subjectId();
        Instant now = clock.instant();

        repository.lockCalculationContext(command.productCode(), command.regionCode(), command.periodCode());
        SupplyCalculationMaterial material;
        try {
            material = repository.loadCalculationMaterial(
                    command.inputSetId(), command.productCode(), command.regionCode(), command.periodCode());
        } catch (IllegalArgumentException exception) {
            throw formulaContract(exception.getMessage());
        }
        if (material == null
                || !material.inputSet().productCode().equals(command.productCode())
                || !material.inputSet().regionCode().equals(command.regionCode())
                || !material.inputSet().periodCode().equals(command.periodCode())) throw invalidInputSet();
        validateFormula(material.formula().formula());
        List<SupplySource> sources = material.inputSet().sources().stream().map(source -> new SupplySource(
                source.roleCode(), source.domain(), source.recordId(), source.sourceVersion(), ApprovalState.APPROVED,
                QualityState.valueOf(source.qualityState()), source.accountValue(), material.inputSet().reason(),
                route(source.domain(), source.recordId()))).toList();
        List<String> errors;
        try {
            errors = new ArrayList<>(SupplyAccountCalculator.validate(material.formula().formula(), sources));
        } catch (IllegalArgumentException exception) {
            throw formulaContract(exception.getMessage());
        }
        SupplyAccountCalculation calculation = null;
        if (errors.isEmpty()) {
            calculation = SupplyAccountCalculator.calculate(
                    material.formula().formula(), sources, command.adjustmentProposalValue());
            if (!calculation.balanced()) errors.add("OUTSIDE_BALANCE_TOLERANCE");
        }

        boolean eligible = errors.isEmpty() && calculation != null && calculation.balanced();
        boolean formal = eligible && command.publish();
        String state = formal ? "PUBLISHED" : eligible ? "CONFIRMED" : "DRAFT";
        String checksum = calculationChecksum(material, calculation, command);
        SupplyAccountView existing = repository.find(
                        command.productCode(), command.regionCode(), command.periodCode(), null, null, Set.of("*"))
                .stream().filter(view -> checksum.equals(view.calculationChecksum())).findFirst().orElse(null);
        if (existing != null) return existing;
        if (material.decision().version() != command.expectedDecisionVersion()) {
            throw new ConflictException("SUPPLY_DECISION_VERSION_CONFLICT", "Supply decision has changed");
        }
        long decisionVersion = material.decision().version();
        if (formal) {
            repository.persistFormalDecision(command, material, current, now);
            decisionVersion = material.decision().exists() ? material.decision().version() + 1 : 0;
        }
        SupplyAccountView persisted = repository.persistRun(new SupplyRunPersistence(
                material,
                formulaSnapshot(material.formula()),
                checksum,
                command.productCode(),
                command.regionCode(),
                temporalContext,
                state,
                errors,
                calculation,
                command.adjustmentProposalValue(),
                command.adjustmentProposalReason(),
                current,
                now,
                decisionVersion));
        audit(principal, "SUPPLY_ACCOUNT", persisted.id(), "SUPPLY_ACCOUNT_CALCULATED",
                command.productCode(), command.regionCode(), temporalContext.surveyYear());
        if (formal) {
            audit(principal, "SUPPLY_ACCOUNT", persisted.id(), "SUPPLY_ACCOUNT_PUBLISHED",
                    command.productCode(), command.regionCode(), temporalContext.surveyYear());
        }
        return persisted;
    }

    @Transactional
    public SupplyReleaseView release(UpstreamSourceReleaseCommand command) {
        if (command == null || !Set.of("PRODUCTION", "MARKET", "LOGISTICS").contains(command.sourceDomain())
                || blank(command.sourceRecordId()) || command.sourceVersion() < 0
                || !PRODUCTS.contains(command.productCode()) || blank(command.regionCode())
                || blank(command.periodCode()) || blank(command.roleCode()) || blank(command.sourceFieldCode())
                || !Set.of("PASSED", "WARNING", "BLOCKING").contains(command.qualityState())) throw invalid();
        SupplyTemporalContext temporalContext = requireTemporalContext(command.periodCode());
        SecurityPrincipal principal = authorize("BUSINESS_UPDATE", command.regionCode());
        String current = principal.subjectId();

        SupplySourceReleaseMaterial material = repository.loadSourceReleaseMaterial(command);
        if (!material.contextExists()) throw invalid();
        if (!material.semanticsApplicable()) throw sourceMapping();
        if (material.upstreamFact() == null) throw sourceProvenance();
        if (material.mapping() == null) throw sourceMapping();
        if (material.existingRelease() != null && !material.existingRelease().matchesContext()) {
            throw new ConflictException("SUPPLY_SOURCE_RELEASE_CONFLICT", "Source release context has changed");
        }
        BigDecimal value = material.upstreamFact().value()
                .multiply(material.mapping().conversionFactor()).setScale(4, RoundingMode.HALF_UP);
        SupplyReleaseView persisted = repository.persistSourceRelease(new SupplySourceReleasePersistence(
                command, temporalContext, material, value, digest(command, value), current, clock.instant()));
        audit(principal, "SUPPLY_SOURCE_RELEASE", persisted.id(), "SUPPLY_SOURCE_RELEASED",
                command.productCode(), command.regionCode(), temporalContext.surveyYear());
        return persisted;
    }

    @Transactional
    public SupplyReleaseView approveManual(ManualInputDecisionCommand command) {
        if (command == null || !PRODUCTS.contains(command.productCode()) || blank(command.regionCode())
                || blank(command.periodCode()) || blank(command.roleCode()) || command.value() == null
                || blank(command.reason()) || command.expectedVersion() < 0) throw invalid();
        SupplyTemporalContext temporalContext = requireTemporalContext(command.periodCode());
        SecurityPrincipal principal = authorize("BUSINESS_APPROVE", command.regionCode());
        String current = principal.subjectId();

        SupplyManualDecisionMaterial material = repository.loadManualDecisionMaterial(command);
        if (!material.contextExists()) throw invalid();
        if (material.mapping() == null) throw sourceMapping();
        if (material.currentVersion() != command.expectedVersion()) decisionConflict();
        long version = material.decisionExists() ? material.currentVersion() + 1 : 0;
        SupplyReleaseView persisted = repository.persistManualDecision(new SupplyManualDecisionPersistence(
                command, temporalContext, material.mapping(), version, digest(command, command.value()), current, clock.instant()));
        audit(principal, "SUPPLY_MANUAL_INPUT", persisted.id(), "SUPPLY_MANUAL_INPUT_APPROVED",
                command.productCode(), command.regionCode(), temporalContext.surveyYear());
        return persisted;
    }

    @Transactional
    public SupplyInputSetView createInputSet(SupplyInputSetCommand command) {
        if (command == null || !PRODUCTS.contains(command.productCode()) || blank(command.regionCode())
                || blank(command.periodCode()) || blank(command.reason()) || command.expectedVersion() < 0
                || command.items() == null || command.items().isEmpty()) throw invalidInputSet();
        SupplyTemporalContext temporalContext = requireTemporalContext(command.periodCode());
        SecurityPrincipal principal = authorize("BUSINESS_CREATE", command.regionCode());
        String current = principal.subjectId();
        Set<String> roles = new HashSet<>();
        Set<String> releases = new HashSet<>();
        if (command.items().stream().anyMatch(item -> item == null || blank(item.roleCode())
                || blank(item.sourceReleaseId()) || !roles.add(item.roleCode())
                || !releases.add(item.sourceReleaseId()))) throw invalidInputSet();

        SupplyInputSetMaterial material = repository.loadInputSetMaterial(command);
        if (!material.contextExists()) throw invalidInputSet();
        if (material.currentVersion() != command.expectedVersion()) {
            throw new ConflictException("SUPPLY_INPUT_SET_VERSION_CONFLICT", "Supply input selection has changed");
        }
        if (!roles.equals(material.requiredRoles()) || material.selectedSources().size() != command.items().size()) {
            throw invalidInputSet();
        }
        Set<String> upstreamFacts = new HashSet<>();
        if (material.selectedSources().stream().anyMatch(source -> !upstreamFacts.add(source.upstreamKey()))) {
            throw invalidInputSet();
        }
        SupplyInputSetView persisted = repository.persistInputSet(new SupplyInputSetPersistence(
                command, temporalContext, material.currentVersion() + 1, material.selectedSources(), current, clock.instant()));
        audit(principal, "SUPPLY_INPUT_SET", persisted.id(), "SUPPLY_INPUT_SET_CREATED",
                command.productCode(), command.regionCode(), temporalContext.surveyYear());
        return persisted;
    }

    private void validateFormula(SupplyFormula formula) {
        try {
            SupplyAccountCalculator.validateFormula(formula);
        } catch (IllegalArgumentException exception) {
            throw formulaContract(exception.getMessage());
        }
    }

    private SupplyTemporalContext requireTemporalContext(String periodCode) {
        SupplyTemporalContext context = repository.findTemporalContext(periodCode);
        if (context == null) throw invalid();
        return context;
    }

    private String formulaSnapshot(SupplyCalculationMaterial.FormulaDefinition definition) {
        SupplyFormula formula = definition.formula();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("code", formula.code()).put("version", formula.version()).put("name", definition.name())
                .put("precision", formula.precision()).put("scale", formula.scale())
                .put("roundingMode", formula.roundingMode().name()).put("tolerance", formula.tolerance());
        ArrayNode results = root.putArray("results");
        formula.results().forEach(result -> {
            ObjectNode resultNode = results.addObject().put("role", result.role()).put("label", result.label())
                    .put("required", result.required()).put("order", result.order())
                    .put("expression", expression(result));
            ArrayNode terms = resultNode.putArray("terms");
            result.terms().forEach(term -> terms.addObject().put("operandRole", term.operandRole())
                    .put("coefficient", term.coefficient()).put("order", term.order()));
        });
        return root.toString();
    }

    private static String expression(SupplyFormula.Result result) {
        StringBuilder expression = new StringBuilder();
        for (int index = 0; index < result.terms().size(); index++) {
            SupplyFormula.Term term = result.terms().get(index);
            BigDecimal coefficient = term.coefficient().stripTrailingZeros();
            boolean negative = coefficient.signum() < 0;
            BigDecimal absolute = coefficient.abs();
            if (index > 0) expression.append(negative ? " - " : " + ");
            else if (negative) expression.append("-");
            if (absolute.compareTo(BigDecimal.ONE) != 0) {
                expression.append(absolute.toPlainString()).append(" * ");
            }
            expression.append(term.operandRole());
        }
        return expression.toString();
    }

    private String actor() {
        return actor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();
    }

    private SecurityPrincipal authorize(String permissionCode, String regionCode) {
        if (accessControl != null) return accessControl.require(permissionCode, regionCode);
        return new SecurityPrincipal(actor(), "UNIT_TEST", Set.of(), Set.of());
    }

    private AuthorizedReadScope readScope() {
        return accessControl == null ? AuthorizedReadScope.unrestricted() : accessControl.requireReadScope();
    }

    private void audit(SecurityPrincipal principal, String aggregateType, String aggregateId,
            String actionCode, String productCode, String regionCode, int surveyYear) {
        if (audit != null) {
            audit.record(principal, aggregateType, aggregateId, actionCode, clock.instant(),
                    "{\"regionCode\":\"" + regionCode + "\",\"productCode\":\""
                            + productCode + "\",\"surveyYear\":" + surveyYear + "}");
        }
    }

    private static String digest(Object command, BigDecimal value) {
        return digest(command + "|" + value.toPlainString());
    }

    private String calculationChecksum(
            SupplyCalculationMaterial material, SupplyAccountCalculation calculation, SupplyRunCommand command) {
        StringBuilder contents = new StringBuilder(formulaSnapshot(material.formula()))
                .append('|').append(material.inputSet().id())
                .append('|').append(material.inputSet().version())
                .append('|').append(command.adjustmentProposalValue().toPlainString())
                .append('|').append(command.adjustmentProposalReason())
                .append('|').append(command.expectedDecisionVersion())
                .append('|').append(command.publish());
        material.inputSet().sources().forEach(source -> contents.append('|').append(source.roleCode())
                .append('|').append(source.releaseId()).append('|').append(source.recordId())
                .append('|').append(source.sourceVersion()).append('|').append(source.sourceFieldCode())
                .append('|').append(source.accountValue().toPlainString()));
        if (calculation != null) {
            contents.append('|').append(calculation.totalSupply().toPlainString())
                    .append('|').append(calculation.totalUse().toPlainString())
                    .append('|').append(calculation.calculatedEndingInventory().toPlainString())
                    .append('|').append(calculation.approvedAdjustment().toPlainString())
                    .append('|').append(calculation.adoptedEndingInventory().toPlainString())
                    .append('|').append(calculation.surveyedEndingInventory().toPlainString())
                    .append('|').append(calculation.inventoryReconciliationDifference().toPlainString())
                    .append('|').append(calculation.balanced());
        }
        return digest(contents.toString());
    }

    private static String digest(String contents) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(contents.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_SUPPLY_ACCOUNT_REQUEST", "Supply account request is invalid");
    }

    private static ClientRequestException invalidInputSet() {
        return new ClientRequestException("INVALID_SUPPLY_INPUT_SET", "Supply input set is invalid");
    }

    private static ClientRequestException sourceMapping() {
        return new ClientRequestException("INVALID_SUPPLY_SOURCE_MAPPING",
                "Source field semantics and unit are not confirmed for the account role");
    }

    private static ClientRequestException sourceProvenance() {
        return new ClientRequestException("INVALID_SUPPLY_SOURCE_PROVENANCE",
                "Source must match an approved upstream record version and field");
    }

    private static ServerContractException formulaContract(String detail) {
        return new ServerContractException("INVALID_SUPPLY_FORMULA_METADATA",
                "Active supply formula metadata is invalid: " + detail);
    }

    private static void decisionConflict() {
        throw new ConflictException("SUPPLY_DECISION_VERSION_CONFLICT", "Supply decision has changed");
    }

    private static String route(String domain, String id) {
        return switch (domain) {
            case "PRODUCTION" -> "/api/v1/production-records/" + id;
            case "MARKET" -> "/api/v1/market-records/" + id;
            case "LOGISTICS" -> "/api/v1/logistics-records/" + id;
            case "MANUAL" -> "/api/v1/supply-inputs/manual-decisions/" + id;
            default -> "/api/v1/supply-sources/" + id;
        };
    }
}
