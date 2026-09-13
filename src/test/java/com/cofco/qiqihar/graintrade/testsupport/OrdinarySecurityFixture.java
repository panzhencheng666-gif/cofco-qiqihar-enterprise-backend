package com.cofco.qiqihar.graintrade.testsupport;

import org.springframework.jdbc.core.simple.JdbcClient;

/** An explicit non-administrator identity for ownership and scope assertions. */
public final class OrdinarySecurityFixture implements AutoCloseable {
    private final JdbcClient jdbc;
    private final String subject;

    private OrdinarySecurityFixture(JdbcClient jdbc, String subject) {
        this.jdbc = jdbc;
        this.subject = subject;
    }

    public static OrdinarySecurityFixture create(JdbcClient jdbc, String subject, String region) {
        jdbc.sql("""
                INSERT INTO platform.access_role(code,name,active,sort_order)
                VALUES('ORDINARY_CI_REVIEW','普通员工测试权限',true,19997)
                ON CONFLICT(code) DO NOTHING;
                INSERT INTO platform.access_role_permission(role_code,permission_code)
                SELECT 'ORDINARY_CI_REVIEW',code FROM platform.access_permission
                WHERE active AND code<>'BUSINESS_SELF_APPROVE'
                ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:subject,'普通员工测试账号','TEST') ON CONFLICT(subject_id) DO NOTHING;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'ORDINARY_CI_REVIEW') ON CONFLICT DO NOTHING
                """).param("subject", subject).update();
        if (region != null) {
            jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES(:subject,:region)")
                    .param("subject", subject).param("region", region).update();
        }
        return new OrdinarySecurityFixture(jdbc, subject);
    }

    @Override
    public void close() {
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("""
                DELETE FROM platform.access_role_permission
                WHERE role_code='ORDINARY_CI_REVIEW' AND NOT EXISTS (
                  SELECT 1 FROM platform.security_user_role WHERE role_code='ORDINARY_CI_REVIEW');
                DELETE FROM platform.access_role
                WHERE code='ORDINARY_CI_REVIEW' AND NOT EXISTS (
                  SELECT 1 FROM platform.security_user_role WHERE role_code='ORDINARY_CI_REVIEW')
                """).update();
    }
}
