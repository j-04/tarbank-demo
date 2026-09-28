package com.tarbank.security.application;

import com.tarbank.common.http.ApiSecurityErrorWriter;
import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    private final UserRepository users;

    private final StringRedisTemplate redis;

    private final ApiSecurityErrorWriter errors;

    private final IdentityProfileValidator profiles;

    public JwtAuthenticationFilter(JwtService jwt,
                                   UserRepository users,
                                   StringRedisTemplate redis,
                                   ApiSecurityErrorWriter errors,
                                   IdentityProfileValidator profiles) {
        this.jwt = jwt;
        this.users = users;
        this.redis = redis;
        this.errors = errors;
        this.profiles = profiles;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.startsWith("Bearer ") || header.length() <= 7) {
            errors.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
            return;
        }
        try {
            TarbankPrincipal principal = jwt.verify(header.substring(7));
            var user = users.findById(principal.userId()).orElseThrow(IllegalArgumentException::new);
            if (user.getStatus() != UserStatus.ACTIVE || !profiles.matches(user)) {
                errors.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.");
                return;
            }
            if (user.getCredentialVersion() != principal.credentialVersion()
                    || Boolean.TRUE.equals(redis.hasKey("jwt:invalidated:" + principal.tokenId()))) {
                throw new IllegalArgumentException();
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                                                                                   List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
            errors.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
        }
    }
}