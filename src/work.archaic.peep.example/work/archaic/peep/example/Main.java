package work.archaic.peep.example;

import java.io.IOException;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutionException;
import work.archaic.service.logging.v01.GoalProvider;
import work.archaic.service.logging.v01.Log;

/** Service-loaded consumer depending only on the catalog. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        GoalProvider provider = exactlyOne(GoalProvider.class);
        Log log = exactlyOne(Log.class);
        log.note("Peep example started");
        var goal = provider.goal("example.operation", log);
        goal.run(() -> provider.note("Successful evidence is discarded"));
        try (var executor = goal.executor()) {
            var result = executor.submit(() -> {
                provider.note("Loading configuration");
                provider.note("Attempting unavailable service");
                throw new IOException("Demonstration failure");
            });
            try {
                result.get();
            } catch (ExecutionException expected) {
                // Failure report has already been published. Caller handles its own outcome.
            }
        }
        log.note("Peep example finished");
    }

    private static <T> T exactlyOne(Class<T> service) {
        var providers = ServiceLoader.load(service).stream().toList();
        if (providers.size() != 1) throw new IllegalStateException("Expected one " + service.getName());
        return providers.getFirst().get();
    }
}
