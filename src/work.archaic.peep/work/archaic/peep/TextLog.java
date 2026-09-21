package work.archaic.peep;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import work.archaic.service.logging.v01.FailureReport;
import work.archaic.service.logging.v01.Log;

/**
 * Plain-text stderr output. Each message/report is written and flushed under the stream's lock.
 * This provider never closes stderr. Flush is not a durability promise.
 */
public final class TextLog implements Log {
    private final Writer destination;
    private final Object lock;
    private final PrintStream checkedStream;

    /** Writes UTF-8 to the current System.err, checking PrintStream's error flag. */
    public TextLog() {
        this(System.err);
    }

    private TextLog(PrintStream stream) {
        destination = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        lock = stream;
        checkedStream = stream;
    }

    @Override
    public void note(String message) throws IOException {
        Objects.requireNonNull(message, "message");
        publish(Instant.now() + " " + oneLine(message) + "\n");
    }

    @Override
    public void write(FailureReport report) throws IOException {
        Objects.requireNonNull(report, "report");
        var text = new StringBuilder();
        text.append(report.startedAt()).append(" ").append(oneLine(report.goal()))
                .append(" [").append(report.executionId()).append("] failed after ")
                .append(report.duration()).append('\n');
        for (var observation : report.observations()) {
            text.append("  +").append(observation.elapsed()).append(" ")
                    .append(oneLine(observation.message())).append('\n');
        }
        if (report.omittedObservations() != 0 || report.truncatedObservations() != 0) {
            text.append("  evidence: ").append(report.omittedObservations()).append(" omitted, ")
                    .append(report.truncatedObservations()).append(" retained messages truncated\n");
        }
        var stack = new StringWriter();
        report.failure().printStackTrace(new PrintWriter(stack));
        text.append(stack);
        publish(text.toString());
    }

    private void publish(String text) throws IOException {
        synchronized (lock) {
            destination.write(text);
            destination.flush();
            if (checkedStream != null && checkedStream.checkError()) {
                throw new IOException("Standard error rejected log output");
            }
        }
    }

    private static String oneLine(String message) {
        return message.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
