package com.tarbank.common.config;

import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.security.api.AuthController;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    static final String BEARER_AUTH = "bearerAuth";

    private static final String ERROR_SCHEMA = "#/components/schemas/ApiErrorResponse";

    private static final String UUID_V4_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private static final String ETAG_PATTERN =
            "^(?:\"(?:customer|account)-v(?:0|[1-9][0-9]*)\"|(?:customer|account)-v(?:0|[1-9][0-9]*))$";

    private static final Map<String, String> COMMON_ERRORS = commonErrors();

    private static void documentConcurrencyHeaders(io.swagger.v3.oas.models.Operation operation) {
        operation.getParameters()
                 .forEach(parameter -> {
                     if (!"header".equals(parameter.getIn())) {
                         return;
                     }
                     if ("Idempotency-Key".equalsIgnoreCase(parameter.getName())) {
                         parameter.setRequired(true);
                         parameter.setDescription(
                                 "UUID v4 for one logical operation; reuse only for an identical retry.");
                         parameter.setSchema(new Schema<String>()
                                                     .type("string")
                                                     .format("uuid")
                                                     .pattern(UUID_V4_PATTERN));
                     } else if ("If-Match".equalsIgnoreCase(parameter.getName())) {
                         parameter.setRequired(true);
                         parameter.setDescription(
                                 "Latest quoted customer-vN or account-vN ETag returned by the resource; "
                                         + "the equivalent unquoted value is also accepted.");
                         parameter.setSchema(new Schema<String>()
                                                     .type("string")
                                                     .pattern(ETAG_PATTERN));
                     }
                 });
    }

    private static Content errorContent() {
        return new Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<>().$ref(ERROR_SCHEMA)));
    }

    private static Map<String, String> commonErrors() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("400", "VALIDATION_ERROR: malformed headers, parameters, or request body.");
        errors.put("401", "UNAUTHENTICATED or INVALID_CREDENTIALS.");
        errors.put("403", "ACCESS_DENIED.");
        errors.put("429", "RATE_LIMIT_EXCEEDED; Retry-After is returned.");
        errors.put("500", "INTERNAL_ERROR with no implementation details.");
        errors.put("503", "DEPENDENCY_UNAVAILABLE.");
        return Map.copyOf(errors);
    }

    @Bean
    OpenAPI tarbankOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT returned by POST /api/v1/auth/login."));
        ModelConverters.getInstance()
                       .readAll(ApiErrorResponse.class)
                       .forEach(components::addSchemas);
        return new OpenAPI()
                .info(new Info()
                              .title("Tarbank API")
                              .version("v1")
                              .description(
                                      "Manager-assisted customer and account administration with customer-owned money operations."))
                .components(components);
    }

    @Bean
    OperationCustomizer tarbankOperationCustomizer() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(new HeaderParameter()
                                                .name("X-Correlation-Id")
                                                .required(false)
                                                .description(
                                                        "Optional request UUID. A valid supplied value is preserved in the response and telemetry.")
                                                .schema(new Schema<String>().type("string")
                                                                            .format("uuid")));
            documentConcurrencyHeaders(operation);

            boolean login = handlerMethod.getBeanType() == AuthController.class
                    && handlerMethod.getMethod()
                                    .getName()
                                    .equals("login");
            if (!login) {
                operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
            }

            COMMON_ERRORS.forEach((status, description) -> {
                if (login && status.equals("403")) {
                    return;
                }
                operation.getResponses()
                         .computeIfAbsent(status, ignored -> new ApiResponse().description(description));
            });
            operation.getResponses()
                     .forEach((status, response) -> {
                         response.addHeaderObject("X-Correlation-Id", new io.swagger.v3.oas.models.headers.Header()
                                 .description(
                                         "Request correlation UUID; omitted only when the supplied value is malformed.")
                                 .schema(new Schema<String>().type("string")
                                                             .format("uuid")));
                         if (!status.startsWith("2")) {
                             response.setContent(errorContent());
                         }
                     });
            operation.getResponses()
                     .get("429")
                     .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                             .description("Whole seconds until a retry may be accepted.")
                             .schema(new Schema<Long>().type("integer")
                                                       .format("int64")
                                                       .minimum(BigDecimal.ONE)));
            return operation;
        };
    }
}
