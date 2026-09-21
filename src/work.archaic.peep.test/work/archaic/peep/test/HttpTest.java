package work.archaic.peep.test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import work.archaic.peep.Peep;
import work.archaic.service.logging.v01.GoalProvider;
import work.archaic.service.test.v01.Test;
import work.archaic.service.test.v01.TestSuite;
import static work.archaic.peep.test.Support.*;

public final class HttpTest implements TestSuite {
    @Test public void jdkServerHandlersUseScopedVirtualThreads() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var executor = provider.goal("http.request", log).executor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var virtual = new AtomicBoolean();
        var scoped = new AtomicBoolean();
        server.setExecutor(executor);
        server.createContext("/ok", exchange -> {
            try (exchange) {
                virtual.set(Thread.currentThread().isVirtual());
                scoped.set(provider.currentTrail() != null);
                provider.note("Request accepted");
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            }
        });
        try {
            server.start();
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(request(server, "/ok"), HttpResponse.BodyHandlers.ofString());
                assert response.statusCode() == 200 && response.body().equals("ok");
            }
        } finally {
            server.stop(0);
            executor.close();
        }
        assert virtual.get() && scoped.get();
        assert log.reports.isEmpty();
    }

    @Test public void httpStatusAndServerCaughtExceptionAreNotEscapingTaskFailures() throws Exception {
        GoalProvider provider = new Peep();
        var log = new MemoryLog();
        var executor = provider.goal("http.request", log).executor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var reached = new AtomicBoolean();
        server.setExecutor(executor);
        server.createContext("/status", exchange -> {
            try (exchange) {
                provider.note("Returning server error status");
                exchange.sendResponseHeaders(500, -1);
            }
        });
        server.createContext("/throw", exchange -> {
            try (exchange) {
                reached.set(true);
                provider.note("Handler throws, JDK server handles it");
                throw new IOException("handler failure");
            }
        });
        try {
            server.start();
            try (var client = HttpClient.newHttpClient()) {
                assert client.send(request(server, "/status"), HttpResponse.BodyHandlers.discarding())
                        .statusCode() == 500;
                expect(IOException.class, () -> client.send(request(server, "/throw"),
                        HttpResponse.BodyHandlers.discarding()));
            }
        } finally {
            server.stop(0);
            executor.close();
        }
        assert reached.get();
        assert log.reports.isEmpty() : "This JDK server catches handler IOExceptions inside its task";
        var failure = new IOException("direct task failure");
        try (var direct = provider.goal("direct", log).executor()) {
            expect(java.util.concurrent.ExecutionException.class,
                    () -> direct.submit((java.util.concurrent.Callable<Void>) () -> {
                        throw failure;
                    }).get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assert log.reports.size() == 1 && log.reports.getFirst().failure() == failure;
    }

    private static HttpRequest request(HttpServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
    }
}
