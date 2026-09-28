package com.tarbank.common.http;

import com.tarbank.common.config.RateLimitProperties.Policy;
import com.tarbank.common.resilience.RedisTokenBucketRateLimiter;
import com.tarbank.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tarbank.rate-limit.anonymous.capacity=20",
        "tarbank.rate-limit.anonymous.refill-tokens-per-second=0.01",
        "tarbank.rate-limit.login-ip.capacity=2",
        "tarbank.rate-limit.login-ip.refill-tokens-per-second=0.01",
        "tarbank.rate-limit.login-username.capacity=1",
        "tarbank.rate-limit.login-username.refill-tokens-per-second=0.01",
        "tarbank.rate-limit.authenticated.capacity=3",
        "tarbank.rate-limit.authenticated.refill-tokens-per-second=0.01",
        "tarbank.rate-limit.money-account.capacity=1",
        "tarbank.rate-limit.money-account.refill-tokens-per-second=0.01"
})
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class RateLimitIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisTokenBucketRateLimiter buckets;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void tokenBucketEnforcesCapacityRetryAfterAndAtomicRefill() throws Exception {
        Policy policy = new Policy(1, 2.0);

        assertThat(buckets.consume("refill_test", "subject", policy).allowed()).isTrue();
        RedisTokenBucketRateLimiter.Decision rejected =
                buckets.consume("refill_test", "subject", policy);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
        Thread.sleep(600);
        assertThat(buckets.consume("refill_test", "subject", policy).allowed()).isTrue();
    }

    @Test
    void redisObservationCarriesCorrelationOnlyAsAHighCardinalityAttribute() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        RedisTokenBucketRateLimiter observedBuckets =
                new RedisTokenBucketRateLimiter(redisTemplate, observations);
        UUID correlationId = UUID.randomUUID();
        CorrelationIdContext.set(correlationId);
        try {
            observedBuckets.consume("trace_test", "subject", new Policy(1, 1.0));
        } finally {
            CorrelationIdContext.clear();
        }

        TestObservationRegistryAssert.assertThat(observations)
                                     .hasAnObservation(context -> context
                                             .hasNameEqualTo("tarbank.redis")
                                             .hasLowCardinalityKeyValue("operation", "rate_limit")
                                             .hasHighCardinalityKeyValue(
                                                     "correlation.id", correlationId.toString()));
    }

    @Test
    void coarseAnonymousLimitUsesTheDirectAddress() throws Exception {
        HttpResult result = null;
        for (int request = 0; request < 21; request++) {
            result = perform(get("/api/v1/unknown"), "198.51.100.20");
        }

        assertThat(result.status()).isEqualTo(429);
        assertThat(result.body()).contains("\"code\":\"RATE_LIMIT_EXCEEDED\"");
        assertThat(Long.parseLong(result.retryAfter())).isPositive();
    }

    @Test
    void loginIsLimitedByDirectIpAndNormalizedUsernameBeforeAuthentication() throws Exception {
        assertThat(loginAttempt("missing-one", "wrong", "198.51.100.1").status()).isEqualTo(401);
        assertThat(loginAttempt("missing-two", "wrong", "198.51.100.1").status()).isEqualTo(401);
        HttpResult ipRejected = loginAttempt("missing-three", "wrong", "198.51.100.1");
        assertThat(ipRejected.status()).isEqualTo(429);
        assertThat(Long.parseLong(ipRejected.retryAfter())).isPositive();

        assertThat(loginAttempt("NormalizedUser", "wrong", "198.51.100.2").status()).isEqualTo(401);
        HttpResult usernameRejected = loginAttempt(" normalizeduser ", "wrong", "198.51.100.3");
        assertThat(usernameRejected.status()).isEqualTo(429);
        assertThat(Long.parseLong(usernameRejected.retryAfter())).isPositive();
    }

    @Test
    void authenticatedUserLimitRejectsBeforeTheControllerRuns() throws Exception {
        String manager = login("manager", "TestPass123!", "198.51.100.4");
        HttpResult result = null;
        for (int request = 0; request < 4; request++) {
            result = perform(get("/api/v1/customers/999999999")
                                     .header("Authorization", "Bearer " + manager),
                             "198.51.100.5");
        }

        assertThat(result.status()).isEqualTo(429);
        assertThat(result.body()).contains("\"code\":\"RATE_LIMIT_EXCEEDED\"");
        assertThat(Long.parseLong(result.retryAfter())).isPositive();
    }

    @Test
    void sourceAccountLimitPreventsASecondMoneyWrite() throws Exception {
        String manager = login("manager", "TestPass123!", "198.51.100.6");
        Long customerId = createCustomer("ratelimitowner", "R9000001", manager);
        String account = createAccount(customerId, manager);
        String customer = login("ratelimitowner", "StrongPwd123", "198.51.100.7");
        UUID firstKey = UUID.randomUUID();
        UUID rejectedKey = UUID.randomUUID();

        HttpResult first = perform(post("/api/v1/accounts/{account}/deposits", account)
                                           .header("Authorization", "Bearer " + customer)
                                           .header("Idempotency-Key", firstKey)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"amount\":\"10.0000\"}"),
                                   "198.51.100.8");
        HttpResult rejected = perform(post("/api/v1/accounts/{account}/deposits", account)
                                              .header("Authorization", "Bearer " + customer)
                                              .header("Idempotency-Key", rejectedKey)
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"amount\":\"10.0000\"}"),
                                      "198.51.100.8");

        assertThat(first.status()).isEqualTo(201);
        assertThat(rejected.status()).isEqualTo(429);
        assertThat(Long.parseLong(rejected.retryAfter())).isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where initiated_by_customer_id=?",
                Integer.class, customerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, rejectedKey)).isZero();
        assertThat(metrics.get("tarbank.rate_limit.rejections")
                          .tag("group", "money_account")
                          .counter()
                          .count()).isGreaterThanOrEqualTo(1.0);
        assertThat(metrics.getMeters()).allSatisfy(meter -> meter.getId()
                                                                .getTags()
                                                                .forEach(tag -> assertThat(tag.getKey())
                                                                        .isNotIn("accountNumber", "userId",
                                                                                 "idempotencyKey", "correlationId",
                                                                        "documentIdentifier", "transactionId")));
    }

    @Test
    void encodedAndCanonicalAccountPathsShareOneSourceAccountBucket() throws Exception {
        String manager = login("manager", "TestPass123!", "198.51.100.61");
        Long customerId = createCustomer("encodedowner", "R9000061", manager);
        String account = createAccount(customerId, manager);
        String customer = login("encodedowner", "StrongPwd123", "198.51.100.62");
        HttpResult first = perform(post("/api/v1/accounts/{account}/deposits", account)
                                           .header("Authorization", "Bearer " + customer)
                                           .header("Idempotency-Key", UUID.randomUUID())
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"amount\":\"10.0000\"}"),
                                   "198.51.100.63");
        String encodedAccount = account.substring(0, 2) + "%3" + account.charAt(2)
                + account.substring(3);
        HttpResult second = perform(post(URI.create(
                "/api/v1/accounts/" + encodedAccount + "/deposits"))
                                            .header("Authorization", "Bearer " + customer)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"amount\":\"10.0000\"}"),
                                    "198.51.100.63");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(429);
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where initiated_by_customer_id=?",
                Integer.class, customerId)).isEqualTo(1);
    }

    @Test
    void forbiddenCallerCannotExhaustAnotherCustomersAccountBucket() throws Exception {
        String manager = login("manager", "TestPass123!", "198.51.100.71");
        Long ownerId = createCustomer("victimowner", "R9000071", manager);
        String account = createAccount(ownerId, manager);
        createCustomer("unrelatedcaller", "R9000072", manager);
        String unrelated = login("unrelatedcaller", "StrongPwd123", "198.51.100.72");
        String owner = login("victimowner", "StrongPwd123", "198.51.100.73");

        HttpResult forbidden = perform(post("/api/v1/accounts/{account}/deposits", account)
                                               .header("Authorization", "Bearer " + unrelated)
                                               .header("Idempotency-Key", UUID.randomUUID())
                                               .contentType(MediaType.APPLICATION_JSON)
                                               .content("{\"amount\":\"10.0000\"}"),
                                       "198.51.100.74");
        HttpResult completed = perform(post("/api/v1/accounts/{account}/deposits", account)
                                               .header("Authorization", "Bearer " + owner)
                                               .header("Idempotency-Key", UUID.randomUUID())
                                               .contentType(MediaType.APPLICATION_JSON)
                                               .content("{\"amount\":\"10.0000\"}"),
                                       "198.51.100.75");

        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(completed.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where initiated_by_customer_id=?",
                Integer.class, ownerId)).isEqualTo(1);
    }

    private HttpResult loginAttempt(String username,
                                    String password,
                                    String address) throws Exception {
        return perform(post("/api/v1/auth/login")
                               .contentType(MediaType.APPLICATION_JSON)
                               .content("{\"username\":\"" + username
                                                + "\",\"password\":\"" + password + "\"}"),
                       address);
    }

    private String login(String username,
                         String password,
                         String address) throws Exception {
        HttpResult response = loginAttempt(username, password, address);
        assertThat(response.status()).isEqualTo(200);
        return extract(response.body(), "accessToken");
    }

    private Long createCustomer(String username,
                                String document,
                                String manager) throws Exception {
        HttpResult response = perform(post("/api/v1/customers")
                                              .header("Authorization", "Bearer " + manager)
                                              .header("Idempotency-Key", UUID.randomUUID())
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(customer(username, document)),
                                      "198.51.100.9");
        assertThat(response.status()).isEqualTo(201);
        return Long.valueOf(response.body().replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String createAccount(Long customerId,
                                 String manager) throws Exception {
        HttpResult response = perform(post("/api/v1/customers/{id}/accounts", customerId)
                                              .header("Authorization", "Bearer " + manager)
                                              .header("Idempotency-Key", UUID.randomUUID())
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"currency\":\"EUR\"}"),
                                      "198.51.100.9");
        assertThat(response.status()).isEqualTo(201);
        return extract(response.body(), "accountNumber");
    }

    private HttpResult perform(MockHttpServletRequestBuilder request,
                               String address) throws Exception {
        var response = mvc.perform(request.with(httpRequest -> {
                                      httpRequest.setRemoteAddr(address);
                                      return httpRequest;
                                  }))
                          .andReturn()
                          .getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString(),
                              response.getHeader("Retry-After"));
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

    private record HttpResult(int status, String body, String retryAfter) {
    }
}
