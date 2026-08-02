package com.cofco.qiqihar.graintrade.shared.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionRepository;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPageDefinitionRepository implements PageDefinitionRepository {

    private final JdbcClient jdbc;

    public JdbcPageDefinitionRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<BusinessPageDefinition> find(BusinessPageKey key) {
        Optional<String> title = sql("""
                        SELECT title
                        FROM platform.page_presentation
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        """, key)
                .query(String.class)
                .optional();
        if (title.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new BusinessPageDefinition(
                key,
                title.orElseThrow(),
                breadcrumbs(key),
                filters(key),
                defaultContext(key),
                columnGroups(key),
                actions(key),
                pagination(key)));
    }

    private List<BusinessPageDefinition.Breadcrumb> breadcrumbs(BusinessPageKey key) {
        return sql("""
                        SELECT code, label
                        FROM platform.page_breadcrumb
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY sort_order
                        """, key)
                .query((row, rowNumber) -> new BusinessPageDefinition.Breadcrumb(
                        row.getString("code"), row.getString("label")))
                .list();
    }

    private List<BusinessPageDefinition.Filter> filters(BusinessPageKey key) {
        record FilterRow(String code, String label, String control, String placeholder) {
        }
        return sql("""
                        SELECT code, label, control_type, placeholder
                        FROM platform.page_filter_definition
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY sort_order
                        """, key)
                .query((row, rowNumber) -> new FilterRow(
                        row.getString("code"),
                        row.getString("label"),
                        row.getString("control_type"),
                        row.getString("placeholder")))
                .list()
                .stream()
                .map(row -> new BusinessPageDefinition.Filter(
                        row.code(),
                        row.label(),
                        BusinessPageDefinition.FilterControl.valueOf(row.control()),
                        row.placeholder(),
                        filterOptions(key, row.code())))
                .toList();
    }

    private List<BusinessPageDefinition.Option> filterOptions(BusinessPageKey key, String filterCode) {
        return sql("""
                        SELECT value, label
                        FROM platform.page_filter_option
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                          AND filter_code = :filterCode
                        ORDER BY sort_order
                        """, key)
                .param("filterCode", filterCode)
                .query((row, rowNumber) -> new BusinessPageDefinition.Option(
                        row.getString("value"), row.getString("label")))
                .list();
    }

    private Map<String, String> defaultContext(BusinessPageKey key) {
        Map<String, String> defaults = new LinkedHashMap<>();
        sql("""
                        SELECT filter_code, value
                        FROM platform.page_default_value
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY filter_code
                        """, key)
                .query((row, rowNumber) -> Map.entry(
                        row.getString("filter_code"), row.getString("value")))
                .list()
                .forEach(entry -> defaults.put(entry.getKey(), entry.getValue()));
        return defaults;
    }

    private List<BusinessPageDefinition.ColumnGroup> columnGroups(BusinessPageKey key) {
        record GroupRow(String code, String label) {
        }
        return sql("""
                        SELECT code, label
                        FROM platform.page_column_group
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY sort_order
                        """, key)
                .query((row, rowNumber) -> new GroupRow(
                        row.getString("code"), row.getString("label")))
                .list()
                .stream()
                .map(group -> new BusinessPageDefinition.ColumnGroup(
                        group.code(), group.label(), fields(key, group.code())))
                .toList();
    }

    private List<BusinessPageDefinition.Field> fields(BusinessPageKey key, String groupCode) {
        return sql("""
                        SELECT field.code, field.name, field.value_type, link.unit, link.description
                        FROM platform.page_column_group_field link
                        JOIN platform.field_definition field ON field.code = link.field_code
                        WHERE link.product_code IS NOT DISTINCT FROM :productCode
                          AND link.business_domain = :domain
                          AND link.page_kind = :pageKind
                          AND link.group_code = :groupCode
                        ORDER BY link.sort_order
                        """, key)
                .param("groupCode", groupCode)
                .query((row, rowNumber) -> new BusinessPageDefinition.Field(
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("value_type"),
                        row.getString("unit"),
                        row.getString("description")))
                .list();
    }

    private List<BusinessPageDefinition.Action> actions(BusinessPageKey key) {
        return sql("""
                        SELECT code, label, action_scope
                        FROM platform.page_action
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY sort_order
                        """, key)
                .query((row, rowNumber) -> new BusinessPageDefinition.Action(
                        row.getString("code"),
                        row.getString("label"),
                        BusinessPageDefinition.ActionScope.valueOf(row.getString("action_scope"))))
                .list();
    }

    private BusinessPageDefinition.Pagination pagination(BusinessPageKey key) {
        int defaultPageSize = sql("""
                        SELECT default_page_size
                        FROM platform.page_pagination
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        """, key)
                .query(Integer.class)
                .single();
        List<Integer> sizes = sql("""
                        SELECT page_size
                        FROM platform.page_size_option
                        WHERE product_code IS NOT DISTINCT FROM :productCode
                          AND business_domain = :domain
                          AND page_kind = :pageKind
                        ORDER BY sort_order
                        """, key)
                .query(Integer.class)
                .list();
        return new BusinessPageDefinition.Pagination(defaultPageSize, sizes);
    }

    private JdbcClient.StatementSpec sql(String sql, BusinessPageKey key) {
        return jdbc.sql(sql)
                .param("productCode", key.productCode(), Types.VARCHAR)
                .param("domain", key.domain())
                .param("pageKind", key.pageKind());
    }
}
