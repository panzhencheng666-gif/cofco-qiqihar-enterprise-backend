package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SmsChallengeService {
    private final JdbcClient jdbc;
    private final SmsVerificationGateway gateway;
    private final boolean enabled;
    public SmsChallengeService(JdbcClient jdbc,SmsVerificationGateway gateway,
            @Value("${QIQIHAR_SMS_ENABLED:false}") boolean enabled) {
        this.jdbc=jdbc;this.gateway=gateway;this.enabled=enabled;
    }
    // Commit attempt counters even when the provider fails; never refund an abuse budget.
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=ClientRequestException.class)
    public UUID send(String phone,String purpose,String session,String client) {
        if(!enabled)throw invalid("SMS_DISABLED","短信认证未启用");
        validatePhone(phone);
        if(!Set.of("LOGIN","REGISTER","BIND","MERGE").contains(purpose))throw invalid("SMS_PURPOSE","验证码用途无效");
        jdbc.sql("SELECT pg_advisory_xact_lock(186,1)").query(Object.class).single();
        long recent=jdbc.sql("SELECT count(*) FROM platform.sms_challenge WHERE phone=:phone AND created_at>now()-interval '60 seconds'")
                .param("phone",phone).query(Long.class).single();
        long daily=jdbc.sql("SELECT count(*) FROM platform.sms_challenge WHERE phone=:phone AND created_at>now()-interval '1 day'")
                .param("phone",phone).query(Long.class).single();
        long clients=jdbc.sql("SELECT count(*) FROM platform.sms_challenge WHERE client_hash=:client AND created_at>now()-interval '1 hour'")
                .param("client",hash(client)).query(Long.class).single();
        if(recent>0||daily>=10||clients>=20)throw invalid("SMS_RATE_LIMIT","发送过于频繁，请稍后再试");
        UUID id=UUID.randomUUID();
        jdbc.sql("UPDATE platform.sms_challenge SET consumed=true WHERE phone=:phone AND NOT consumed")
                .param("phone",phone).update();
        jdbc.sql("INSERT INTO platform.sms_challenge(challenge_id,phone,purpose,session_hash,client_hash,expires_at) VALUES(:id,:phone,:purpose,:session,:client,now()+interval '5 minutes')")
                .param("id",id).param("phone",phone).param("purpose",purpose)
                .param("session",hash(session)).param("client",hash(client)).update();
        gateway.send(phone,purpose,id.toString());
        jdbc.sql("UPDATE platform.sms_challenge SET sent=true WHERE challenge_id=:id").param("id",id).update();
        return id;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=ClientRequestException.class)
    public String verify(UUID id,String code,String purpose,String session) {
        if(!enabled||id==null||code==null||!code.matches("[0-9]{6}"))throw rejected();
        var rows=jdbc.sql("SELECT phone,purpose,session_hash,sent AND NOT consumed AND attempts<5 AND expires_at>now() AS usable FROM platform.sms_challenge WHERE challenge_id=:id FOR UPDATE")
                .param("id",id).query((r,n)->new Challenge(r.getString(1),r.getString(2),r.getString(3),r.getBoolean(4))).list();
        if(rows.isEmpty())throw rejected();
        var c=rows.getFirst();
        if(!c.usable()||!c.purpose().equals(purpose)||!c.session().equals(hash(session)))throw rejected();
        jdbc.sql("UPDATE platform.sms_challenge SET attempts=attempts+1 WHERE challenge_id=:id").param("id",id).update();
        if(!gateway.verify(c.phone(),code,id.toString()))throw rejected();
        jdbc.sql("UPDATE platform.sms_challenge SET consumed=true WHERE challenge_id=:id").param("id",id).update();
        return c.phone();
    }
    public static void validatePhone(String phone) {
        if(phone==null||!phone.matches("1[3-9][0-9]{9}"))throw invalid("INVALID_PHONE","请填写有效的11位手机号");
    }
    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private record Challenge(String phone,String purpose,String session,boolean usable) {}
    private static ClientRequestException rejected(){return invalid("SMS_INVALID","验证码错误、过期或已使用，请重新获取");}
    private static ClientRequestException invalid(String code,String message){return new ClientRequestException(code,message);}
}
