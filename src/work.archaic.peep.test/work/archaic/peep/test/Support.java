package work.archaic.peep.test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import work.archaic.service.logging.v01.FailureReport;
import work.archaic.service.logging.v01.Log;

final class Support {
    private Support() {}

    static work.archaic.service.logging.v01.GoalProvider provider() {
        return service(work.archaic.service.logging.v01.GoalProvider.class);
    }

    static <T> T service(Class<T> type) {
        var providers = java.util.ServiceLoader.load(type).stream().toList();
        assert providers.size() == 1 : "Expected exactly one " + type.getName();
        return providers.getFirst().get();
    }

    static final class MemoryLog implements Log {
        final List<String> notes = new CopyOnWriteArrayList<>();
        final List<FailureReport> reports = new CopyOnWriteArrayList<>();
        @Override public void note(String message) { notes.add(message); }
        @Override public void write(FailureReport report) { reports.add(report); }
    }

    @FunctionalInterface interface Work { void run() throws Throwable; }

    static <T extends Throwable> T expect(Class<T> type, Work work) {
        try {
            work.run();
        } catch (Throwable failure) {
            assert type.isInstance(failure) : "Expected " + type + ", got " + failure;
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        assert latch.await(5, TimeUnit.SECONDS) : "Timed out waiting for test operation";
    }
}
