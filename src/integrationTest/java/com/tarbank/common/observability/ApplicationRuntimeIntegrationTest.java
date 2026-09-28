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
}
