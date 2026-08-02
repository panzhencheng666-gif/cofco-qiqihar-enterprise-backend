package com.cofco.qiqihar.graintrade.shared.application;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultPageDefinitionQueryTest {

    @Test
    void reportsAnUnknownDefinitionAsAControlledClientError() {
        DefaultPageDefinitionQuery query = new DefaultPageDefinitionQuery(key -> Optional.empty());

        assertThatThrownBy(() -> query.find(new BusinessPageKey("MARKET", "QUALITY", "CORN")))
                .isInstanceOf(ClientRequestException.class)
                .satisfies(error -> assertThat(((ClientRequestException) error).code())
                        .isEqualTo("PAGE_DEFINITION_NOT_FOUND"));
    }

    @Test
    void ownsTheReadOnlyTransactionBoundary() {
        Transactional transaction = DefaultPageDefinitionQuery.class.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
    }
}
