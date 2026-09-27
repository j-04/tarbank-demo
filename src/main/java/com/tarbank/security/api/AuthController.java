package com.tarbank.security.api;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.security.application.AuthService;
import com.tarbank.security.application.TarbankPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiSuccessResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        var token = auth.login(request.username(), request.password());
        return ResponseEntity.ok(new ApiSuccessResponse<>(new LoginResponse(token.value(), "Bearer", token.expiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond()), CorrelationIdContext.current()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiSuccessResponse<Map<String, String>>> logout(@AuthenticationPrincipal TarbankPrincipal principal) {
        auth.logout(principal);
        return ResponseEntity.ok(new ApiSuccessResponse<>(Map.of("status", "LOGGED_OUT"), CorrelationIdContext.current()));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}