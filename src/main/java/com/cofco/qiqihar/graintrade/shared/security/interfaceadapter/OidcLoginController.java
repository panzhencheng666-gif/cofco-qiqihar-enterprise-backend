package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/** Stable same-origin entry into the enterprise OIDC authorization-code flow. */
@Controller
public class OidcLoginController {
    static final String LOGIN_RETURN_TO_ATTRIBUTE = "COFCO_LOGIN_RETURN_TO";
    private static final String RISK_APPLICATION_PATH = "/risk/";

    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    org.springframework.http.ResponseEntity<String> loginRecovery(HttpServletRequest request) {
        if (!request.getParameterMap().containsKey("error")) {
            return org.springframework.http.ResponseEntity.status(302)
                    .header("Location", request.getContextPath() + "/api/v1/session/login")
                    .build();
        }
        var session = request.getSession(false);
        if (session != null) {
            Object failure = session.getAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
            session.removeAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
            if (failure instanceof org.springframework.security.core.AuthenticationException exception) {
                String code = exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauth
                        ? oauth.getError().getErrorCode() : "authentication_failed";
                if (code == null || !code.matches("[a-zA-Z0-9_]{1,80}")) code = "authentication_failed";
                Throwable cause = exception;
                for (int depth = 0; depth < 8 && cause.getCause() != null; depth++) cause = cause.getCause();
                // Never log provider descriptions, credentials, authorization codes or session identifiers.
                org.slf4j.LoggerFactory.getLogger(OidcLoginController.class).warn(
                        "OIDC_LOGIN_FAILURE code={} causeType={}", code, cause.getClass().getSimpleName());
            }
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                .body("""
                        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>登录未完成 · 齐齐哈尔粮食商情平台</title>
                        <style>body{margin:0;background:#f4f7f6;color:#244a4a;font:16px/1.8 system-ui,sans-serif}
                        main{max-width:520px;margin:15vh auto;padding:36px;background:white;border:1px solid #dce5e2;border-radius:12px}
                        h1{font-size:26px}a{display:inline-block;margin:12px 20px 0 0;color:#28634f}</style></head>
                        <body><main><p>齐齐哈尔粮食商情平台</p><h1>登录未完成</h1>
                        <p>本次身份验证未能完成。请重新登录；若同一账号仍无法进入，请联系管理员核对账号状态与身份绑定。</p>
                        <p>系统不会自动重复跳转，也未更改你的账号或权限。</p>
                        <a href="/oauth2/authorization/enterprise">重新登录</a>
                        <a href="/#/applications">返回应用中心</a></main></body></html>
                        """);
    }

    @GetMapping("/api/v1/session/login")
    RedirectView login(
            @RequestParam(required = false) String returnTo,
            HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(LOGIN_RETURN_TO_ATTRIBUTE);
        String[] targets = request.getParameterValues("returnTo");
        boolean risk = targets != null && targets.length == 1 && RISK_APPLICATION_PATH.equals(returnTo);
        RedirectView redirect = new RedirectView("/oauth2/authorization/enterprise" + (risk ? "?returnTo=/risk/" : ""));
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
