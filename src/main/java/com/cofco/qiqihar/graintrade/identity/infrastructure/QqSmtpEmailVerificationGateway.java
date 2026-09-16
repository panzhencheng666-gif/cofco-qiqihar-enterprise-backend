package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.EmailVerificationGateway;
import com.cofco.qiqihar.graintrade.messaging.application.OutboundEmailGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class QqSmtpEmailVerificationGateway implements EmailVerificationGateway, OutboundEmailGateway {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final String authorizationCode;

    public QqSmtpEmailVerificationGateway(
            @Value("${QIQIHAR_EMAIL_SMTP_FROM:7911945@qq.com}") String from,
            @Value("${QIQIHAR_EMAIL_SMTP_AUTH_CODE:}") String authorizationCode) {
        this.from=from;this.authorizationCode=authorizationCode;
        sender.setHost("smtp.qq.com");sender.setPort(465);sender.setUsername(from);
        var properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth","true");
        properties.setProperty("mail.smtp.ssl.enable","true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        properties.setProperty("mail.smtp.connectiontimeout","10000");
        properties.setProperty("mail.smtp.timeout","10000");
        properties.setProperty("mail.smtp.writetimeout","10000");
    }
    @Override public void send(String email,String subject,String body,String verificationCode) {
        send(email,subject,body);
    }
    @Override public void send(String email,String subject,String body) {
        if(authorizationCode==null||authorizationCode.isBlank())
            throw new IllegalStateException("QQ SMTP authorization code is not configured");
        sender.setPassword(authorizationCode);
        var message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(email);message.setSubject(subject);message.setText(body);
        sender.send(message);
    }
}
