package com.cofco.qiqihar.graintrade.shared.infrastructure;

import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPageDefinitionRepositoryTest {

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private final JdbcPageDefinitionRepository repository =
            new JdbcPageDefinitionRepository(DATABASE.dataSource());

    @BeforeAll
    static void migrateTestDatabase() {
        DATABASE.flyway().migrate();
    }

    @Test
    void loadsTheConfirmedRiceDefinitionWithoutInventingDefaultsOrStatuses() {
        BusinessPageDefinition definition = repository
                .find(new BusinessPageKey("MARKET", "QUALITY", "RICE"))
                .orElseThrow();

        assertThat(definition.title()).isEqualTo("稻谷质量指标");
        assertThat(definition.breadcrumbs()).extracting(BusinessPageDefinition.Breadcrumb::label)
                .containsExactly("市场监测", "稻谷质量指标");
        assertThat(definition.filters()).isEmpty();
        assertThat(definition.defaultContext()).isEmpty();
        assertThat(definition.columnGroups()).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("质量指标");
            assertThat(group.fields()).extracting(BusinessPageDefinition.Field::label)
                    .containsExactly("水分", "出米率", "出糙率", "杂质");
        });
        assertThat(definition.actions()).extracting(BusinessPageDefinition.Action::label)
                .containsExactly("查看");
        assertThat(definition.pagination().defaultPageSize()).isEqualTo(20);
        assertThat(definition.pagination().pageSizeOptions()).containsExactly(20, 50, 100);
    }

    @Test
    void doesNotSynthesizeAnUnconfirmedCornQualityDefinition() {
        assertThat(repository.find(new BusinessPageKey("MARKET", "QUALITY", "CORN"))).isEmpty();
    }
}
