package com.eudistack.audit.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Attaches a list appender to one logger for a test.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    public static LogCapture attach(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    public java.util.List<ILoggingEvent> events() {
        return appender.list;
    }

    public static String value(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) {
            return null;
        }
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> String.valueOf(pair.value))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
