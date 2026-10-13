package com.flooddepth.depth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import java.util.List;

/** Captures the actual application logger; no provider or live backend is involved. */
public final class DepthDiagnosticCapture implements AutoCloseable {
    private final Logger logger = (Logger) DepthDiagnostics.logger();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final String previous = MDC.get("depthRequestId");
    public DepthDiagnosticCapture() {
        MDC.put("depthRequestId", "89a4d44c-f36b-45b6-aeab-a6e5fe9e435c");
        appender.start(); logger.addAppender(appender);
    }
    List<JsonNode> entries(String event) {
        var mapper = new ObjectMapper();
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(event + " ")).map(message -> {
                    try { return mapper.readTree(message.substring(message.indexOf("diagnostic=") + 11)); }
                    catch (Exception error) { throw new AssertionError(error); }
                }).toList();
    }
    public String messages() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }
    @Override public void close() {
        logger.detachAppender(appender); appender.stop();
        if (previous == null) MDC.remove("depthRequestId"); else MDC.put("depthRequestId", previous);
    }
}
