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
                    VALUES ('LOCAL_DEV', '本地开发操作组', 9900)
                    ON CONFLICT (code) DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.work_unit_region_scope(work_unit_code, region_code)
                    SELECT 'LOCAL_DEV', code FROM platform.region WHERE code LIKE '2302%'
                    ON CONFLICT DO NOTHING
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
                    INSERT INTO platform.security_user_region_scope(subject_id, region_code)
                    SELECT 'wang-yang', code FROM platform.region WHERE code LIKE '2302%'
                    ON CONFLICT DO NOTHING
                    """).update();
        };
    }
}
