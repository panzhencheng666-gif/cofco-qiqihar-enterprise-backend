package com.cofco.qiqihar.graintrade.bootstrap;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Repeatable local-only identity bootstrap; production profiles never create this subject. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalSecurityBootstrapConfiguration {

    @Bean
    ApplicationRunner localSecurityBootstrap(JdbcClient jdbc,TransactionTemplate transactions) {
        return args -> transactions.executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO platform.work_unit(code, name, sort_order)
                    SELECT 'LOCAL_DEV', '平台运营管理部', COALESCE(MAX(sort_order), 0) + 1
                    FROM platform.work_unit
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, active = true
                    """).update();
            jdbc.sql("""
                    WITH current_order AS (
                      SELECT COALESCE(MAX(sort_order), 0) AS value FROM platform.work_unit
                    ), formal_unit(code, name, offset_value) AS (
                      VALUES
                        ('QIQIHAR_BUSINESS', '齐齐哈尔经营部', 1),
                        ('NEHE_DEPOT', '讷河库', 2),
                        ('KESHAN_DEPOT', '克山库', 3),
                        ('KEDONG_DEPOT', '克东库', 4),
                        ('LONGZHEN_DEPOT', '龙镇库', 5),
                        ('CHENGJISIHAN_DEPOT', '成吉思汗库', 6)
                    )
                    INSERT INTO platform.work_unit(code, name, sort_order)
                    SELECT formal_unit.code, formal_unit.name, current_order.value + formal_unit.offset_value
                    FROM formal_unit CROSS JOIN current_order
                    ON CONFLICT (code) DO UPDATE
                    SET name = EXCLUDED.name, active = true
                    """).update();
            jdbc.sql("""
                    DELETE FROM platform.work_unit_region_scope
                    WHERE work_unit_code IN (
                      'LOCAL_DEV','QIQIHAR_BUSINESS','NEHE_DEPOT','KESHAN_DEPOT',
                      'KEDONG_DEPOT','LONGZHEN_DEPOT','CHENGJISIHAN_DEPOT')
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.work_unit_region_scope(work_unit_code, region_code)
                    VALUES
                      ('QIQIHAR_BUSINESS', '230200'),
                      ('QIQIHAR_BUSINESS', '231100'),
                      ('QIQIHAR_BUSINESS', '150700'),
                      ('QIQIHAR_BUSINESS', '232700'),
                      ('NEHE_DEPOT', '230281'),
                      ('KESHAN_DEPOT', '230229'),
                      ('KEDONG_DEPOT', '230230'),
                      ('LONGZHEN_DEPOT', '231182'),
                      ('CHENGJISIHAN_DEPOT', '150783')
                    """).update();
            jdbc.sql("""
                    UPDATE platform.security_user
                    SET work_unit_code = 'QIQIHAR_BUSINESS'
                    WHERE work_unit_code = 'LOCAL_DEV'
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user(subject_id, display_name, work_unit_code)
                    VALUES ('wang-yang', '吴雨桐', 'QIQIHAR_BUSINESS')
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
                    INSERT INTO platform.security_user_role(subject_id, role_code)
                    VALUES ('wang-yang', 'ACCOUNT_OWNER')
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
                           WHERE eligible.work_unit_code='QIQIHAR_BUSINESS'
                             AND eligible.region_code=scope.region_code)
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_region_scope(
                        subject_id,region_code,valid_from,granted_by,granted_at,last_reviewed_at,review_due_at)
                    SELECT 'wang-yang',eligible.region_code,now(),'wang-yang',now(),now(),now()+interval '90 days'
                    FROM platform.work_unit_region_scope eligible
                    WHERE eligible.work_unit_code='QIQIHAR_BUSINESS'
                      AND NOT EXISTS (
                          SELECT 1 FROM platform.security_user_region_scope active_scope
                          WHERE active_scope.subject_id='wang-yang'
                            AND active_scope.region_code=eligible.region_code
                            AND now()>=active_scope.valid_from
                            AND (active_scope.valid_until IS NULL OR now()<active_scope.valid_until))
                    """).update();
            jdbc.sql("""
                    UPDATE platform.work_unit
                    SET active = true
                    WHERE code = 'DATABASE_AUTOMATION'
                    """).update();
        });
    }
}
