package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultPageDefinitionQuery implements PageDefinitionQuery {

    private final PageDefinitionRepository repository;

    public DefaultPageDefinitionQuery(PageDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public BusinessPageDefinition find(BusinessPageKey key) {
        return repository.find(key).orElseThrow(() -> new ResourceNotFoundException(
                "PAGE_DEFINITION_NOT_FOUND",
                "Requested page definition does not exist"));
    }
}
