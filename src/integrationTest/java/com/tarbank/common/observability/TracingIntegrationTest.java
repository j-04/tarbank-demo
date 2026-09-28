package com.tarbank.common.observability;

import com.tarbank.common.http.CorrelationIdFilter;
import com.tarbank.support.AbstractIntegrationTest;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import(TracingIntegrationTest.ExporterConfiguration.class)
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class TracingIntegrationTest extends AbstractIntegrationTest {
    private static final AttributeKey<String> CORRELATION_ID =
            AttributeKey.stringKey("correlation.id");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private Tracer tracer;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Test
    void runtimeExportsCorrelatedHttpRedisPostgresAndMoneySpans() throws Exception {
        assertThat(tracer).isNotNull();
        assertThat(openTelemetry).isNotNull();
        String manager = login("manager", "TestPass123!");
        Long customerId = createCustomer("traceowner", "T9000001", manager);
        String account = createAccount(customerId, manager);
        String customer = login("traceowner", "StrongPwd123");
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        spans.reset();
        UUID correlationId = UUID.randomUUID();

        var response = mvc.perform(post("/api/v1/accounts/{account}/deposits", account)
                                           .header("Authorization", "Bearer " + customer)
                                           .header("Idempotency-Key", UUID.randomUUID())
                                           .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"amount\":\"10.0000\"}"))
                          .andReturn()
                          .getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        List<SpanData> finished = spans.getFinishedSpanItems();

        SpanData money = only(finished, "tarbank.money.operation");
        String traceId = money.getTraceId();
        SpanData http = finished.stream()
                                .filter(span -> span.getTraceId().equals(traceId))
                                .filter(span -> span.getKind() == SpanKind.SERVER)
                                .findFirst()
                                .orElseThrow();
        SpanData postgres = finished.stream()
                                    .filter(span -> span.getTraceId().equals(traceId))
                                    .filter(span -> span.getName().equals("tarbank.postgresql"))
                                    .filter(span -> span.getParentSpanId().equals(money.getSpanId()))
                                    .findFirst()
                                    .orElseThrow();
        SpanData redis = finished.stream()
                                 .filter(span -> span.getTraceId().equals(traceId))
                                 .filter(span -> span.getName().equals("tarbank.redis"))
                                 .filter(span -> span.getParentSpanId().equals(http.getSpanId()))
                                 .findFirst()
                                 .orElseThrow();

        assertThat(isDescendantOf(money, http, finished)).isTrue();
        assertThat(redis.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(List.of(http, money, postgres, redis)).allSatisfy(span ->
                assertThat(span.getAttributes().get(CORRELATION_ID))
                        .isEqualTo(correlationId.toString()));
    }

    private boolean isDescendantOf(SpanData child,
                                   SpanData ancestor,
                                   List<SpanData> spans) {
        String parentSpanId = child.getParentSpanId();
        while (!parentSpanId.equals("0000000000000000")) {
            if (parentSpanId.equals(ancestor.getSpanId())) {
                return true;
            }
            String currentParentSpanId = parentSpanId;
            parentSpanId = spans.stream()
                                .filter(span -> span.getSpanId().equals(currentParentSpanId))
                                .map(SpanData::getParentSpanId)
                                .findFirst()
                                .orElse("0000000000000000");
        }
        return false;
    }

    private SpanData only(List<SpanData> spans,
                          String name) {
        List<SpanData> matches = spans.stream()
                                      .filter(span -> span.getName().equals(name))
                                      .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private String login(String username,
                         String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content("{\"username\":\"" + username
                                                           + "\",\"password\":\"" + password + "\"}"))
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        return extract(body, "accessToken");
    }

    private Long createCustomer(String username,
                                String document,
                                String manager) throws Exception {
        String body = mvc.perform(post("/api/v1/customers")
                                          .header("Authorization", "Bearer " + manager)
                                          .header("Idempotency-Key", UUID.randomUUID())
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content(customer(username, document)))
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        return Long.valueOf(body.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String createAccount(Long customerId,
                                 String manager) throws Exception {
        String body = mvc.perform(post("/api/v1/customers/{id}/accounts", customerId)
                                          .header("Authorization", "Bearer " + manager)
                                          .header("Idempotency-Key", UUID.randomUUID())
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content("{\"currency\":\"EUR\"}"))
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        return extract(body, "accountNumber");
    }

    private String customer(String username,
                            String document) {
        return "{\"username\":\"" + username + "\",\"password\":\"StrongPwd123\","
                + "\"firstName\":\"Alice\",\"lastName\":\"Example\",\"dateOfBirth\":\"1990-01-01\","
                + "\"email\":\"" + username + "@example.test\",\"phoneNumber\":\"+381601234567\","
                + "\"residentialAddress\":{\"country\":\"RS\",\"city\":\"Belgrade\","
                + "\"postalCode\":\"11000\",\"line1\":\"Example 1\"},"
                + "\"identityDocument\":{\"type\":\"PASSPORT\",\"issuingCountry\":\"RS\","
                + "\"number\":\"" + document + "\",\"expiresOn\":\"2030-01-01\"},"
                + "\"timezone\":\"Europe/Belgrade\"}";
    }

    private String extract(String body,
                           String field) {
        var matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                                              + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                             .matcher(body);
        if (!matcher.find()) throw new AssertionError("Missing field " + field + " in " + body);
        return matcher.group(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExporterConfiguration {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
