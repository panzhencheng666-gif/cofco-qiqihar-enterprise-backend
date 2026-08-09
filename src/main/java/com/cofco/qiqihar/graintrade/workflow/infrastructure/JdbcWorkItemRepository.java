package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.workflow.application.WorkItemRepository;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemScope;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkItemRepository implements WorkItemRepository {

    private static final String FROM = """
            FROM workflow.work_item item
            JOIN platform.region region ON region.code = item.region_code
            LEFT JOIN platform.product product ON product.code = item.product_code
            JOIN platform.business_period period ON period.code = item.business_period_code
            JOIN workflow.workflow_node node ON node.node_id = item.workflow_node_id
            LEFT JOIN workflow.work_item_status status ON status.code = item.status_code
            JOIN workflow.responsible_party party
              ON party.responsible_party_id = item.responsible_party_id
            """;

    private final JdbcClient jdbc;

    public JdbcWorkItemRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public boolean allowsFilters(WorkItemQuery query) {
        return (query.domain() == null || exists("""
                        SELECT EXISTS (
                            SELECT 1 FROM platform.page_filter_option
                            WHERE product_code IS NULL
                              AND business_domain = 'WORKFLOW'
                              AND page_kind = 'WORK_ITEMS'
                              AND filter_code = 'domain'
                              AND value = :value)
                        """, query.domain()))
                && (query.regionId() == null || exists(
                        "SELECT EXISTS (SELECT 1 FROM platform.region WHERE code = :value)",
                        query.regionId()))
                && (query.productCode() == null || exists(
                        "SELECT EXISTS (SELECT 1 FROM platform.product WHERE code = :value)",
                        query.productCode()));
    }

    @Override
    public PagedResult<WorkItem> findPage(WorkItemQuery query) {
        String where = where(query);
        long total = bind(jdbc.sql("SELECT count(*) " + FROM + where), query)
                .query(Long.class)
                .single();
        String order = query.scope() == WorkItemScope.PENDING
                ? " ORDER BY item.due_at, item.work_item_id"
                : " ORDER BY item.completed_at DESC, item.work_item_id DESC";
        List<WorkItem> items = bind(jdbc.sql("""
                        SELECT item.work_item_id::text AS id,
                               item.task_name,
                               item.business_domain,
                               item.region_code,
                               region.name AS region_name,
                               product.name AS product_name,
                               period.code AS business_period_code,
                               period.name AS business_period,
                               item.due_at,
                               node.label AS node_label,
                               item.status_code,
                               status.label AS status_label,
                               party.external_code AS responsible_party_code,
                               party.display_name AS responsible_party,
                               item.source_type,
                               item.source_id
                        """ + FROM + where + order + " LIMIT :pageSize OFFSET :offset"), query)
                .param("pageSize", query.pageSize())
                .param("offset", (long) query.pageNumber() * query.pageSize())
                .query((row, rowNumber) -> new WorkItem(
                        row.getString("id"),
                        row.getString("task_name"),
                        row.getString("business_domain"),
                        row.getString("region_code"),
                        row.getString("region_name"),
                        row.getString("product_name"),
                        row.getString("business_period_code"),
                        row.getString("business_period"),
                        row.getObject("due_at", java.time.OffsetDateTime.class),
                        row.getString("node_label"),
                        row.getString("status_code"),
                        row.getString("status_label"),
                        row.getString("responsible_party_code"),
                        row.getString("responsible_party"),
                        row.getString("source_type"),
                        row.getString("source_id")))
                .list();
        return new PagedResult<>(items, query.pageNumber(), query.pageSize(), total);
    }

    private String where(WorkItemQuery query) {
        StringBuilder sql = new StringBuilder(query.scope() == WorkItemScope.PENDING
                ? " WHERE item.completed_at IS NULL"
                : " WHERE item.completed_at IS NOT NULL");
        if (query.status() != null) sql.append(" AND item.status_code = :status");
        if (query.domain() != null) sql.append(" AND item.business_domain = :domain");
        if (query.regionId() != null) sql.append(" AND item.region_code = :regionId");
        if (query.productCode() != null) sql.append(" AND item.product_code = :productCode");
        if (!query.authorizedRegionCodes().contains("*")) {
            if (query.authorizedRegionCodes().isEmpty()) sql.append(" AND 1=0");
            else sql.append(" AND item.region_code IN (:authorizedRegionCodes)");
        }
        if (!query.assignedSubjectId().isBlank()) {
            sql.append("""
                     AND (
                         (party.party_type = 'USER'
                          AND party.external_code = :assignedSubjectId
                          AND (item.status_code IS DISTINCT FROM 'TO_REVIEW'
                               OR EXISTS (
                                   SELECT 1
                                   FROM platform.security_user_role user_role
                                   JOIN platform.access_role_permission role_permission
                                     ON role_permission.role_code = user_role.role_code
                                   WHERE user_role.subject_id = :assignedSubjectId
                                     AND role_permission.permission_code IN
                                         ('BUSINESS_APPROVE', 'BUSINESS_RETURN'))))
                         OR
                         (party.party_type = 'WORK_UNIT'
                          AND item.status_code = 'TO_REVIEW'
                          AND party.external_code = (
                              SELECT security_user.work_unit_code
                              FROM platform.security_user
                              WHERE security_user.subject_id = :assignedSubjectId
                                AND security_user.enabled)
                          AND EXISTS (
                              SELECT 1
                              FROM platform.security_user_role user_role
                              JOIN platform.access_role_permission role_permission
                                ON role_permission.role_code = user_role.role_code
                              WHERE user_role.subject_id = :assignedSubjectId
                                AND role_permission.permission_code IN
                                    ('BUSINESS_APPROVE', 'BUSINESS_RETURN')))
                     )
                    """);
        }
        return sql.toString();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, WorkItemQuery query) {
        if (query.status() != null) statement = statement.param("status", query.status().name());
        if (query.domain() != null) statement = statement.param("domain", query.domain());
        if (query.regionId() != null) statement = statement.param("regionId", query.regionId());
        if (query.productCode() != null) statement = statement.param("productCode", query.productCode());
        if (!query.authorizedRegionCodes().isEmpty()
                && !query.authorizedRegionCodes().contains("*")) {
            statement = statement.param("authorizedRegionCodes", query.authorizedRegionCodes());
        }
        if (!query.assignedSubjectId().isBlank()) {
            statement = statement.param("assignedSubjectId", query.assignedSubjectId());
        }
        return statement;
    }

    private boolean exists(String sql, String value) {
        return Boolean.TRUE.equals(jdbc.sql(sql)
                .param("value", value)
                .query(Boolean.class)
                .single());
    }
}
