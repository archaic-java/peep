package work.archaic.peep.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import work.archaic.service.logging.v01.FailureReport;
import work.archaic.service.logging.v01.Log;
import work.archaic.service.logging.v01.Observation;
import static work.archaic.peep.test.Support.*;

/** Catalog-only consumer; process isolation permits testing stderr without provider access. */
public final class LogProbe {
    private LogProbe() {}

    public static void main(String[] args) throws Exception {
        var diagnostics = System.err;
        try {
            switch (args[0]) {
                case "output" -> output();
                case "failure" -> failure();
                case "concurrent" -> concurrent();
                default -> throw new AssertionError("Unknown probe");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(diagnostics);
            System.exit(1);
        }
    }

    private static void output() throws Exception {
        var bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, false, StandardCharsets.UTF_8));
        Log log = service(Log.class);
        log.note("Application started; listening on port 8080");
        assert bytes.toString(StandardCharsets.UTF_8).contains("listening on port 8080");
        var provider = provider();
        provider.goal("successful", log).run(() -> {
            provider.note("discarded observation");
            log.note("reload completed");
        });
        assert !bytes.toString(StandardCharsets.UTF_8).contains("discarded observation");
        log.write(report("failure"));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assert text.contains("reload completed") && text.contains("failed after");
        assert text.contains("observed") && text.contains("1 omitted, 1 retained messages truncated");
        assert text.contains("java.io.IOException: failure");
        log.note("still open");
        assert bytes.toString(StandardCharsets.UTF_8).contains("still open");
    }

    private static void failure() {
        System.setErr(new PrintStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("unavailable"); }
        }));
        Log log = service(Log.class);
        expect(IOException.class, () -> log.note("immediate"));
        expect(IOException.class, () -> log.write(report("failure")));
        expect(NullPointerException.class, () -> log.note(null));
        expect(NullPointerException.class, () -> log.write(null));
    }

    private static void concurrent() throws Exception {
        var bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, false, StandardCharsets.UTF_8));
        Log first = service(Log.class);
        Log second = service(Log.class);
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
        String output = bytes.toString(StandardCharsets.UTF_8);
        for (int i = 0; i < 40; i++) {
            String marker = i % 2 == 0 ? "note-" + i + "\\ncontinued" : "goal-" + i + " [";
            int start = output.indexOf(marker);
            assert start >= 0 && output.indexOf(marker, start + 1) == -1;
            if (i % 2 != 0) {
                int stackEnd = output.indexOf("java.io.IOException: failure", start);
                assert stackEnd > start;
                String headerAndEvidence = output.substring(start, stackEnd);
                assert headerAndEvidence.contains("observed");
                assert !headerAndEvidence.contains("note-");
                assert headerAndEvidence.indexOf("goal-", 1) == -1;
            }
        }
    }

    private static FailureReport report(String name) {
        return new FailureReport(UUID.randomUUID(), name, Instant.now(), Duration.ofMillis(1),
                List.of(new Observation(Duration.ZERO, "observed")), 1, 1, new IOException("failure"));
    }
}
