package work.archaic.peep;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import work.archaic.service.logging.v01.Goal;
import work.archaic.service.logging.v01.GoalProvider;
import work.archaic.service.logging.v01.Log;
import work.archaic.service.logging.v01.Trail;

/**
 * Scoped-value goal provider. Share one instance with the code contributing observations.
 * Instances have independent scope keys; no global provider is selected implicitly.
 */
public final class Peep implements GoalProvider {
    private final ScopedValue<TrailBuffer> current = ScopedValue.newInstance();
    private final int maxEntries;
    private final int maxMessageCharacters;

    /** Keeps the latest 256 observations, each limited to 2048 UTF-16 code units. */
    public Peep() {
        this(256, 2048);
    }

    /**
     * Configures bounds for retained evidence; construction starts no work.
     * @param maxEntries positive maximum retained observation count
     * @param maxMessageCharacters positive maximum UTF-16 code units per message
     * @throws IllegalArgumentException if either bound is not positive
     */
    public Peep(int maxEntries, int maxMessageCharacters) {
        if (maxEntries <= 0 || maxMessageCharacters <= 0) {
            throw new IllegalArgumentException("Trail bounds must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxMessageCharacters = maxMessageCharacters;
    }

    @Override
    public Goal goal(String name, Log log) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(log, "log");
        if (name.isBlank()) throw new IllegalArgumentException("Blank goal name");
        return new ScopedGoal(name, log);
    }

    @Override
    public Trail currentTrail() {
        if (!current.isBound()) throw new IllegalStateException("No active Peep goal");
        return current.get();
    }

    private final class ScopedGoal implements Goal {
        private final String name;
        private final Log log;

        private ScopedGoal(String name, Log log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public <T, X extends Throwable> T call(Operation<T, X> work) throws X {
            Objects.requireNonNull(work, "work");
            if (current.isBound()) throw new IllegalStateException("Nested goals are not supported");
            var trail = new TrailBuffer(maxEntries, maxMessageCharacters);
            try {
                return ScopedValue.where(current, trail).call(work::call);
            } catch (Throwable failure) {
                // ScopedValue has unwound. Freeze evidence before invoking application output.
                try {
                    log.write(trail.failureReport(name, failure));
                } catch (Throwable reportingFailure) {
                    if (reportingFailure != failure) {
                        try {
                            failure.addSuppressed(reportingFailure);
                        } catch (Throwable ignored) {
                            // Reporting must never replace the original work failure.
                        }
                    }
                }
                throw failure;
            } finally {
                trail.discard();
            }
        }

        @Override
        public ExecutorService executor() {
            return new GoalExecutor(this);
        }
    }
}
