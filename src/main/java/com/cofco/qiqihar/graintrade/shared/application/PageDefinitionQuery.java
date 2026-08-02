package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@FunctionalInterface
public interface PageDefinitionQuery {

    BusinessPageDefinition find(BusinessPageKey key);

    default boolean allowsListQuery(
            String domain,
            String pageKind,
            String productCode,
            int pageSize,
            Set<String> filterCodes) {
        BusinessPageDefinition definition = find(
                new BusinessPageKey(domain, pageKind, productCode));
        Set<String> allowedFilters = definition.filters().stream()
                .map(BusinessPageDefinition.Filter::code)
                .collect(Collectors.toUnmodifiableSet());
        return definition.pagination().pageSizeOptions().contains(pageSize)
                && allowedFilters.containsAll(filterCodes);
    }

    default boolean allowsListQueryValues(
            String domain, String pageKind, String productCode, int pageSize,
            Map<String, String> filters) {
        BusinessPageDefinition definition = find(new BusinessPageKey(domain, pageKind, productCode));
        if (!definition.pagination().pageSizeOptions().contains(pageSize)) return false;
        Map<String, BusinessPageDefinition.Filter> definitions = definition.filters().stream()
                .collect(Collectors.toUnmodifiableMap(BusinessPageDefinition.Filter::code, item -> item));
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            BusinessPageDefinition.Filter filter = definitions.get(entry.getKey());
            if (filter == null || entry.getValue() == null || entry.getValue().isBlank()) return false;
            if (filter.control() == BusinessPageDefinition.FilterControl.SELECT
                    && filter.options().stream().noneMatch(option -> option.value().equals(entry.getValue()))) return false;
            if (filter.control() == BusinessPageDefinition.FilterControl.DATE) {
                try { LocalDate.parse(entry.getValue()); } catch (DateTimeException exception) { return false; }
            }
        }
        return true;
    }
}
