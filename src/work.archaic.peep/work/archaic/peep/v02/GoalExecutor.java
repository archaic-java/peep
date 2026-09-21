package work.archaic.peep.v02;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import work.archaic.service.logging.v02.Goal;

/** Wrap user work before the JDK executor places it inside Future exception capture. */
final class GoalExecutor implements ExecutorService {
    private final Goal goal;
    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("peep-", 0).inheritInheritableThreadLocals(false).factory());

    GoalExecutor(Goal goal) {
        this.goal = goal;
    }

    private Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> goal.run(task::run);
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            // ExecutorService retains its standard Callable contract; Goal itself is run-only.
            class Result { T value; }
            var result = new Result();
            goal.run(() -> { result.value = task.call(); });
            return result.value;
        };
    }

    private <T> List<Callable<T>> wrap(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        var wrapped = new ArrayList<Callable<T>>(tasks.size());
        for (var task : tasks) wrapped.add(wrap(task));
        return wrapped;
    }

    @Override public void execute(Runnable task) { threads.execute(wrap(task)); }
    @Override public Future<?> submit(Runnable task) { return threads.submit(wrap(task)); }
    @Override public <T> Future<T> submit(Runnable task, T result) {
        return threads.submit(wrap(task), result);
    }
    @Override public <T> Future<T> submit(Callable<T> task) { return threads.submit(wrap(task)); }
    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return threads.invokeAll(wrap(tasks));
    }
    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
            long timeout, TimeUnit unit) throws InterruptedException {
        return threads.invokeAll(wrap(tasks), timeout, unit);
    }
    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return threads.invokeAny(wrap(tasks));
    }
    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
            long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return threads.invokeAny(wrap(tasks), timeout, unit);
    }
    @Override public void shutdown() { threads.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return threads.shutdownNow(); }
    @Override public boolean isShutdown() { return threads.isShutdown(); }
    @Override public boolean isTerminated() { return threads.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threads.awaitTermination(timeout, unit);
    }
    @Override public void close() { threads.close(); }
}

