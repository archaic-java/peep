package work.archaic.peep.example;

import java.io.IOException;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutionException;
import work.archaic.service.logging.v02.Diagnostics;
import work.archaic.service.logging.v02.Log;

/** Service-loaded consumer depending only on the catalog. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Diagnostics diagnostics = exactlyOne(Diagnostics.class);
        Log log = exactlyOne(Log.class);
        log.write("Peep example started");
        var goal = diagnostics.goal("example.fulfill-intent", log);
        goal.run(() -> diagnostics.note("Successful evidence is discarded"));
        try (var executor = goal.newExecutor()) {
            var result = executor.submit(() -> {
                diagnostics.note("Loading configuration");
                diagnostics.note("Attempting unavailable service");
                var failure = new IOException("Demonstration failure");
                try {
                    log.write("Could not fulfill the requested intent");
                } catch (IOException responseFailure) {
                    failure.addSuppressed(responseFailure);
                }
                throw failure;
            });
            try {
                result.get();
            } catch (ExecutionException expected) {
                // The response was attempted inside the goal; its failure report is now published.
            }
        }
        log.write("Peep example finished");
    }

    private static <T> T exactlyOne(Class<T> service) {
        var providers = ServiceLoader.load(service).stream().toList();
        if (providers.size() != 1) throw new IllegalStateException("Expected one " + service.getName());
        return providers.getFirst().get();
    }
}

