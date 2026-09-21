package work.archaic.peep.v02;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import work.archaic.service.logging.v02.Goal;
import work.archaic.service.logging.v02.Diagnostics;
import work.archaic.service.logging.v02.Log;
import work.archaic.service.logging.v02.Trail;

/**
 * Scoped-value goal provider. Share one instance with the code contributing observations.
 * Instances have independent scope keys; no global provider is selected implicitly.
 */
public final class Peep implements Diagnostics {
    private final ScopedValue<TrailBuffer> current = ScopedValue.newInstance();
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_MESSAGE_CHARACTERS = 2048;

    /** ServiceLoader constructor; retention limits are implementation choices. */
    public Peep() {}

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
        public <X extends Throwable> void run(Action<X> work) throws X {
            Objects.requireNonNull(work, "work");
            if (current.isBound()) throw new IllegalStateException("Nested goals are not supported");
            var trail = new TrailBuffer(MAX_ENTRIES, MAX_MESSAGE_CHARACTERS);
            try {
                ScopedValue.where(current, trail).call(() -> {
                    work.run();
                    return null;
                });
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
        public ExecutorService newExecutor() {
            return new GoalExecutor(this);
        }
    }
}

