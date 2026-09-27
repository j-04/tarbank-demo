package com.tarbank.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarbank.common.api.ApiSuccessResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CorrelationIdFilterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final CorrelationIdFilter filter = new CorrelationIdFilter(jsonMapper);

    @Test
    void generatesAndReturnsCorrelationIdWhenHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, successfulResponseChain());

        UUID correlationId = UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER_NAME));
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("correlationId").asString()).isEqualTo(correlationId.toString());
    }

    @Test
    void preservesValidSuppliedCorrelationId() throws Exception {
        UUID supplied = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, supplied.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, successfulResponseChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(supplied.toString());
        assertThat(jsonMapper.readTree(response.getContentAsString()).path("correlationId").asString())
                .isEqualTo(supplied.toString());
    }

    @Test
    void rejectsMalformedSuppliedCorrelationIdWithoutEchoingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run.");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNull();
        assertThat(response.getContentAsString()).doesNotContain("not-a-uuid");
        assertThat(jsonMapper.readTree(response.getContentAsString()).path("correlationId").isNull()).isTrue();
    }

    @Test
    void rejectsShortenedUuidForm() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "1-1-1-1-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run.");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).doesNotContain("1-1-1-1-1");
    }

    private jakarta.servlet.FilterChain successfulResponseChain() {
        return (ignoredRequest, response) -> {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(jsonMapper.writeValueAsString(
                    new ApiSuccessResponse<>("ok", CorrelationIdContext.current())));
        };
    }
}