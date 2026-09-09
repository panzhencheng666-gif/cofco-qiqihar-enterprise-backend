package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.FirstAdministratorService;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!local & !test")
public class FirstAdministratorController {
    private final FirstAdministratorService service;
    public FirstAdministratorController(FirstAdministratorService service) {this.service=service;}

    @PostMapping("/api/v1/identity/invitations/first-administrator")
    public void bind(Authentication authentication,@RequestBody Claim request,HttpServletRequest servletRequest) {
        if(!(authentication instanceof OAuth2AuthenticationToken token)
                ||!token.isAuthenticated()||!(token.getPrincipal() instanceof OidcUser user)
                ||user.getIssuer()==null||request==null)throw new AuthenticationRequiredException();
        service.bind(user.getIssuer().toString(),user.getSubject(),request.token());
        var session=servletRequest.getSession(false);
        if(session!=null)session.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    public record Claim(String token) {}
}
