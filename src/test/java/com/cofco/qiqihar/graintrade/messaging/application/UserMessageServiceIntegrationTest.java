package com.cofco.qiqihar.graintrade.messaging.application;

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
class UserMessageServiceIntegrationTest {
    @Autowired UserMessageService service;
    @Autowired JdbcClient jdbc;

    @Test void exactRecipientLookupStationUnreadAndPrivacyAreEnforced() {
        users();
        var sent=service.send("message-sender",new SendUserMessageCommand(
                "message-recipient","STATION","通知标题","通知正文","station-1"));
        assertThat(service.unreadCount("message-recipient")).isOne();
        assertThat(service.inbox("message-recipient",0,20)).extracting(UserMessageView::id).containsExactly(sent.id());
        assertThat(service.inbox("message-other",0,20)).isEmpty();
        assertThat(service.markRead("message-other",sent.id())).isFalse();
        assertThat(service.markRead("message-recipient",sent.id())).isTrue();
        assertThat(service.unreadCount("message-recipient")).isZero();
        assertThatThrownBy(() -> service.send("message-sender",new SendUserMessageCommand(
                "message","STATION","标题","正文","station-2"))).hasMessageContaining("精确");
    }

    @Test void emailRequiresVerifiedRecipientAndPersistsDeliveryBeforeSending() {
        users();
        jdbc.sql("INSERT INTO platform.email_identity(email_normalized,subject_id,email_display,verified_at) VALUES('recipient@example.com','message-recipient','recipient@example.com',now())").update();
        var sent=service.send("message-sender",new SendUserMessageCommand(
                "RECIPIENT@example.com","EMAIL","邮件标题","邮件正文","email-1"));
        assertThat(sent.channel()).isEqualTo("EMAIL");
        assertThat(jdbc.sql("SELECT status FROM platform.message_email_delivery WHERE message_id=:id")
                .param("id",sent.id()).query(String.class).single()).isEqualTo("PENDING");
        assertThat(service.unreadCount("message-recipient")).isZero();
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform.private_message WHERE message_id=:id")
                .param("id",sent.id()).update()).hasMessageContaining("permanent");
    }

    @Test void limitsConcurrentUniqueSendsButAllowsAnIdempotentReplay() {
        users();
        for(int index=0;index<20;index++)service.send("message-sender",new SendUserMessageCommand(
                "message-recipient","STATION","标题"+index,"正文","rate-"+index));
        assertThatThrownBy(()->service.send("message-sender",new SendUserMessageCommand(
                "message-recipient","STATION","超限","正文","rate-over")))
                .hasMessageContaining("频繁");
        assertThat(service.send("message-sender",new SendUserMessageCommand(
                "message-recipient","STATION","标题0","正文","rate-0")).title()).isEqualTo("标题0");
    }

    private void users() {
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('MESSAGE_TEST','站内信隔离测试单位',99211) ON CONFLICT DO NOTHING").update();
        for(String subject:java.util.List.of("message-sender","message-recipient","message-other"))
            jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,'MESSAGE_TEST') ON CONFLICT DO NOTHING")
                    .param("s",subject).update();
    }
}
