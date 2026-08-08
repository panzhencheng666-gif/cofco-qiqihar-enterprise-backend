package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@EnableWebSecurity
public class ProductionSecurityConfiguration {

    @Bean
    SecurityFilterChain productionSecurityFilterChain(
            HttpSecurity http, SecurityStartupInvariant startupInvariant) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
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
