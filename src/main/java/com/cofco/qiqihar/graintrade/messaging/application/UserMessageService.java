package com.cofco.qiqihar.graintrade.messaging.application;

import com.cofco.qiqihar.graintrade.identity.application.EmailChallengeService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserMessageService {
    private static final String SELECT="""
            SELECT m.message_id,m.sender_subject_id,sender.display_name AS sender_name,
              m.recipient_subject_id,recipient.display_name AS recipient_name,m.channel,
              m.title,m.body,coalesce(delivery.status,'STATION') AS delivery_status,
              receipt.read_at IS NOT NULL AS is_read,m.created_at
            FROM platform.private_message m
            JOIN platform.security_user sender ON sender.subject_id=m.sender_subject_id
            JOIN platform.security_user recipient ON recipient.subject_id=m.recipient_subject_id
            LEFT JOIN platform.private_message_receipt receipt ON receipt.message_id=m.message_id
            LEFT JOIN platform.message_email_delivery delivery ON delivery.message_id=m.message_id
            """;
    private final JdbcClient jdbc;
    public UserMessageService(JdbcClient jdbc){this.jdbc=jdbc;}

    @Transactional
    public UserMessageView send(String sender,SendUserMessageCommand command) {
        validate(command);requireEnabled(sender);
        jdbc.sql("SELECT pg_advisory_xact_lock(211,hashtext(:sender))")
                .param("sender",sender).query(Object.class).single();
        var replay=jdbc.sql(SELECT+" WHERE m.sender_subject_id=:sender AND m.idempotency_key=:key")
                .param("sender",sender).param("key",command.idempotencyKey()).query(this::map).optional();
        if(replay.isPresent())return replay.get();
        long recent=jdbc.sql("SELECT count(*) FROM platform.private_message WHERE sender_subject_id=:sender AND created_at>now()-interval '1 minute'")
                .param("sender",sender).query(Long.class).single();
        long daily=jdbc.sql("SELECT count(*) FROM platform.private_message WHERE sender_subject_id=:sender AND created_at>now()-interval '1 day'")
                .param("sender",sender).query(Long.class).single();
        if(recent>=20||daily>=500)throw invalid("MESSAGE_RATE_LIMIT","发送过于频繁，请稍后再试");
        Recipient recipient=resolve(command.recipient());
        String address=null;
        if("EMAIL".equals(command.channel())) {
            address=jdbc.sql("SELECT email_normalized FROM platform.email_identity WHERE subject_id=:subject AND verified_at IS NOT NULL")
                    .param("subject",recipient.subject()).query(String.class).optional()
                    .orElseThrow(()->invalid("MESSAGE_EMAIL_UNVERIFIED","收件人没有已验证邮箱"));
        }
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.private_message(message_id,sender_subject_id,recipient_subject_id,
                  channel,title,body,recipient_address,idempotency_key)
                VALUES(:id,:sender,:recipient,:channel,:title,:body,:address,:key)
                """).param("id",id).param("sender",sender).param("recipient",recipient.subject())
                .param("channel",command.channel()).param("title",command.title().strip())
                .param("body",command.body().strip()).param("address",address)
                .param("key",command.idempotencyKey()).update();
        if("STATION".equals(command.channel()))
            jdbc.sql("INSERT INTO platform.private_message_receipt(message_id,recipient_subject_id) VALUES(:id,:recipient)")
                    .param("id",id).param("recipient",recipient.subject()).update();
        else jdbc.sql("INSERT INTO platform.message_email_delivery(message_id) VALUES(:id)")
                    .param("id",id).update();
        return findForParticipant(id,sender).orElseThrow();
    }

    @Transactional(readOnly=true)
    public List<UserMessageView> inbox(String subject,int page,int size) {
        page(page,size);return jdbc.sql(SELECT+"""
                WHERE m.recipient_subject_id=:subject AND m.channel='STATION'
                ORDER BY m.created_at DESC,m.message_id LIMIT :limit OFFSET :offset
                """).param("subject",subject).param("limit",size).param("offset",Math.multiplyExact(page,size))
                .query(this::map).list();
    }
    @Transactional(readOnly=true)
    public List<UserMessageView> sent(String subject,int page,int size) {
        page(page,size);return jdbc.sql(SELECT+"""
                WHERE m.sender_subject_id=:subject
                ORDER BY m.created_at DESC,m.message_id LIMIT :limit OFFSET :offset
                """).param("subject",subject).param("limit",size).param("offset",Math.multiplyExact(page,size))
                .query(this::map).list();
    }
    @Transactional(readOnly=true)
    public long unreadCount(String subject) {
        return jdbc.sql("SELECT count(*) FROM platform.private_message_receipt WHERE recipient_subject_id=:subject AND read_at IS NULL")
                .param("subject",subject).query(Long.class).single();
    }
    @Transactional public boolean markRead(String subject,UUID id) {
        return jdbc.sql("UPDATE platform.private_message_receipt SET read_at=coalesce(read_at,now()) WHERE message_id=:id AND recipient_subject_id=:subject")
                .param("id",id).param("subject",subject).update()==1;
    }

    private Optional<UserMessageView> findForParticipant(UUID id,String subject) {
        return jdbc.sql(SELECT+" WHERE m.message_id=:id AND (m.sender_subject_id=:subject OR m.recipient_subject_id=:subject)")
                .param("id",id).param("subject",subject).query(this::map).optional();
    }
    private Recipient resolve(String exact) {
        String email=exact!=null&&exact.contains("@")?EmailChallengeService.normalize(exact):"";
        var matches=jdbc.sql("""
                SELECT DISTINCT u.subject_id
                FROM platform.security_user u
                LEFT JOIN platform.phone_identity phone ON phone.subject_id=u.subject_id
                LEFT JOIN platform.email_identity email ON email.subject_id=u.subject_id
                WHERE (u.subject_id=:exact OR phone.phone=:exact OR email.email_normalized=:email)
                  AND u.enabled AND u.account_status='ACTIVE' AND u.employment_status='ACTIVE'
                  AND (u.termination_effective_at IS NULL OR u.termination_effective_at>now())
                """).param("exact",exact).param("email",email).query(String.class).list();
        if(matches.size()!=1)throw invalid("MESSAGE_RECIPIENT_NOT_FOUND","请填写完整且精确的用户名、手机号或邮箱");
        return new Recipient(matches.getFirst());
    }
    private void requireEnabled(String subject) {
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.security_user WHERE subject_id=:subject AND enabled AND account_status='ACTIVE' AND employment_status='ACTIVE')")
                .param("subject",subject).query(Boolean.class).single())
            throw invalid("MESSAGE_SENDER_DISABLED","账号不可用，不能发送消息");
    }
    private UserMessageView map(ResultSet r,int n)throws SQLException {
        return new UserMessageView(r.getObject("message_id",UUID.class),r.getString("sender_subject_id"),
                r.getString("sender_name"),r.getString("recipient_subject_id"),r.getString("recipient_name"),
                r.getString("channel"),r.getString("title"),r.getString("body"),r.getString("delivery_status"),
                r.getBoolean("is_read"),r.getTimestamp("created_at").toInstant());
    }
    private static void validate(SendUserMessageCommand c) {
        if(c==null||c.recipient()==null||c.recipient().isBlank())throw invalid("MESSAGE_RECIPIENT_REQUIRED","请填写收件人");
        if(!"STATION".equals(c.channel())&&!"EMAIL".equals(c.channel()))throw invalid("MESSAGE_CHANNEL_INVALID","请选择站内信或邮箱");
        if(c.title()==null||c.title().isBlank()||c.title().strip().length()>200)throw invalid("MESSAGE_TITLE_INVALID","标题不能为空且不能超过200字");
        if(c.body()==null||c.body().isBlank()||c.body().strip().length()>20000)throw invalid("MESSAGE_BODY_INVALID","正文不能为空且不能超过20000字");
        if(c.idempotencyKey()==null||c.idempotencyKey().isBlank()||c.idempotencyKey().length()>160)throw invalid("MESSAGE_KEY_INVALID","发送标识无效");
    }
    private static void page(int page,int size){if(page<0||size<1||size>100)throw invalid("MESSAGE_PAGE_INVALID","分页参数无效");}
    private record Recipient(String subject) {}
    private static ClientRequestException invalid(String code,String message){return new ClientRequestException(code,message);}
}
