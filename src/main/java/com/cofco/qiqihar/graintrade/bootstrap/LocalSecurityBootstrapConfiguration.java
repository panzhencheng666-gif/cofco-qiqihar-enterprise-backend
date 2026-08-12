package com.cofco.qiqihar.graintrade.bootstrap;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Repeatable local-only identity bootstrap; production profiles never create this subject. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalSecurityBootstrapConfiguration {

    @Bean
    ApplicationRunner localSecurityBootstrap(JdbcClient jdbc) {
        return args -> {
            jdbc.sql("""
                    INSERT INTO platform.work_unit(code, name, sort_order)
                    SELECT 'LOCAL_DEV', '平台运营管理部', COALESCE(MAX(sort_order), 0) + 1
                    FROM platform.work_unit
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, active = true
                    """).update();
            jdbc.sql("""
                    DELETE FROM platform.work_unit_region_scope
                    WHERE work_unit_code = 'LOCAL_DEV'
                    """).update();
            jdbc.sql("""
                    WITH preferred AS (
                        SELECT scope.region_code
                        FROM platform.monitoring_scope_region scope
                        JOIN platform.region region ON region.code=scope.region_code
                        WHERE scope.scope_code='FORMAL_BUSINESS' AND scope.included
                          AND region.administrative_level='TOWNSHIP'
                    ), eligible AS (
                        SELECT region_code FROM preferred
                        UNION ALL
                        SELECT scope.region_code
                        FROM platform.monitoring_scope_region scope
                        WHERE scope.scope_code='FORMAL_BUSINESS' AND scope.included
                          AND NOT EXISTS (SELECT 1 FROM preferred)
                    )
                    INSERT INTO platform.work_unit_region_scope(work_unit_code, region_code)
                    SELECT 'LOCAL_DEV', region_code FROM eligible
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user(subject_id, display_name, work_unit_code)
                    VALUES ('wang-yang', '王洋', 'LOCAL_DEV')
                    ON CONFLICT (subject_id) DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        work_unit_code = EXCLUDED.work_unit_code,
                        enabled = true
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_role(subject_id, role_code)
                    VALUES ('wang-yang', 'SYSTEM_ADMIN')
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    UPDATE platform.security_user_region_scope scope
                       SET valid_until=now(),last_reviewed_at=now(),review_due_at=now()+interval '90 days'
                     WHERE scope.subject_id='wang-yang'
                       AND now()>=scope.valid_from
                       AND (scope.valid_until IS NULL OR now()<scope.valid_until)
                       AND NOT EXISTS (
                           SELECT 1 FROM platform.work_unit_region_scope eligible
                           WHERE eligible.work_unit_code='LOCAL_DEV'
                             AND eligible.region_code=scope.region_code)
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_region_scope(
                        subject_id,region_code,valid_from,granted_by,granted_at,last_reviewed_at,review_due_at)
                    SELECT 'wang-yang',eligible.region_code,now(),'wang-yang',now(),now(),now()+interval '90 days'
                    FROM platform.work_unit_region_scope eligible
                    WHERE eligible.work_unit_code='LOCAL_DEV'
                      AND NOT EXISTS (
                          SELECT 1 FROM platform.security_user_region_scope active_scope
                          WHERE active_scope.subject_id='wang-yang'
                            AND active_scope.region_code=eligible.region_code
                            AND now()>=active_scope.valid_from
                            AND (active_scope.valid_until IS NULL OR now()<active_scope.valid_until))
                    """).update();
        };
    }
}
