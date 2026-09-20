package work.archaic.peep.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import work.archaic.peep.Peep;
import work.archaic.service.logging.v01.GoalProvider;
import work.archaic.service.test.v01.Test;
import work.archaic.service.test.v01.TestSuite;
import static work.archaic.peep.test.Support.*;

public final class ExecutorTest implements TestSuite {
    @Test public void submittedTasksHaveIndependentVirtualExecutions() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var goal = provider.goal("parallel", log);
        try (var executor = goal.executor()) {
            var ready = new CountDownLatch(16);
            var release = new CountDownLatch(1);
            var futures = new ArrayList<Future<Void>>();
            for (int i = 0; i < 16; i++) {
                var message = "task-" + i;
                futures.add(executor.submit((Callable<Void>) () -> {
                    assert Thread.currentThread().isVirtual();
                    provider.note(message);
                    ready.countDown();
                    await(release);
                    throw new IOException(message);
                }));
            }
            try { await(ready); } finally { release.countDown(); }
            for (var future : futures) expect(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }
        assert log.reports.size() == 16;
        assert log.reports.stream().map(r -> r.executionId()).distinct().count() == 16;
        assert log.reports.stream().map(r -> r.observations().getFirst().message()).distinct().count() == 16;
        for (var report : log.reports) {
            assert report.observations().size() == 1;
            assert report.failure().getMessage().equals(report.observations().getFirst().message());
        }
        goal.run(() -> provider.note("reuse after executor close"));
        expect(IllegalStateException.class, provider::currentTrail);
    }

    @Test public void submitOverloadsAndBulkTasksReportInsideFuture() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var failure = new IllegalArgumentException("runnable");
        Runnable broken = () -> { provider.note("runnable"); throw failure; };
        try (var executor = provider.goal("future", log).executor()) {
            assert expect(ExecutionException.class,
                    () -> executor.submit(broken).get(5, TimeUnit.SECONDS)).getCause() == failure;
            assert log.reports.size() == 1;
            assert expect(ExecutionException.class,
                    () -> executor.submit(broken, "unused").get(5, TimeUnit.SECONDS)).getCause() == failure;
            assert executor.submit(() -> provider.note("ok"), "result").get(5, TimeUnit.SECONDS).equals("result");
            Callable<Integer> good = () -> { provider.note("bulk ok"); return 42; };
            Callable<Integer> bad = () -> { provider.note("bulk failed"); throw new IOException("bulk"); };
            var results = executor.invokeAll(List.of(good, bad));
            assert results.getFirst().get() == 42;
            expect(ExecutionException.class, () -> results.getLast().get());
            var timed = executor.invokeAll(List.of(good, bad), 5, TimeUnit.SECONDS);
            assert timed.getFirst().get() == 42;
            expect(ExecutionException.class, () -> timed.getLast().get());
            expect(ExecutionException.class, () -> executor.invokeAny(List.of(bad)));
            expect(ExecutionException.class, () -> executor.invokeAny(List.of(bad), 5, TimeUnit.SECONDS));
            assert executor.invokeAny(List.of(good)) == 42;
            assert log.reports.size() == 6 : "Bulk tasks must not hide or duplicate reports";
        }
    }

    @Test public void executePreservesUncaughtFailureAfterReporting() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var handled = new CountDownLatch(1);
        var observed = new AtomicReference<Throwable>();
        var failure = new IllegalStateException("execute");
        try (var executor = provider.goal("execute", log).executor()) {
            executor.execute(() -> {
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                    observed.set(error);
                    handled.countDown();
                });
                provider.note("before failure");
                throw failure;
            });
            await(handled);
        }
        assert observed.get() == failure;
        assert log.reports.size() == 1 && log.reports.getFirst().failure() == failure;
    }

    @Test public void cancellationReportsActualInterruptionAndCloseWaits() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var started = new CountDownLatch(1);
        var blocker = new CountDownLatch(1);
        var executor = provider.goal("cancel", log).executor();
        try {
            var future = executor.submit((Callable<Void>) () -> {
                provider.note("waiting");
                started.countDown();
                blocker.await();
                return null;
            });
            await(started);
            assert future.cancel(true);
            executor.shutdown();
            assert executor.awaitTermination(5, TimeUnit.SECONDS);
            assert future.isCancelled();
            assert log.reports.size() == 1;
            assert log.reports.getFirst().failure() instanceof InterruptedException;
        } finally {
            blocker.countDown();
            executor.close();
        }
        assert executor.isShutdown() && executor.isTerminated();
        int count = log.reports.size();
        expect(RejectedExecutionException.class, () -> executor.submit(() -> {}));
        assert log.reports.size() == count;
    }

    @Test public void timedBulkCancellationAndShutdownNow() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var executor = provider.goal("shutdown", log).executor();
        try {
            executor.submit((Callable<Void>) () -> { started.countDown(); release.await(); return null; });
            await(started);
            executor.shutdownNow();
            assert executor.awaitTermination(5, TimeUnit.SECONDS);
            assert log.reports.size() == 1;
        } finally {
            release.countDown();
            executor.close();
        }
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        try (var fresh = provider.goal("expired", log).executor()) {
            var results = fresh.invokeAll(List.<Callable<Integer>>of(() -> invocations.incrementAndGet()),
                    0, TimeUnit.NANOSECONDS);
            // A new virtual thread can start or even finish before timeout cancellation wins.
            assert results.getFirst().isDone();
            if (!results.getFirst().isCancelled()) assert results.getFirst().get() == 1;
        }
        assert invocations.get() <= 1;
        assert log.reports.size() == 1;
    }

    @Test public void dispatchedGoalsDoNotInheritAndExecutorsCloseIndependently() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var goal = provider.goal("root", log);
        var first = goal.executor();
        var second = goal.executor();
        first.close();
        try (second) {
            goal.run(() -> {
                var outerTrail = provider.currentTrail();
                second.submit(() -> {
                    assert provider.currentTrail() != outerTrail;
                    provider.note("independent root");
                }).get(5, TimeUnit.SECONDS);
            });
        }
        assert log.reports.isEmpty();
    }
}
