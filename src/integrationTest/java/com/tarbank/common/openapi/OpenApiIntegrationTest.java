package com.tarbank.common.openapi;

import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends AbstractIntegrationTest {
    private static final List<ApiOperation> OPERATIONS = List.of(
            new ApiOperation("/api/v1/auth/login", "post", false, "200"),
            new ApiOperation("/api/v1/auth/logout", "post", true, "200"),
            new ApiOperation("/api/v1/customers", "post", true, "201"),
            new ApiOperation("/api/v1/customers", "get", true, "200"),
            new ApiOperation("/api/v1/customers/{customerId}", "get", true, "200"),
            new ApiOperation("/api/v1/customers/{customerId}", "patch", true, "200"),
            new ApiOperation("/api/v1/customers/{customerId}/status", "patch", true, "200"),
            new ApiOperation("/api/v1/customers/{customerId}/password-reset", "post", true, "200"),
            new ApiOperation("/api/v1/customers/{customerId}/accounts", "post", true, "201"),
            new ApiOperation("/api/v1/customers/{customerId}/accounts", "get", true, "200"),
            new ApiOperation("/api/v1/accounts", "get", true, "200"),
            new ApiOperation("/api/v1/accounts/{accountNumber}", "get", true, "200"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/status", "patch", true, "200"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/daily-limits", "patch", true, "200"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/transactions", "get", true, "200"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/deposits", "post", true, "201"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/withdrawals", "post", true, "201"),
            new ApiOperation("/api/v1/accounts/{accountNumber}/transfers", "post", true, "201"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void generatedContractDocumentsEveryOperationAndCrossCuttingHeader() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                         .andExpect(status().isOk())
                         .andExpect(content().contentType("application/json"))
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        JsonNode document = json.readTree(body);

        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.path("info").path("title").asText()).isEqualTo("Tarbank API");
        assertThat(document.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        assertThat(document.path("components").path("schemas").has("ApiErrorResponse")).isTrue();
        assertThat(document.path("components").path("schemas").has("ErrorDetails")).isTrue();
        assertThat(document.path("components").path("schemas").has("FieldError")).isTrue();
        assertLocalReferencesResolve(document, document);

        for (ApiOperation expected : OPERATIONS) {
            JsonNode operation = operation(document, expected.path(), expected.method());
            assertThat(hasHeaderParameter(operation, "X-Correlation-Id")).isTrue();
            assertThat(operation.path("responses").has(expected.successStatus())).isTrue();
            assertThat(operation.path("responses")
                                .path(expected.successStatus())
                                .path("content")
                                .has("application/json"))
                    .as("%s %s documents its success body", expected.method().toUpperCase(), expected.path())
                    .isTrue();
            assertThat(operation.path("responses")
                                .path("400")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
            assertThat(operation.path("security").isArray()).isEqualTo(expected.protectedOperation());
        }
    }

    @Test
    void contractDocumentsConcurrencyHeadersStatusesConstraintsAndSwaggerUi() throws Exception {
        JsonNode document = json.readTree(mvc.perform(get("/v3/api-docs"))
                                             .andExpect(status().isOk())
                                             .andReturn()
                                             .getResponse()
                                             .getContentAsString());

        assertThat(hasHeaderParameter(operation(document, "/api/v1/customers", "post"),
                                      "Idempotency-Key")).isTrue();
        assertThat(headerParameter(operation(document, "/api/v1/customers", "post"),
                                   "Idempotency-Key")
                           .path("schema")
                           .path("pattern")
                           .asText()).contains("-4");
        assertThat(hasHeaderParameter(operation(document,
                                                 "/api/v1/accounts/{accountNumber}/deposits", "post"),
                                      "Idempotency-Key")).isTrue();
        assertThat(hasHeaderParameter(operation(document, "/api/v1/customers/{customerId}", "patch"),
                                      "If-Match")).isTrue();
        assertThat(headerParameter(operation(document, "/api/v1/customers/{customerId}", "patch"),
                                   "If-Match")
                           .path("schema")
                           .path("pattern")
                           .asText()).contains("customer|account");
        assertThat(hasHeaderParameter(operation(document,
                                                 "/api/v1/accounts/{accountNumber}/daily-limits", "patch"),
                                      "If-Match")).isTrue();
        assertThat(operation(document, "/api/v1/accounts/{accountNumber}/daily-limits", "patch")
                           .path("responses").has("422")).isTrue();
        assertThat(operation(document, "/api/v1/customers/{customerId}", "patch")
                           .path("responses").has("428")).isTrue();
        assertThat(operation(document, "/api/v1/accounts/{accountNumber}", "get")
                           .path("responses").path("200").path("headers").has("ETag")).isTrue();
        assertThat(operation(document, "/api/v1/accounts", "get")
                           .path("responses").path("429").path("headers").has("Retry-After")).isTrue();

        List<OperationHeader> requiredHeaders = List.of(
                new OperationHeader("/api/v1/customers", "post", "Idempotency-Key"),
                new OperationHeader("/api/v1/customers/{customerId}", "patch", "If-Match"),
                new OperationHeader("/api/v1/customers/{customerId}/status", "patch", "If-Match"),
                new OperationHeader("/api/v1/customers/{customerId}/password-reset", "post", "Idempotency-Key"),
                new OperationHeader("/api/v1/customers/{customerId}/accounts", "post", "Idempotency-Key"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/status", "patch", "If-Match"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/daily-limits", "patch", "If-Match"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/daily-limits", "patch", "Idempotency-Key"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/deposits", "post", "Idempotency-Key"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/withdrawals", "post", "Idempotency-Key"),
                new OperationHeader("/api/v1/accounts/{accountNumber}/transfers", "post", "Idempotency-Key"));
        requiredHeaders.forEach(expected -> assertThat(headerParameter(
                operation(document, expected.path(), expected.method()), expected.header()).path("required").asBoolean())
                .as("%s %s requires %s", expected.method().toUpperCase(), expected.path(), expected.header())
                .isTrue());

        String etagPattern = headerParameter(operation(document, "/api/v1/accounts/{accountNumber}/status", "patch"),
                                             "If-Match")
                .path("schema")
                .path("pattern")
                .asText();
        assertThat(Pattern.compile(etagPattern).matcher("\"account-v0\"").matches()).isTrue();
        assertThat(Pattern.compile(etagPattern).matcher("\"customer-v12\"").matches()).isTrue();
        assertThat(Pattern.compile(etagPattern).matcher("account-v0").matches()).isTrue();
        assertThat(Pattern.compile(etagPattern).matcher("\"account-v0").matches()).isFalse();

        JsonNode amount = document.path("components")
                                  .path("schemas")
                                  .path("AmountRequest")
                                  .path("properties")
                                  .path("amount");
        assertThat(amount.path("minimum").decimalValue()).isEqualByComparingTo("0.0001");
        assertThat(amount.path("multipleOf").decimalValue()).isEqualByComparingTo("0.0001");

        JsonNode withdrawalAmount = document.path("components")
                                            .path("schemas")
                                            .path("WithdrawalRequest")
                                            .path("properties")
                                            .path("amount");
        assertThat(withdrawalAmount.path("minimum").decimalValue()).isEqualByComparingTo("5.0000");

        JsonNode username = document.path("components")
                                    .path("schemas")
                                    .path("CreateCustomerRequest")
                                    .path("properties")
                                    .path("username");
        assertThat(username.path("minLength").asInt()).isEqualTo(3);
        assertThat(username.path("maxLength").asInt()).isEqualTo(32);
        assertThat(username.path("pattern").asText()).isEqualTo("^[a-z][a-z0-9._-]{2,31}$");

        JsonNode password = document.path("components")
                                    .path("schemas")
                                    .path("LoginRequest")
                                    .path("properties")
                                    .path("password");
        assertThat(password.path("minLength").asInt()).isEqualTo(12);
        assertThat(password.path("maxLength").asInt()).isEqualTo(12);

        JsonNode withdrawalLimit = document.path("components")
                                           .path("schemas")
                                           .path("DailyLimitUpdateRequest")
                                           .path("properties")
                                           .path("withdrawalLimit");
        assertThat(withdrawalLimit.path("minimum").decimalValue()).isEqualByComparingTo("1000.0000");
        assertThat(withdrawalLimit.path("maximum").decimalValue()).isEqualByComparingTo("3000.0000");

        mvc.perform(get("/swagger-ui/index.html"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    private JsonNode operation(JsonNode document,
                               String path,
                               String method) {
        JsonNode operation = document.path("paths").path(path).path(method);
        assertThat(operation.isMissingNode())
                .as("%s %s is documented", method.toUpperCase(), path)
                .isFalse();
        return operation;
    }

    private boolean hasHeaderParameter(JsonNode operation,
                                       String name) {
        return headerParameter(operation, name) != null;
    }

    private JsonNode headerParameter(JsonNode operation,
                                     String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                            .filter(parameter -> parameter.path("in").asText().equals("header")
                                    && parameter.path("name").asText().equalsIgnoreCase(name))
                            .findFirst()
                            .orElse(null);
    }

    private void assertLocalReferencesResolve(JsonNode document,
                                              JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (reference.isTextual() && reference.asText().startsWith("#/components/schemas/")) {
            String schemaName = reference.asText().substring("#/components/schemas/".length());
            assertThat(document.path("components").path("schemas").has(schemaName))
                    .as("local OpenAPI reference %s resolves", reference.asText())
                    .isTrue();
        }
        node.forEach(child -> assertLocalReferencesResolve(document, child));
    }

    private record ApiOperation(String path, String method, boolean protectedOperation, String successStatus) {
    }

    private record OperationHeader(String path, String method, String header) {
    }
}
