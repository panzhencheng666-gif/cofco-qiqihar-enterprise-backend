package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.IdentityActivationResult;
import com.cofco.qiqihar.graintrade.identity.application.IdentityGovernanceService;
import com.cofco.qiqihar.graintrade.identity.application.IdentityLifecycleContract;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/v1/identity/invitations")
public class IdentityInvitationController {
    private final IdentityGovernanceService service;

    public IdentityInvitationController(IdentityGovernanceService service) {
        this.service=service;
    }

    @GetMapping("/activation-bootstrap")
    ApiResponse<ActivationBootstrap> activationBootstrap() {
        return new ApiResponse<>(new ActivationBootstrap(IdentityLifecycleContract.VERSION,true));
    }

    @PostMapping("/activate")
    ApiResponse<IdentityActivationResult> activate(
            Authentication authentication,@RequestBody ActivationRequest request,
            HttpServletRequest servletRequest) {
        if(!(authentication instanceof OAuth2AuthenticationToken token)
                ||!(token.getPrincipal() instanceof OidcUser user)
                ||user.getIssuer()==null||request==null) {
            throw new AuthenticationRequiredException();
        }
        IdentityActivationResult result=service.activate(
                request.token(),user.getIssuer().toString(),user.getSubject());
        var session=servletRequest.getSession(false);
        if(session!=null)session.invalidate();
        return new ApiResponse<>(result);
    }

    record ActivationRequest(String token) {}

    record ActivationBootstrap(String contractVersion,boolean csrfReady) {}

    @PostMapping("/{invitationId}/revoke")
    ApiResponse<com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationReceipt> revoke(
            @PathVariable java.util.UUID invitationId) {
        return new ApiResponse<>(service.revokeInvitation(invitationId));
    }
}
