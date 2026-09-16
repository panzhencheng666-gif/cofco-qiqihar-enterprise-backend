package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/registration-entry")
public class RegistrationEntryController {
    private final EmployeeRegistrationService employees;
    private final RegistrationDraftService drafts;
    private final SmsChallengeService sms;
    private final EmailChallengeService email;
    public RegistrationEntryController(EmployeeRegistrationService employees,RegistrationDraftService drafts,
            SmsChallengeService sms,EmailChallengeService email) {
        this.employees=employees;this.drafts=drafts;this.sms=sms;this.email=email;
    }
    @GetMapping("/bootstrap")
    ApiResponse<Map<String,Object>> bootstrap(HttpServletRequest request, Authentication authentication) {
        request.getSession();
        CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        OidcUser user=oidc(authentication);
        boolean registered=user!=null && employees.alreadyRegistered(user.getIssuer().toString(),user.getSubject());
        return new ApiResponse<>(Map.of("csrfToken",token.getToken(),"registered",registered,
                "credentialsComplete",user!=null && !registered,"draft",drafts.state(request.getSession()),"registrationError",drafts.error(request.getSession()),
                "username",user==null?"":java.util.Objects.toString(user.getPreferredUsername(),""),
                "phone",user==null?"":java.util.Objects.toString(user.getClaimAsString("phone_number"),"")));
    }
    @GetMapping("/options")
    ApiResponse<AssignmentOptions> options(@RequestParam(defaultValue="QIQIHAR_BUSINESS") String workUnitCode) {
        return new ApiResponse<>(employees.options(workUnitCode));
    }
    @PostMapping("/challenge")
    ApiResponse<Map<String,Object>> challenge(@RequestBody Verification input,HttpServletRequest request) {
        employees.options("QIQIHAR_BUSINESS");
        String method=input.verificationMethod();
        if (!"PHONE".equals(method) && !"EMAIL".equals(method)) {
            throw new com.cofco.qiqihar.graintrade.shared.application.ClientRequestException(
                    "REGISTRATION_VERIFICATION_METHOD_INVALID", "请选择手机验证码或邮箱验证码");
        }
        UUID id="EMAIL".equals(method)
                ? email.send(input.email(),"REGISTER",request.getSession().getId(),request.getRemoteAddr())
                : sms.send(input.phone(),"REGISTER",request.getSession().getId(),request.getRemoteAddr());
        return new ApiResponse<>(Map.of("challengeId",id,"expiresIn",300,"retryAfter",60));
    }
    @PostMapping("/draft")
    ApiResponse<Map<String,Boolean>> draft(@RequestBody Draft input,HttpServletRequest request,Authentication authentication) {
        drafts.prepare(request.getSession(),input.username(),input.displayName(),input.workUnitCode(),
                input.regionCodes(),input.phone(),input.email(),input.verificationMethod(),input.challengeId(),input.code());
        OidcUser user=oidc(authentication);
        boolean complete=user!=null && drafts.completeAuthenticated(request.getSession(),user);
        return new ApiResponse<>(Map.of("ready",true,"complete",complete));
    }
    private static OidcUser oidc(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user ? user : null;
    }
    record Verification(String verificationMethod,String phone,String email) {}
    record Draft(String username,String displayName,String workUnitCode,List<String> regionCodes,
            String phone,String email,String verificationMethod,UUID challengeId,String code) {}
}
