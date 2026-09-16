package com.cofco.qiqihar.graintrade.annotation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class UserMapAnnotationServiceIntegrationTest {
    @Autowired UserMapAnnotationService service;
    @Autowired JdbcClient jdbc;

    @Test
    void keepsExactlyOneAnnotationPerSubjectAndNeverExposesAnotherSubject() {
        user("annotation-a");
        user("annotation-b");

        service.save("annotation-a", new UserMapAnnotationCommand(
                "POINT", decimal("123.10"), decimal("47.10"), null, null,
                "230200", "CITY"));
        assertThat(service.current("annotation-a").orElseThrow().type()).isEqualTo("POINT");
        assertThat(service.current("annotation-b")).isEmpty();

        service.save("annotation-a", new UserMapAnnotationCommand(
                "RECTANGLE", decimal("123.20"), decimal("47.20"),
                decimal("123.80"), decimal("47.90"), "230202", "COUNTY"));

        var current = service.current("annotation-a").orElseThrow();
        assertThat(current.type()).isEqualTo("RECTANGLE");
        assertThat(current.minLongitude()).isEqualByComparingTo("123.20");
        assertThat(current.maxLongitude()).isEqualByComparingTo("123.80");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.user_map_annotation WHERE subject_id='annotation-a'")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT action_code FROM platform.business_audit_event
                WHERE actor_subject_id='annotation-a' AND aggregate_type='USER_MAP_ANNOTATION'
                ORDER BY occurred_at,event_id
                """).query(String.class).list())
                .containsExactly("MAP_ANNOTATION_CREATED","MAP_ANNOTATION_REPLACED");
    }

    @Test
    void deletionSurvivesARepositoryRequery() {
        user("annotation-delete");
        service.save("annotation-delete", new UserMapAnnotationCommand(
                "POINT", decimal("124.10"), decimal("48.10"), null, null,
                null, null));

        assertThat(service.delete("annotation-delete")).isTrue();
        assertThat(service.current("annotation-delete")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.user_map_annotation WHERE subject_id='annotation-delete'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE actor_subject_id='annotation-delete' AND action_code='MAP_ANNOTATION_DELETED'
                """).query(String.class).single()).doesNotContain("124.10","48.10");
    }

    private void user(String subject) {
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('ANNOTATION_TEST','地图标注隔离测试单位',99209) ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:subject,:subject,'ANNOTATION_TEST') ON CONFLICT DO NOTHING
                """).param("subject",subject).update();
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
