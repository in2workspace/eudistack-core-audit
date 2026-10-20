package com.eudistack.audit.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-03 under concurrency (design §3.4.7): the same event sent by several threads at once is stored
 * once; every other request gets {@code duplicate}, none fails.
 */
class IdempotencyRaceIT extends AuditIntegrationTest {

    @RepeatedTest(3)
    void sameEventConcurrently_storedOnce_othersDuplicate() throws Exception {
        String correlation = correlation();
        ObjectNode event = TestEvents.issuanceCompleted("red-logistica", correlation, "77777777G");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return recordOk(event.deepCopy());
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<JsonNode> response : responses) {
                statuses.add(response.get().at("/results/0/status").asText());
            }

            assertThat(statuses).containsOnlyOnce("accepted");
            assertThat(statuses).filteredOn("duplicate"::equals).hasSize(threads - 1);
        }
        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).hasSize(1);
    }
}
