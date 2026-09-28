package com.tarbank.common.config;

import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesTheIdentityAndOnboardingSchemaToAFreshPostgreSqlDatabase() {
        assertThat(publicTables()).containsExactly("accounts", "api_request_idempotency", "audit_events", "customers", "databasechangelog", "databasechangeloglock", "managers", "users");
        assertThat(changelogEntryCount()).isEqualTo(2);
    }

    @Test
    void secondApplicationStartupKeepsCompletedMigrationsUnchanged() {
        int changelogEntriesBeforeRestart = changelogEntryCount();

        try (ConfigurableApplicationContext secondApplication = startApplication(Map.of())) {
            assertThat(secondApplication.isRunning()).isTrue();
        }

        assertThat(changelogEntryCount()).isEqualTo(changelogEntriesBeforeRestart);
        assertThat(publicTables()).containsExactly("accounts", "api_request_idempotency", "audit_events", "customers", "databasechangelog", "databasechangeloglock", "managers", "users");
    }

    @Test
    void missingChangelogFailsStartupWithoutHibernateCreatingBankingTables() {
        assertThatThrownBy(() -> startApplication(Map.of(
                "spring.liquibase.change-log", "classpath:db/changelog/missing-changelog.xml")))
                .hasStackTraceContaining("missing-changelog.xml");

        assertThat(publicTables()).containsExactly("accounts", "api_request_idempotency", "audit_events", "customers", "databasechangelog", "databasechangeloglock", "managers", "users");
    }

    @Test
    void missingRequiredSecretFailsWithASafeConfigurationError() {
        Throwable failure = catchThrowable(() -> startApplication(Map.of(
                "tarbank.document-protection.encryption-key", "")));

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining("tarbank.document-protection.encryption-key must be configured");
        assertThat(stackTrace(failure)).doesNotContain("Value:");
    }

    private int changelogEntryCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from databasechangelog", Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> publicTables() {
        return jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' order by table_name",
                String.class);
    }

    private Throwable catchThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}