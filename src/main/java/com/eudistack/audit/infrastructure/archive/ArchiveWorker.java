package com.eudistack.audit.infrastructure.archive;

import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import com.eudistack.audit.application.workflow.ArchiveRunLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily archiving at 02:00 UTC (design §3.4.6). Two workers never take the same tenant and day: the
 * lock lives in {@code platform.archive_lock}.
 */
public class ArchiveWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveWorker.class);

    private final ArchiveEventsWorkflow workflow;

    public ArchiveWorker(ArchiveEventsWorkflow workflow) {
        this.workflow = workflow;
    }

    @Scheduled(cron = "${audit.archive.cron:0 0 2 * * *}", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    /**
     * @return {@code true} when every tenant and day was archived or skipped, {@code false} on any failure
     */
    public boolean runOnce() {
        try {
            List<ArchiveEventsWorkflow.DayOutcome> outcomes = workflow.run(operationalLog());
            return outcomes.stream().noneMatch(outcome -> outcome.status() == ArchiveEventsWorkflow.Status.FAILURE);
        } catch (RuntimeException e) {
            LOG.error("Archiving run failed; nothing was deleted for the tenant in progress", e);
            return false;
        }
    }

    /**
     * The lines are emitted by the workflow, inside {@code audit.archive.run}. Logging them here,
     * after {@code run} returns, would leave them without that trace.
     */
    private ArchiveRunLog operationalLog() {
        return new ArchiveRunLog() {
            @Override
            public void started(LocalDate cutoffDay, int scopes) {
                LOG.atInfo().addKeyValue("cutoffDay", cutoffDay.toString()).addKeyValue("scopes", scopes)
                        .log("Archiving run started");
            }

            @Override
            public void finished(int tenantDays, long failures, long durationMs, String result) {
                LOG.atInfo()
                        .addKeyValue("tenantDays", tenantDays)
                        .addKeyValue("failures", failures)
                        .addKeyValue("durationMs", durationMs)
                        .addKeyValue("result", result)
                        .log("Archiving run finished");
            }
        };
    }
}
