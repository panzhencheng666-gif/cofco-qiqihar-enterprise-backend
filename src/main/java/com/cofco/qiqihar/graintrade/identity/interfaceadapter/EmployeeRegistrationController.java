package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.*;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/registration")
public class EmployeeRegistrationController {
    private final EmployeeRegistrationService service;
    public EmployeeRegistrationController(EmployeeRegistrationService service){this.service=service;}
    @GetMapping("/options")
    ApiResponse<AssignmentOptions> options(Authentication authentication,
            @RequestParam(defaultValue="QIQIHAR_BUSINESS") String workUnitCode) {
        identity(authentication);
        return new ApiResponse<>(service.options(workUnitCode));
    }
    @PostMapping
    ApiResponse<IdentityActivationResult> register(Authentication authentication,
            @RequestBody RegistrationRequest request,HttpServletRequest servletRequest) {
        OidcUser user=identity(authentication);
        if(request==null||request.workUnitCode()==null||request.workUnitCode().isBlank()
                ||hasNull(request.roleCodes())||hasNull(request.positionCodes())||hasNull(request.regionCodes()))
            throw new com.cofco.qiqihar.graintrade.shared.application.ClientRequestException(
                    "INVALID_REGISTRATION", "请完整填写员工资料，选项不能为空");
        var result=service.register(user.getIssuer().toString(),user.getSubject(),user.getPreferredUsername(),
                new EmployeeAssignment(request.displayName(),request.workUnitCode(),"ACTIVE","ACTIVE",
                    request.roleCodes()==null?List.of("BUSINESS_OPERATOR"):request.roleCodes(),
                    request.positionCodes()==null?List.of():request.positionCodes(),
                    request.regionCodes()==null?List.of():request.regionCodes()));
        var session=servletRequest.getSession(false);
        if(session!=null)session.invalidate();
        return new ApiResponse<>(result);
    }
    private static boolean hasNull(List<String> values) {
        return values!=null&&values.stream().anyMatch(java.util.Objects::isNull);
    }
    private static OidcUser identity(Authentication authentication) {
        if(authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user&&user.getIssuer()!=null)return user;
        throw new AuthenticationRequiredException();
    }
    record RegistrationRequest(String displayName,String workUnitCode,List<String> positionCodes,
            List<String> regionCodes,List<String> roleCodes) {}
}
