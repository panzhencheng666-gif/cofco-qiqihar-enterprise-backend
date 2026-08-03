package com.cofco.qiqihar.graintrade.supply.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import com.cofco.qiqihar.graintrade.supply.application.ManualInputDecisionCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountRepository;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAdjustmentAuditView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyFormulaView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyReleaseView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyRunCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplySourceView;
import com.cofco.qiqihar.graintrade.supply.application.UpstreamSourceReleaseCommand;
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
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplyAccountRepository implements SupplyAccountRepository {
    private final JdbcClient jdbc;

    public JdbcSupplyAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SupplyAccountView> find(
            String product, String region, String year, String state, Integer resultVersion) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.calculation_run_id::text id,r.product_code,r.region_code,r.marketing_year,
                  r.result_state,r.validation_codes,r.balanced,r.decision_version,
                  round(r.total_supply,f.scale_value) total_supply,round(r.total_use,f.scale_value) total_use,
                  round(r.calculated_ending_inventory,f.scale_value) calculated_ending_inventory,
                  round(r.approved_adjustment,f.scale_value) approved_adjustment,
                  round(r.adopted_ending_inventory,f.scale_value) adopted_ending_inventory,
                  round(r.surveyed_ending_inventory,f.scale_value) surveyed_ending_inventory,
                  round(r.inventory_reconciliation_difference,f.scale_value) inventory_reconciliation_difference,
                  r.adjustment_reason_snapshot,r.adjustment_actor_snapshot,r.adjustment_decided_at_snapshot,
                  rv.version_no,r.formula_version_id
                FROM supply.calculation_run r
                JOIN supply.result_version rv ON rv.calculation_run_id=r.calculation_run_id
                JOIN supply.formula_version f ON f.formula_version_id=r.formula_version_id
                WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("product", product);
        params.put("region", region);
        params.put("year", year);
        if (state != null) {
            sql.append(" AND r.result_state=:state");
            params.put("state", state);
        }
        if (resultVersion != null) {
            sql.append(" AND rv.version_no=:resultVersion");
            params.put("resultVersion", resultVersion);
        }
        sql.append(" ORDER BY rv.version_no DESC");
        List<Header> headers = jdbc.sql(sql.toString()).params(params).query((row, index) -> new Header(
                row.getString("id"), row.getString("product_code"), row.getString("region_code"),
                row.getString("marketing_year"), row.getInt("version_no"), row.getLong("decision_version"),
                row.getString("result_state"), strings(row.getArray("validation_codes")),
                Boolean.TRUE.equals(row.getObject("balanced", Boolean.class)), plain(row.getBigDecimal("total_supply")),
                plain(row.getBigDecimal("total_use")), plain(row.getBigDecimal("calculated_ending_inventory")),
                plain(row.getBigDecimal("approved_adjustment")), plain(row.getBigDecimal("adopted_ending_inventory")),
                plain(row.getBigDecimal("surveyed_ending_inventory")),
                plain(row.getBigDecimal("inventory_reconciliation_difference")),
                row.getString("adjustment_reason_snapshot"), row.getString("adjustment_actor_snapshot"),
                timestamp(row.getObject("adjustment_decided_at_snapshot", OffsetDateTime.class)),
                row.getLong("formula_version_id"))).list();
        return assemble(headers);
    }

    @Override
    public SupplyAccountView run(SupplyRunCommand command, String actor, Instant now) {
        requireContext(command.productCode(), command.regionCode());
        lockContext(command.productCode(), command.regionCode(), command.marketingYear());
        DecisionState current = decisionState(command.productCode(), command.regionCode(), command.marketingYear());
        if (current.version != command.expectedDecisionVersion()) conflict();

        long formulaId = jdbc.sql("""
                SELECT formula_version_id FROM supply.formula_version
                WHERE active ORDER BY version_no DESC LIMIT 1
                """).query(Long.class).optional().orElseThrow(() -> formulaContract("No active formula"));
        SupplyFormula formula = domainFormula(formulaId);
        List<SourceRow> rows = sourceCandidates(command.productCode(), command.regionCode(), command.marketingYear());
        List<SupplySource> sources = rows.stream().map(row -> new SupplySource(
                row.role, row.domain, row.record, row.sourceVersion, ApprovalState.APPROVED,
                QualityState.valueOf(row.quality), row.value, command.adoptionReason(),
                route(row.domain, row.record))).toList();
        List<String> errors;
        try {
            errors = new ArrayList<>(SupplyAccountCalculator.validate(formula, sources));
        } catch (IllegalArgumentException exception) {
            throw formulaContract(exception.getMessage());
        }

        SupplyAccountCalculation calculation = null;
        if (errors.isEmpty()) {
            calculation = SupplyAccountCalculator.calculate(formula, sources, command.approvedAdjustment());
            if (!calculation.balanced()) errors.add("OUTSIDE_BALANCE_TOLERANCE");
        }
        boolean eligible = errors.isEmpty() && calculation != null && calculation.balanced();
        String state = eligible ? (command.publish() ? "FORMAL" : "FORMAL_CANDIDATE") : "TRIAL";
        long decisionVersion = current.version;
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        if (eligible) {
            rows.forEach(row -> upsertDecision(command, row, actor, timestamp));
            upsertAdjustment(command, actor, timestamp);
            decisionVersion = current.exists ? current.version + 1 : 0;
        }

        int resultVersion = nextResultVersion(command);
        String runId = UUID.randomUUID().toString();
        insertRun(runId, command, formulaId, state, errors, calculation, decisionVersion, actor, timestamp);
        jdbc.sql("""
                INSERT INTO supply.result_version(result_version_id,calculation_run_id,version_no,published_by,published_at)
                VALUES(CAST(:result AS uuid),CAST(:run AS uuid),:version,:publisher,:publishedAt)
                """).param("result", UUID.randomUUID().toString()).param("run", runId)
                .param("version", resultVersion).param("publisher", state.equals("FORMAL") ? actor : null)
                .param("publishedAt", state.equals("FORMAL") ? timestamp : null).update();
        rows.forEach(row -> insertSourceSnapshot(runId, row, command.adoptionReason()));
        return find(command.productCode(), command.regionCode(), command.marketingYear(), null, resultVersion).getFirst();
    }

    @Override
    public SupplyReleaseView release(UpstreamSourceReleaseCommand command, String actor, Instant now) {
        requireContext(command.productCode(), command.regionCode());
        BigDecimal value = approvedUpstreamValue(command);
        String releaseId = existingRelease(command.sourceDomain(), command.sourceRecordId(), command.sourceVersion());
        if (releaseId == null) {
            releaseId = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,
                      approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest)
                    VALUES(CAST(:id AS uuid),:domain,:record,:version,'APPROVED',:approvedAt,:quality,:product,:region,:year,:digest)
                    """).param("id", releaseId).param("domain", command.sourceDomain())
                    .param("record", command.sourceRecordId()).param("version", command.sourceVersion())
                    .param("approvedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("quality", command.qualityState())
                    .param("product", command.productCode()).param("region", command.regionCode())
                    .param("year", command.marketingYear()).param("digest", digest(command, value)).update();
        } else if (!releaseMatches(releaseId, command)) {
            throw new ConflictException("SUPPLY_SOURCE_RELEASE_CONFLICT", "Source release context has changed");
        }
        jdbc.sql("""
                INSERT INTO supply.source_release_binding(source_release_id,role_code,source_field_code,source_value,unit_code)
                VALUES(CAST(:release AS uuid),:role,:field,:value,:unit)
                """).param("release", releaseId).param("role", command.roleCode())
                .param("field", command.sourceFieldCode()).param("value", value).param("unit", command.unitCode()).update();
        return releaseView(releaseId, command.roleCode());
    }

    @Override
    public SupplyReleaseView approveManual(ManualInputDecisionCommand command, String actor, Instant now) {
        requireContext(command.productCode(), command.regionCode());
        lockContext(command.productCode(), command.regionCode(), command.marketingYear() + "|" + command.roleCode());
        ManualState state = jdbc.sql("""
                SELECT version FROM supply.manual_input_decision
                WHERE product_code=:product AND region_code=:region AND marketing_year=:year AND role_code=:role
                ORDER BY version DESC LIMIT 1
                """).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("role", command.roleCode())
                .query(Long.class).optional().map(version -> new ManualState(true, version)).orElse(new ManualState(false, 0));
        if (state.version != command.expectedVersion()) conflict();
        long version = state.exists ? state.version + 1 : 0;
        String manualId = UUID.randomUUID().toString();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO supply.manual_input_decision(manual_input_id,product_code,region_code,marketing_year,role_code,
                  value,unit_code,reason,status_code,decided_by,approved_at,version)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:role,:value,:unit,:reason,'APPROVED',:actor,:approvedAt,:version)
                """).param("id", manualId).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("role", command.roleCode()).param("value", command.value())
                .param("unit", command.unitCode()).param("reason", command.reason()).param("actor", actor)
                .param("approvedAt", timestamp).param("version", version).update();
        String releaseId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,
                  approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest)
                VALUES(CAST(:release AS uuid),'MANUAL',:manual,:version,'APPROVED',:approvedAt,'PASSED',:product,:region,:year,:digest)
                """).param("release", releaseId).param("manual", manualId).param("version", version)
                .param("approvedAt", timestamp).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("digest", digest(command, command.value())).update();
        jdbc.sql("""
                INSERT INTO supply.source_release_binding(source_release_id,role_code,source_field_code,source_value,unit_code,manual_input_id)
                VALUES(CAST(:release AS uuid),:role,'MANUAL_APPROVED_VALUE',:value,:unit,CAST(:manual AS uuid))
                """).param("release", releaseId).param("role", command.roleCode()).param("value", command.value())
                .param("unit", command.unitCode()).param("manual", manualId).update();
        return releaseView(releaseId, command.roleCode());
    }

    private List<SourceRow> sourceCandidates(String product, String region, String year) {
        return jdbc.sql("""
                SELECT * FROM (
                  SELECT release.source_release_id::text,release.source_domain,release.source_record_id,
                    release.source_version,release.approved_at,release.quality_state,binding.role_code,
                    role.label,role.group_code,role.sort_order,binding.source_field_code,binding.source_value,
                    binding.unit_code,row_number() OVER(PARTITION BY binding.role_code
                      ORDER BY release.approved_at DESC,release.source_version DESC,release.source_release_id DESC) candidate_rank
                  FROM supply.source_release release
                  JOIN supply.source_release_binding binding ON binding.source_release_id=release.source_release_id
                  JOIN supply.account_input_role role ON role.role_code=binding.role_code
                  WHERE release.product_code=:product AND release.region_code=:region
                    AND release.marketing_year=:year AND release.approval_state='APPROVED'
                ) candidate WHERE candidate_rank=1 ORDER BY sort_order
                """).param("product", product).param("region", region).param("year", year)
                .query((row, index) -> new SourceRow(row.getString("source_release_id"),
                        row.getString("source_domain"), row.getString("source_record_id"),
                        row.getLong("source_version"), timestamp(row.getObject("approved_at", OffsetDateTime.class)),
                        row.getString("quality_state"), row.getString("role_code"), row.getString("label"),
                        row.getString("group_code"), row.getInt("sort_order"), row.getString("source_field_code"),
                        row.getBigDecimal("source_value"), row.getString("unit_code"))).list();
    }

    private void upsertDecision(SupplyRunCommand command, SourceRow row, String actor, OffsetDateTime now) {
        int updated = jdbc.sql("""
                INSERT INTO supply.adoption_decision(adoption_decision_id,product_code,region_code,marketing_year,
                  role_code,source_release_id,adopted_value,reason,decided_by,decided_at,version)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:role,CAST(:release AS uuid),:value,:reason,:actor,:now,0)
                ON CONFLICT(product_code,region_code,marketing_year,role_code) DO UPDATE
                  SET source_release_id=excluded.source_release_id,adopted_value=excluded.adopted_value,
                    reason=excluded.reason,decided_by=excluded.decided_by,decided_at=excluded.decided_at,
                    version=supply.adoption_decision.version+1
                  WHERE supply.adoption_decision.version=:expected
                """).param("id", UUID.randomUUID().toString()).param("product", command.productCode())
                .param("region", command.regionCode()).param("year", command.marketingYear()).param("role", row.role)
                .param("release", row.releaseId).param("value", row.value).param("reason", command.adoptionReason())
                .param("actor", actor).param("now", now).param("expected", command.expectedDecisionVersion()).update();
        if (updated == 0) conflict();
    }

    private void upsertAdjustment(SupplyRunCommand command, String actor, OffsetDateTime now) {
        int updated = jdbc.sql("""
                INSERT INTO supply.approved_adjustment(adjustment_id,product_code,region_code,marketing_year,
                  value,reason,decided_by,decided_at,version)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:value,:reason,:actor,:now,0)
                ON CONFLICT(product_code,region_code,marketing_year) DO UPDATE
                  SET value=excluded.value,reason=excluded.reason,decided_by=excluded.decided_by,
                    decided_at=excluded.decided_at,version=supply.approved_adjustment.version+1
                  WHERE supply.approved_adjustment.version=:expected
                """).param("id", UUID.randomUUID().toString()).param("product", command.productCode())
                .param("region", command.regionCode()).param("year", command.marketingYear())
                .param("value", command.approvedAdjustment()).param("reason", command.adjustmentReason())
                .param("actor", actor).param("now", now).param("expected", command.expectedDecisionVersion()).update();
        if (updated == 0) conflict();
    }

    private void insertRun(String id, SupplyRunCommand command, long formulaId, String state,
            List<String> errors, SupplyAccountCalculation calculation, long decisionVersion,
            String actor, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO supply.calculation_run(calculation_run_id,product_code,region_code,marketing_year,
                  formula_version_id,result_state,validation_codes,total_supply,total_use,calculated_ending_inventory,
                  approved_adjustment,adopted_ending_inventory,surveyed_ending_inventory,
                  inventory_reconciliation_difference,balanced,decision_version,adjustment_reason_snapshot,
                  adjustment_actor_snapshot,adjustment_decided_at_snapshot,created_by,created_at)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:formula,:state,CAST(:errors AS text[]),:supply,
                  :use,:calculated,:adjustment,:adopted,:surveyed,:difference,:balanced,:decisionVersion,
                  :adjustmentReason,:adjustmentActor,:adjustmentAt,:actor,:now)
                """).param("id", id).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("formula", formulaId).param("state", state)
                .param("errors", errors.toArray(String[]::new)).param("supply", calculation == null ? null : calculation.totalSupply())
                .param("use", calculation == null ? null : calculation.totalUse())
                .param("calculated", calculation == null ? null : calculation.calculatedEndingInventory())
                .param("adjustment", calculation == null ? command.approvedAdjustment() : calculation.approvedAdjustment())
                .param("adopted", calculation == null ? null : calculation.adoptedEndingInventory())
                .param("surveyed", calculation == null ? null : calculation.surveyedEndingInventory())
                .param("difference", calculation == null ? null : calculation.inventoryReconciliationDifference())
                .param("balanced", calculation != null && calculation.balanced()).param("decisionVersion", decisionVersion)
                .param("adjustmentReason", command.adjustmentReason()).param("adjustmentActor", actor)
                .param("adjustmentAt", now).param("actor", actor).param("now", now).update();
    }

    private void insertSourceSnapshot(String runId, SourceRow row, String reason) {
        jdbc.sql("""
                INSERT INTO supply.calculation_source_reference(calculation_run_id,role_code,source_release_id,
                  source_record_id,source_version,adopted_value,reason,drill_down_route,source_domain_snapshot,
                  source_field_code_snapshot,source_value_snapshot,unit_code_snapshot,approval_state_snapshot,
                  approved_at_snapshot,quality_state_snapshot,role_label_snapshot,group_code_snapshot,role_sort_order_snapshot)
                VALUES(CAST(:run AS uuid),:role,CAST(:release AS uuid),:record,:version,:value,:reason,:route,
                  :domain,:field,:sourceValue,:unit,'APPROVED',CAST(:approvedAt AS timestamptz),:quality,:label,:group,:sortOrder)
                """).param("run", runId).param("role", row.role).param("release", row.releaseId)
                .param("record", row.record).param("version", row.sourceVersion).param("value", row.value)
                .param("reason", reason).param("route", route(row.domain, row.record)).param("domain", row.domain)
                .param("field", row.field).param("sourceValue", row.value).param("unit", row.unit)
                .param("approvedAt", row.approvedAt).param("quality", row.quality).param("label", row.label)
                .param("group", row.group).param("sortOrder", row.sortOrder).update();
    }

    private int nextResultVersion(SupplyRunCommand command) {
        return jdbc.sql("""
                SELECT COALESCE(max(rv.version_no),0)+1 FROM supply.result_version rv
                JOIN supply.calculation_run r ON r.calculation_run_id=rv.calculation_run_id
                WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
                """).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).query(Integer.class).single();
    }

    private DecisionState decisionState(String product, String region, String year) {
        return jdbc.sql("""
                SELECT version FROM supply.approved_adjustment
                WHERE product_code=:product AND region_code=:region AND marketing_year=:year
                """).param("product", product).param("region", region).param("year", year)
                .query(Long.class).optional().map(version -> new DecisionState(true, version))
                .orElse(new DecisionState(false, 0));
    }

    private List<SupplyAccountView> assemble(List<Header> headers) {
        if (headers.isEmpty()) return List.of();
        List<String> ids = headers.stream().map(Header::id).toList();
        Map<String, List<SupplySourceView>> sources = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT calculation_run_id::text run_id,role_code,role_label_snapshot,group_code_snapshot,
                  source_domain_snapshot,source_record_id,source_version,source_field_code_snapshot,unit_code_snapshot,
                  approval_state_snapshot,approved_at_snapshot,quality_state_snapshot,source_value_snapshot,
                  adopted_value,reason,drill_down_route
                FROM supply.calculation_source_reference WHERE calculation_run_id::text IN (:ids)
                ORDER BY role_sort_order_snapshot
                """).param("ids", ids).query((row, index) -> new AbstractMap.SimpleImmutableEntry<>(
                        row.getString("run_id"), new SupplySourceView(row.getString("role_code"),
                                row.getString("role_label_snapshot"), row.getString("group_code_snapshot"),
                                row.getString("source_domain_snapshot"), row.getString("source_record_id"),
                                row.getLong("source_version"), row.getString("source_field_code_snapshot"),
                                row.getString("unit_code_snapshot"), row.getString("approval_state_snapshot"),
                                timestamp(row.getObject("approved_at_snapshot", OffsetDateTime.class)),
                                row.getString("quality_state_snapshot"), plain(row.getBigDecimal("source_value_snapshot")),
                                plain(row.getBigDecimal("adopted_value")), row.getString("reason"),
                                row.getString("drill_down_route"))))
                .list().forEach(entry -> sources.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        Map<Long, SupplyFormulaView> formulas = new LinkedHashMap<>();
        headers.stream().map(Header::formulaId).distinct().forEach(id -> formulas.put(id, formulaView(id)));
        return headers.stream().map(header -> {
            boolean publishable = header.balanced && header.errors.isEmpty();
            String balanceReason = !header.errors.isEmpty() ? String.join(",", header.errors)
                    : header.balanced ? "WITHIN_TOLERANCE" : "OUTSIDE_BALANCE_TOLERANCE";
            SupplyAdjustmentAuditView audit = new SupplyAdjustmentAuditView(header.adjustment,
                    header.adjustmentReason, header.adjustmentActor, header.adjustmentAt, header.decisionVersion);
            return new SupplyAccountView(header.id, header.product, header.region, header.year,
                    header.resultVersion, header.decisionVersion, header.state, header.errors,
                    header.balanced, publishable, balanceReason, header.totalSupply, header.totalUse,
                    header.calculated, header.adjustment, header.adopted, header.surveyed, header.difference,
                    audit, formulas.get(header.formulaId), List.copyOf(sources.getOrDefault(header.id, List.of())));
        }).toList();
    }

    private SupplyFormula domainFormula(long id) {
        FormulaMetadata metadata = jdbc.sql("""
                SELECT code,version_no,name,precision_value,scale_value,rounding_mode,tolerance
                FROM supply.formula_version WHERE formula_version_id=:id
                """).param("id", id).query((row, index) -> new FormulaMetadata(row.getString("code"),
                        row.getInt("version_no"), row.getString("name"), row.getInt("precision_value"),
                        row.getInt("scale_value"), RoundingMode.valueOf(row.getString("rounding_mode")),
                        row.getBigDecimal("tolerance"))).single();
        List<ResultMetadata> results = jdbc.sql("""
                SELECT result_role,label,required,sort_order FROM supply.formula_result_role
                WHERE formula_version_id=:id ORDER BY sort_order
                """).param("id", id).query((row, index) -> new ResultMetadata(row.getString("result_role"),
                        row.getString("label"), row.getBoolean("required"), row.getInt("sort_order"))).list();
        Map<String, List<SupplyFormula.Term>> terms = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT result_role,operand_role,coefficient,term_order FROM supply.formula_term
                WHERE formula_version_id=:id ORDER BY result_role,term_order
                """).param("id", id).query((row, index) -> new AbstractMap.SimpleImmutableEntry<>(
                        row.getString("result_role"), new SupplyFormula.Term(row.getString("operand_role"),
                                row.getBigDecimal("coefficient"), row.getInt("term_order"))))
                .list().forEach(entry -> terms.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        try {
            SupplyFormula formula = new SupplyFormula(metadata.code, metadata.version, metadata.precision,
                    metadata.scale, metadata.roundingMode, metadata.tolerance, results.stream()
                    .map(result -> new SupplyFormula.Result(result.role, result.label, result.required, result.order,
                            List.copyOf(terms.getOrDefault(result.role, List.of())))).toList());
            SupplyAccountCalculator.validateFormula(formula);
            return formula;
        } catch (IllegalArgumentException exception) {
            throw formulaContract(exception.getMessage());
        }
    }

    private SupplyFormulaView formulaView(long id) {
        FormulaMetadata metadata = jdbc.sql("""
                SELECT code,version_no,name,precision_value,scale_value,rounding_mode,tolerance
                FROM supply.formula_version WHERE formula_version_id=:id
                """).param("id", id).query((row, index) -> new FormulaMetadata(row.getString("code"),
                        row.getInt("version_no"), row.getString("name"), row.getInt("precision_value"),
                        row.getInt("scale_value"), RoundingMode.valueOf(row.getString("rounding_mode")),
                        row.getBigDecimal("tolerance"))).single();
        SupplyFormula formula = domainFormula(id);
        List<SupplyFormulaView.Expression> expressions = formula.results().stream()
                .map(result -> new SupplyFormulaView.Expression(result.role(), result.label(), expression(result), result.order()))
                .toList();
        SupplyFormula.Result difference = formula.results().stream()
                .filter(result -> result.role().equals(SupplyAccountCalculator.DIFFERENCE_CODE)).findFirst()
                .orElseThrow(() -> formulaContract("Missing difference result"));
        return new SupplyFormulaView(metadata.code, metadata.version, metadata.name, metadata.precision,
                metadata.scale, metadata.roundingMode.name(), metadata.tolerance.setScale(metadata.scale).toPlainString(),
                difference.role(), difference.label(), expression(difference), expressions);
    }

    private BigDecimal approvedUpstreamValue(UpstreamSourceReleaseCommand command) {
        String sql = switch (command.sourceDomain()) {
            case "PRODUCTION" -> command.sourceFieldCode().equals("PROD_ESTIMATED_OUTPUT") ? """
                    SELECT estimated_output_kg FROM production.production_record
                    WHERE record_id=:record AND version=:version AND product_code=:product AND region_code=:region AND status_code='APPROVED'
                    """ : """
                    SELECT fact.value FROM production.production_record record JOIN (
                      SELECT record_id,quality_code code,value FROM production.production_record_quality
                      UNION ALL SELECT record_id,cost_code,value FROM production.production_record_cost
                      UNION ALL SELECT record_id,insurance_code,value FROM production.production_record_insurance
                      UNION ALL SELECT record_id,subsidy_code,value FROM production.production_record_subsidy
                    ) fact ON fact.record_id=record.record_id
                    WHERE record.record_id=:record AND record.version=:version AND record.product_code=:product
                      AND record.region_code=:region AND record.status_code='APPROVED' AND fact.code=:field
                    """;
            case "MARKET" -> command.sourceFieldCode().equals("MKT_ACTUAL_TRADE_PRICE") ? """
                    SELECT actual_trade_price FROM market.market_record
                    WHERE record_id=:record AND version=:version AND product_code=:product AND region_code=:region AND status_code='APPROVED'
                    """ : """
                    SELECT fact.value FROM market.market_record record JOIN market.market_record_fact fact ON fact.record_id=record.record_id
                    WHERE record.record_id=:record AND record.version=:version AND record.product_code=:product
                      AND record.region_code=:region AND record.status_code='APPROVED' AND fact.fact_code=:field
                    """;
            case "LOGISTICS" -> """
                    SELECT fact.value FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id
                    WHERE event.event_id::text=:record AND event.version=:version AND event.product_code=:product
                      AND event.status_code='APPROVED' AND :region IN(event.origin_region_code,event.destination_region_code)
                      AND fact.fact_code=:field
                    """;
            default -> throw invalid();
        };
        return jdbc.sql(sql).param("record", command.sourceRecordId()).param("version", command.sourceVersion())
                .param("product", command.productCode()).param("region", command.regionCode())
                .param("field", command.sourceFieldCode()).query(BigDecimal.class).optional()
                .orElseThrow(() -> new ClientRequestException("INVALID_SUPPLY_SOURCE_PROVENANCE",
                        "Source must match an approved upstream record version and field"));
    }

    private SupplyReleaseView releaseView(String releaseId, String role) {
        return jdbc.sql("""
                SELECT release.source_release_id::text,release.source_domain,release.source_record_id,
                  release.source_version,binding.role_code,binding.source_field_code,binding.source_value,
                  binding.unit_code,release.approval_state,release.quality_state
                FROM supply.source_release release JOIN supply.source_release_binding binding
                  ON binding.source_release_id=release.source_release_id
                WHERE release.source_release_id::text=:id AND binding.role_code=:role
                """).param("id", releaseId).param("role", role).query((row, index) -> new SupplyReleaseView(
                        row.getString("source_release_id"), row.getString("source_domain"),
                        row.getString("source_record_id"), row.getLong("source_version"), row.getString("role_code"),
                        row.getString("source_field_code"), plain(row.getBigDecimal("source_value")),
                        row.getString("unit_code"), row.getString("approval_state"), row.getString("quality_state"))).single();
    }

    private String existingRelease(String domain, String record, long version) {
        return jdbc.sql("""
                SELECT source_release_id::text FROM supply.source_release
                WHERE source_domain=:domain AND source_record_id=:record AND source_version=:version
                """).param("domain", domain).param("record", record).param("version", version)
                .query(String.class).optional().orElse(null);
    }

    private boolean releaseMatches(String releaseId, UpstreamSourceReleaseCommand command) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM supply.source_release WHERE source_release_id::text=:id
                  AND product_code=:product AND region_code=:region AND marketing_year=:year
                  AND quality_state=:quality AND approval_state='APPROVED')
                """).param("id", releaseId).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("quality", command.qualityState()).query(Boolean.class).single());
    }

    private void requireContext(String product, String region) {
        if (!exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)", product)
                || !exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)", region)) throw invalid();
    }

    private void lockContext(String product, String region, String year) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:context,0))")
                .param("context", product + "|" + region + "|" + year)
                .query((row,index) -> Boolean.TRUE).single();
    }

    private boolean exists(String sql, String value) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("value", value).query(Boolean.class).single());
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
            if (absolute.compareTo(BigDecimal.ONE) != 0) expression.append(absolute.toPlainString()).append(" * ");
            expression.append(term.operandRole());
        }
        return expression.toString();
    }

    private static String digest(Object command, BigDecimal value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((command.toString() + "|" + value.toPlainString()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void conflict() {
        throw new ConflictException("SUPPLY_DECISION_VERSION_CONFLICT", "Supply decision has changed");
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException("INVALID_SUPPLY_ACCOUNT_REQUEST", "Supply account request is invalid");
    }

    private static ServerContractException formulaContract(String detail) {
        return new ServerContractException("INVALID_SUPPLY_FORMULA_METADATA",
                "Active supply formula metadata is invalid: " + detail);
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

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String timestamp(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private static List<String> strings(Array array) {
        if (array == null) return List.of();
        try {
            return List.of((String[]) array.getArray());
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record FormulaMetadata(String code, int version, String name, int precision, int scale,
                                   RoundingMode roundingMode, BigDecimal tolerance) {}
    private record ResultMetadata(String role, String label, boolean required, int order) {}
    private record DecisionState(boolean exists, long version) {}
    private record ManualState(boolean exists, long version) {}
    private record SourceRow(String releaseId, String domain, String record, long sourceVersion,
                             String approvedAt, String quality, String role, String label, String group,
                             int sortOrder, String field, BigDecimal value, String unit) {}
    private record Header(String id, String product, String region, String year, int resultVersion,
                          long decisionVersion, String state, List<String> errors, boolean balanced,
                          String totalSupply, String totalUse, String calculated, String adjustment,
                          String adopted, String surveyed, String difference, String adjustmentReason,
                          String adjustmentActor, String adjustmentAt, long formulaId) {}
}
