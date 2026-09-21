package work.archaic.peep.test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import work.archaic.service.test.v01.Test;
import work.archaic.service.test.v01.TestSuite;

/** Separate JVMs isolate stderr replacement from concurrent Minau tests. */
public final class LogTest implements TestSuite {
    @Test public void formatsAndFlushesImmediateNotesAndFailureEvidence() throws Exception {
        probe("output");
    }

    @Test public void rejectsNullAndPropagatesOutputFailures() throws Exception {
        probe("failure");
    }

    @Test public void serializesConcurrentRecordsAcrossLogsSharingStderr() throws Exception {
        probe("concurrent");
    }

    private static void probe(String mode) throws Exception {
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-ea", "--module-path", System.getProperty("jdk.module.path"),
                "-m", "work.archaic.peep.test/work.archaic.peep.test.LogProbe", mode)
                .redirectErrorStream(true).start();
        try {
            assert process.waitFor(10, TimeUnit.SECONDS) : "Log probe timed out: " + mode;
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assert process.exitValue() == 0 : mode + ": " + output;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
