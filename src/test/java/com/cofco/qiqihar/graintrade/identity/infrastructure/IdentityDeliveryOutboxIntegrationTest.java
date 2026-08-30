package com.cofco.qiqihar.graintrade.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.identity.application.IdentityDeliveryGateway;
import com.cofco.qiqihar.graintrade.identity.application.IdentityDeliveryWorker;
import com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationTokenCodec;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes=GrainTradeApplication.class)
@UsesProtectedTestDatabase
class IdentityDeliveryOutboxIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired IdentityInvitationTokenCodec tokens;
    @Autowired IdentityDeliveryWorker worker;
    @MockitoBean IdentityDeliveryGateway gateway;
    private JdbcClient jdbc;
    private String subject;
    private UUID invitationId;
    private UUID eventId;

    @BeforeEach
    void prepare() {
        jdbc=JdbcClient.create(dataSource);
        subject="identity-delivery-"+UUID.randomUUID();
        invitationId=UUID.randomUUID();
        eventId=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('IDENTITY_DELIVERY_TEST','邀请投递测试单位',9991) ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled,
                    account_status,employment_status)
                VALUES(:subject,'邀请投递员工','IDENTITY_DELIVERY_TEST',false,'INVITED','ACTIVE')
                """).param("subject",subject).update();
        String token=tokens.generateToken();
        jdbc.sql("""
                INSERT INTO platform.identity_invitation(
                    invitation_id,security_subject_id,token_hash,encrypted_delivery_payload,
                    delivery_address_sha256,expires_at,created_by,idempotency_key,request_fingerprint)
                VALUES(:id,:subject,:tokenHash,:payload,:addressHash,:expiresAt,
                       'production-tester',:key,:fingerprint)
                """).param("id",invitationId).param("subject",subject)
                .param("tokenHash",tokens.sha256(token))
                .param("payload",tokens.encryptDeliveryPayload("delivery@example.test",token))
                .param("addressHash",tokens.sha256("delivery@example.test"))
                .param("expiresAt",Timestamp.from(Instant.now().plusSeconds(3600)))
                .param("key","delivery-"+eventId).param("fingerprint",tokens.sha256(subject)).update();
        jdbc.sql("""
                INSERT INTO platform.identity_delivery_outbox(
                    event_id,invitation_id,security_subject_id,event_type)
                VALUES(:event,:invitation,:subject,'INVITATION_DELIVERY')
                """).param("event",eventId).param("invitation",invitationId).param("subject",subject).update();
    }

    @AfterEach
    void cleanup() {
        if(jdbc==null)return;
        jdbc.sql("DELETE FROM platform.identity_delivery_outbox WHERE event_id=:id").param("id",eventId).update();
        jdbc.sql("DELETE FROM platform.identity_invitation WHERE invitation_id=:id").param("id",invitationId).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject").param("subject",subject).update();
    }

    @Test
    void retriesWithoutLeakingSecretsAndConfirmsDeliveryOnlyAfterAdapterSuccess() {
        doThrow(new IllegalStateException("secret-token-must-not-be-stored"))
                .when(gateway).deliver(any());

        assertThat(worker.drainOne()).isTrue();
        MapState failed=state();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.invitationDelivery()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.error()).doesNotContain("secret-token-must-not-be-stored");

        reset(gateway);
        jdbc.sql("UPDATE platform.identity_delivery_outbox SET available_at=now() WHERE event_id=:id")
                .param("id",eventId).update();
        assertThat(worker.drainOne()).isTrue();
        MapState delivered=state();
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.invitationDelivery()).isEqualTo("DELIVERED");
        assertThat(delivered.attempts()).isEqualTo(2);
        verify(gateway).deliver(org.mockito.ArgumentMatchers.argThat(command ->
                command.subjectId().equals(subject)
                        &&command.deliveryAddress().equals("delivery@example.test")
                        &&command.activationToken().length()>=40));
    }

    @Test
    void expiredProcessingLeaseIsClaimedAgainAfterWorkerInterruption() {
        jdbc.sql("""
                UPDATE platform.identity_delivery_outbox
                SET delivery_status='PROCESSING',attempt_count=1,leased_until=now()-interval '1 second'
                WHERE event_id=:id
                """).param("id",eventId).update();

        assertThat(worker.drainOne()).isTrue();

        MapState delivered=state();
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.invitationDelivery()).isEqualTo("DELIVERED");
        assertThat(delivered.attempts()).isEqualTo(2);
    }

    private MapState state() {
        return jdbc.sql("""
                SELECT outbox.delivery_status,invitation.delivery_status,outbox.attempt_count,
                       COALESCE(outbox.last_error_message,'')
                FROM platform.identity_delivery_outbox outbox
                JOIN platform.identity_invitation invitation USING(invitation_id)
                WHERE outbox.event_id=:id
                """).param("id",eventId).query((row,index)->new MapState(
                        row.getString(1),row.getString(2),row.getInt(3),row.getString(4))).single();
    }

    private record MapState(String status,String invitationDelivery,int attempts,String error) {}
}
