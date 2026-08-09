package com.cofco.qiqihar.graintrade.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemScope;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class LocalRecordWorkItemProjectionTest {

    private static final String EVENT_ID = "70000000-0000-0000-0000-000000000031";
    private static final String ORIGIN_NODE = "WORKFLOW_LOGISTICS_ORIGIN";
    private static final String DESTINATION_NODE = "WORKFLOW_LOGISTICS_DESTINATION";
    private static final String WORK_UNIT = "WORKFLOW_TEST_UNIT";
    private static final String OWNER = "workflow-test-owner";
    private static final String COLLEAGUE = "workflow-test-colleague";
    private static final String REVIEWER = "workflow-test-reviewer";

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private final JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
    private final LocalRecordWorkItemProjection projection = new LocalRecordWorkItemProjection(jdbc);
    private final JdbcWorkItemRepository repository = new JdbcWorkItemRepository(DATABASE.dataSource());

    @BeforeEach
    void insertPendingLogisticsRecord() {
        DATABASE.flyway().migrate();
        deleteFixtures();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code, name, sort_order)
                VALUES (:unit, '工作流任务测试单位', 9880)
                """).param("unit", WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id, display_name, work_unit_code)
                VALUES (:owner, '物流填报员工', :unit),
                       (:colleague, '同单位填报员工', :unit),
                       (:reviewer, '物流审核员工', :unit)
                """)
                .param("owner", OWNER)
                .param("colleague", COLLEAGUE)
                .param("reviewer", REVIEWER)
                .param("unit", WORK_UNIT)
                .update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id, role_code)
                VALUES (:owner, 'BUSINESS_OPERATOR'),
                       (:colleague, 'BUSINESS_OPERATOR'),
                       (:reviewer, 'BUSINESS_REVIEWER')
                """)
                .param("owner", OWNER)
                .param("colleague", COLLEAGUE)
                .param("reviewer", REVIEWER)
                .update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(
                    node_code, node_name, node_type_code, region_code, active)
                VALUES
                    (:origin, '工作流测试起运节点', 'RAIL_NODE', '230202', true),
                    (:destination, '工作流测试到达节点', 'ROAD_NODE', '230203', true)
                """)
                .param("origin", ORIGIN_NODE)
                .param("destination", DESTINATION_NODE)
                .update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                    event_id, product_code, monitoring_period_code, collection_date,
                    reported_at, origin_region_code, origin_node_id,
                    origin_node_code, destination_region_code, destination_node_id,
                    destination_node_code,
                    transport_mode_code, direction_code, source_organization,
                    reporter, status_code, version, created_by, last_modified_by,
                    created_at, updated_at)
                SELECT CAST(:id AS uuid), 'CORN', period.code, DATE '2026-08-08',
                       :now, '230202', origin.node_id, origin.node_code,
                       '230203', destination.node_id, destination.node_code,
                       'RAIL', 'OUTFLOW', '工作流测试单位', '工作流测试员工',
                       'PENDING_REVIEW', 0, :owner, :owner, :now, :now
                FROM platform.business_period period
                JOIN logistics.logistics_node origin ON origin.node_code = :origin
                JOIN logistics.logistics_node destination ON destination.node_code = :destination
                WHERE period.starts_on <= DATE '2026-08-08'
                  AND period.ends_on >= DATE '2026-08-08'
                ORDER BY period.sort_order DESC
                LIMIT 1
                """)
                .param("id", EVENT_ID)
                .param("now", OffsetDateTime.parse("2026-08-08T10:00:00+08:00"))
                .param("origin", ORIGIN_NODE)
                .param("destination", DESTINATION_NODE)
                .param("owner", OWNER)
                .update();
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.sql("DELETE FROM workflow.work_item WHERE source_type = 'LOGISTICS' AND source_id = :id")
                .param("id", EVENT_ID)
                .update();
        jdbc.sql("DELETE FROM logistics.route_event WHERE event_id = CAST(:id AS uuid)")
                .param("id", EVENT_ID)
                .update();
        jdbc.sql("DELETE FROM logistics.logistics_node WHERE node_code IN (:nodes)")
                .param("nodes", java.util.List.of(ORIGIN_NODE, DESTINATION_NODE))
                .update();
        jdbc.sql("DELETE FROM workflow.responsible_party WHERE external_code IN (:parties)")
                .param("parties", java.util.List.of(OWNER, WORK_UNIT)).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id IN (:subjects)")
                .param("subjects", java.util.List.of(OWNER, COLLEAGUE, REVIEWER)).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id IN (:subjects)")
                .param("subjects", java.util.List.of(OWNER, COLLEAGUE, REVIEWER)).update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code = :unit")
                .param("unit", WORK_UNIT).update();
    }

    @Test
    void projectsLogisticsReviewWorkAgainstItsOriginalSourceAndResponsibleRegion() {
        projection.refresh();

        Map<String, Object> item = jdbc.sql("""
                SELECT item.business_domain, item.region_code, item.status_code,
                       item.source_type, item.source_id, item.completed_at,
                       party.party_type, party.external_code
                FROM workflow.work_item item
                JOIN workflow.responsible_party party
                  ON party.responsible_party_id = item.responsible_party_id
                WHERE item.source_type = 'LOGISTICS' AND item.source_id = :id
                """)
                .param("id", EVENT_ID)
                .query()
                .singleRow();

        assertThat(item)
                .containsEntry("business_domain", "MARKET")
                .containsEntry("region_code", "230202")
                .containsEntry("status_code", "TO_REVIEW")
                .containsEntry("source_type", "LOGISTICS")
                .containsEntry("source_id", EVENT_ID)
                .containsEntry("party_type", "WORK_UNIT")
                .containsEntry("external_code", WORK_UNIT);
        assertThat(item.get("completed_at")).isNull();

        assertThat(repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, 0, 20).assignedTo(OWNER)).items())
                .isEmpty();
        assertThat(repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, 0, 20).assignedTo(COLLEAGUE)).items())
                .isEmpty();
        assertThat(repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, 0, 20).assignedTo(REVIEWER)).items())
                .singleElement().satisfies(work -> assertThat(work.sourceId()).isEqualTo(EVENT_ID));

        jdbc.sql("""
                UPDATE logistics.route_event
                SET status_code = 'RETURNED', version = version + 1,
                    return_reason = '请补充物流来源说明',
                    updated_at = :now, last_modified_by = :reviewer
                WHERE event_id = CAST(:id AS uuid)
                """)
                .param("id", EVENT_ID)
                .param("reviewer", REVIEWER)
                .param("now", OffsetDateTime.parse("2026-08-08T10:30:00+08:00"))
                .update();

        projection.refresh();

        assertThat(repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, 0, 20).assignedTo(OWNER)).items())
                .singleElement().satisfies(work -> {
                    assertThat(work.status()).isEqualTo("退回补充");
                    assertThat(work.sourceId()).isEqualTo(EVENT_ID);
                });
        assertThat(repository.findPage(WorkItemQuery.of(
                WorkItemScope.PENDING, null, null, null, null, 0, 20).assignedTo(REVIEWER)).items())
                .isEmpty();

        jdbc.sql("""
                UPDATE logistics.route_event
                SET status_code = 'APPROVED', version = version + 1,
                    return_reason = NULL,
                    updated_at = :now, last_modified_by = :reviewer
                WHERE event_id = CAST(:id AS uuid)
                """)
                .param("id", EVENT_ID)
                .param("reviewer", REVIEWER)
                .param("now", OffsetDateTime.parse("2026-08-08T11:00:00+08:00"))
                .update();

        projection.refresh();

        Map<String, Object> completed = jdbc.sql("""
                SELECT item.status_code, item.completed_at,
                       party.party_type, party.external_code
                FROM workflow.work_item item
                JOIN workflow.responsible_party party
                  ON party.responsible_party_id = item.responsible_party_id
                WHERE item.source_type = 'LOGISTICS' AND item.source_id = :id
                """)
                .param("id", EVENT_ID)
                .query()
                .singleRow();
        assertThat(completed.get("status_code")).isNull();
        assertThat(completed.get("completed_at")).isNotNull();
        assertThat(completed)
                .containsEntry("party_type", "USER")
                .containsEntry("external_code", OWNER);
    }
}
