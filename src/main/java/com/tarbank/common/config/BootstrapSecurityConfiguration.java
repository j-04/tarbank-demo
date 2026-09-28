package com.tarbank.common.config;

import com.tarbank.common.http.ApiSecurityErrorWriter;
import com.tarbank.security.application.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BootstrapSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain management(HttpSecurity http,
                                   @Value("${management.server.port:8081}") int port) throws Exception {
        return http.securityMatcher(r -> r.getLocalPort() == port)
                   .csrf(AbstractHttpConfigurer::disable)
                   .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                   .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**")
                                                .permitAll()
                                                .anyRequest()
                                                .denyAll())
                   .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain application(HttpSecurity http,
                                    ApiSecurityErrorWriter errors,
                                    JwtAuthenticationFilter jwt) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                   .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                   .exceptionHandling(e -> e.authenticationEntryPoint((q, p, x) -> errors.write(p, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."))
                                            .accessDeniedHandler((q, p, x) -> errors.write(p, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.")))
                   .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                   .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                                                .permitAll()
                                                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                                                .permitAll()
                                                .requestMatchers("/api/v1/auth/logout")
                                                .authenticated()
                                                .requestMatchers("/api/v1/customers/**")
                                                .hasRole("MANAGER")
                                                .requestMatchers(HttpMethod.POST,
                                                                 "/api/v1/accounts/*/deposits",
                                                                 "/api/v1/accounts/*/withdrawals",
                                                                 "/api/v1/accounts/*/transfers")
                                                .hasRole("CUSTOMER")
                                                .requestMatchers(HttpMethod.PATCH, "/api/v1/accounts/*/status")
                                                .hasRole("MANAGER")
                                                .requestMatchers(HttpMethod.PATCH,
                                                                 "/api/v1/accounts/*/daily-limits")
                                                .hasAnyRole("MANAGER", "CUSTOMER")
                                                .requestMatchers(HttpMethod.GET, "/api/v1/accounts")
                                                .hasRole("CUSTOMER")
                                                .requestMatchers(HttpMethod.GET,
                                                                 "/api/v1/accounts/*/transactions")
                                                .hasAnyRole("MANAGER", "CUSTOMER")
                                                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*")
                                                .hasAnyRole("MANAGER", "CUSTOMER")
                                                .anyRequest()
                                                .denyAll())
                   .build();
    }
}
