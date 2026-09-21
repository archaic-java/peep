# Peep

Goal-scoped diagnostics for Java 25. Peep implements the service catalog's
`work.archaic.service.logging.v02` contracts using scoped values and virtual threads.
It has no SLF4J dependency, logging levels, MDC, reflection or preview-feature requirement.

## Use

```java
Diagnostics diagnostics = exactlyOne(Diagnostics.class);
Log log = exactlyOne(Log.class);
Goal goal = diagnostics.goal("orders.create", log);

log.write("Application started; listening on port 8080");
goal.run(() -> {
    diagnostics.note("Checking inventory");
    Order order = orders.create();
    respondWithConfirmation(order);
});
```

Import contracts from `work.archaic.service.logging.v02`. Resolve providers once at application
startup through ServiceLoader (the `exactlyOne` helper is shown below) and share the selected
Diagnostics with participating code. Peep exports no packages; consumers cannot construct or
configure implementation classes. Consumer configuration must belong to the catalog contract.
`goal.run(...)` uses the current thread and preserves checked exception types. A goal represents a complete application intent,
including its response when applicable, and has no result-returning `call` method. Ordinary
methods inside a goal may still return values.

Each invocation has a fresh trail, UUID and timing. Success discards its trail; an escaping
Throwable publishes one failure report and propagates the original throwable. Output failures
are attached as suppressed where possible and never replace the original work failure.
`log.write(...)` writes and flushes immediate information regardless of any active goal.
There is no static global facade or implicit provider selection.

The same reusable goal may run concurrently. Current-trail access outside its scope, reuse of
a completed trail, cross-thread trail access and nested goals on the same provider fail
explicitly. Independently dispatched work gets its own root; no goal inheritance is provided.

## Virtual-thread execution

```java
try (var executor = goal.newExecutor()) {
    var completion = executor.submit(() -> {
        diagnostics.note("Loading customer");
        respondWithCustomer(customers.load(customerId));
    });
    completion.get();
}
```

The executor retains the standard `ExecutorService` contract, including Callable results for
JDK interoperability; the synchronous Goal API is run-only. Each executor is caller-owned and
uses a new virtual thread per task. `execute`, all `submit`
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
var executor = diagnostics.goal("http.request", log).newExecutor();
server.setExecutor(executor);
server.createContext("/orders", exchange -> {
    try (exchange) {
        diagnostics.note("Loading orders");
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
server, alongside an escaping task failure that is reported. To observe handler exceptions,
establish the goal at the handler boundary using `goal.run(...)`
and give the server an ordinary executor. Do not nest that goal inside a goal executor. Respond
to a failed intent inside the goal and rethrow its exception; HTTP status codes alone do not
indicate failure to diagnostics.
Keep response writing and closing synchronous within the handler. No request metadata or
trace headers are automatically collected.

## Bounds and output

Peep retains the latest 256 observations, with at most 2048 UTF-16 code units per message.
These are documented implementation defaults, not consumer-configurable settings. Oldest entries are
evicted, omitted observations counted, and truncated retained messages counted separately.
Truncation does not split a surrogate pair. Elapsed times use the monotonic clock; wall-clock
start times and execution UUIDs enable correlation. Successful operations still pay capture
costs. Bounds cover retained diagnostic messages, not Throwable graphs or incoming strings.

The v02 TextLog writes a timestamp for immediate notes and a contiguous failure report with elapsed
observations, loss counts and the original stack trace. Newlines in messages/names are escaped.
The service-loaded log writes to System.err and serializes writes/flushes on that stream.
Uncoordinated external writers are outside that guarantee. The log never closes stderr and
checks its PrintStream error flag to report I/O failures explicitly. There is no provider-specific
output destination setting. Applications can supply their own implementation of the catalog
Log contract when they need another destination. Flushing does not guarantee
durable storage; there is no retry, file rotation, background queue or crash recovery.

## JPMS and provider selection

The provider module is `work.archaic.peep` and exports/opens no packages. Consumers depend only on
`work.archaic.service.catalog` and discover providers with ServiceLoader:

```java
module example {
    requires work.archaic.service.catalog;
    uses work.archaic.service.logging.v02.Diagnostics;
    uses work.archaic.service.logging.v02.Log;
}
```

Peep registers both v02 providers using `provides ... with ...`. Select exactly one provider or
explicitly choose its type; zero or ambiguous providers are configuration errors. The example
module demonstrates discovery without depending on Peep's module or implementation classes.
Public service-provider constructors exist only for ServiceLoader; their package is not
exported. There are no qualified exports or reflective access overrides for tests.

```java
private static <T> T exactlyOne(Class<T> service) {
    var providers = ServiceLoader.load(service).stream().toList();
    if (providers.size() != 1) {
        throw new IllegalStateException("Expected one " + service.getName());
    }
    return providers.getFirst().get();
}
```

Tests also require only the catalog and discover the providers as consumers do. Stderr tests
run in isolated JVMs so changing a process's stderr does not affect concurrent tests. Boundary
checks assert that the provider module has no exports or opens. Further configuration requires
a catalog change first; the current contract remains unchanged.

## Build and verify

Check out sibling repositories, using these tested revisions. The catalog revision is supplied
by [service-catalog PR #3](https://github.com/archaic-java/service-catalog/pull/3), which must land
before this Peep update:

| Repository | Revision | Role |
| --- | --- | --- |
| [service-catalog](https://github.com/archaic-java/service-catalog) | `05d0ab690ef1d05161cd881092a8329418751b09` | Production contract |
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

## Migrating from v01

Peep continues to register its v01 providers for existing consumers. The v01 catalog package and
its behavior remain unchanged. New code should use `work.archaic.service.logging.v02` throughout
its imports, custom Log implementations and module `uses` declarations. Versions have separate
scope state and data types; use one selected Diagnostics instance for each participating group
of v02 consumers.

| v01 | v02 |
| --- | --- |
| `GoalProvider` | `Diagnostics` |
| `Log.note(String)` | `Log.write(String)` |
| `Goal.call(Operation)` | Removed; use `run(Action)` around the complete intent |
| `Goal.executor()` | `Goal.newExecutor()` |
| `FailureReport.goal()` | `FailureReport.goalName()` |

`Trail.note`, `Diagnostics.note`, `Observation` and `FailureReport` retain their roles.
Normal completion discards the trail; an escaping exception or error reports failure. Informing
the user about failure does not turn it into success: rethrow the original failure afterward.
If the response also fails, attach that failure as suppressed and rethrow the original. Catching
a failure and returning normally deliberately counts as success. There is no separate outcome
or completion API. See the catalog's [v02 contract](https://github.com/archaic-java/service-catalog/blob/main/docs/logging-v02.md).

Metrics/JFR, tracing, child goals and static logging conveniences remain future work.

