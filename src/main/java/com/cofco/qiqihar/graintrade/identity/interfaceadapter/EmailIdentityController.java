package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.interfaceadapter.EmailAuthenticationToken;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/email")
public class EmailIdentityController {
    private final EmailChallengeService challenges;
    private final EmailIdentityService identities;
    private final com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder audit;
    public EmailIdentityController(EmailChallengeService challenges,EmailIdentityService identities,
            com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder audit) {
        this.challenges=challenges;this.identities=identities;this.audit=audit;
    }
    @GetMapping("/bootstrap") ApiResponse<Map<String,String>> bootstrap(HttpServletRequest request) {
        request.getSession();return new ApiResponse<>(Map.of("authenticationMethod","email"));
    }
    @PostMapping("/challenge") ApiResponse<Map<String,Object>> challenge(@RequestBody Send input,HttpServletRequest request) {
        if(input==null||(!"LOGIN".equals(input.purpose())&&!"BIND".equals(input.purpose())))
            throw new com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException();
        UUID id=challenges.send(input.email(),input.purpose(),request.getSession().getId(),request.getRemoteAddr());
        return new ApiResponse<>(Map.of("challengeId",id,"expiresIn",300,"retryAfter",60));
    }
    @PostMapping("/login") ApiResponse<Map<String,String>> login(@RequestBody Code input,
            HttpServletRequest request,HttpServletResponse response) {
        String email=challenges.verify(input.challengeId(),input.code(),"LOGIN",request.getSession().getId());
        var login=identities.login(email);
        request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new EmailAuthenticationToken(login.subject(),login.sessionVersion()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        audit.record(login.subject(),request.getSession().getId(),"LOGIN_SUCCESS","{\"amr\":[\"email_otp\"]}");
        return new ApiResponse<>(Map.of("subjectId",login.subject(),"authenticationMethod","email"));
    }
    @PostMapping("/bind") ApiResponse<Map<String,Boolean>> bind(@RequestBody Code input,Authentication authentication,HttpServletRequest request) {
        identities.verifyBinding(authentication.getName(),challenges.verify(input.challengeId(),input.code(),"BIND",request.getSession().getId()));
        return new ApiResponse<>(Map.of("bound",true));
    }
    record Send(String email,String purpose) {}
    record Code(UUID challengeId,String code) {}
}
