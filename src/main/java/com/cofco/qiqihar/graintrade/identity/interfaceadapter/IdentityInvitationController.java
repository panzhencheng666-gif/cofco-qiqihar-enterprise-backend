package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.IdentityActivationResult;
import com.cofco.qiqihar.graintrade.identity.application.IdentityGovernanceService;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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

    @PostMapping("/activate")
    ApiResponse<IdentityActivationResult> activate(
            Authentication authentication,@RequestBody ActivationRequest request) {
        if(!(authentication instanceof OAuth2AuthenticationToken token)
                ||!(token.getPrincipal() instanceof OidcUser user)
                ||user.getIssuer()==null||request==null) {
            throw new AuthenticationRequiredException();
        }
        return new ApiResponse<>(service.activate(
                request.token(),user.getIssuer().toString(),user.getSubject()));
    }

    record ActivationRequest(String token) {}

    @PostMapping("/{invitationId}/revoke")
    ApiResponse<com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationReceipt> revoke(
            @PathVariable java.util.UUID invitationId) {
        return new ApiResponse<>(service.revokeInvitation(invitationId));
    }
}
