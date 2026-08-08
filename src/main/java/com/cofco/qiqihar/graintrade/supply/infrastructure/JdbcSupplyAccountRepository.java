package com.cofco.qiqihar.graintrade.supply.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import com.cofco.qiqihar.graintrade.supply.application.ManualInputDecisionCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountRepository;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAdjustmentAuditView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAdjustmentProposalView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyCalculationMaterial;
import com.cofco.qiqihar.graintrade.supply.application.SupplyFormulaView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetMaterial;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetPersistence;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyManualDecisionMaterial;
import com.cofco.qiqihar.graintrade.supply.application.SupplyManualDecisionPersistence;
import com.cofco.qiqihar.graintrade.supply.application.SupplyReleaseView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyRunCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyRunPersistence;
import com.cofco.qiqihar.graintrade.supply.application.SupplySourceReleaseMaterial;
import com.cofco.qiqihar.graintrade.supply.application.SupplySourceReleasePersistence;
import com.cofco.qiqihar.graintrade.supply.application.SupplySourceView;
import com.cofco.qiqihar.graintrade.supply.application.UpstreamSourceReleaseCommand;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyAccountCalculation;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyAccountCalculator;
import com.cofco.qiqihar.graintrade.supply.domain.SupplyFormula;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        return find(product, region, year, state, resultVersion, Set.of("*"));
    }

    @Override
    public List<SupplyAccountView> find(
            String product, String region, String year, String state, Integer resultVersion,
            Set<String> authorizedRegionCodes) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.calculation_run_id::text id,r.product_code,r.region_code,r.marketing_year,
                  r.result_state,r.validation_codes,r.balanced,r.decision_version,
                  r.total_supply,r.total_use,r.calculated_ending_inventory,r.approved_adjustment,
                  r.adopted_ending_inventory,r.surveyed_ending_inventory,r.inventory_reconciliation_difference,
                  r.adjustment_reason_snapshot,r.adjustment_actor_snapshot,r.adjustment_decided_at_snapshot,
                  r.adjustment_proposal_value,r.adjustment_proposal_reason,r.adjustment_requested_by,
                  r.adjustment_requested_at,r.input_set_id::text,
                  (r.input_set_id IS NULL OR COALESCE(input_set.legacy, false)) legacy_read_only,
                  r.formula_snapshot->>'code' formula_code,(r.formula_snapshot->>'version')::integer formula_version,
                  r.formula_snapshot->>'name' formula_name,(r.formula_snapshot->>'precision')::integer formula_precision,
                  (r.formula_snapshot->>'scale')::integer formula_scale,r.formula_snapshot->>'roundingMode' rounding_mode,
                  r.formula_snapshot->>'tolerance' tolerance,rv.version_no
                FROM supply.calculation_run r
                JOIN supply.result_version rv ON rv.calculation_run_id=r.calculation_run_id
                LEFT JOIN supply.source_adoption_set input_set ON input_set.input_set_id=r.input_set_id
                WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("product", product);
        params.put("region", region);
        params.put("year", year);
        if (!authorizedRegionCodes.contains("*")) {
            if (authorizedRegionCodes.isEmpty()) sql.append(" AND 1=0");
            else {
                sql.append(" AND r.region_code IN (:authorizedRegions)");
                params.put("authorizedRegions", authorizedRegionCodes);
            }
        }
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
                plain(row.getBigDecimal("adjustment_proposal_value")), row.getString("adjustment_proposal_reason"),
                row.getString("adjustment_requested_by"),
                timestamp(row.getObject("adjustment_requested_at", OffsetDateTime.class)), row.getString("input_set_id"),
                Boolean.TRUE.equals(row.getObject("legacy_read_only", Boolean.class)),
                new FormulaHeader(row.getString("formula_code"), row.getInt("formula_version"),
                        row.getString("formula_name"), row.getInt("formula_precision"), row.getInt("formula_scale"),
                        row.getString("rounding_mode"), row.getString("tolerance")))).list();
        return assemble(headers);
    }

    @Override
    public void lockCalculationContext(String product, String region, String year) {
        requireContext(product, region);
        lockContext(product, region, year);
    }

    @Override
    public SupplyCalculationMaterial loadCalculationMaterial(
            String inputSetId, String product, String region, String year) {
        long formulaId = jdbc.sql("""
                SELECT formula_version_id FROM supply.formula_version
                WHERE active ORDER BY version_no DESC LIMIT 1
                """).query(Long.class).optional().orElseThrow(() -> formulaContract("No active formula"));
        SupplyFormula formula = domainFormula(formulaId);
        String formulaName = jdbc.sql("SELECT name FROM supply.formula_version WHERE formula_version_id=:id")
                .param("id", formulaId).query(String.class).single();
        InputSetHeader inputSet = jdbc.sql("""
                SELECT input_set_id::text,version_no,product_code,region_code,marketing_year,reason
                FROM supply.source_adoption_set WHERE input_set_id::text=:id AND NOT legacy
                  AND product_code=:product AND region_code=:region AND marketing_year=:year
                """).param("id", inputSetId).param("product", product).param("region", region).param("year", year)
                .query((row, index) -> new InputSetHeader(row.getString("input_set_id"), row.getLong("version_no"),
                        row.getString("product_code"), row.getString("region_code"), row.getString("marketing_year"),
                        row.getString("reason"))).optional().orElse(null);
        if (inputSet == null) return null;
        List<SupplyCalculationMaterial.Source> sources = jdbc.sql("""
                SELECT release.source_release_id::text,release.source_domain,release.source_record_id,
                  release.source_version,release.approved_at,release.quality_state,binding.role_code,
                  role.label,role.group_code,role.sort_order,binding.source_field_code,binding.source_value,binding.unit_code
                FROM supply.source_adoption_set_item item
                JOIN supply.source_release release ON release.source_release_id=item.source_release_id
                JOIN supply.source_release_binding binding ON binding.source_release_id=item.source_release_id
                  AND binding.role_code=item.role_code
                JOIN supply.account_input_role role ON role.role_code=item.role_code
                WHERE item.input_set_id=CAST(:id AS uuid) ORDER BY role.sort_order
                """).param("id", inputSetId).query((row, index) -> new SupplyCalculationMaterial.Source(
                        row.getString("source_release_id"), row.getString("source_domain"),
                        row.getString("source_record_id"), row.getLong("source_version"),
                        timestamp(row.getObject("approved_at", OffsetDateTime.class)), row.getString("quality_state"),
                        row.getString("role_code"), row.getString("label"), row.getString("group_code"),
                        row.getInt("sort_order"), row.getString("source_field_code"),
                        row.getBigDecimal("source_value"), row.getString("unit_code"))).list();
        DecisionState current = decisionState(product, region, year);
        return new SupplyCalculationMaterial(
                new SupplyCalculationMaterial.FormulaDefinition(formulaId, formulaName, formula),
                new SupplyCalculationMaterial.InputSet(inputSet.id, inputSet.version, inputSet.product,
                        inputSet.region, inputSet.year, inputSet.reason, sources),
                new SupplyCalculationMaterial.DecisionState(current.exists, current.version),
                nextResultVersion(product, region, year));
    }

    @Override
    public void persistFormalDecision(
            SupplyRunCommand command, SupplyCalculationMaterial material, String actor, Instant now) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        material.inputSet().sources().forEach(source -> upsertDecision(command, material.inputSet().reason(),
                source.releaseId(), source.roleCode(), source.accountValue(), actor, timestamp));
        upsertAdjustment(command, actor, timestamp);
    }

    @Override
    public SupplyAccountView persistRun(SupplyRunPersistence run) {
        int resultVersion = run.material().nextResultVersion();
        String runId = UUID.randomUUID().toString();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(run.occurredAt(), ZoneOffset.UTC);
        insertRun(runId, run, timestamp);
        jdbc.sql("""
                INSERT INTO supply.result_version(result_version_id,calculation_run_id,version_no,published_by,published_at)
                VALUES(CAST(:result AS uuid),CAST(:run AS uuid),:version,:publisher,:publishedAt)
                """).param("result", UUID.randomUUID().toString()).param("run", runId)
                .param("version", resultVersion).param("publisher", run.resultState().equals("FORMAL") ? run.actor() : null)
                .param("publishedAt", run.resultState().equals("FORMAL") ? timestamp : null).update();
        run.material().inputSet().sources().forEach(source -> insertSourceSnapshot(
                runId, source, run.material().inputSet().reason()));
        return find(run.productCode(), run.regionCode(), run.marketingYear(), null, resultVersion).getFirst();
    }

    @Override
    public SupplySourceReleaseMaterial loadSourceReleaseMaterial(UpstreamSourceReleaseCommand command) {
        boolean contextExists = contextExists(command.productCode(), command.regionCode());
        boolean semanticsApplicable = Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM supply.role_source_applicability
                  WHERE product_code=:product AND role_code=:role AND source_domain=:domain
                    AND source_field_code=:field AND active)
                """).param("product", command.productCode()).param("role", command.roleCode())
                .param("domain", command.sourceDomain()).param("field", command.sourceFieldCode())
                .query(Boolean.class).single());
        SupplySourceReleaseMaterial.UpstreamFact fact = semanticsApplicable ? upstreamFact(command) : null;
        SupplySourceReleaseMaterial.SourceMapping mapping = fact == null ? null : sourceMapping(
                command.productCode(), command.roleCode(), command.sourceDomain(), command.sourceFieldCode(),
                fact.unitCode(), fact.directionCode());
        String existingId = existingRelease(command.sourceDomain(), command.sourceRecordId(), command.sourceVersion());
        SupplySourceReleaseMaterial.ExistingRelease existing = existingId == null ? null
                : new SupplySourceReleaseMaterial.ExistingRelease(existingId, releaseMatches(existingId, command));
        return new SupplySourceReleaseMaterial(contextExists, semanticsApplicable, fact, mapping, existing);
    }

    @Override
    public SupplyReleaseView persistSourceRelease(SupplySourceReleasePersistence release) {
        UpstreamSourceReleaseCommand command = release.command();
        SupplySourceReleaseMaterial material = release.material();
        SupplySourceReleaseMaterial.SourceMapping mapping = material.mapping();
        String releaseId = material.existingRelease() == null ? null : material.existingRelease().id();
        if (releaseId == null) {
            releaseId = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,
                      approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest)
                    VALUES(CAST(:id AS uuid),:domain,:record,:version,'APPROVED',:approvedAt,:quality,:product,:region,:year,:digest)
                    """).param("id", releaseId).param("domain", command.sourceDomain())
                    .param("record", command.sourceRecordId()).param("version", command.sourceVersion())
                    .param("approvedAt", OffsetDateTime.ofInstant(release.occurredAt(), ZoneOffset.UTC))
                    .param("quality", command.qualityState())
                    .param("product", command.productCode()).param("region", command.regionCode())
                    .param("year", command.marketingYear()).param("digest", release.immutableDigest()).update();
        }
        jdbc.sql("""
                INSERT INTO supply.source_release_binding(source_release_id,role_code,source_field_code,source_value,unit_code,
                  mapping_id,mapping_version,source_raw_value,source_unit_code,conversion_rule_snapshot,conversion_factor_snapshot)
                VALUES(CAST(:release AS uuid),:role,:field,:value,:unit,:mapping,:mappingVersion,:rawValue,:sourceUnit,
                  :conversionRule,:conversionFactor)
                """).param("release", releaseId).param("role", command.roleCode())
                .param("field", command.sourceFieldCode()).param("value", release.accountValue())
                .param("unit", mapping.accountUnitCode()).param("mapping", mapping.id())
                .param("mappingVersion", mapping.version()).param("rawValue", material.upstreamFact().value())
                .param("sourceUnit", material.upstreamFact().unitCode())
                .param("conversionRule", mapping.conversionRule())
                .param("conversionFactor", mapping.conversionFactor()).update();
        return releaseView(releaseId, command.roleCode());
    }

    @Override
    public SupplyManualDecisionMaterial loadManualDecisionMaterial(ManualInputDecisionCommand command) {
        boolean contextExists = contextExists(command.productCode(), command.regionCode());
        SupplySourceReleaseMaterial.SourceMapping mapping = sourceMapping(command.productCode(), command.roleCode(), "MANUAL",
                "MANUAL_APPROVED_VALUE", "万吨", null);
        lockContext(command.productCode(), command.regionCode(), command.marketingYear() + "|" + command.roleCode());
        ManualState state = jdbc.sql("""
                SELECT version FROM supply.manual_input_decision
                WHERE product_code=:product AND region_code=:region AND marketing_year=:year AND role_code=:role
                ORDER BY version DESC LIMIT 1
                """).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("role", command.roleCode())
                .query(Long.class).optional().map(version -> new ManualState(true, version)).orElse(new ManualState(false, 0));
        return new SupplyManualDecisionMaterial(contextExists, mapping, state.exists, state.version);
    }

    @Override
    public SupplyReleaseView persistManualDecision(SupplyManualDecisionPersistence decision) {
        ManualInputDecisionCommand command = decision.command();
        SupplySourceReleaseMaterial.SourceMapping mapping = decision.mapping();
        String manualId = UUID.randomUUID().toString();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(decision.occurredAt(), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO supply.manual_input_decision(manual_input_id,product_code,region_code,marketing_year,role_code,
                  value,unit_code,reason,status_code,decided_by,approved_at,version)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:role,:value,:unit,:reason,'APPROVED',:actor,:approvedAt,:version)
                """).param("id", manualId).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("role", command.roleCode()).param("value", command.value())
                .param("unit", mapping.sourceUnitCode()).param("reason", command.reason()).param("actor", decision.actor())
                .param("approvedAt", timestamp).param("version", decision.version()).update();
        String releaseId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,
                  approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest)
                VALUES(CAST(:release AS uuid),'MANUAL',:manual,:version,'APPROVED',:approvedAt,'PASSED',:product,:region,:year,:digest)
                """).param("release", releaseId).param("manual", manualId).param("version", decision.version())
                .param("approvedAt", timestamp).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).param("digest", decision.immutableDigest()).update();
        jdbc.sql("""
                INSERT INTO supply.source_release_binding(source_release_id,role_code,source_field_code,source_value,unit_code,manual_input_id,
                  mapping_id,mapping_version,source_raw_value,source_unit_code,conversion_rule_snapshot,conversion_factor_snapshot)
                VALUES(CAST(:release AS uuid),:role,'MANUAL_APPROVED_VALUE',:value,:unit,CAST(:manual AS uuid),
                  :mapping,:mappingVersion,:rawValue,:sourceUnit,:conversionRule,:conversionFactor)
                """).param("release", releaseId).param("role", command.roleCode()).param("value", command.value())
                .param("unit", mapping.accountUnitCode()).param("manual", manualId).param("mapping", mapping.id())
                .param("mappingVersion", mapping.version()).param("rawValue", command.value())
                .param("sourceUnit", mapping.sourceUnitCode()).param("conversionRule", mapping.conversionRule())
                .param("conversionFactor", mapping.conversionFactor()).update();
        return releaseView(releaseId, command.roleCode());
    }

    @Override
    public SupplyInputSetMaterial loadInputSetMaterial(SupplyInputSetCommand command) {
        boolean contextExists = contextExists(command.productCode(), command.regionCode());
        lockContext(command.productCode(), command.regionCode(), command.marketingYear() + "|INPUT_SET");
        long currentVersion = jdbc.sql("""
                SELECT COALESCE(max(version_no),0) FROM supply.source_adoption_set
                WHERE product_code=:product AND region_code=:region AND marketing_year=:year
                """).param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).query(Long.class).single();
        List<String> requiredRoles = jdbc.sql("""
                SELECT role_code FROM supply.account_input_role WHERE required
                """).query(String.class).list();
        String[] roles = command.items().stream().map(SupplyInputSetCommand.Item::roleCode).toArray(String[]::new);
        String[] releases = command.items().stream().map(SupplyInputSetCommand.Item::sourceReleaseId)
                .toArray(String[]::new);
        List<SupplyInputSetMaterial.Source> selected = jdbc.sql("""
                WITH requested(role_code,source_release_id) AS (
                    SELECT * FROM unnest(CAST(:roles AS varchar[]),CAST(:releases AS varchar[]))
                )
                SELECT release.source_release_id::text,requested.role_code,release.source_domain,
                  release.source_record_id,release.source_version,binding.source_field_code
                FROM requested
                JOIN supply.source_release release ON release.source_release_id::text=requested.source_release_id
                JOIN supply.source_release_binding binding ON binding.source_release_id=release.source_release_id
                  AND binding.role_code=requested.role_code
                WHERE release.product_code=:product AND release.region_code=:region
                  AND release.marketing_year=:year AND release.approval_state='APPROVED'
                  AND binding.mapping_id IS NOT NULL
                """).param("roles", roles).param("releases", releases)
                .param("product", command.productCode()).param("region", command.regionCode())
                .param("year", command.marketingYear()).query((row, index) -> new SupplyInputSetMaterial.Source(
                        row.getString("source_release_id"), row.getString("role_code"),
                        row.getString("source_domain"), row.getString("source_record_id"),
                        row.getLong("source_version"), row.getString("source_field_code"))).list();
        return new SupplyInputSetMaterial(contextExists, currentVersion, Set.copyOf(requiredRoles), selected);
    }

    @Override
    public SupplyInputSetView persistInputSet(SupplyInputSetPersistence inputSet) {
        SupplyInputSetCommand command = inputSet.command();
        String id = UUID.randomUUID().toString();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(inputSet.occurredAt(), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO supply.source_adoption_set(input_set_id,version_no,product_code,region_code,
                  marketing_year,reason,created_by,created_at)
                VALUES(CAST(:id AS uuid),:version,:product,:region,:year,:reason,:actor,:now)
                """).param("id", id).param("version", inputSet.version()).param("product", command.productCode())
                .param("region", command.regionCode()).param("year", command.marketingYear())
                .param("reason", command.reason()).param("actor", inputSet.actor()).param("now", timestamp).update();
        inputSet.selectedSources().forEach(source -> jdbc.sql("""
                INSERT INTO supply.source_adoption_set_item(input_set_id,role_code,source_release_id,
                  source_domain,source_record_id,source_version,source_field_code)
                VALUES(CAST(:set AS uuid),:role,CAST(:release AS uuid),:domain,:record,:version,:field)
                """).param("set", id).param("role", source.roleCode()).param("release", source.releaseId())
                .param("domain", source.sourceDomain()).param("record", source.sourceRecordId())
                .param("version", source.sourceVersion()).param("field", source.sourceFieldCode()).update());
        return new SupplyInputSetView(id, inputSet.version(), command.productCode(), command.regionCode(),
                command.marketingYear());
    }

    private void upsertDecision(SupplyRunCommand command, String reason, String releaseId,
            String role, BigDecimal value, String actor, OffsetDateTime now) {
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
                .param("region", command.regionCode()).param("year", command.marketingYear()).param("role", role)
                .param("release", releaseId).param("value", value).param("reason", reason)
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
                .param("value", command.adjustmentProposalValue()).param("reason", command.adjustmentProposalReason())
                .param("actor", actor).param("now", now).param("expected", command.expectedDecisionVersion()).update();
        if (updated == 0) conflict();
    }

    private void insertRun(String id, SupplyRunPersistence run, OffsetDateTime now) {
        SupplyAccountCalculation calculation = run.calculation();
        boolean formal = run.resultState().equals("FORMAL");
        jdbc.sql("""
                INSERT INTO supply.calculation_run(calculation_run_id,product_code,region_code,marketing_year,
                  formula_version_id,result_state,validation_codes,total_supply,total_use,calculated_ending_inventory,
                  approved_adjustment,adopted_ending_inventory,surveyed_ending_inventory,
                  inventory_reconciliation_difference,balanced,decision_version,adjustment_reason_snapshot,
                  adjustment_actor_snapshot,adjustment_decided_at_snapshot,created_by,created_at,input_set_id,
                  formula_snapshot,adjustment_proposal_value,adjustment_proposal_reason,adjustment_requested_by,
                  adjustment_requested_at)
                VALUES(CAST(:id AS uuid),:product,:region,:year,:formula,:state,CAST(:errors AS text[]),:supply,
                  :use,:calculated,:adjustment,:adopted,:surveyed,:difference,:balanced,:decisionVersion,
                  :auditReason,:auditActor,:auditAt,:actor,:now,CAST(:inputSet AS uuid),
                  CAST(:formulaSnapshot AS jsonb),
                  :proposalValue,:proposalReason,:requestedBy,:requestedAt)
                """).param("id", id).param("product", run.productCode()).param("region", run.regionCode())
                .param("year", run.marketingYear()).param("formula", run.material().formula().id())
                .param("state", run.resultState()).param("errors", run.validationCodes().toArray(String[]::new))
                .param("supply", calculation == null ? null : calculation.totalSupply())
                .param("use", calculation == null ? null : calculation.totalUse())
                .param("calculated", calculation == null ? null : calculation.calculatedEndingInventory())
                .param("adjustment", calculation == null ? run.proposalValue() : calculation.approvedAdjustment())
                .param("adopted", calculation == null ? null : calculation.adoptedEndingInventory())
                .param("surveyed", calculation == null ? null : calculation.surveyedEndingInventory())
                .param("difference", calculation == null ? null : calculation.inventoryReconciliationDifference())
                .param("balanced", calculation != null && calculation.balanced())
                .param("decisionVersion", run.decisionVersion())
                .param("auditReason", formal ? run.proposalReason() : null)
                .param("auditActor", formal ? run.actor() : null).param("auditAt", formal ? now : null)
                .param("actor", run.actor()).param("now", now).param("inputSet", run.material().inputSet().id())
                .param("formulaSnapshot", run.formulaSnapshot())
                .param("proposalValue", formal ? null : run.proposalValue())
                .param("proposalReason", formal ? null : run.proposalReason())
                .param("requestedBy", formal ? null : run.actor()).param("requestedAt", formal ? null : now).update();
    }

    private void insertSourceSnapshot(String runId, SupplyCalculationMaterial.Source row, String reason) {
        jdbc.sql("""
                INSERT INTO supply.calculation_source_reference(calculation_run_id,role_code,source_release_id,
                  source_record_id,source_version,adopted_value,reason,drill_down_route,source_domain_snapshot,
                  source_field_code_snapshot,source_value_snapshot,unit_code_snapshot,approval_state_snapshot,
                  approved_at_snapshot,quality_state_snapshot,role_label_snapshot,group_code_snapshot,role_sort_order_snapshot)
                VALUES(CAST(:run AS uuid),:role,CAST(:release AS uuid),:record,:version,:value,:reason,:route,
                  :domain,:field,:sourceValue,:unit,'APPROVED',CAST(:approvedAt AS timestamptz),:quality,:label,:group,:sortOrder)
                """).param("run", runId).param("role", row.roleCode()).param("release", row.releaseId())
                .param("record", row.recordId()).param("version", row.sourceVersion()).param("value", row.accountValue())
                .param("reason", reason).param("route", route(row.domain(), row.recordId())).param("domain", row.domain())
                .param("field", row.sourceFieldCode()).param("sourceValue", row.accountValue()).param("unit", row.accountUnit())
                .param("approvedAt", row.approvedAt()).param("quality", row.qualityState()).param("label", row.roleLabel())
                .param("group", row.groupCode()).param("sortOrder", row.sortOrder()).update();
    }

    private int nextResultVersion(String product, String region, String year) {
        return jdbc.sql("""
                SELECT COALESCE(max(rv.version_no),0)+1 FROM supply.result_version rv
                JOIN supply.calculation_run r ON r.calculation_run_id=rv.calculation_run_id
                WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
                """).param("product", product).param("region", region)
                .param("year", year).query(Integer.class).single();
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
        Map<String, List<SupplyFormulaView.Expression>> expressions = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT run.calculation_run_id::text run_id,result->>'role' result_role,result->>'label' label,
                  result->>'expression' expression,(result->>'order')::integer sort_order
                FROM supply.calculation_run run CROSS JOIN LATERAL
                  jsonb_array_elements(run.formula_snapshot->'results') result
                WHERE run.calculation_run_id::text IN (:ids)
                ORDER BY run.calculation_run_id,(result->>'order')::integer
                """).param("ids", ids).query((row, index) -> new AbstractMap.SimpleImmutableEntry<>(
                        row.getString("run_id"), new SupplyFormulaView.Expression(row.getString("result_role"),
                                row.getString("label"), row.getString("expression"), row.getInt("sort_order"))))
                .list().forEach(entry -> expressions.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return headers.stream().map(header -> {
            boolean publishable = header.balanced && header.errors.isEmpty();
            String balanceReason = !header.errors.isEmpty() ? String.join(",", header.errors)
                    : header.balanced ? "WITHIN_TOLERANCE" : "OUTSIDE_BALANCE_TOLERANCE";
            SupplyAdjustmentAuditView audit = header.state.equals("FORMAL") ? new SupplyAdjustmentAuditView(
                    normalized(header.adjustment, header.formula), header.adjustmentReason, header.adjustmentActor,
                    header.adjustmentAt, header.decisionVersion) : null;
            SupplyAdjustmentProposalView proposal = header.state.equals("FORMAL") ? null
                    : new SupplyAdjustmentProposalView(normalized(header.proposalValue, header.formula), header.proposalReason,
                            header.proposalActor, header.proposalAt);
            List<SupplyFormulaView.Expression> runExpressions = List.copyOf(
                    expressions.getOrDefault(header.id, List.of()));
            SupplyFormulaView.Expression difference = runExpressions.stream()
                    .filter(value -> value.resultCode().equals(SupplyAccountCalculator.DIFFERENCE_CODE))
                    .findFirst().orElseThrow(() -> formulaContract("Missing snapshotted difference result"));
            SupplyFormulaView formula = new SupplyFormulaView(header.formula.code, header.formula.version,
                    header.formula.name, header.formula.precision, header.formula.scale,
                    header.formula.roundingMode, normalized(header.formula.tolerance, header.formula),
                    difference.resultCode(), difference.label(), difference.expression(), runExpressions);
            return new SupplyAccountView(header.id, header.product, header.region, header.year,
                    header.resultVersion, header.decisionVersion, header.state, header.errors,
                    header.balanced, publishable, balanceReason, normalized(header.totalSupply, header.formula),
                    normalized(header.totalUse, header.formula), normalized(header.calculated, header.formula),
                    normalized(header.adjustment, header.formula), normalized(header.adopted, header.formula),
                    normalized(header.surveyed, header.formula), normalized(header.difference, header.formula),
                    header.inputSetId, header.legacyReadOnly, proposal, audit, formula,
                    List.copyOf(sources.getOrDefault(header.id, List.of())));
        }).toList();
    }

    private static String normalized(String value, FormulaHeader formula) {
        return value == null ? null : new BigDecimal(value)
                .setScale(formula.scale, RoundingMode.valueOf(formula.roundingMode)).toPlainString();
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
        return new SupplyFormula(metadata.code, metadata.version, metadata.precision,
                metadata.scale, metadata.roundingMode, metadata.tolerance, results.stream()
                .map(result -> new SupplyFormula.Result(result.role, result.label, result.required, result.order,
                        List.copyOf(terms.getOrDefault(result.role, List.of())))).toList());
    }

    private SupplySourceReleaseMaterial.UpstreamFact upstreamFact(UpstreamSourceReleaseCommand command) {
        String sql = switch (command.sourceDomain()) {
            case "PRODUCTION" -> """
                    SELECT estimated_output_kg value,'公斤' unit_code,NULL::varchar direction_code
                    FROM production.production_record
                    WHERE record_id=:record AND version=:version AND product_code=:product AND region_code=:region AND status_code='APPROVED'
                    """;
            case "LOGISTICS" -> """
                    SELECT fact.value,fact.unit_code,event.direction_code
                    FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id
                    WHERE event.event_id::text=:record AND event.version=:version AND event.product_code=:product
                      AND event.status_code='APPROVED' AND :region IN(event.origin_region_code,event.destination_region_code)
                      AND fact.fact_code=:field
                    """;
            default -> null;
        };
        if (sql == null) return null;
        return jdbc.sql(sql).param("record", command.sourceRecordId()).param("version", command.sourceVersion())
                .param("product", command.productCode()).param("region", command.regionCode())
                .param("field", command.sourceFieldCode())
                .query((row, index) -> new SupplySourceReleaseMaterial.UpstreamFact(
                        row.getBigDecimal("value"), row.getString("unit_code"), row.getString("direction_code")))
                .optional().orElse(null);
    }

    private SupplySourceReleaseMaterial.SourceMapping sourceMapping(
            String product, String role, String domain, String field,
            String sourceUnit, String direction) {
        return jdbc.sql("""
                SELECT mapping_id,mapping_version,source_unit_code,account_unit_code,conversion_rule,conversion_factor
                FROM supply.role_source_applicability
                WHERE product_code=:product AND role_code=:role AND source_domain=:domain
                  AND source_field_code=:field AND source_unit_code=:sourceUnit AND active
                  AND required_direction_code IS NOT DISTINCT FROM CAST(:direction AS varchar)
                ORDER BY mapping_version DESC LIMIT 1
                """).param("product", product).param("role", role).param("domain", domain).param("field", field)
                .param("sourceUnit", sourceUnit).param("direction", direction)
                .query((row, index) -> new SupplySourceReleaseMaterial.SourceMapping(
                        row.getLong("mapping_id"), row.getInt("mapping_version"),
                        row.getString("source_unit_code"), row.getString("account_unit_code"),
                        row.getString("conversion_rule"), row.getBigDecimal("conversion_factor")))
                .optional().orElse(null);
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
        if (!contextExists(product, region)) throw invalid();
    }

    private boolean contextExists(String product, String region) {
        return exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)", product)
                && exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)", region);
    }

    private void lockContext(String product, String region, String year) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:context,0))")
                .param("context", product + "|" + region + "|" + year)
                .query((row,index) -> Boolean.TRUE).single();
    }

    private boolean exists(String sql, String value) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("value", value).query(Boolean.class).single());
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
    private record InputSetHeader(String id, long version, String product, String region,
                                  String year, String reason) {}
    private record FormulaHeader(String code, int version, String name, int precision, int scale,
                                 String roundingMode, String tolerance) {}
    private record Header(String id, String product, String region, String year, int resultVersion,
                          long decisionVersion, String state, List<String> errors, boolean balanced,
                          String totalSupply, String totalUse, String calculated, String adjustment,
                          String adopted, String surveyed, String difference, String adjustmentReason,
                          String adjustmentActor, String adjustmentAt, String proposalValue,
                          String proposalReason, String proposalActor, String proposalAt,
                          String inputSetId, boolean legacyReadOnly, FormulaHeader formula) {}
}
