package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.ActivityReportExport;
import com.cofco.qiqihar.graintrade.reporting.application.ActivityReportRepository;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcActivityReportRepository implements ActivityReportRepository {
    private final JdbcClient jdbc;

    public JdbcActivityReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot personal(String subjectId, Instant start, Instant cutoff) {
        var identity = jdbc.sql("""
                SELECT user_account.display_name,unit.name work_unit_name
                FROM platform.security_user user_account
                JOIN platform.work_unit unit ON unit.code=user_account.work_unit_code
                WHERE user_account.subject_id=:subject
                """).param("subject", subjectId).query((row, index) ->
                new String[] {row.getString("display_name"), row.getString("work_unit_name")}).single();
        return new Snapshot(identity[0], identity[1], 1,
                events("event.actor_subject_id=:subject", subjectId, start, cutoff));
    }

    @Override
    public Snapshot system(Instant start, Instant cutoff) {
        long effectiveUsers = jdbc.sql("""
                SELECT count(*) FROM platform.security_user
                WHERE enabled AND account_status='ACTIVE' AND employment_status='ACTIVE'
                """).query(Long.class).single();
        return new Snapshot(null, null, effectiveUsers,
                events("user_account.enabled AND user_account.account_status='ACTIVE' "
                        + "AND user_account.employment_status='ACTIVE'", null, start, cutoff));
    }

    private List<EventCount> events(String subjectPredicate, String subjectId,
            Instant start, Instant cutoff) {
        var query = jdbc.sql("""
                SELECT event.action_code,event.aggregate_type,event.work_unit_code,
                       unit.name work_unit_name,count(*) event_count
                FROM platform.business_audit_event event
                JOIN platform.security_user user_account ON user_account.subject_id=event.actor_subject_id
                JOIN platform.work_unit unit ON unit.code=event.work_unit_code
                WHERE event.occurred_at>=:start AND event.occurred_at<:cutoff
                  AND %s
                GROUP BY event.action_code,event.aggregate_type,event.work_unit_code,unit.name
                ORDER BY event.action_code,event.aggregate_type,event.work_unit_code
                """.formatted(subjectPredicate))
                .param("start", Timestamp.from(start)).param("cutoff", Timestamp.from(cutoff));
        if (subjectId != null) query = query.param("subject", subjectId);
        return query.query((row, index) -> new EventCount(
                row.getString("action_code"), row.getString("aggregate_type"),
                row.getString("work_unit_code"), row.getString("work_unit_name"),
                row.getLong("event_count"))).list();
    }

    @Override
    public void saveExport(ExportRecord export) {
        jdbc.sql("""
                INSERT INTO reporting.activity_report_export(
                  export_id,report_kind,period_days,period_start,period_end,event_cutoff,
                  generated_by,generated_at,filename,content_type,content_sha256,content_bytes)
                VALUES (CAST(:id AS uuid),'SYSTEM',:days,:start,:end,:cutoff,
                  :actor,:generatedAt,:filename,:contentType,:sha256,:bytes)
                """).param("id", export.id()).param("days", export.periodDays())
                .param("start", Timestamp.from(export.periodStart()))
                .param("end", Timestamp.from(export.periodEnd()))
                .param("cutoff", Timestamp.from(export.eventCutoff()))
                .param("actor", export.generatedBy())
                .param("generatedAt", Timestamp.from(export.generatedAt()))
                .param("filename", export.filename()).param("contentType", export.contentType())
                .param("sha256", export.sha256()).param("bytes", export.bytes()).update();
    }

    @Override
    public ActivityReportExport.Content exportContent(String exportId, String requestedBy) {
        return jdbc.sql("""
                SELECT filename,content_type,content_bytes
                FROM reporting.activity_report_export
                WHERE export_id=CAST(:id AS uuid)
                """).param("id", exportId).query((row, index) -> new ActivityReportExport.Content(
                row.getString("filename"), row.getString("content_type"), row.getBytes("content_bytes")))
                .optional().orElseThrow(() -> new ClientRequestException(
                        "ACTIVITY_REPORT_EXPORT_NOT_FOUND", "周期总结文档不存在"));
    }
}
