package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportJobHistoryReader;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcImportJobHistoryReader implements ImportJobHistoryReader {
    private final JdbcClient jdbc;

    public JdbcImportJobHistoryReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResult<ImportJobView> findPage(
            String subjectId, String workUnitCode, String domainCode,
            int pageNumber, int pageSize) {
        String correctionSourcePrefix = domainCode + "-RETURNED-CORRECTION-V1:%";
        long total = jdbc.sql("""
                SELECT count(*) FROM platform.import_job job
                WHERE job.requested_by=:subjectId
                  AND job.work_unit_code=:workUnitCode
                  AND job.domain_code=:domainCode
                  AND job.retry_of_import_job_id IS NULL
                  AND job.source_content NOT LIKE :correctionSourcePrefix
                """).param("subjectId", subjectId).param("workUnitCode", workUnitCode)
                .param("domainCode", domainCode)
                .param("correctionSourcePrefix", correctionSourcePrefix)
                .query(Long.class).single();
        List<ImportJobView> items = jdbc.sql("""
                WITH RECURSIVE job_tree(root_id,attempt_id,depth) AS (
                  SELECT job.import_job_id,job.import_job_id,0
                    FROM platform.import_job job
                   WHERE job.requested_by=:subjectId
                     AND job.work_unit_code=:workUnitCode
                     AND job.domain_code=:domainCode
                     AND job.retry_of_import_job_id IS NULL
                     AND job.source_content NOT LIKE :correctionSourcePrefix
                  UNION ALL
                  SELECT tree.root_id,child.import_job_id,tree.depth+1
                    FROM job_tree tree
                    JOIN platform.import_job child
                      ON child.retry_of_import_job_id=tree.attempt_id
                   WHERE child.requested_by=:subjectId
                     AND child.work_unit_code=:workUnitCode
                     AND child.domain_code=:domainCode
                     AND child.source_content NOT LIKE :correctionSourcePrefix
                ),
                root_activity AS (
                  SELECT tree.root_id,max(attempt.created_at) AS activity_at
                    FROM job_tree tree
                    JOIN platform.import_job attempt ON attempt.import_job_id=tree.attempt_id
                   GROUP BY tree.root_id
                ),
                root_page AS (
                  SELECT activity.root_id,activity.activity_at
                    FROM root_activity activity
                   ORDER BY activity.activity_at DESC,activity.root_id DESC
                   LIMIT :pageSize OFFSET :offset
                ),
                page_attempt AS (
                  SELECT tree.root_id,tree.depth,attempt.*
                    FROM job_tree tree
                    JOIN root_page page ON page.root_id=tree.root_id
                    JOIN platform.import_job attempt ON attempt.import_job_id=tree.attempt_id
                ),
                latest_attempt AS (
                  SELECT DISTINCT ON (attempt.root_id)
                    attempt.root_id,attempt.import_job_id AS action_job_id,attempt.status_code,
                    attempt.created_at,attempt.started_at,attempt.completed_at,attempt.attempt_count,
                    attempt.failure_code,attempt.failure_message
                    FROM page_attempt attempt
                   ORDER BY attempt.root_id,attempt.depth DESC,attempt.created_at DESC,
                            attempt.import_job_id DESC
                ),
                ranked_rows AS (
                  SELECT attempt.root_id,row_result.*,
                    row_number() OVER (
                      PARTITION BY attempt.root_id,row_result.row_number
                      ORDER BY attempt.depth DESC,attempt.created_at DESC,
                               attempt.import_job_id DESC) AS outcome_rank
                    FROM page_attempt attempt
                    JOIN platform.import_row_result row_result
                      ON row_result.import_job_id=attempt.import_job_id
                ),
                latest_rows AS (
                  SELECT * FROM ranked_rows WHERE outcome_rank=1
                ),
                scope_products AS (
                  SELECT row_result.root_id,
                    nullif(btrim(row_result.row_data->>'productCode'),'') AS product_code
                    FROM latest_rows row_result
                  UNION
                  SELECT attempt.root_id,
                    nullif(btrim(convert_from(
                      decode(substring(attempt.source_content
                        FROM length('GOVERNED-DRAFT-V1:') + 1),'base64'),
                      'UTF8')::jsonb->>'productCode'),'') AS product_code
                    FROM page_attempt attempt
                   WHERE attempt.source_content ~
                         '^GOVERNED-DRAFT-V1:[A-Za-z0-9+/]+={0,2}$'
                     AND mod(length(substring(attempt.source_content
                       FROM length('GOVERNED-DRAFT-V1:') + 1)),4)=0
                ),
                scope_periods AS (
                  SELECT row_result.root_id,
                    CASE
                      WHEN nullif(btrim(row_result.row_data->>'surveyYear'),'') IS NULL THEN NULL
                      WHEN nullif(btrim(row_result.row_data->>'surveyMonth'),'') IS NULL
                        THEN btrim(row_result.row_data->>'surveyYear')
                      WHEN btrim(row_result.row_data->>'surveyMonth') ~ '^[0-9]{1,2}$'
                        THEN btrim(row_result.row_data->>'surveyYear') || '-' ||
                          lpad(btrim(row_result.row_data->>'surveyMonth'),2,'0')
                      ELSE btrim(row_result.row_data->>'surveyYear') || '-' ||
                        btrim(row_result.row_data->>'surveyMonth')
                    END AS survey_period
                    FROM latest_rows row_result
                )
                SELECT root.import_job_id::text,latest.action_job_id::text,root.domain_code,
                  CASE
                    WHEN latest.status_code IN ('QUEUED','PROCESSING','FAILED')
                      THEN latest.status_code
                    WHEN count(*) FILTER (WHERE row_result.outcome_code='ERROR')>0
                      THEN 'COMPLETED_WITH_ERRORS'
                    ELSE 'COMPLETED'
                  END AS status_code,
                  count(*) FILTER (WHERE row_result.outcome_code='IMPORTED')::integer AS imported_rows,
                  count(*) FILTER (WHERE row_result.outcome_code='ERROR')::integer AS failed_rows,
                  count(*) FILTER (WHERE row_result.warning_code IS NOT NULL)::integer AS warning_rows,
                  (SELECT string_agg(DISTINCT product.product_code,',' ORDER BY product.product_code)
                     FROM scope_products product
                    WHERE product.root_id=root.import_job_id
                      AND product.product_code IS NOT NULL) AS product_codes,
                  (SELECT string_agg(DISTINCT period.survey_period,',' ORDER BY period.survey_period)
                     FROM scope_periods period
                    WHERE period.root_id=root.import_job_id
                      AND period.survey_period IS NOT NULL) AS survey_periods,
                  NULL::text AS retry_of_import_job_id,root.created_at,root.started_at,
                  latest.completed_at,latest.attempt_count,latest.failure_code,latest.failure_message
                  FROM root_page page
                  JOIN platform.import_job root ON root.import_job_id=page.root_id
                  JOIN latest_attempt latest ON latest.root_id=page.root_id
                  LEFT JOIN latest_rows row_result ON row_result.root_id=page.root_id
                 GROUP BY page.activity_at,root.import_job_id,latest.action_job_id,
                          latest.status_code,latest.completed_at,latest.attempt_count,
                          latest.failure_code,latest.failure_message
                 ORDER BY page.activity_at DESC,root.import_job_id DESC
                """).param("subjectId", subjectId).param("workUnitCode", workUnitCode)
                .param("domainCode", domainCode)
                .param("correctionSourcePrefix", correctionSourcePrefix)
                .param("pageSize", pageSize)
                .param("offset", Math.multiplyExact((long) pageNumber, pageSize))
                .query((row, index) -> new ImportJobView(
                        UUID.fromString(row.getString("import_job_id")),
                        UUID.fromString(row.getString("action_job_id")),
                        row.getString("domain_code"), row.getString("status_code"),
                        row.getInt("imported_rows"), row.getInt("failed_rows"),
                        row.getInt("warning_rows"),
                        values(row.getString("product_codes")), values(row.getString("survey_periods")),
                        uuid(row.getString("retry_of_import_job_id")),
                        row.getTimestamp("created_at").toInstant(),
                        instant(row.getTimestamp("started_at")),
                        instant(row.getTimestamp("completed_at")),
                        row.getInt("attempt_count"), row.getString("failure_code"),
                        row.getString("failure_message")))
                .list();
        return new PagedResult<>(items, pageNumber, pageSize, total);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static List<String> values(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
    }
}
