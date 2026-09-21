package com.cofco.qiqihar.graintrade.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.identity.infrastructure.QqSmtpEmailVerificationGateway;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(classes=GrainTradeApplication.class)
@UsesProtectedTestDatabase
class MessageEmailDeliveryWorkerIntegrationTest {
    @Autowired UserMessageService messages;
    @Autowired MessageEmailDeliveryWorker worker;
    @Autowired JdbcClient jdbc;
    @MockitoBean QqSmtpEmailVerificationGateway gateway;

    @Test
    void sendsOutsideTheClaimTransactionAfterTheSendingStateIsCommitted() {
        String suffix=java.util.UUID.randomUUID().toString();
        String sender="delivery-sender-"+suffix;
        String recipient="delivery-recipient-"+suffix;
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES(:code,:name,99212)")
                .param("code","DELIVERY_"+suffix).param("name","邮件事务测试单位"+suffix).update();
        for(String subject:java.util.List.of(sender,recipient))
            jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,:unit)")
                    .param("s",subject).param("unit","DELIVERY_"+suffix).update();
        jdbc.sql("INSERT INTO platform.email_identity(email_normalized,subject_id,email_display,verified_at) VALUES(:email,:subject,:email,now())")
                .param("email",recipient+"@example.com").param("subject",recipient).update();
        var message=messages.send(sender,new SendUserMessageCommand(
                recipient,"EMAIL","事务外邮件","正文","delivery-"+suffix));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.sql("SELECT status FROM platform.message_email_delivery WHERE message_id=:id")
                    .param("id",message.id()).query(String.class).single()).isEqualTo("SENDING");
            return null;
        }).when(gateway).send(anyString(),anyString(),anyString());

        assertThat(worker.deliverNext()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM platform.message_email_delivery WHERE message_id=:id")
                .param("id",message.id()).query(String.class).single()).isEqualTo("SENT");
    }
}
