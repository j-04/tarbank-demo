package com.tarbank.common.config;

import com.tarbank.common.http.ApiSecurityErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class BootstrapSecurityConfiguration {

    private static final int MANAGEMENT_PORT = 8081;

    @Bean
    @Order(1)
    SecurityFilterChain managementHealthSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(request -> request.getLocalPort() == MANAGEMENT_PORT)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http, ApiSecurityErrorWriter errorWriter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(response, HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(response, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "Access is denied.")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .build();
    }

    @Bean
    UserDetailsService bootstrapUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}