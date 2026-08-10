package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationReportRepository;
import com.cofco.qiqihar.graintrade.workflow.application.WorkObligationWeeklyReport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkObligationReportRepository implements WorkObligationReportRepository {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    public JdbcWorkObligationReportRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public WorkObligationWeeklyReport findWeekly(Query query, Instant now) {
        OffsetDateTime from = query.weekStart().atStartOfDay(REPORTING_ZONE).toOffsetDateTime();
        OffsetDateTime until = query.weekStart().plusDays(7).atStartOfDay(REPORTING_ZONE).toOffsetDateTime();
        StringBuilder where = new StringBuilder("""
                WHERE item.owner_subject_id IS NOT NULL
                  AND item.due_at >= :from AND item.due_at < :until
                """);
        if (query.subjectId() != null) where.append(" AND item.owner_subject_id=:subjectId");
        if (query.workUnitCode() != null) where.append(" AND item.owner_work_unit_code=:workUnitCode");
        if (query.businessDomain() != null) where.append(" AND item.business_domain=:domain");
        if (query.regionCode() != null) where.append(" AND item.region_code=:regionCode");
        if (query.authorizedRegionCodes().isEmpty()) where.append(" AND 1=0");
        else where.append(" AND item.region_code IN (:authorizedRegions)");

        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT item.work_item_id::text AS work_item_id,
                       owner.subject_id, owner.display_name,
                       unit.code AS work_unit_code, unit.name AS work_unit_name,
                       item.business_domain,
                       CASE item.business_domain
                         WHEN 'PRODUCTION' THEN '产情监测'
                         WHEN 'MARKET' THEN '市场监测'
                         WHEN 'LOGISTICS' THEN '物流监测'
                         WHEN 'SUPPLY' THEN '供需分析'
                         WHEN 'REPORTING' THEN '报表中心'
                         ELSE item.business_domain
                       END AS business_domain_label,
                       item.region_code, region.name AS region_name,
                       COALESCE(product.name,'—') AS product_name,
                       period.name AS business_period,
                       item.due_at, item.completed_at,
                       item.status_code, COALESCE(status.label,'已完成') AS status_label,
                       item.source_type, item.source_id
                FROM workflow.work_item item
                JOIN platform.security_user owner ON owner.subject_id=item.owner_subject_id
                JOIN platform.work_unit unit ON unit.code=item.owner_work_unit_code
                JOIN platform.region region ON region.code=item.region_code
                LEFT JOIN platform.product product ON product.code=item.product_code
                JOIN platform.business_period period ON period.code=item.business_period_code
                LEFT JOIN workflow.work_item_status status ON status.code=item.status_code
                """ + where + " ORDER BY owner.display_name,item.due_at,item.work_item_id")
                .param("from", from)
                .param("until", until);
        if (query.subjectId() != null) statement = statement.param("subjectId", query.subjectId());
        if (query.workUnitCode() != null) statement = statement.param("workUnitCode", query.workUnitCode());
        if (query.businessDomain() != null) statement = statement.param("domain", query.businessDomain());
        if (query.regionCode() != null) statement = statement.param("regionCode", query.regionCode());
        if (!query.authorizedRegionCodes().isEmpty()) {
            statement = statement.param("authorizedRegions", query.authorizedRegionCodes());
        }
        List<WorkObligationWeeklyReport.Row> rows = statement.query((row, ignored) -> {
            OffsetDateTime dueAt = row.getObject("due_at", OffsetDateTime.class);
            OffsetDateTime completedAt = row.getObject("completed_at", OffsetDateTime.class);
            String statusCode = row.getString("status_code");
            Compliance compliance = compliance(statusCode, dueAt, completedAt, now);
            return new WorkObligationWeeklyReport.Row(
                    row.getString("work_item_id"), row.getString("subject_id"),
                    row.getString("display_name"), row.getString("work_unit_code"),
                    row.getString("work_unit_name"), row.getString("business_domain"),
                    row.getString("business_domain_label"), row.getString("region_code"),
                    row.getString("region_name"), row.getString("product_name"),
                    row.getString("business_period"), dueAt, completedAt, statusCode,
                    row.getString("status_label"), compliance.code(), compliance.label(),
                    row.getString("source_type"), row.getString("source_id"));
        }).list();
        WorkObligationWeeklyReport.Summary summary = new WorkObligationWeeklyReport.Summary(
                rows.size(), count(rows, "ON_TIME"), count(rows, "LATE_COMPLETED"),
                count(rows, "OVERDUE_OUTSTANDING"), count(rows, "PENDING"),
                rows.stream().filter(item -> "RETURNED".equals(item.statusCode())).count());
        String scope = rows.isEmpty()
                ? (query.subjectId() == null ? query.workUnitCode() : query.subjectId())
                : (query.subjectId() == null ? rows.getFirst().workUnitName() : rows.getFirst().employeeName());
        return new WorkObligationWeeklyReport(
                query.weekStart(), query.weekStart().plusDays(6), scope, summary, rows);
    }

    @Override
    public String employeeWorkUnit(String subjectId) {
        return jdbc.sql("""
                SELECT work_unit_code FROM platform.security_user
                WHERE subject_id=:subjectId AND enabled
                """).param("subjectId", subjectId).query(String.class).optional().orElse(null);
    }

    @Override
    public void persistExport(Export export) {
        jdbc.sql("""
                INSERT INTO workflow.obligation_report_export(
                    export_id,week_start,week_end,subject_id,work_unit_code,business_domain,
                    region_code,generated_by,generated_at,filename,content_type,content_sha256,content)
                VALUES (CAST(:id AS uuid),:weekStart,:weekEnd,:subjectId,:workUnitCode,:domain,
                    :regionCode,:generatedBy,:generatedAt,:filename,:contentType,:checksum,:content)
                """)
                .param("id", export.id())
                .param("weekStart", export.query().weekStart())
                .param("weekEnd", export.query().weekStart().plusDays(6))
                .param("subjectId", export.query().subjectId(), java.sql.Types.VARCHAR)
                .param("workUnitCode", export.query().workUnitCode(), java.sql.Types.VARCHAR)
                .param("domain", export.query().businessDomain(), java.sql.Types.VARCHAR)
                .param("regionCode", export.query().regionCode(), java.sql.Types.VARCHAR)
                .param("generatedBy", export.generatedBy())
                .param("generatedAt", OffsetDateTime.ofInstant(export.generatedAt(), REPORTING_ZONE),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("filename", export.filename())
                .param("contentType", export.contentType())
                .param("checksum", export.checksum())
                .param("content", export.content())
                .update();
    }

    @Override
    public ExportContent findExport(String exportId) {
        return jdbc.sql("""
                SELECT export_id::text,generated_by,filename,content_type,content
                FROM workflow.obligation_report_export WHERE export_id=CAST(:id AS uuid)
                """).param("id", exportId).query((row, ignored) -> new ExportContent(
                        row.getString("export_id"), row.getString("generated_by"),
                        row.getString("filename"), row.getString("content_type"),
                        row.getBytes("content"))).optional().orElse(null);
    }

    private static long count(List<WorkObligationWeeklyReport.Row> rows, String code) {
        return rows.stream().filter(row -> code.equals(row.complianceCode())).count();
    }

    private static Compliance compliance(
            String statusCode, OffsetDateTime dueAt, OffsetDateTime completedAt, Instant now) {
        if (completedAt != null) {
            return completedAt.isAfter(dueAt)
                    ? new Compliance("LATE_COMPLETED", "逾期完成")
                    : new Compliance("ON_TIME", "按时完成");
        }
        if (dueAt.toInstant().isBefore(now)) {
            return new Compliance("OVERDUE_OUTSTANDING", "已逾期未完成");
        }
        return new Compliance("PENDING", "未到期");
    }

    private record Compliance(String code, String label) {}
}
