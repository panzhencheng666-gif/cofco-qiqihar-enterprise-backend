package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.IdentityDeliveryOutboxRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcIdentityDeliveryOutboxRepository implements IdentityDeliveryOutboxRepository {
    private final JdbcClient jdbc;

    public JdbcIdentityDeliveryOutboxRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    @Override
    @Transactional
    public Optional<ClaimedDelivery> claimNext(Instant now,Duration lease) {
        ClaimedDelivery candidate=jdbc.sql("""
                SELECT outbox.event_id,outbox.invitation_id,outbox.security_subject_id,
                       invitation.encrypted_delivery_payload,invitation.expires_at,
                       outbox.attempt_count+1
                FROM platform.identity_delivery_outbox outbox
                JOIN platform.identity_invitation invitation USING(invitation_id)
                WHERE outbox.delivery_status IN ('QUEUED','FAILED')
                  AND outbox.available_at<=:now
                  AND (outbox.leased_until IS NULL OR outbox.leased_until<:now)
                  AND invitation.state='PENDING' AND invitation.expires_at>:now
                ORDER BY outbox.available_at,outbox.created_at,outbox.event_id
                FOR UPDATE OF outbox SKIP LOCKED LIMIT 1
                """).param("now",Timestamp.from(now)).query((row,index)->new ClaimedDelivery(
                        row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),
                        row.getString(4),row.getTimestamp(5).toInstant(),row.getInt(6))).optional().orElse(null);
        if(candidate==null)return Optional.empty();
        int updated=jdbc.sql("""
                UPDATE platform.identity_delivery_outbox
                SET delivery_status='PROCESSING',attempt_count=:attempt,
                    leased_until=:leasedUntil,last_error_code=NULL,last_error_message=NULL
                WHERE event_id=:event AND delivery_status IN ('QUEUED','FAILED')
                """).param("attempt",candidate.attemptCount())
                .param("leasedUntil",Timestamp.from(now.plus(lease)))
                .param("event",candidate.eventId()).update();
        return updated==1?Optional.of(candidate):Optional.empty();
    }

    @Override
    @Transactional
    public void markDelivered(UUID eventId,UUID invitationId,Instant deliveredAt) {
        jdbc.sql("""
                UPDATE platform.identity_delivery_outbox
                SET delivery_status='DELIVERED',delivered_at=:at,leased_until=NULL,
                    last_error_code=NULL,last_error_message=NULL
                WHERE event_id=:event AND delivery_status='PROCESSING'
                """).param("at",Timestamp.from(deliveredAt)).param("event",eventId).update();
        jdbc.sql("""
                UPDATE platform.identity_invitation SET delivery_status='DELIVERED',version=version+1
                WHERE invitation_id=:invitation AND state='PENDING'
                """).param("invitation",invitationId).update();
    }

    @Override
    @Transactional
    public void markFailed(UUID eventId,UUID invitationId,Instant retryAt,
            String errorCode,String safeMessage) {
        jdbc.sql("""
                UPDATE platform.identity_delivery_outbox
                SET delivery_status='FAILED',available_at=:retryAt,leased_until=NULL,
                    last_error_code=:errorCode,last_error_message=:safeMessage
                WHERE event_id=:event AND delivery_status='PROCESSING'
                """).param("retryAt",Timestamp.from(retryAt)).param("errorCode",errorCode)
                .param("safeMessage",safeMessage).param("event",eventId).update();
        jdbc.sql("""
                UPDATE platform.identity_invitation SET delivery_status='FAILED',version=version+1
                WHERE invitation_id=:invitation AND state='PENDING' AND delivery_status<>'DELIVERED'
                """).param("invitation",invitationId).update();
    }

    @Override
    @Transactional
    public int expireInvitations(Instant now) {
        ListIds expired=new ListIds(jdbc.sql("""
                SELECT invitation_id FROM platform.identity_invitation
                WHERE state='PENDING' AND expires_at<=:now FOR UPDATE
                """).param("now",Timestamp.from(now)).query(UUID.class).list());
        if(expired.values().isEmpty())return 0;
        jdbc.sql("""
                UPDATE platform.identity_invitation
                SET state='EXPIRED',version=version+1
                WHERE invitation_id IN (:ids) AND state='PENDING'
                """).param("ids",expired.values()).update();
        jdbc.sql("""
                UPDATE platform.identity_delivery_outbox
                SET delivery_status='DEAD_LETTER',leased_until=NULL,last_error_code='INVITATION_EXPIRED',
                    last_error_message='Invitation expired before delivery'
                WHERE invitation_id IN (:ids) AND delivery_status<>'DELIVERED'
                """).param("ids",expired.values()).update();
        return expired.values().size();
    }

    private record ListIds(java.util.List<UUID> values) {}
}
