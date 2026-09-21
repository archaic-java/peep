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
 * Plain-text output. Each message/report is written and flushed under the destination's lock.
 * The caller owns the destination; this class never closes it. Flush is not a durability promise.
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

    /**
     * Uses a caller-owned writer. Other users must synchronize on the same writer for coherence.
     * @param destination output writer; PrintWriter error flags are checked explicitly
     */
    public TextLog(Writer destination) {
        this.destination = Objects.requireNonNull(destination, "destination");
        lock = destination;
        checkedStream = null;
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
            if (destination instanceof PrintWriter printWriter && printWriter.checkError()) {
                throw new IOException("Writer rejected log output");
            }
        }
    }

    private static String oneLine(String message) {
        return message.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
