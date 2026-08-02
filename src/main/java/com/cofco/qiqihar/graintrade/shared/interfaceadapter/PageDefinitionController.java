package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PageDefinitionController {

    private final PageDefinitionQuery query;

    public PageDefinitionController(PageDefinitionQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/page-definitions/{domain}/{pageKind}")
    ApiResponse<PageDefinitionResponse> definition(
            @PathVariable String domain,
            @PathVariable String pageKind,
            @RequestParam String productCode) {
        BusinessPageDefinition definition = query.find(new BusinessPageKey(domain, pageKind, productCode));
        return new ApiResponse<>(PageDefinitionResponse.from(definition));
    }

    record PageDefinitionResponse(
            String domain,
            String pageKind,
            String productCode,
            String title,
            List<BreadcrumbResponse> breadcrumbs,
            List<FilterResponse> filters,
            Map<String, String> defaultContext,
            List<ColumnGroupResponse> columnGroups,
            List<ActionResponse> actions,
            PaginationResponse pagination) {

        static PageDefinitionResponse from(BusinessPageDefinition definition) {
            return new PageDefinitionResponse(
                    definition.key().domain(),
                    definition.key().pageKind(),
                    definition.key().productCode(),
                    definition.title(),
                    definition.breadcrumbs().stream().map(BreadcrumbResponse::from).toList(),
                    definition.filters().stream().map(FilterResponse::from).toList(),
                    definition.defaultContext(),
                    definition.columnGroups().stream().map(ColumnGroupResponse::from).toList(),
                    definition.actions().stream().map(ActionResponse::from).toList(),
                    PaginationResponse.from(definition.pagination()));
        }
    }

    record BreadcrumbResponse(String code, String label) {
        static BreadcrumbResponse from(BusinessPageDefinition.Breadcrumb breadcrumb) {
            return new BreadcrumbResponse(breadcrumb.code(), breadcrumb.label());
        }
    }

    record OptionResponse(String value, String label) {
        static OptionResponse from(BusinessPageDefinition.Option option) {
            return new OptionResponse(option.value(), option.label());
        }
    }

    record FilterResponse(
            String code,
            String label,
            String control,
            String placeholder,
            List<OptionResponse> options) {
        static FilterResponse from(BusinessPageDefinition.Filter filter) {
            return new FilterResponse(
                    filter.code(),
                    filter.label(),
                    toKebabCase(filter.control().name()),
                    filter.placeholder(),
                    filter.options().stream().map(OptionResponse::from).toList());
        }
    }

    record FieldResponse(String code, String label, String valueType, String unit, String description) {
        static FieldResponse from(BusinessPageDefinition.Field field) {
            return new FieldResponse(
                    field.code(), field.label(), field.valueType(), field.unit(), field.description());
        }
    }

    record ColumnGroupResponse(String code, String label, List<FieldResponse> fields) {
        static ColumnGroupResponse from(BusinessPageDefinition.ColumnGroup group) {
            return new ColumnGroupResponse(
                    group.code(), group.label(), group.fields().stream().map(FieldResponse::from).toList());
        }
    }

    record ActionResponse(String code, String label, String scope) {
        static ActionResponse from(BusinessPageDefinition.Action action) {
            return new ActionResponse(action.code(), action.label(), action.scope().name().toLowerCase());
        }
    }

    record PaginationResponse(int defaultPageSize, List<Integer> pageSizeOptions) {
        static PaginationResponse from(BusinessPageDefinition.Pagination pagination) {
            return new PaginationResponse(pagination.defaultPageSize(), pagination.pageSizeOptions());
        }
    }

    private static String toKebabCase(String value) {
        return value.toLowerCase().replace('_', '-');
    }
}
