package com.cofco.qiqihar.graintrade.shared.domain;

import java.util.List;
import java.util.Map;

public record BusinessPageDefinition(
        BusinessPageKey key,
        String title,
        List<Breadcrumb> breadcrumbs,
        List<Filter> filters,
        Map<String, String> defaultContext,
        List<ColumnGroup> columnGroups,
        List<Action> actions,
        Pagination pagination) {

    public BusinessPageDefinition {
        breadcrumbs = List.copyOf(breadcrumbs);
        filters = List.copyOf(filters);
        defaultContext = Map.copyOf(defaultContext);
        columnGroups = List.copyOf(columnGroups);
        actions = List.copyOf(actions);
    }

    public record Breadcrumb(String code, String label) {
    }

    public record Option(String value, String label) {
    }

    public record Filter(
            String code,
            String label,
            FilterControl control,
            String placeholder,
            List<Option> options) {

        public Filter {
            options = List.copyOf(options);
        }
    }

    public enum FilterControl {
        TEXT,
        DATE,
        SELECT,
        REGION_HIERARCHY
    }

    public record Field(String code, String label, String valueType, String unit, String description) {
    }

    public record ColumnGroup(String code, String label, List<Field> fields) {

        public ColumnGroup {
            fields = List.copyOf(fields);
        }
    }

    public record Action(String code, String label, ActionScope scope) {
    }

    public enum ActionScope {
        PAGE,
        ROW
    }

    public record Pagination(int defaultPageSize, List<Integer> pageSizeOptions) {

        public Pagination {
            pageSizeOptions = List.copyOf(pageSizeOptions);
        }
    }
}
