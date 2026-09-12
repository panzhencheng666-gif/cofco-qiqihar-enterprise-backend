package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.SmsVerificationGateway;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AliyunSmsVerificationGateway implements SmsVerificationGateway {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AliyunSmsVerificationGateway.class);
    private final String credentialsFile;
    private final String signName;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AliyunSmsVerificationGateway(ObjectMapper json,
            @Value("${QIQIHAR_SMS_CREDENTIALS_FILE:}") String credentialsFile,
            @Value("${QIQIHAR_SMS_SIGN_NAME:恒创联众}") String signName) {
        this.json=json; this.credentialsFile=credentialsFile; this.signName=signName;
    }
    public void send(String phone,String purpose,String challengeId) {
        var parameters=new TreeMap<String,String>();
        parameters.put("SignName",signName);
        parameters.put("TemplateCode",purpose.equals("LOGIN")?"100001":"100004");
        parameters.put("TemplateParam","{\"code\":\"##code##\",\"min\":\"5\"}");
        parameters.put("CodeLength","6"); parameters.put("CodeType","1");
        parameters.put("ValidTime","300"); parameters.put("ReturnVerifyCode","false");
        parameters.put("DuplicatePolicy","1"); parameters.put("Interval","60");
        parameters.put("AutoRetry","0");
        call("SendSmsVerifyCode",phone,challengeId,parameters);
    }
    public boolean verify(String phone,String code,String challengeId) {
        return "PASS".equals(call("CheckSmsVerifyCode",phone,challengeId,
                new TreeMap<>(Map.of("VerifyCode",code))).path("Model").path("VerifyResult").asText());
    }
    private JsonNode call(String action,String phone,String challengeId,TreeMap<String,String> p) {
        try {
            if(credentialsFile.isBlank()) throw new IllegalStateException();
            var secret=json.readTree(Files.readString(Path.of(credentialsFile)));
            p.put("AccessKeyId",secret.path("accessKeyId").asText());
            p.put("Action",action);p.put("Version","2017-05-25");p.put("Format","JSON");
            p.put("SignatureMethod","HMAC-SHA1");p.put("SignatureVersion","1.0");
            p.put("SignatureNonce",UUID.randomUUID().toString());
            p.put("Timestamp",Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            p.put("PhoneNumber",phone);p.put("CountryCode","86");p.put("OutId",challengeId);
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret.path("accessKeySecret").asText()+"&").getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
            p.put("Signature",Base64.getEncoder().encodeToString(mac.doFinal(
                    ("POST&%2F&"+encode(query(p))).getBytes(StandardCharsets.UTF_8))));
            var response=http.send(HttpRequest.newBuilder(URI.create("https://dypnsapi.aliyuncs.com/"))
                    .timeout(Duration.ofSeconds(12)).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(query(p))).build(),HttpResponse.BodyHandlers.ofString());
            var result=json.readTree(response.body());
            if(action.equals("CheckSmsVerifyCode") && "isv.ValidateFail".equals(result.path("Code").asText()))return result;
            if(response.statusCode()!=200 || !"OK".equals(result.path("Code").asText())
                    || !result.path("Success").asBoolean()) {
                log.warn("SMS provider request failed: action={}, httpStatus={}, code={}",action,response.statusCode(),result.path("Code").asText().replaceAll("[^A-Za-z0-9._-]", ""));
                throw new IllegalStateException();
            }
            return result;
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();throw unavailable();
        } catch(Exception exception) { log.warn("SMS gateway failure type={}",exception.getClass().getSimpleName());throw unavailable(); }
    }
    private static String query(Map<String,String> p) {
        return p.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
    }
    static String encode(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~"); }
    private static ClientRequestException unavailable() {
        return new ClientRequestException("SMS_UNAVAILABLE","短信服务暂不可用，请稍后重试");
    }
}
