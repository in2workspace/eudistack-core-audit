package com.eudistack.audit.infrastructure.web.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-05, the part of this service: when the store cannot be reached the API answers 503 with
 * {@code Retry-After} and stores nothing, so the producer keeps the events in its outbox and retries
 * the whole batch (the outbox itself is the producers' responsibility, DT-002).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecordEventsUnavailableIT extends AuditIntegrationTest {

    @Autowired
    @Qualifier("writerDataSource")
    private HikariDataSource writerDataSource;

    @Test
    void storeUnavailable_is503WithRetryAfter_andNothingStored() throws Exception {
        String correlation = correlation();
        writerDataSource.close();

        mvc.perform(write(TestEvents.batch(TestEvents.issuanceCompleted("red-logistica", correlation, "8H")),
                        TestTokens.writer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("service_unavailable"));

        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).isEmpty();
    }
}
