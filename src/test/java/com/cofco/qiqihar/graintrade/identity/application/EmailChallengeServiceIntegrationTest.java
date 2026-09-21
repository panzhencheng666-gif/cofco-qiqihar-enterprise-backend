package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.*;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class EmailChallengeServiceIntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void codeIsFiveMinuteSingleUseAndNewerCodeInvalidatesOlderCode() {
        var captured = new AtomicReference<String>();
        var service = service((email,subject,body,code) -> captured.set(code));
        UUID first = service.send(" User@Example.COM ","LOGIN","browser","client");
        String firstCode = captured.get();
        jdbc.sql("UPDATE platform.email_challenge SET created_at=created_at-interval '61 seconds' WHERE challenge_id=:id")
                .param("id",first).update();
        UUID second = service.send("user@example.com","LOGIN","browser","client");
        String secondCode = captured.get();

        assertThatThrownBy(() -> service.verify(first,firstCode,"LOGIN","browser"))
                .hasMessageContaining("错误、过期或已使用");
        assertThat(service.verify(second,secondCode,"LOGIN","browser"))
                .isEqualTo("user@example.com");
        assertThatThrownBy(() -> service.verify(second,secondCode,"LOGIN","browser"))
                .hasMessageContaining("错误、过期或已使用");
        assertThat(jdbc.sql("SELECT extract(epoch FROM expires_at-created_at)::bigint FROM platform.email_challenge WHERE challenge_id=:id")
                .param("id",second).query(Long.class).single()).isEqualTo(300L);
        assertThat(jdbc.sql("SELECT code_digest FROM platform.email_challenge WHERE challenge_id=:id")
                .param("id",second).query(String.class).single()).doesNotContain(secondCode);
    }

    @Test
    void deliveryFailureCannotLeaveAUsableChallenge() {
        var service = service((email,subject,body,code) -> { throw new IllegalStateException("provider down"); });
        assertThatThrownBy(() -> service.send("user@example.com","LOGIN","browser","client"))
                .hasMessageContaining("邮件发送失败");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.email_challenge WHERE email_normalized='user@example.com' AND sent_at IS NULL AND consumed_at IS NOT NULL")
                .query(Long.class).single()).isOne();
    }

    @Test
    void aNewCodeInvalidatesOnlyTheSamePurpose() {
        var captured = new AtomicReference<String>();
        var service = service((email,subject,body,code) -> captured.set(code));
        UUID register = service.send("purpose@example.com","REGISTER","browser","client-register");
        String registerCode = captured.get();
        jdbc.sql("UPDATE platform.email_challenge SET created_at=created_at-interval '61 seconds' WHERE challenge_id=:id")
                .param("id",register).update();
        UUID login = service.send("purpose@example.com","LOGIN","browser","client-login");
        String loginCode = captured.get();

        assertThat(service.verify(register,registerCode,"REGISTER","browser"))
                .isEqualTo("purpose@example.com");
        assertThat(service.verify(login,loginCode,"LOGIN","browser"))
                .isEqualTo("purpose@example.com");
    }

    private EmailChallengeService service(EmailVerificationGateway gateway) {
        return new EmailChallengeService(jdbc,gateway,true,"test-only-secret-with-enough-entropy");
    }
}
