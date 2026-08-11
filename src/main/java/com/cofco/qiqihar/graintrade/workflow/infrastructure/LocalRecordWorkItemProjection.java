package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.workflow.application.WorkItemProjection;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative bridge from real production, market, and logistics records to the
 * work-item read model in every runtime profile.
 * It is deliberately idempotent and uses a stable source key, so refreshing the work page
 * never duplicates tasks.
 */
@Component
public class LocalRecordWorkItemProjection implements WorkItemProjection {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;

    public LocalRecordWorkItemProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void refresh() {
        ensureReferenceData();
        refreshRecords("PRODUCTION", "产情监测", "production.production_record", "survey_date",
                "PRODUCTION_RECORD", "PRODUCTION_RECORD_CREATED", "PRODUCTION_RECORD_IMPORTED");
        refreshRecords("MARKET", "市场监测", "market.market_record", "trade_date",
                "MARKET_RECORD", "MARKET_RECORD_CREATED", "MARKET_RECORD_IMPORTED");
        refreshLogistics();
    }

    private void ensureReferenceData() {
        jdbc.sql("""
                INSERT INTO workflow.workflow_node(code, label)
                VALUES ('LOCAL_FILL', '填报'), ('LOCAL_REVIEW', '审核'), ('LOCAL_COMPLETE', '已完成')
                ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label
                """).update();
    }

    private void refreshRecords(
            String domain, String domainLabel, String table, String dateColumn,
            String aggregateType, String createdAction, String importedAction) {
        String sql = """
                SELECT source.record_id,
                       source.product_code,
                       source.region_code,
                       source.%s AS business_date,
                       source.status_code,
                       COALESCE(created.actor_subject_id, source.last_modified_by) AS owner_subject_id,
                       COALESCE(owner.display_name, created.actor_subject_id,
                                source.last_modified_by) AS owner_display_name,
                       COALESCE(created.work_unit_code, owner.work_unit_code) AS owner_work_unit_code,
                       unit.name AS owner_work_unit_name
                FROM %s source
                LEFT JOIN LATERAL (
                    SELECT audit.actor_subject_id, audit.work_unit_code
                    FROM platform.business_audit_event audit
                    WHERE audit.aggregate_type = :aggregateType
                      AND audit.aggregate_id = source.record_id
                      AND audit.action_code IN (:createdAction, :importedAction)
                    ORDER BY audit.occurred_at, audit.event_id
                    LIMIT 1
                ) created ON true
                LEFT JOIN platform.security_user owner
                  ON owner.subject_id = COALESCE(created.actor_subject_id, source.last_modified_by)
                LEFT JOIN platform.work_unit unit
                  ON unit.code = COALESCE(created.work_unit_code, owner.work_unit_code)
                """.formatted(dateColumn, table);
        List<SourceRecord> records = jdbc.sql(sql)
                .param("aggregateType", aggregateType)
                .param("createdAction", createdAction)
                .param("importedAction", importedAction)
                .query((row, ignored) -> sourceRecord(row))
                .list();
        records.forEach(record -> upsert(domain, domainLabel, domain, record));
    }

    private void refreshLogistics() {
        List<SourceRecord> records = jdbc.sql("""
                SELECT event_id::text AS record_id,
                       product_code,
                       CASE direction_code
                           WHEN 'INFLOW' THEN destination_region_code
                           ELSE origin_region_code
                       END AS region_code,
                       collection_date AS business_date,
                       event.status_code,
                       event.created_by AS owner_subject_id,
                       COALESCE(owner.display_name, event.created_by) AS owner_display_name,
                       owner.work_unit_code AS owner_work_unit_code,
                       unit.name AS owner_work_unit_name
                FROM logistics.route_event event
                LEFT JOIN platform.security_user owner
                  ON owner.subject_id = event.created_by
                LEFT JOIN platform.work_unit unit
                  ON unit.code = owner.work_unit_code
                """)
                .query((row, ignored) -> sourceRecord(row))
                .list();
        records.forEach(record -> upsert("LOGISTICS", "物流监测", "LOGISTICS", record));
    }

    private void upsert(
            String domain, String domainLabel, String sourceType, SourceRecord record) {
        String status = switch (record.statusCode()) {
            case "DRAFT" -> "TO_FILL";
            case "PENDING_REVIEW" -> "TO_REVIEW";
            case "RETURNED" -> "RETURNED";
            case "APPROVED", "VOIDED" -> null;
            default -> throw new IllegalStateException(
                    "Unsupported local workflow source status: " + record.statusCode());
        };
        Period period = period(record.businessDate());
        String nodeCode = status == null ? "LOCAL_COMPLETE"
                : ("TO_REVIEW".equals(status) ? "LOCAL_REVIEW" : "LOCAL_FILL");
        ResponsibleParty party = responsibleParty(record, status);
        jdbc.sql("""
                INSERT INTO workflow.responsible_party(party_type, external_code, display_name)
                VALUES (:partyType, :partyCode, :partyName)
                ON CONFLICT (party_type, external_code) DO UPDATE
                SET display_name = EXCLUDED.display_name
                """)
                .param("partyType", party.type())
                .param("partyCode", party.code())
                .param("partyName", party.name())
                .update();
        String task = domainLabel + " · " + record.recordId();
        jdbc.sql("""
                INSERT INTO workflow.work_item(
                    task_name, business_domain, region_code, product_code,
                    business_period_code, due_at, workflow_node_id, status_code,
                    responsible_party_id, completed_at, source_type, source_id,
                    owner_subject_id, owner_work_unit_code)
                VALUES (
                    :task, :domain, :region, :product, :period,
                    :dueAt, (SELECT node_id FROM workflow.workflow_node WHERE code = :node),
                    :status, (SELECT responsible_party_id FROM workflow.responsible_party
                              WHERE party_type = :partyType AND external_code = :partyCode),
                    :completedAt, :sourceType, :sourceId, :ownerSubject, :ownerWorkUnit)
                ON CONFLICT (source_type, source_id) DO UPDATE SET
                    task_name = EXCLUDED.task_name,
                    business_domain = EXCLUDED.business_domain,
                    region_code = EXCLUDED.region_code,
                    product_code = EXCLUDED.product_code,
                    business_period_code = EXCLUDED.business_period_code,
                    due_at = EXCLUDED.due_at,
                    workflow_node_id = EXCLUDED.workflow_node_id,
                    status_code = EXCLUDED.status_code,
                    responsible_party_id = EXCLUDED.responsible_party_id,
                    completed_at = CASE
                        WHEN EXCLUDED.status_code IS NULL
                            THEN COALESCE(workflow.work_item.completed_at, EXCLUDED.completed_at)
                        ELSE NULL
                    END,
                    owner_subject_id = EXCLUDED.owner_subject_id,
                    owner_work_unit_code = EXCLUDED.owner_work_unit_code
                """)
                .param("task", task)
                .param("domain", domain)
                .param("region", record.regionCode())
                .param("product", record.productCode())
                .param("period", period.code())
                .param("dueAt", period.endsOn().atTime(23, 59, 59).atZone(REPORTING_ZONE).toOffsetDateTime())
                .param("node", nodeCode)
                .param("status", status)
                .param("partyType", party.type())
                .param("partyCode", party.code())
                .param("completedAt", status == null ? OffsetDateTime.now(REPORTING_ZONE) : null)
                .param("sourceType", sourceType)
                .param("sourceId", record.recordId())
                .param("ownerSubject", record.ownerSubjectId())
                .param("ownerWorkUnit", record.ownerWorkUnitCode())
                .update();
    }

    private SourceRecord sourceRecord(java.sql.ResultSet row) throws java.sql.SQLException {
        return new SourceRecord(
                row.getString("record_id"),
                row.getString("product_code"),
                row.getString("region_code"),
                row.getObject("business_date", LocalDate.class),
                row.getString("status_code"),
                row.getString("owner_subject_id"),
                row.getString("owner_display_name"),
                row.getString("owner_work_unit_code"),
                row.getString("owner_work_unit_name"));
    }

    private ResponsibleParty responsibleParty(SourceRecord record, String status) {
        if ("TO_REVIEW".equals(status)
                && record.ownerWorkUnitCode() != null
                && !record.ownerWorkUnitCode().isBlank()) {
            String workUnitName = record.ownerWorkUnitName() == null
                    || record.ownerWorkUnitName().isBlank()
                    ? record.ownerWorkUnitCode() : record.ownerWorkUnitName();
            return new ResponsibleParty("WORK_UNIT", record.ownerWorkUnitCode(), workUnitName);
        }
        return new ResponsibleParty("USER", record.ownerSubjectId(), record.ownerDisplayName());
    }

    private Period period(LocalDate date) {
        return jdbc.sql("""
                SELECT code, ends_on
                FROM platform.business_period
                WHERE starts_on <= :date AND ends_on >= :date
                ORDER BY sort_order DESC
                LIMIT 1
                """)
                .param("date", Date.valueOf(date))
                .query((row, ignored) -> new Period(
                        row.getString("code"), row.getObject("ends_on", LocalDate.class)))
                .optional()
                .orElseGet(() -> jdbc.sql("""
                        SELECT code, ends_on FROM platform.business_period
                        ORDER BY sort_order DESC LIMIT 1
                        """).query((row, ignored) -> new Period(
                                row.getString("code"), row.getObject("ends_on", LocalDate.class)))
                        .single());
    }

    private record SourceRecord(
            String recordId, String productCode, String regionCode,
            LocalDate businessDate, String statusCode,
            String ownerSubjectId, String ownerDisplayName,
            String ownerWorkUnitCode, String ownerWorkUnitName) {}

    private record ResponsibleParty(String type, String code, String name) {}

    private record Period(String code, LocalDate endsOn) {}
}
