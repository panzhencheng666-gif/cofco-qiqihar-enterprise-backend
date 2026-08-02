package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
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
}
