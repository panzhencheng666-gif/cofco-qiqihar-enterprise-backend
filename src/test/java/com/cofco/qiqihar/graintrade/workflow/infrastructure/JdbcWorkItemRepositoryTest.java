package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkItemRepositoryTest {

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private final JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
    private final JdbcWorkItemRepository repository = new JdbcWorkItemRepository(DATABASE.dataSource());

    @BeforeEach
    void insertFixtures() {
        DATABASE.flyway().migrate();
        jdbc.sql("""
                INSERT INTO platform.business_period (code, name, starts_on, ends_on, sort_order)
                VALUES ('WORK_TEST_PERIOD', '2026年8月', DATE '2026-08-01', DATE '2026-08-31', 9900)
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.workflow_node (code, label)
                VALUES ('WORK_TEST_NODE', '经营部复核')
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.responsible_party
                    (party_type, external_code, display_name)
                VALUES ('USER', 'WORK_TEST_USER', '李明')
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.work_item
                    (task_name, business_domain, region_code, product_code,
                     business_period_code, due_at, workflow_node_id, status_code,
                     responsible_party_id, completed_at, source_type, source_id)
                SELECT '待审核任务', 'MARKET', '230202', 'SOYBEAN', 'WORK_TEST_PERIOD',
                       TIMESTAMPTZ '2026-08-10 09:00:00+08', node_id, 'TO_REVIEW',
                       responsible_party_id, NULL, 'MARKET', 'market-source-record-1'
                FROM workflow.workflow_node CROSS JOIN workflow.responsible_party
                WHERE code = 'WORK_TEST_NODE' AND external_code = 'WORK_TEST_USER'
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.work_item
                    (task_name, business_domain, region_code, product_code,
                     business_period_code, due_at, workflow_node_id, status_code,
                     responsible_party_id, completed_at)
                SELECT '已处理任务', 'PRODUCTION', '230203', 'CORN', 'WORK_TEST_PERIOD',
                       TIMESTAMPTZ '2026-08-09 09:00:00+08', node_id, NULL,
                       responsible_party_id, TIMESTAMPTZ '2026-08-08 12:00:00+08'
                FROM workflow.workflow_node CROSS JOIN workflow.responsible_party
                WHERE code = 'WORK_TEST_NODE' AND external_code = 'WORK_TEST_USER'
                """).update();
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.sql("DELETE FROM workflow.work_item").update();
        jdbc.sql("DELETE FROM workflow.responsible_party WHERE external_code = 'WORK_TEST_USER'").update();
        jdbc.sql("DELETE FROM workflow.workflow_node WHERE code = 'WORK_TEST_NODE'").update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code = 'WORK_TEST_PERIOD'").update();
    }

    @Test
    void returnsServerPagedPendingItemsWithDatabaseLabels() {
        var page = repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, "TO_REVIEW", "MARKET", "230202", "SOYBEAN", 0, 20));

        assertThat(page.totalElements()).isOne();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.task()).isEqualTo("待审核任务");
            assertThat(item.domain()).isEqualTo("MARKET");
            assertThat(item.region()).isEqualTo("龙沙区");
            assertThat(item.product()).isEqualTo("大豆");
            assertThat(item.businessPeriod()).isEqualTo("2026年8月");
            assertThat(item.workflowNode()).isEqualTo("经营部复核");
            assertThat(item.status()).isEqualTo("待审核");
            assertThat(item.responsibleParty()).isEqualTo("李明");
            assertThat(item.sourceType()).isEqualTo("MARKET");
            assertThat(item.sourceId()).isEqualTo("market-source-record-1");
        });
    }

    @Test
    void returnsCompletedItemsWithoutUsingAPendingStatus() {
        var page = repository.findPage(WorkItemQuery.of(
                WorkItemScope.COMPLETED, null, null, null, null, 0, 20));

        assertThat(page.totalElements()).isOne();
        assertThat(page.items().getFirst().task()).isEqualTo("已处理任务");
        assertThat(page.items().getFirst().status()).isNull();
    }

    @Test
    void usesALongOffsetForTheLargestSupportedPageNumber() {
        var page = repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, Integer.MAX_VALUE, 100));

        assertThat(page.items()).isEmpty();
        assertThat(page.pageNumber()).isEqualTo(Integer.MAX_VALUE);
        assertThat(page.pageSize()).isEqualTo(100);
        assertThat(page.totalElements()).isOne();
    }
}
