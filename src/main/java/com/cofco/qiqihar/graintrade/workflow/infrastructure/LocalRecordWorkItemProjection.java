package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.workflow.application.WorkItemProjection;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local-only bridge from the real production/market records to the work-item read model.
 * It is deliberately idempotent and uses a stable source key, so refreshing the work page
 * never duplicates tasks.
 */
@Component
@Profile("local")
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
        refreshRecords("PRODUCTION", "产情监测", "production.production_record", "survey_date");
        refreshRecords("MARKET", "市场监测", "market.market_record", "trade_date");
    }

    private void ensureReferenceData() {
        jdbc.sql("""
                INSERT INTO workflow.workflow_node(code, label)
                VALUES ('LOCAL_FILL', '填报'), ('LOCAL_REVIEW', '审核'), ('LOCAL_COMPLETE', '已完成')
                ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.responsible_party(party_type, external_code, display_name)
                VALUES ('USER', 'wang-yang', '王洋')
                ON CONFLICT (party_type, external_code) DO UPDATE
                SET display_name = EXCLUDED.display_name
                """).update();
    }

    private void refreshRecords(
            String domain, String domainLabel, String table, String dateColumn) {
        String sql = """
                SELECT record_id, product_code, region_code, %s AS business_date, status_code
                FROM %s
                """.formatted(dateColumn, table);
        List<SourceRecord> records = jdbc.sql(sql)
                .query((row, ignored) -> new SourceRecord(
                        row.getString("record_id"),
                        row.getString("product_code"),
                        row.getString("region_code"),
                        row.getObject("business_date", LocalDate.class),
                        row.getString("status_code")))
                .list();
        records.forEach(record -> upsert(domain, domainLabel, record));
    }

    private void upsert(String domain, String domainLabel, SourceRecord record) {
        String status = switch (record.statusCode()) {
            case "DRAFT" -> "TO_FILL";
            case "PENDING_REVIEW" -> "TO_REVIEW";
            case "RETURNED" -> "RETURNED";
            case "APPROVED" -> null;
            default -> throw new IllegalStateException(
                    "Unsupported local workflow source status: " + record.statusCode());
        };
        Period period = period(record.businessDate());
        String nodeCode = status == null ? "LOCAL_COMPLETE"
                : ("TO_REVIEW".equals(status) ? "LOCAL_REVIEW" : "LOCAL_FILL");
        String task = domainLabel + " · " + record.recordId();
        jdbc.sql("""
                INSERT INTO workflow.work_item(
                    task_name, business_domain, region_code, product_code,
                    business_period_code, due_at, workflow_node_id, status_code,
                    responsible_party_id, completed_at, source_type, source_id)
                VALUES (
                    :task, :domain, :region, :product, :period,
                    :dueAt, (SELECT node_id FROM workflow.workflow_node WHERE code = :node),
                    :status, (SELECT responsible_party_id FROM workflow.responsible_party
                              WHERE party_type = 'USER' AND external_code = 'wang-yang'),
                    :completedAt, :sourceType, :sourceId)
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
                    completed_at = EXCLUDED.completed_at
                """)
                .param("task", task)
                .param("domain", domain)
                .param("region", record.regionCode())
                .param("product", record.productCode())
                .param("period", period.code())
                .param("dueAt", period.endsOn().atTime(23, 59, 59).atZone(REPORTING_ZONE).toOffsetDateTime())
                .param("node", nodeCode)
                .param("status", status)
                .param("completedAt", status == null ? OffsetDateTime.now(REPORTING_ZONE) : null)
                .param("sourceType", domain)
                .param("sourceId", record.recordId())
                .update();
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
            LocalDate businessDate, String statusCode) {}

    private record Period(String code, LocalDate endsOn) {}
}
