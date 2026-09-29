package com.tarbank.common.observability;

import com.tarbank.support.AbstractIntegrationTest;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class ApplicationRuntimeIntegrationTest extends AbstractIntegrationTest {

    @Test
    void regularStartupConfiguresTracingWithoutLoggingSecrets(CapturedOutput output) {
        try (var context = startApplication(Map.of(
                "management.tracing.sampling.probability", "1.0",
                "server.port", "0",
                "management.server.port", "0"), WebApplicationType.SERVLET)) {
            assertThat(context.getBeansOfType(Tracer.class)).isNotEmpty();
            assertThat(context.getBeansOfType(OpenTelemetry.class)).isNotEmpty();
            assertThat(context.getBeansOfType(ObservationHandler.class)
                              .values())
                    .anyMatch(handler -> handler.getClass()
                                                .getName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("tracing"));
        }

        assertThat(output.getAll())
                .doesNotContain("Database JDBC URL")
                .doesNotContain("Using generated security password")
                .doesNotContain(POSTGRES.getJdbcUrl());
    }

    @Test
    void prometheusIsOptInAndAvailableOnlyOnTheManagementPort() throws Exception {
        try (var context = startApplication(Map.of(
                "management.prometheus.metrics.export.enabled", "true",
                "server.port", "0",
                "management.server.port", "0"), WebApplicationType.SERVLET)) {
            assertThat(context.getBeansOfType(io.micrometer.core.instrument.MeterRegistry.class)
                              .values())
                    .anyMatch(registry -> registry.getClass()
                                                  .getSimpleName()
                                                  .equals("PrometheusMeterRegistry"));

            int applicationPort = Integer.parseInt(context.getEnvironment()
                                                          .getRequiredProperty("local.server.port"));
            int managementPort = Integer.parseInt(context.getEnvironment()
                                                         .getRequiredProperty("local.management.port"));
            HttpClient client = HttpClient.newHttpClient();
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort
                                                                  + "/v3/api-docs"))
                                   .GET()
                                   .build(), HttpResponse.BodyHandlers.discarding());

            HttpResponse<String> publicResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort
                                                              + "/actuator/prometheus"))
                               .GET()
                               .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(publicResponse.statusCode()).isNotEqualTo(200);

            HttpResponse<String> scrape = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort
                                                              + "/actuator/prometheus"))
                               .GET()
                               .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("http_server_requests_seconds_bucket")
                                     .doesNotContain("Bearer ", "test-only-jwt-signing-key", "correlation.id");
        }
    }
}
