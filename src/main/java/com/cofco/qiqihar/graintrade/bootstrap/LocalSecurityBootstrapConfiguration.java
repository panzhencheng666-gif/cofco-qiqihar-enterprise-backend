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
                    INSERT INTO platform.work_unit_region_scope(work_unit_code, region_code)
                    SELECT 'LOCAL_DEV', region_code
                    FROM platform.monitoring_scope_region
                    WHERE scope_code = 'FORMAL_BUSINESS' AND included
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
                    DELETE FROM platform.security_user_region_scope
                    WHERE subject_id = 'wang-yang'
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_region_scope(subject_id, region_code)
                    SELECT 'wang-yang', region_code
                    FROM platform.monitoring_scope_region
                    WHERE scope_code = 'FORMAL_BUSINESS' AND included
                    """).update();
        };
    }
}
