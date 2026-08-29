package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.cofco.qiqihar.graintrade.shared.security.infrastructure.JdbcOidcSessionRegistry;
import tools.jackson.databind.ObjectMapper;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@EnableWebSecurity
public class ProductionSecurityConfiguration {

    @Bean
    OidcSessionRegistry enterpriseOidcSessionRegistry(
            JdbcClient jdbc,ObjectMapper json,
            @Value("${qiqihar.security.maximum-concurrent-sessions:1}") int maximumSessions) {
        return new JdbcOidcSessionRegistry(jdbc,json,maximumSessions);
    }

    @Bean
    OidcBackChannelLogoutHandler enterpriseBackChannelLogoutHandler(OidcSessionRegistry sessions) {
        OidcBackChannelLogoutHandler handler=new OidcBackChannelLogoutHandler(sessions);
        handler.setSessionCookieName("COFCO_SESSION");
        return handler;
    }

    @Bean
    HttpSessionEventPublisher enterpriseHttpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    ClientRegistrationRepository enterpriseClientRegistrationRepository(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${qiqihar.security.oidc.client-id:}") String clientId,
            @Value("${qiqihar.security.oidc.client-secret:}") String clientSecret,
            @Value("${qiqihar.security.oidc.authorization-uri:}") String authorizationUri,
            @Value("${qiqihar.security.oidc.token-uri:}") String tokenUri,
            @Value("${qiqihar.security.oidc.jwk-set-uri:}") String jwkSetUri,
            @Value("${qiqihar.security.oidc.user-info-uri:}") String userInfoUri,
            @Value("${qiqihar.security.oidc.end-session-uri:}") String endSessionUri,
            @Value("${qiqihar.security.oidc.redirect-uri:}") String redirectUri) {
        ClientRegistration.Builder registration;
        if (authorizationUri.isBlank() || tokenUri.isBlank() || jwkSetUri.isBlank()) {
            registration = ClientRegistrations.fromIssuerLocation(issuerUri);
        } else {
            registration = ClientRegistration.withRegistrationId("enterprise")
                    .authorizationUri(authorizationUri)
                    .tokenUri(tokenUri)
                    .jwkSetUri(jwkSetUri)
                    .issuerUri(issuerUri);
            if (!userInfoUri.isBlank()) {
                registration.userInfoUri(userInfoUri).userNameAttributeName(IdTokenClaimNames.SUB);
            }
            if (!endSessionUri.isBlank()) {
                registration.providerConfigurationMetadata(Map.of("end_session_endpoint",endSessionUri));
            }
        }
        return new InMemoryClientRegistrationRepository(registration
                .registrationId("enterprise")
                .clientName("企业统一身份认证")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "profile")
                .build());
    }

    @Bean
    SecurityFilterChain productionSecurityFilterChain(
            HttpSecurity http,
            SecurityStartupInvariant startupInvariant,
            SecurityPrincipalRepository principals,
            SecuritySessionAuditRecorder sessionAudit,
            ClientRegistrationRepository clientRegistrations,
            @Value("${qiqihar.security.oidc.mfa-amr-values:}") String mfaAmrValues,
            @Value("${qiqihar.security.oidc.mfa-acr-values:}") String mfaAcrValues,
            @Value("${qiqihar.security.oidc.post-logout-redirect-uri:}") String postLogoutRedirectUri,
            @Value("${qiqihar.security.session-cookie-secure:true}") boolean secureCookies) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secureCookies));
        CsrfTokenRequestAttributeHandler csrfRequestHandler=new CsrfTokenRequestAttributeHandler();
        Set<String> acceptedAmr=values(mfaAmrValues);
        Set<String> acceptedAcr=values(mfaAcrValues);
        AuthenticationSuccessHandler loginSuccess=new EnterpriseAuthenticationSuccessHandler(
                principals,sessionAudit,acceptedAmr,acceptedAcr);
        OidcClientInitiatedLogoutSuccessHandler providerLogout=
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        providerLogout.setPostLogoutRedirectUri(postLogoutRedirectUri);
        LogoutSuccessHandler logoutSuccess=providerLogout;
        LogoutHandler logoutAudit=new SecuritySessionLogoutHandler(sessionAudit);
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith(request.getContextPath() + "/api/")))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/session/login", "/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/logout/connect/back-channel/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login.successHandler(loginSuccess))
                .oidcLogout(oidc -> oidc.backChannel(backChannel -> { }))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/session/logout")
                        .deleteCookies("COFCO_SESSION")
                        .addLogoutHandler(logoutAudit)
                        .logoutSuccessHandler(logoutSuccess))
                .addFilterBefore(new OidcBackChannelFailureResponseFilter(), SecurityContextHolderFilter.class)
                .addFilterBefore(new ExpiredSessionAuditFilter(sessionAudit),AnonymousAuthenticationFilter.class)
                .addFilterBefore(new EnterpriseOidcAccessFilter(
                        acceptedAmr,acceptedAcr,principals,sessionAudit),AuthorizationFilter.class)
                .addFilterAfter(new CsrfCookieExposureFilter(),AuthorizationFilter.class);
        return http.build();
    }

    private static Set<String> values(String configured) {
        return Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static final class EnterpriseOidcAccessFilter extends OncePerRequestFilter {
        private final Set<String> acceptedAmr;
        private final Set<String> acceptedAcr;
        private final SecurityPrincipalRepository principals;
        private final SecuritySessionAuditRecorder audit;

        private EnterpriseOidcAccessFilter(Set<String> acceptedAmr,Set<String> acceptedAcr,
                SecurityPrincipalRepository principals,SecuritySessionAuditRecorder audit) {
            this.acceptedAmr = Set.copyOf(acceptedAmr);
            this.acceptedAcr = Set.copyOf(acceptedAcr);
            this.principals=principals;
            this.audit=audit;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (protectedApi(request) && authenticated(authentication)) {
                if (!approvedMfa(authentication)) {
                    deny(request,response,authentication,"MFA_REQUIRED");
                    return;
                }
                SecurityPrincipal principal=findEnabledOidc(principals,authentication).orElse(null);
                if(principal==null&&invitationActivationEntry(request)) {
                    filterChain.doFilter(request,response);
                    return;
                }
                if(principal==null||principal.roleCodes().isEmpty()) {
                    deny(request,response,authentication,principal==null?"SUBJECT_DISABLED":"ROLE_REQUIRED");
                    return;
                }
                if(authentication instanceof StableSubjectOAuth2AuthenticationToken
                        && !authentication.getName().equals(principal.subjectId())) {
                    deny(request,response,authentication,"BINDING_CHANGED");
                    return;
                }
                if(authentication instanceof StableSubjectOAuth2AuthenticationToken stable
                        && !stable.authorizationFingerprint().equals(authorizationFingerprint(principal))) {
                    deny(request,response,authentication,"IDENTITY_CHANGED");
                    return;
                }
                authentication=bindStableSubject(authentication,principal);
            }
            filterChain.doFilter(request, response);
        }

        private void deny(HttpServletRequest request,HttpServletResponse response,
                Authentication authentication,String reason) throws IOException {
            var session=request.getSession(false);
            audit.record(authentication.getName(),session==null?null:session.getId(),
                    "SESSION_ACCESS_DENIED","{\"reason\":\""+reason+"\"}");
            if(session!=null)session.invalidate();
            SecurityContextHolder.clearContext();
            response.sendError(HttpStatus.FORBIDDEN.value());
        }

        private boolean approvedMfa(Authentication authentication) {
            return ProductionSecurityConfiguration.approvedMfa(authentication,acceptedAmr,acceptedAcr);
        }

        private static boolean protectedApi(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.startsWith("/api/v1/") && !path.equals("/api/v1/session/login");
        }

        private static boolean invitationActivationEntry(HttpServletRequest request) {
            String path=request.getRequestURI().substring(request.getContextPath().length());
            return (request.getMethod().equals("POST")
                    && path.equals("/api/v1/identity/invitations/activate"))
                    ||(request.getMethod().equals("GET")
                    && path.equals("/api/v1/identity/invitations/activation-bootstrap"));
        }

        private static boolean authenticated(Authentication authentication) {
            return authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
        }
    }

    private static final class EnterpriseAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
        private final SecurityPrincipalRepository principals;
        private final SecuritySessionAuditRecorder audit;
        private final Set<String> acceptedAmr;
        private final Set<String> acceptedAcr;
        private final AuthenticationSuccessHandler delegate=new SavedRequestAwareAuthenticationSuccessHandler();

        private EnterpriseAuthenticationSuccessHandler(SecurityPrincipalRepository principals,
                SecuritySessionAuditRecorder audit,Set<String> acceptedAmr,Set<String> acceptedAcr) {
            this.principals=principals;
            this.audit=audit;
            this.acceptedAmr=acceptedAmr;
            this.acceptedAcr=acceptedAcr;
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,
                Authentication authentication) throws IOException,ServletException {
            SecurityPrincipal principal=findEnabledOidc(principals,authentication).orElse(null);
            String reason=!approvedMfa(authentication,acceptedAmr,acceptedAcr)?"MFA_REQUIRED"
                    : principal!=null&&principal.roleCodes().isEmpty()?"ROLE_REQUIRED":null;
            if(reason!=null) {
                var session=request.getSession(false);
                audit.record(authentication.getName(),session==null?null:session.getId(),
                        "LOGIN_DENIED","{\"reason\":\""+reason+"\"}");
                if(session!=null)session.invalidate();
                SecurityContextHolder.clearContext();
                response.sendError(HttpStatus.FORBIDDEN.value());
                return;
            }
            if(principal==null) {
                var session=request.getSession();
                audit.record(authentication.getName(),session.getId(),
                        "LOGIN_ACTIVATION_REQUIRED","{}");
                delegate.onAuthenticationSuccess(request,response,authentication);
                return;
            }
            var session=request.getSession();
            Authentication stableAuthentication=bindStableSubject(authentication,principal);
            audit.record(stableAuthentication.getName(),session.getId(),"LOGIN_SUCCESS","{}");
            delegate.onAuthenticationSuccess(request,response,stableAuthentication);
        }
    }

    private static final class SecuritySessionLogoutHandler implements LogoutHandler {
        private final SecuritySessionAuditRecorder audit;

        private SecuritySessionLogoutHandler(SecuritySessionAuditRecorder audit){this.audit=audit;}

        @Override
        public void logout(HttpServletRequest request,HttpServletResponse response,Authentication authentication) {
            var session=request.getSession(false);
            boolean providerInitiated = authentication != null
                    && authentication.getPrincipal() instanceof OidcLogoutToken;
            String subject = authentication == null ? null : authentication.getName();
            if (providerInitiated && authentication.getPrincipal() instanceof OidcLogoutToken token) {
                subject = token.getSubject();
            }
            audit.record(subject,session==null?null:session.getId(),
                    providerInitiated ? "OIDC_BACK_CHANNEL_LOGOUT" : "LOGOUT","{}");
        }
    }

    private static final class OidcBackChannelFailureResponseFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                FilterChain chain) throws ServletException,IOException {
            try {
                chain.doFilter(request,response);
            } catch (AuthenticationServiceException exception) {
                if (!isBackChannelLogout(request)) {
                    throw exception;
                }
                response.resetBuffer();
                response.setStatus(HttpStatus.BAD_REQUEST.value());
            }
        }

        private static boolean isBackChannelLogout(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return request.getMethod().equals("POST")
                    && path.startsWith("/logout/connect/back-channel/");
        }
    }

    private static final class ExpiredSessionAuditFilter extends OncePerRequestFilter {
        private final SecuritySessionAuditRecorder audit;

        private ExpiredSessionAuditFilter(SecuritySessionAuditRecorder audit){this.audit=audit;}

        @Override
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                FilterChain chain) throws ServletException,IOException {
            String expiredSessionId=sessionCookie(request);
            if(EnterpriseOidcAccessFilter.protectedApi(request)&&expiredSessionId!=null
                    && request.getSession(false)==null) {
                audit.record(null,expiredSessionId,"SESSION_EXPIRED","{}");
            }
            chain.doFilter(request,response);
        }

        private static String sessionCookie(HttpServletRequest request) {
            if(request.getCookies()==null)return null;
            return Arrays.stream(request.getCookies())
                    .filter(cookie->cookie.getName().equals("COFCO_SESSION"))
                    .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
        }
    }

    private static final class CsrfCookieExposureFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                FilterChain chain) throws ServletException,IOException {
            CsrfToken csrfToken=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
            if(csrfToken!=null)csrfToken.getToken();
            chain.doFilter(request,response);
        }
    }

    private static boolean approvedMfa(Authentication authentication,Set<String> acceptedAmr,Set<String> acceptedAcr) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser user)) return false;
        Object amrClaim=user.getClaims().get("amr");
        if(amrClaim instanceof Iterable<?> methods) {
            for(Object method:methods)if(method!=null&&acceptedAmr.contains(method.toString()))return true;
        }
        Object acrClaim=user.getClaims().get("acr");
        return acrClaim!=null&&acceptedAcr.contains(acrClaim.toString());
    }

    private static Authentication bindStableSubject(Authentication authentication,SecurityPrincipal principal) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || authentication instanceof StableSubjectOAuth2AuthenticationToken) return authentication;
        var stable=new StableSubjectOAuth2AuthenticationToken(
                token,principal.subjectId(),authorizationFingerprint(principal));
        SecurityContextHolder.getContext().setAuthentication(stable);
        return stable;
    }

    private static String authorizationFingerprint(SecurityPrincipal principal) {
        return String.join("|",principal.subjectId(),principal.workUnitCode(),
                principal.accountStatus(),principal.employmentStatus(),
                principal.roleCodes().stream().sorted().collect(Collectors.joining(",")),
                principal.permissionCodes().stream().sorted().collect(Collectors.joining(",")),
                principal.regionCodes().stream().sorted().collect(Collectors.joining(",")));
    }

    private static java.util.Optional<SecurityPrincipal> findEnabledOidc(
            SecurityPrincipalRepository principals,Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token) {
            if(token.getPrincipal() instanceof OidcUser user
                    && user.getIssuer()!=null && user.getSubject()!=null) {
                return principals.findEnabledByOidcIdentity(user.getIssuer().toString(),user.getSubject());
            }
            return java.util.Optional.empty();
        }
        return principals.findEnabled(authentication.getName());
    }

    private static final class StableSubjectOAuth2AuthenticationToken extends OAuth2AuthenticationToken {
        private final String stableSubjectId;
        private final String authorizationFingerprint;

        private StableSubjectOAuth2AuthenticationToken(
                OAuth2AuthenticationToken source,String stableSubjectId,String authorizationFingerprint) {
            super(source.getPrincipal(),source.getAuthorities(),source.getAuthorizedClientRegistrationId());
            this.stableSubjectId=stableSubjectId;
            this.authorizationFingerprint=authorizationFingerprint;
            setDetails(source.getDetails());
        }

        @Override
        public String getName() {
            return stableSubjectId;
        }

        private String authorizationFingerprint() {
            return authorizationFingerprint;
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableWebSecurity
class LocalSecurityConfiguration {

    @Bean
    SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            SecurityStartupInvariant startupInvariant,
            @Value("${qiqihar.security.trusted-subject-header:}") String trustedSubjectHeader) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new LocalActorAuthenticationFilter(trustedSubjectHeader),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    private static final class LocalActorAuthenticationFilter extends OncePerRequestFilter {
        private final String headerName;

        private LocalActorAuthenticationFilter(String headerName) {
            this.headerName = headerName;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String subject = headerName.isBlank() ? null : request.getHeader(headerName);
            if (subject != null && !subject.isBlank()) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        subject.trim(), "N/A", List.of()));
                SecurityContextHolder.setContext(context);
            }
            filterChain.doFilter(request, response);
        }
    }
}
