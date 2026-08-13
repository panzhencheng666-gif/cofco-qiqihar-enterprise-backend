package com.cofco.qiqihar.graintrade.testsupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only security: explicit MockMvc principals are trusted; anonymous API traffic is not. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class TestSecurityConfiguration {
    private static final String UNRESTRICTED_TEST_READ = "__UNRESTRICTED_TEST_READ__";

    @Bean
    SecurityFilterChain testSecurityFilterChain(
            HttpSecurity http,
            @Value("${qiqihar.security.test-default-subject:}") String defaultSubject) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ExplicitTestPrincipalFilter(defaultSubject),
                        SecurityContextHolderAwareRequestFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    "{\"error\":{\"code\":\"AUTHENTICATION_REQUIRED\","
                                    + "\"message\":\"Authentication is required\"}}");
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    private static final class ExplicitTestPrincipalFilter extends OncePerRequestFilter {
        private final String defaultSubject;

        private ExplicitTestPrincipalFilter(String defaultSubject) {
            this.defaultSubject = defaultSubject;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            var principal = request.getUserPrincipal();
            String subject = principal == null ? null : principal.getName();
            if (subject == null || subject.isBlank()) {
                subject = defaultSubject;
            }
            if (subject != null && !subject.isBlank()) {
                Object authenticationPrincipal = UNRESTRICTED_TEST_READ.equals(subject)
                        ? (Principal) () -> ""
                        : subject.trim();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        authenticationPrincipal, "N/A", List.of()));
                SecurityContextHolder.setContext(context);
            }
            filterChain.doFilter(request, response);
        }
    }
}
