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
        assertThat(definition.actions()).isEmpty();
        assertThat(definition.pagination().defaultPageSize()).isEqualTo(20);
        assertThat(definition.pagination().pageSizeOptions()).containsExactly(20, 50, 100);
    }

    @Test
    void doesNotSynthesizeAnUnconfirmedCornQualityDefinition() {
        assertThat(repository.find(new BusinessPageKey("MARKET", "QUALITY", "CORN"))).isEmpty();
    }

    @Test
    void loadsTheProductIndependentWorkflowDefinition() {
        BusinessPageDefinition definition = repository
                .find(new BusinessPageKey("WORKFLOW", "WORK_ITEMS", null))
                .orElseThrow();

        assertThat(definition.title()).isEqualTo("任务列表");
        assertThat(definition.filters()).extracting(BusinessPageDefinition.Filter::label)
                .containsExactly("状态", "业务类型", "地区", "产品");
        assertThat(definition.filters().getFirst().options())
                .extracting(BusinessPageDefinition.Option::label)
                .containsExactly("待填报", "待审核", "退回补充", "异常处理");
        assertThat(definition.columnGroups().getFirst().fields())
                .extracting(BusinessPageDefinition.Field::label)
                .containsExactly(
                        "任务", "业务类型", "地区", "产品", "业务期间", "截止时间",
                        "流程节点", "状态", "责任人");
        assertThat(definition.actions()).isEmpty();
    }

    @Test
    void exposesTheSupplySourceConfirmationAsAnEmployeeFacingAction() {
        BusinessPageDefinition definition = repository
                .find(new BusinessPageKey("SUPPLY", "ACCOUNT", "CORN"))
                .orElseThrow();

        assertThat(definition.actions())
                .filteredOn(action -> action.code().equals("ADJUST"))
                .extracting(BusinessPageDefinition.Action::label)
                .containsExactly("确认数据来源");
    }
}
