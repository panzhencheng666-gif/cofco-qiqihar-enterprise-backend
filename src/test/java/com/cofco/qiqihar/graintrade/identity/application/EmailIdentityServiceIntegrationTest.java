package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.*;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes=GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class EmailIdentityServiceIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired EmailIdentityService service;

    @Test void unverifiedRequiredRegistrationEmailCannotLoginUntilVerified() {
        user("email-user");
        service.bind("email-user","User@Example.com",false);
        assertThatThrownBy(() -> service.login("user@example.com")).hasMessageContaining("尚未验证");
        service.verifyBinding("email-user","user@example.com");
        assertThat(service.login("USER@example.com").subject()).isEqualTo("email-user");
    }

    @Test void emailIsCaseInsensitiveUniqueAndDisabledAccountCannotLogin() {
        user("email-a");user("email-b");
        service.bind("email-a","unique@example.com",true);
        assertThatThrownBy(() -> service.bind("email-b","UNIQUE@example.com",true)).hasMessageContaining("已绑定");
        jdbc.sql("UPDATE platform.security_user SET enabled=false WHERE subject_id='email-a'").update();
        assertThatThrownBy(() -> service.login("unique@example.com")).hasMessageContaining("账号不可用");
    }

    private void user(String subject) {
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('EMAIL_TEST','邮箱隔离测试单位',99210) ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,'EMAIL_TEST') ON CONFLICT DO NOTHING")
                .param("s",subject).update();
    }
}
