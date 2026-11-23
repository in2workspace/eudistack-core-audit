package com.eudistack.audit.infrastructure.web;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * At most one WARN per minute and bucket. The next one that is emitted reports how many were
 * dropped since the previous line. In memory, on purpose: a restart just starts the window again.
 */
final class WarnThrottle {

    record Decision(boolean emit, long suppressed) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    WarnThrottle(Clock clock) {
        this.clock = clock;
    }

    synchronized Decision admit(String bucket) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Window window = windows.get(bucket);
        if (window == null || !window.minute.equals(minute)) {
            long suppressed = window == null ? 0 : window.suppressed;
            windows.put(bucket, new Window(minute));
            return new Decision(true, suppressed);
        }
        window.suppressed++;
        return new Decision(false, 0);
    }

    private static final class Window {
        private final Instant minute;
        private long suppressed;

        private Window(Instant minute) {
            this.minute = minute;
        }
    }
}
