package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;

@FunctionalInterface
public interface PageDefinitionQuery {

    BusinessPageDefinition find(BusinessPageKey key);
}
