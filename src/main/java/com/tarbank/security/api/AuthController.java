package com.tarbank.security.api;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.resilience.RateLimitService;
import com.tarbank.security.application.AuthService;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "JWT session creation and invalidation.")
public class AuthController {
    private final AuthService auth;

    private final RateLimitService rateLimits;

    public AuthController(AuthService auth,
                          RateLimitService rateLimits) {
        this.auth = auth;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate a user", description = "Issues a one-hour JWT by default.")
    public ResponseEntity<ApiSuccessResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        rateLimits.checkLogin(httpRequest.getRemoteAddr(), request.username());
        var token = auth.login(request.username(), request.password());
        return ResponseEntity.ok(new ApiSuccessResponse<>(new LoginResponse(token.value(), "Bearer", token.expiresAt()
                                                                                                          .getEpochSecond() - java.time.Instant.now()
                                                                                                                                               .getEpochSecond()),
                                                          CorrelationIdContext.current()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate the current JWT")
    @ApiResponse(responseCode = "200", description = "Token invalidated until its expiry.",
            useReturnTypeSchema = true)
    public ResponseEntity<ApiSuccessResponse<Map<String, String>>> logout(
            @AuthenticationPrincipal TarbankPrincipal principal) {
        auth.logout(principal);
        return ResponseEntity.ok(
                new ApiSuccessResponse<>(Map.of("status", "LOGGED_OUT"), CorrelationIdContext.current()));
    }

    public record LoginRequest(
            @Schema(minLength = 3, maxLength = 32, pattern = "^[a-z][a-z0-9._-]{2,31}$")
            @NotBlank String username,
            @Schema(minLength = 12, maxLength = 12, pattern = "^[\\x20-\\x7E]{12}$")
            @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
