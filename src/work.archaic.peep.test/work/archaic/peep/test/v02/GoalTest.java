package work.archaic.peep.test.v02;

import java.io.IOException;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import work.archaic.service.logging.v02.Diagnostics;
import work.archaic.service.logging.v02.Log;
import work.archaic.service.logging.v02.FailureReport;
import work.archaic.service.logging.v02.Trail;
import work.archaic.service.test.v01.Test;
import work.archaic.service.test.v01.TestSuite;
import static work.archaic.peep.test.v02.Support.*;

public final class GoalTest implements TestSuite {
    @Test public void discoversProviders() {
        var providers = ServiceLoader.load(Diagnostics.class).stream().toList();
        var logs = ServiceLoader.load(Log.class).stream().toList();
        assert providers.size() == 1;
        assert logs.size() == 1;
        var implementation = providers.getFirst().type().getModule();
        assert implementation.getDescriptor().exports().isEmpty();
        assert implementation.getDescriptor().opens().isEmpty();
        assert GoalTest.class.getModule().getDescriptor().requires().stream()
                .noneMatch(dependency -> dependency.name().equals("work.archaic.peep"));
        Diagnostics provider = providers.getFirst().get();
        provider.goal("loaded", new MemoryLog()).run(() -> provider.note("loaded"));
        assert logs.getFirst().get() != null;
    }

    @Test public void validatesCreationAndUnboundAccess() {
        Diagnostics provider = provider();
        expect(IllegalArgumentException.class, () -> provider.goal(" ", new MemoryLog()));
        expect(NullPointerException.class, () -> provider.goal(null, new MemoryLog()));
        expect(NullPointerException.class, () -> provider.goal("a", null));
        expect(IllegalStateException.class, provider::currentTrail);
        expect(IllegalStateException.class, () -> provider.note("unbound"));
        expect(NullPointerException.class, () -> provider.goal("a", new MemoryLog()).run(null));
    }

    @Test public void successDiscardsAndRunsOnCallingThread() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        var goal = provider.goal("lookup", log);
        var caller = Thread.currentThread();
        assert goal.name().equals("lookup");
        goal.run(() -> {
            assert Thread.currentThread() == caller;
            provider.note("evidence");
            assert provider.currentTrail() == provider.currentTrail();
        });
        goal.run(() -> {});
        assert log.reports.isEmpty();
        expect(IllegalStateException.class, provider::currentTrail);
    }

    @Test public void failureRetainsEvidenceAndThrowableIdentity() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        var goal = provider.goal("lookup", log);
        var failure = new IOException("failed");
        assert expect(IOException.class, () -> goal.run(() -> {
            provider.note("first");
            provider.note("second");
            throw failure;
        })) == failure;
        var report = log.reports.getFirst();
        assert log.reports.size() == 1;
        assert report.failure() == failure;
        assert report.goalName().equals("lookup");
        assert report.observations().stream().map(o -> o.message()).toList()
                .equals(java.util.List.of("first", "second"));
        assert report.duration().compareTo(report.observations().getLast().elapsed()) >= 0;
        goal.run(() -> provider.note("later success"));
        assert report.observations().size() == 2;
        assert log.reports.size() == 1;
        expect(IllegalStateException.class, provider::currentTrail);
    }

    @Test public void errorsAndInterruptionsArePreserved() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        var goal = provider.goal("failure", log);
        var error = new AssertionError("original");
        assert expect(AssertionError.class, () -> goal.run(() -> { throw error; })) == error;
        var interrupted = new InterruptedException("original");
        assert expect(InterruptedException.class, () -> goal.run(() -> { throw interrupted; })) == interrupted;
        assert log.reports.size() == 2;
        assert !log.reports.get(0).executionId().equals(log.reports.get(1).executionId());
    }

    @Test public void failureResponsePrecedesReportAndOriginalFailureEscapes() {
        Diagnostics diagnostics = provider();
        var events = new java.util.ArrayList<String>();
        var failure = new IOException("order storage failed");
        var responseFailure = new IOException("response unavailable");
        Log log = new Log() {
            @Override public void write(String message) { events.add(message); }
            @Override public void write(FailureReport report) {
                events.add("report");
                assert report.failure() == failure;
                assert report.failure().getSuppressed()[0] == responseFailure;
                assert report.observations().getFirst().message().equals("Saving order");
            }
        };
        assert expect(IOException.class, () -> diagnostics.goal("orders.place", log).run(() -> {
            diagnostics.note("Saving order");
            try {
                throw failure;
            } catch (IOException original) {
                try {
                    events.add("response");
                    throw responseFailure;
                } catch (IOException response) {
                    original.addSuppressed(response);
                }
                throw original;
            }
        })) == failure;
        assert events.equals(java.util.List.of("response", "report"));
        expect(IllegalStateException.class, diagnostics::currentTrail);
    }

    @Test public void recoveredFailureIsSuccess() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        provider.goal("retry", log).run(() -> {
            try { throw new IOException("first attempt"); }
            catch (IOException expected) { provider.note("Retry succeeded"); }
        });
        assert log.reports.isEmpty();
    }

    @Test public void staleAndForeignTrailsAreRejected() throws Exception {
        Diagnostics provider = provider();
        var trail = new AtomicReference<Trail>();
        var goal = provider.goal("scope", new MemoryLog());
        goal.run(() -> {
            trail.set(provider.currentTrail());
            expect(NullPointerException.class, () -> provider.note(null));
            try (var foreign = Executors.newVirtualThreadPerTaskExecutor()) {
                foreign.submit(() -> {
                    expect(IllegalStateException.class, () -> trail.get().note("foreign"));
                    expect(IllegalStateException.class, provider::currentTrail);
                }).get(5, TimeUnit.SECONDS);
            }
        });
        expect(IllegalStateException.class, () -> trail.get().note("closed"));
        expect(IOException.class, () -> goal.run(() -> {
            trail.set(provider.currentTrail());
            throw new IOException();
        }));
        expect(IllegalStateException.class, () -> trail.get().note("failed and closed"));
    }

    @Test public void nestingIsRejectedBeforeCallback() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        var outer = provider.goal("outer", log);
        var inner = provider.goal("inner", log);
        var ran = new AtomicBoolean();
        outer.run(() -> {
            var trail = provider.currentTrail();
            expect(IllegalStateException.class, () -> inner.run(() -> ran.set(true)));
            assert provider.currentTrail() == trail;
            provider.note("Still outer");
        });
        assert !ran.get();
        assert log.reports.isEmpty();
    }

    @Test public void reportingFailureDoesNotReplaceWorkFailure() {
        Diagnostics provider = provider();
        var outputFailure = new IOException("output unavailable");
        var trail = new AtomicReference<Trail>();
        Log broken = new Log() {
            @Override public void write(String message) throws IOException { throw outputFailure; }
            @Override public void write(FailureReport report) throws IOException {
                expect(IllegalStateException.class, provider::currentTrail);
                expect(IllegalStateException.class, () -> trail.get().note("too late"));
                throw outputFailure;
            }
        };
        var original = new IOException("work failure");
        assert expect(IOException.class, () -> provider.goal("broken", broken).run(() -> {
            trail.set(provider.currentTrail());
            throw original;
        })) == original;
        assert original.getSuppressed().length == 1;
        assert original.getSuppressed()[0] == outputFailure;
        expect(IllegalStateException.class, provider::currentTrail);
    }

    @Test public void immediateInformationSurvivesSuccessfulGoal() throws Exception {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        log.write("started");
        provider.goal("request", log).run(() -> {
            provider.note("discard me");
            log.write("configuration reloaded");
            assert log.notes.size() == 2;
        });
        assert log.notes.equals(java.util.List.of("started", "configuration reloaded"));
        assert log.reports.isEmpty();
    }

    @Test public void boundedEvidenceCountsOnlyRetainedTruncations() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        expect(IOException.class, () -> provider.goal("bounded", log).run(() -> {
            provider.note("x".repeat(2049));
            for (int i = 0; i < 254; i++) provider.note("filler");
            provider.note("a".repeat(2049));
            provider.note("ok");
            throw new IOException();
        }));
        var report = log.reports.getFirst();
        assert report.omittedObservations() == 1;
        assert report.truncatedObservations() == 1;
        assert report.observations().size() == 256;
        assert report.observations().get(254).message().equals("a".repeat(2048));
        assert report.observations().getLast().message().equals("ok");
    }

    @Test public void truncationDoesNotSplitSurrogatePairs() {
        Diagnostics provider = provider();
        var log = new MemoryLog();
        expect(IOException.class, () -> provider.goal("unicode", log).run(() -> {
            provider.note("a".repeat(2047) + "\uD83D\uDE00");
            throw new IOException();
        }));
        assert log.reports.getFirst().observations().getFirst().message().equals("a".repeat(2047));
        assert log.reports.getFirst().truncatedObservations() == 1;
    }
}

