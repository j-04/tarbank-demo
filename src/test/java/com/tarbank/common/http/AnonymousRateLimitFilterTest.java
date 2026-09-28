package com.tarbank.common.http;

import com.tarbank.common.resilience.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousRateLimitFilterTest {
    @Test
    void unavailableRedisFailsClosedBeforeProtectedBusinessLogic() throws Exception {
        RateLimitService limits = mock(RateLimitService.class);
        ApiSecurityErrorWriter errors = mock(ApiSecurityErrorWriter.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(request.getRemoteAddr()).thenReturn("192.0.2.1");
        doThrow(new DependencyUnavailableException()).when(limits).checkAnonymous("192.0.2.1");

        new AnonymousRateLimitFilter(limits, errors).doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(errors).write(response, HttpStatus.SERVICE_UNAVAILABLE,
                             "DEPENDENCY_UNAVAILABLE",
                             "A required service is temporarily unavailable.");
    }
}
