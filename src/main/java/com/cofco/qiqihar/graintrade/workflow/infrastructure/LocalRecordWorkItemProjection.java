package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.workflow.application.WorkItemProjection;
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
    private final JdbcClient jdbc;

    public LocalRecordWorkItemProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void refresh() {
        // Serialize reconciliation across instances. Each following statement gets a
        // fresh READ COMMITTED snapshot after the previous reconciler commits.
        jdbc.sql("SELECT pg_advisory_xact_lock(746321901)").query().singleRow();
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
                WHERE workflow.workflow_node.label IS DISTINCT FROM EXCLUDED.label
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
                       COALESCE(created.actor_subject_id, source.last_modified_by) AS responsible_subject_id,
                       owner.subject_id AS owner_subject_id,
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
        jdbc.sql(projectionSql(sql))
                .param("aggregateType", aggregateType)
                .param("createdAction", createdAction)
                .param("importedAction", importedAction)
                .param("domain", domain).param("domainLabel", domainLabel).update();
    }

    private void refreshLogistics() {
        String sourceSql = """
                SELECT event_id::text AS record_id,
                       product_code,
                       CASE direction_code
                           WHEN 'INFLOW' THEN destination_region_code
                           ELSE origin_region_code
                       END AS region_code,
                       collection_date AS business_date,
                       event.status_code,
                       event.created_by AS responsible_subject_id,
                       owner.subject_id AS owner_subject_id,
                       COALESCE(owner.display_name, event.created_by) AS owner_display_name,
                       owner.work_unit_code AS owner_work_unit_code,
                       unit.name AS owner_work_unit_name
                FROM logistics.route_event event
                LEFT JOIN platform.security_user owner
                  ON owner.subject_id = event.created_by
                LEFT JOIN platform.work_unit unit
                  ON unit.code = owner.work_unit_code
                """;
        jdbc.sql(projectionSql(sourceSql)).param("domain", "LOGISTICS")
                .param("domainLabel", "物流监测").update();
    }

    /** One set-based statement per domain, with no per-record JDBC round trips.
     * Reconcile against authoritative data on every read; do not introduce a TTL
     * that would hide approval, ownership or period changes.
     */
    private String projectionSql(String sourceSql) {
        return """
                WITH source AS (%s), prepared AS (
                    SELECT source.*,
                        CASE status_code WHEN 'DRAFT' THEN 'TO_FILL'
                            WHEN 'PENDING_REVIEW' THEN 'TO_REVIEW'
                            WHEN 'RETURNED' THEN 'RETURNED' END AS task_status,
                        CASE WHEN status_code IN ('APPROVED', 'VOIDED') THEN 'LOCAL_COMPLETE'
                            WHEN status_code = 'PENDING_REVIEW' THEN 'LOCAL_REVIEW'
                            ELSE 'LOCAL_FILL' END AS node_code,
                        CASE WHEN status_code = 'PENDING_REVIEW' AND NULLIF(trim(owner_work_unit_code), '') IS NOT NULL
                            THEN 'WORK_UNIT' ELSE 'USER' END AS party_type,
                        CASE WHEN status_code = 'PENDING_REVIEW' AND NULLIF(trim(owner_work_unit_code), '') IS NOT NULL
                            THEN owner_work_unit_code ELSE responsible_subject_id END AS party_code,
                        CASE WHEN status_code = 'PENDING_REVIEW' AND NULLIF(trim(owner_work_unit_code), '') IS NOT NULL
                            THEN COALESCE(NULLIF(trim(owner_work_unit_name), ''), owner_work_unit_code)
                            ELSE owner_display_name END AS party_name,
                        period.code AS period_code, period.ends_on
                    FROM source
                    CROSS JOIN LATERAL (
                        SELECT code, ends_on FROM platform.business_period
                        ORDER BY (starts_on <= source.business_date AND ends_on >= source.business_date) DESC,
                                 sort_order DESC
                        LIMIT 1
                    ) period
                ), parties AS (
                    INSERT INTO workflow.responsible_party(party_type, external_code, display_name)
                    SELECT DISTINCT ON (party_type, party_code) party_type, party_code, party_name
                    FROM prepared ORDER BY party_type, party_code, record_id
                    ON CONFLICT (party_type, external_code) DO UPDATE
                    SET display_name = EXCLUDED.display_name
                    WHERE workflow.responsible_party.display_name IS DISTINCT FROM EXCLUDED.display_name
                    RETURNING responsible_party_id, party_type, external_code
                )
                INSERT INTO workflow.work_item(
                    task_name, business_domain, region_code, product_code, business_period_code,
                    due_at, workflow_node_id, status_code, responsible_party_id, completed_at,
                    source_type, source_id, owner_subject_id, owner_work_unit_code)
                SELECT :domainLabel || ' · ' || record_id, :domain, region_code, product_code, period_code,
                    (ends_on + TIME '23:59:59') AT TIME ZONE 'Asia/Shanghai', node.node_id, task_status,
                    party.responsible_party_id, CASE WHEN task_status IS NULL THEN CURRENT_TIMESTAMP END,
                    :domain, record_id, owner_subject_id, owner_work_unit_code
                FROM prepared
                JOIN (SELECT responsible_party_id, party_type, external_code FROM parties
                      UNION SELECT responsible_party_id, party_type, external_code
                      FROM workflow.responsible_party) party ON party.party_type = prepared.party_type AND party.external_code = prepared.party_code
                JOIN workflow.workflow_node node ON node.code = node_code
                ORDER BY record_id
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
                    completed_at = CASE WHEN EXCLUDED.status_code IS NULL
                        THEN COALESCE(workflow.work_item.completed_at, EXCLUDED.completed_at) END,
                    owner_subject_id = EXCLUDED.owner_subject_id,
                    owner_work_unit_code = EXCLUDED.owner_work_unit_code
                WHERE ROW(workflow.work_item.task_name, workflow.work_item.business_domain,
                    workflow.work_item.region_code, workflow.work_item.product_code,
                    workflow.work_item.business_period_code, workflow.work_item.due_at,
                    workflow.work_item.workflow_node_id, workflow.work_item.status_code,
                    workflow.work_item.responsible_party_id, workflow.work_item.completed_at IS NULL,
                    workflow.work_item.owner_subject_id, workflow.work_item.owner_work_unit_code)
                    IS DISTINCT FROM ROW(EXCLUDED.task_name, EXCLUDED.business_domain,
                    EXCLUDED.region_code, EXCLUDED.product_code, EXCLUDED.business_period_code,
                    EXCLUDED.due_at, EXCLUDED.workflow_node_id, EXCLUDED.status_code,
                    EXCLUDED.responsible_party_id, EXCLUDED.completed_at IS NULL,
                    EXCLUDED.owner_subject_id, EXCLUDED.owner_work_unit_code)
                """.formatted(sourceSql);
    }
}
