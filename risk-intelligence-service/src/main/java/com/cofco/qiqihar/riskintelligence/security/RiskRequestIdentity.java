package com.cofco.qiqihar.riskintelligence.security;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class RiskRequestIdentity {
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9._:@-]{1,120}");

    private RiskRequestIdentity() { }

    public static String requireActor(String value) {
        if (value == null || !ACTOR.matcher(value).matches()) {
            throw new RiskApiException(
                    HttpStatus.UNAUTHORIZED,
                    "RISK_IDENTITY_REQUIRED",
                    "风险系统请求身份无效或缺失");
        }
        return value;
    }
}
