package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
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
    public RegistrationEntryController(EmployeeRegistrationService employees,RegistrationDraftService drafts,SmsChallengeService sms) {
        this.employees=employees;this.drafts=drafts;this.sms=sms;
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
    ApiResponse<Map<String,Object>> challenge(@RequestBody Phone input,HttpServletRequest request) {
        employees.options("QIQIHAR_BUSINESS");
        return new ApiResponse<>(Map.of("challengeId",sms.send(input.phone(),"REGISTER",request.getSession().getId(),request.getRemoteAddr()),"retryAfter",60));
    }
    @PostMapping("/draft")
    ApiResponse<Map<String,Boolean>> draft(@RequestBody Draft input,HttpServletRequest request,Authentication authentication) {
        drafts.prepare(request.getSession(),input.username(),input.displayName(),input.workUnitCode(),input.regionCodes(),input.phone(),input.challengeId(),input.code());
        OidcUser user=oidc(authentication);
        boolean complete=user!=null && drafts.completeAuthenticated(request.getSession(),user);
        return new ApiResponse<>(Map.of("ready",true,"complete",complete));
    }
    private static OidcUser oidc(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user ? user : null;
    }
    record Phone(String phone) {}
    record Draft(String username,String displayName,String workUnitCode,List<String> regionCodes,String phone,UUID challengeId,String code) {}
}
