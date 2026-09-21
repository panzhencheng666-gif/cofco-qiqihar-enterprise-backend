package com.cofco.qiqihar.graintrade.messaging.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageEmailDeliveryStore {
    private final JdbcClient jdbc;

    public MessageEmailDeliveryStore(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Transactional
    public Optional<Delivery> claim() {
        var item=jdbc.sql("""
                SELECT d.message_id,m.recipient_address,m.title,m.body
                FROM platform.message_email_delivery d JOIN platform.private_message m USING(message_id)
                WHERE ((d.status IN ('PENDING','FAILED') AND d.next_attempt_at<=now())
                    OR (d.status='SENDING' AND d.locked_at<now()-interval '10 minutes'))
                  AND d.attempts<5
                ORDER BY d.next_attempt_at,d.message_id FOR UPDATE OF d SKIP LOCKED LIMIT 1
                """).query((r,n)->new Delivery(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4))).optional();
        item.ifPresent(value -> jdbc.sql("""
                UPDATE platform.message_email_delivery
                SET status='SENDING',attempts=attempts+1,last_attempt_at=now(),locked_at=now(),updated_at=now()
                WHERE message_id=:id
                """).param("id",value.id()).update());
        return item;
    }

    @Transactional
    public void markSent(UUID id) {
        jdbc.sql("""
                UPDATE platform.message_email_delivery
                SET status='SENT',sent_at=now(),last_error_code=NULL,locked_at=NULL,updated_at=now()
                WHERE message_id=:id AND status='SENDING'
                """).param("id",id).update();
    }

    @Transactional
    public void markFailed(UUID id) {
        jdbc.sql("""
                UPDATE platform.message_email_delivery
                SET status='FAILED',last_error_code='SMTP_DELIVERY_FAILED',
                    next_attempt_at=now()+interval '5 minutes',locked_at=NULL,updated_at=now()
                WHERE message_id=:id AND status='SENDING'
                """).param("id",id).update();
    }

    public record Delivery(UUID id,String address,String title,String body) {}
}
