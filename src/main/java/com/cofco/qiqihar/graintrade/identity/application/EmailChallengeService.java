package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class EmailChallengeService {
    public static final String SUBJECT = "粮食商情统一工作平台验证码";
    private static final String BODY = "尊敬的用户，您好！\n欢迎使用粮食商情统一工作平台\n您本次的登录验证码为：%s";
    private final JdbcClient jdbc;
    private final EmailVerificationGateway gateway;
    private final boolean enabled;
    private final byte[] digestSecret;
    private final SecureRandom random = new SecureRandom();

    public EmailChallengeService(JdbcClient jdbc, EmailVerificationGateway gateway,
            @Value("${QIQIHAR_EMAIL_ENABLED:false}") boolean enabled,
            @Value("${QIQIHAR_EMAIL_CODE_SECRET:}") String digestSecret) {
        this.jdbc=jdbc;this.gateway=gateway;this.enabled=enabled;
        this.digestSecret=Objects.requireNonNullElse(digestSecret,"").getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor={ClientRequestException.class,ServiceUnavailableException.class})
    public UUID send(String address,String purpose,String session,String client) {
        if(!enabled)throw new ServiceUnavailableException("EMAIL_DISABLED","邮箱发送服务尚未配置完成，请使用手机验证码登录或注册");
        String email=normalize(address);
        if(!"LOGIN".equals(purpose)&&!"REGISTER".equals(purpose)&&!"BIND".equals(purpose))
            throw invalid("EMAIL_PURPOSE","验证码用途无效");
        if(digestSecret.length<24)throw new IllegalStateException("Email code digest secret is not configured");
        jdbc.sql("SELECT pg_advisory_xact_lock(209,hashtext(:email))")
                .param("email",email).query(Object.class).single();
        long recent=jdbc.sql("SELECT count(*) FROM platform.email_challenge WHERE email_normalized=:email AND created_at>now()-interval '60 seconds'")
                .param("email",email).query(Long.class).single();
        long daily=jdbc.sql("SELECT count(*) FROM platform.email_challenge WHERE email_normalized=:email AND created_at>now()-interval '1 day'")
                .param("email",email).query(Long.class).single();
        long clients=jdbc.sql("SELECT count(*) FROM platform.email_challenge WHERE client_hash=:client AND created_at>now()-interval '1 hour'")
                .param("client",SmsChallengeService.hash(client)).query(Long.class).single();
        if(recent>0||daily>=10||clients>=20)
            throw invalid("EMAIL_RATE_LIMIT","发送过于频繁，请稍后再试");
        UUID id=UUID.randomUUID();
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        jdbc.sql("""
                UPDATE platform.email_challenge SET consumed_at=coalesce(consumed_at,now())
                WHERE email_normalized=:email AND purpose=:purpose AND consumed_at IS NULL
                """).param("email",email).param("purpose",purpose).update();
        jdbc.sql("""
                INSERT INTO platform.email_challenge(challenge_id,email_normalized,purpose,
                  session_hash,client_hash,code_digest,expires_at)
                VALUES(:id,:email,:purpose,:session,:client,:digest,now()+interval '5 minutes')
                """).param("id",id).param("email",email).param("purpose",purpose)
                .param("session",SmsChallengeService.hash(session))
                .param("client",SmsChallengeService.hash(client))
                .param("digest",digest(id,email,purpose,code)).update();
        try {
            gateway.send(email,SUBJECT,BODY.formatted(code),code);
            jdbc.sql("UPDATE platform.email_challenge SET sent_at=now() WHERE challenge_id=:id")
                    .param("id",id).update();
        } catch(RuntimeException failure) {
            jdbc.sql("UPDATE platform.email_challenge SET consumed_at=now() WHERE challenge_id=:id")
                    .param("id",id).update();
            throw new ServiceUnavailableException("EMAIL_SEND_FAILED","邮件发送失败，请稍后重试",failure);
        }
        return id;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor={ClientRequestException.class,ServiceUnavailableException.class})
    public String verify(UUID id,String code,String purpose,String session) {
        if(!enabled||id==null||code==null||!code.matches("[0-9]{6}"))throw rejected();
        var row=jdbc.sql("""
                SELECT email_normalized,purpose,session_hash,code_digest,
                  sent_at IS NOT NULL AND consumed_at IS NULL AND attempts<5 AND expires_at>now() AS usable
                FROM platform.email_challenge WHERE challenge_id=:id FOR UPDATE
                """).param("id",id).query((r,n)->new Challenge(r.getString(1),r.getString(2),
                        r.getString(3),r.getString(4),r.getBoolean(5))).optional().orElseThrow(EmailChallengeService::rejected);
        if(!row.usable()||!row.purpose().equals(purpose)
                ||!row.sessionHash().equals(SmsChallengeService.hash(session)))throw rejected();
        jdbc.sql("UPDATE platform.email_challenge SET attempts=attempts+1 WHERE challenge_id=:id")
                .param("id",id).update();
        byte[] expected=HexFormat.of().parseHex(row.codeDigest());
        byte[] actual=HexFormat.of().parseHex(digest(id,row.email(),purpose,code));
        if(!MessageDigest.isEqual(expected,actual))throw rejected();
        jdbc.sql("UPDATE platform.email_challenge SET consumed_at=now() WHERE challenge_id=:id")
                .param("id",id).update();
        return row.email();
    }

    public static String normalize(String value) {
        String email=Objects.toString(value,"").strip().toLowerCase(Locale.ROOT);
        if(email.length()>320||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw invalid("INVALID_EMAIL","请填写有效的邮箱地址");
        return email;
    }

    private String digest(UUID id,String email,String purpose,String code) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(digestSecret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id+"\n"+email+"\n"+purpose+"\n"+code)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
    private record Challenge(String email,String purpose,String sessionHash,String codeDigest,boolean usable) {}
    private static ClientRequestException rejected(){return invalid("EMAIL_INVALID","验证码错误、过期或已使用，请重新获取");}
    private static ClientRequestException invalid(String code,String message){return new ClientRequestException(code,message);}
}
