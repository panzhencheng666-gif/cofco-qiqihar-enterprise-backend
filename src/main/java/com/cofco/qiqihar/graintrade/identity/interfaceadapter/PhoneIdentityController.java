package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.*;
import com.cofco.qiqihar.graintrade.shared.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.PhoneAuthenticationToken;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/phone")
public class PhoneIdentityController {
    private final SmsChallengeService sms;
    private final PhoneIdentityService identities;
    private final SecurityPrincipalRepository principals;
    private final com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder audit;
    public PhoneIdentityController(SmsChallengeService sms,PhoneIdentityService identities,SecurityPrincipalRepository principals,com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder audit) {
        this.sms=sms;this.identities=identities;this.principals=principals;this.audit=audit;
    }
    @GetMapping("/bootstrap")
    ApiResponse<Map<String,String>> bootstrap(HttpServletRequest request) {
        request.getSession();return new ApiResponse<>(Map.of("authenticationMethod","sms"));
    }
    @PostMapping("/challenge")
    ApiResponse<Map<String,Object>> send(@RequestBody Send request,Authentication authentication,HttpServletRequest servlet) {
        if(request==null||request.purpose()==null)throw new AuthenticationRequiredException();
        if(!request.purpose().equals("LOGIN")) {
            if(request.purpose().equals("REGISTER"))oidc(authentication);
            else freshOriginal(authentication);
        }
        UUID id=sms.send(request.phone(),request.purpose(),servlet.getSession().getId(),servlet.getRemoteAddr());
        return new ApiResponse<>(Map.of("challengeId",id,"expiresIn",300,"retryAfter",60));
    }
    @PostMapping("/login")
    ApiResponse<Map<String,String>> login(@RequestBody Code request,HttpServletRequest servlet,HttpServletResponse response) {
        String phone=sms.verify(request.challengeId(),request.code(),"LOGIN",servlet.getSession().getId());
        var login=identities.login(phone);
        servlet.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PhoneAuthenticationToken(login.subject(),login.sessionVersion()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,servlet,response);
        audit.record(login.subject(),servlet.getSession().getId(),"LOGIN_SUCCESS","{\"amr\":[\"sms\"]}");
        return new ApiResponse<>(Map.of("subjectId",login.subject(),"authenticationMethod","sms"));
    }
    @PostMapping("/bind")
    ApiResponse<Map<String,Boolean>> bind(@RequestBody Code request,Authentication authentication,HttpServletRequest servlet) {
        String target=freshOriginal(authentication);
        identities.bind(target,sms.verify(request.challengeId(),request.code(),"BIND",servlet.getSession().getId()));
        return new ApiResponse<>(Map.of("bound",true));
    }
    @PostMapping("/merge-preview")
    ApiResponse<PhoneIdentityService.Preview> preview(@RequestBody Code request,Authentication authentication,HttpServletRequest servlet) {
        String target=freshOriginal(authentication),session=servlet.getSession().getId();
        return new ApiResponse<>(identities.preview(target,sms.verify(request.challengeId(),request.code(),"MERGE",session),session));
    }
    @PostMapping("/merge")
    ApiResponse<Map<String,Boolean>> merge(@RequestBody Merge request,Authentication authentication,HttpServletRequest servlet) {
        identities.merge(freshOriginal(authentication),request.ticketId(),servlet.getSession().getId(),request.regionChoice());
        servlet.getSession().invalidate();SecurityContextHolder.clearContext();
        return new ApiResponse<>(Map.of("merged",true,"loginRequired",true));
    }
    private String freshOriginal(Authentication authentication) {
        OidcUser user=oidc(authentication);
        Instant time=user.getAuthenticatedAt();
        if(time==null||time.isBefore(Instant.now().minusSeconds(600))||time.isAfter(Instant.now().plusSeconds(30)))
            throw new AccessDeniedException("FRESH_LOGIN_REQUIRED","请先重新使用原账号密码登录，再绑定或合并");
        return principals.findEnabledByOidcIdentity(user.getIssuer().toString(),user.getSubject())
                .orElseThrow(AuthenticationRequiredException::new).subjectId();
    }
    private static OidcUser oidc(Authentication authentication) {
        if(authentication instanceof OAuth2AuthenticationToken token&&token.getPrincipal() instanceof OidcUser user
                &&user.getIssuer()!=null)return user;
        throw new AuthenticationRequiredException();
    }
    record Send(String phone,String purpose) {}
    record Code(UUID challengeId,String code) {}
    record Merge(UUID ticketId,PhoneIdentityService.RegionChoice regionChoice) {}
}
