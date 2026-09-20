# Peep

Goal-scoped diagnostics for Java 25. Peep implements the service catalog's
`work.archaic.service.logging.v01` contracts using scoped values and virtual threads.
It has no SLF4J dependency, logging levels, MDC, reflection or preview-feature requirement.

## Use

```java
GoalProvider peep = new Peep();
Log log = new TextLog(); // stderr; or new TextLog(callerOwnedWriter)
Goal goal = peep.goal("orders.create", log);

log.note("Application started; listening on port 8080");
Order order = goal.call(() -> {
    peep.note("Checking inventory");
    return orders.create();
});
```

Import contracts from `work.archaic.service.logging.v01` and the concrete `Peep`/`TextLog`
providers from `work.archaic.peep`. Construct providers once at application startup and share
the selected GoalProvider with participating code. `goal.run(...)` is the void-returning form.
Both synchronous forms use the current thread and preserve checked exception types.

Each invocation has a fresh trail, UUID and timing. Success discards its trail; an escaping
Throwable publishes one failure report and propagates the original throwable. Output failures
are attached as suppressed where possible and never replace the original work failure.
`log.note(...)` writes and flushes immediate information regardless of any active goal.
There is no static global facade or implicit provider selection.

The same reusable goal may run concurrently. Current-trail access outside its scope, reuse of
a completed trail, cross-thread trail access and nested goals on the same provider fail
explicitly. Independently dispatched work gets its own root; no goal inheritance is provided.

## Virtual-thread execution

```java
try (var executor = goal.executor()) {
    var result = executor.submit(() -> {
        peep.note("Loading customer");
        return customers.load(customerId);
    });
    return result.get();
}
```

Each executor is caller-owned and uses a new virtual thread per task. `execute`, all `submit`
overloads, `invokeAll` and `invokeAny` instrument the actual user work before the JDK captures
exceptions in futures. Closing waits for termination and does not close the reusable goal,
other executors, or the log. Rejected or cancelled-before-start tasks run no goal. Cancellation
of running work is cooperative: an escaping interruption is reported, normal return is success.
`Future.cancel` can return before the task and its failure reporting finish; executor termination
is the boundary for waiting for all of that work. Do not close an executor from its own task.

An exception escaping `execute` is reported and still reaches the thread's uncaught-exception
handler. The default JVM handler may therefore also print a stack trace. `submit` stores it in
the future instead. Failures hidden inside a supplied Runnable (including a pre-wrapped
FutureTask) are not observable; submit the actual work through Peep's executor methods.

## JDK HTTP server

```java
var executor = peep.goal("http.request", log).executor();
server.setExecutor(executor);
server.createContext("/orders", exchange -> {
    try (exchange) {
        peep.note("Loading orders");
        byte[] body = loadOrders();
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
});
server.start();
// Application shutdown: stop accepting/handling exchanges, then close the executor.
// server.stop(delaySeconds);
// executor.close();
```

The executor observes task completion, not HTTP success. The JDK server can catch a handler's
IOException inside its task; such a failure does not reach the goal. A returned 500 is also
normal task completion. The integration tests demonstrate both cases against the real JDK
server, alongside an escaping task failure that is reported. Full HTTP outcome reporting needs
a future handler/filter adapter and an agreed outcome contract; Peep does not claim it yet.
Keep response writing and closing synchronous within the handler. No request metadata or
trace headers are automatically collected.

## Bounds and output

`new Peep()` retains the latest 256 observations, with at most 2048 UTF-16 code units per
message. `new Peep(maxEntries, maxMessageCharacters)` sets positive limits. Oldest entries are
evicted, omitted observations counted, and truncated retained messages counted separately.
Truncation does not split a surrogate pair. Elapsed times use the monotonic clock; wall-clock
start times and execution UUIDs enable correlation. Successful operations still pay capture
costs. Bounds cover retained diagnostic messages, not Throwable graphs or incoming strings.

TextLog writes a timestamp for immediate notes and a contiguous failure report with elapsed
observations, loss counts and the original stack trace. Newlines in messages/names are escaped.
Writes/flushes serialize on the supplied Writer, or System.err for the default provider. Share
the same writer when multiple logs target it. Uncoordinated external writers are outside that
guarantee. TextLog never closes its destination. I/O failures are explicit, including the
error flags of PrintWriter and the default stderr PrintStream. Flushing does not guarantee
durable storage; there is no retry, file rotation, background queue or crash recovery.

## JPMS and provider selection

The provider module is `work.archaic.peep`. Consumers may depend only on
`work.archaic.service.catalog` and discover providers with ServiceLoader:

```java
module example {
    requires work.archaic.service.catalog;
    uses work.archaic.service.logging.v01.GoalProvider;
    uses work.archaic.service.logging.v01.Log;
}
```

Peep registers both providers using `provides ... with ...`. Select exactly one provider or
explicitly choose its type; zero or ambiguous providers are configuration errors. The example
module demonstrates discovery without depending on Peep's module or implementation classes.
Explicit construction requires `requires work.archaic.peep` at the application's composition
boundary; service-facing code can continue to depend on the catalog.

## Build and verify

Check out sibling repositories, using these tested revisions:

| Repository | Revision | Role |
| --- | --- | --- |
| [service-catalog](https://github.com/archaic-java/service-catalog) | `ad987969d9fac64756e1eb86f7839e80fb2e5dcd` | Production contract |
| [minau](https://github.com/archaic-java/minau) | `13808cc18f93005fb88f5510d37bd2785a435578` | Tests only |

The checked-in relative links under `lib/src` expect `peep`, `service-catalog` and `minau` in
the same parent directory. No dependency source is copied into Peep. With JDK 25 on PATH:

```sh
javac @cmd/compile
java @cmd/test
java @cmd/run
```

The example intentionally demonstrates one failure report and exits normally. Tests use Minau
with assertions enabled, including catalog-level lifecycle, exception identity, concurrency,
bounded evidence, executor overloads/cancellation, output failures and loopback HTTP checks.
Production consumers need only Peep and the catalog, not the test runner or example modules.

Metrics/JFR, tracing, child goals and static logging conveniences are deliberately left for
later work. Existing v01 contracts remain unchanged.
