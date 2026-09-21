package work.archaic.peep.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import work.archaic.peep.Peep;
import work.archaic.peep.TextLog;
import work.archaic.service.logging.v01.FailureReport;
import work.archaic.service.logging.v01.Log;
import work.archaic.service.logging.v01.Observation;
import work.archaic.service.test.v01.Test;
import work.archaic.service.test.v01.TestSuite;
import static work.archaic.peep.test.Support.*;

public final class LogTest implements TestSuite {
    @Test public void formatsAndFlushesImmediateNotesAndFailureEvidence() throws Exception {
        var writer = new TrackingWriter();
        Log log = new TextLog(writer);
        log.note("Application started; listening on port 8080");
        assert writer.flushes == 1;
        assert writer.toString().contains("Application started; listening on port 8080");
        var provider = new Peep(2, 4);
        provider.goal("successful", log).run(() -> {
            provider.note("discarded observation");
            log.note("reload completed");
        });
        assert writer.toString().contains("reload completed");
        assert !writer.toString().contains("discarded");
        expect(IOException.class, () -> provider.goal("failure", log).run(() -> {
            provider.note("evicted");
            provider.note("abcdef");
            provider.note("last");
            throw new IOException("service unavailable");
        }));
        assert writer.flushes == 3;
        assert !writer.closed;
        var output = writer.toString();
        assert output.contains("failure [") && output.contains("failed after");
        assert output.contains("abcd") && output.contains("last");
        assert output.contains("1 omitted, 1 retained messages truncated");
        assert output.contains("java.io.IOException: service unavailable");
    }

    @Test public void rejectsNullAndPropagatesOutputFailures() {
        var failure = new IOException("disk full");
        Writer broken = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException { throw failure; }
            @Override public void flush() throws IOException { throw failure; }
            @Override public void close() {}
        };
        Log log = new TextLog(broken);
        assert expect(IOException.class, () -> log.note("immediate")) == failure;
        assert expect(IOException.class, () -> log.write(report("failure"))) == failure;
        Log printWriterLog = new TextLog(new PrintWriter(broken));
        expect(IOException.class, () -> printWriterLog.note("must not swallow"));
        expect(NullPointerException.class, () -> log.note(null));
        expect(NullPointerException.class, () -> log.write(null));
        expect(NullPointerException.class, () -> new TextLog((Writer) null));
    }

    @Test public void serializesConcurrentRecordsAcrossLogsSharingWriter() throws Exception {
        var writer = new TrackingWriter();
        Log first = new TextLog(writer);
        Log second = new TextLog(writer);
        var tasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 40; i++) {
            int id = i;
            tasks.add(() -> {
                if (id % 2 == 0) first.note("note-" + id + "\ncontinued");
                else second.write(report("goal-" + id));
                return null;
            });
        }
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : threads.invokeAll(tasks, 5, TimeUnit.SECONDS)) result.get();
        }
        assert writer.flushes == 40;
        assert writer.records.size() == 40;
        for (int i = 0; i < 40; i++) {
            String marker = i % 2 == 0 ? "note-" + i + "\\ncontinued" : "goal-" + i + " [";
            assert writer.records.stream().filter(r -> r.contains(marker)).count() == 1;
        }
        assert writer.records.stream().filter(r -> r.contains("goal-")).allMatch(r ->
                r.contains("java.io.IOException: failure") && r.contains("observed"));
    }

    private static FailureReport report(String name) {
        return new FailureReport(UUID.randomUUID(), name, Instant.now(), Duration.ofMillis(1),
                List.of(new Observation(Duration.ZERO, "observed")), 0, 0, new IOException("failure"));
    }

    private static final class TrackingWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final List<String> records = new ArrayList<>();
        private int flushes;
        private boolean closed;
        @Override public void write(char[] chars, int offset, int length) {
            records.add(new String(chars, offset, length));
            for (int i = offset; i < offset + length; i++) {
                text.append(chars[i]);
                Thread.yield();
            }
        }
        @Override public void flush() { flushes++; }
        @Override public void close() { closed = true; }
        @Override public String toString() { return text.toString(); }
    }
}
