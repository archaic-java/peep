package work.archaic.peep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import work.archaic.service.logging.v01.FailureReport;
import work.archaic.service.logging.v01.Observation;
import work.archaic.service.logging.v01.Trail;

/** Thread-confined bounded evidence, inaccessible after completion. */
final class TrailBuffer implements Trail {
    private record Entry(Observation observation, boolean truncated) {}

    private final UUID id = UUID.randomUUID();
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final int maxEntries;
    private final int maxCharacters;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private Thread owner = Thread.currentThread();
    private long omitted;
    private long truncated;

    TrailBuffer(int maxEntries, int maxCharacters) {
        this.maxEntries = maxEntries;
        this.maxCharacters = maxCharacters;
    }

    @Override
    public void note(String message) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Trail is closed or belongs to another thread");
        }
        Objects.requireNonNull(message, "message");
        boolean shortened = message.length() > maxCharacters;
        if (shortened) {
            int end = maxCharacters;
            if (Character.isHighSurrogate(message.charAt(end - 1))
                    && Character.isLowSurrogate(message.charAt(end))) end--;
            message = message.substring(0, end);
        }
        if (entries.size() == maxEntries) {
            if (entries.removeFirst().truncated()) truncated--;
            if (omitted != Long.MAX_VALUE) omitted++;
        }
        entries.addLast(new Entry(new Observation(elapsed(), message), shortened));
        if (shortened) truncated++;
    }

    private Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    FailureReport failureReport(String goal, Throwable failure) {
        var duration = elapsed();
        owner = null;
        return new FailureReport(id, goal, startedAt, duration,
                entries.stream().map(Entry::observation).toList(), omitted, truncated, failure);
    }

    void discard() {
        owner = null;
        entries.clear();
    }
}
