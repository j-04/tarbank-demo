package com.tarbank.common.http;

import com.tarbank.common.resilience.FailurePoint;
import com.tarbank.common.resilience.SimulatedFailureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void databaseConnectionFailureMapsToSafeDependencyUnavailableResponse() {
        var response = handler.dependencyUnavailable(
                new DataAccessResourceFailureException("secret database endpoint"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.getBody().error().message())
                .isEqualTo("A required service is temporarily unavailable.")
                .doesNotContain("database", "endpoint");
    }

    @Test
    void unsupportedContentTypeMapsTo415() {
        var response = handler.unsupportedMediaType();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unsupportedAcceptTypeMapsTo406() {
        var response = handler.notAcceptable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void simulatedFailureUsesExistingSafeInternalErrorWithoutInjectionDetails() {
        var response = handler.simulatedFailure(
                new SimulatedFailureException(FailurePoint.DURING_TRANSACTION_BEFORE_COMMIT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message())
                .doesNotContain("DURING_TRANSACTION_BEFORE_COMMIT", "simulat", "inject");
    }

    @Test
    void dependencyLogEmitsStructuredSafeFieldsWithoutSecretExceptionText() {
        var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ApiExceptionHandler.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.dependencyUnavailable(new DataAccessResourceFailureException(
                    "password=bank-secret Authorization=Bearer secret-token jdbc:postgresql://private"));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }

        assertThat(appender.events).hasSize(1);
        LogEvent event = appender.events.getFirst();
        Map<String, String> context = event.getContextData().toMap();
        assertThat(context.get("internalCode")).isEqualTo("TAR-INFRA-002");
        assertThat(context.get("publicErrorCode"))
                .isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(context.get("httpStatus")).isEqualTo("503");
        assertThat(context.get("outcome"))
                .isEqualTo("dependency_unavailable");
        assertThat(event.getMessage().getFormattedMessage())
                .doesNotContain("bank-secret", "secret-token", "jdbc:postgresql", "Authorization");
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("test-capture", null, PatternLayout.createDefaultLayout(), true,
                  Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
