package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.util.Optional;

@FunctionalInterface
public interface PageDefinitionRepository {

    Optional<BusinessPageDefinition> find(BusinessPageKey key);
}
