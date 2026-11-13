package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor.AzureResponse;
import com.axelor.apps.aiocr.service.ocr.AzureRateLimiter.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the <b>real</b> {@code httpSubmit}/{@code httpGet} bodies go through the rate limiter, on
 * every attempt including retries.
 *
 * <p>Every other extractor test overrides those two methods to script responses, so none of them
 * can see the pacing — the limiter could be deleted from both and they would all still pass. This
 * test therefore runs the production transport code unchanged against a loopback HTTP server, and
 * only substitutes the limiter itself (so acquisitions can be counted).
 */
class AzureLimiterHttpPathTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger posts = new AtomicInteger();
  private final AtomicInteger gets = new AtomicInteger();

  /** Limiter that counts acquisitions; spacing is kept at 1 ms so the test stays fast. */
  private static final class CountingLimiter extends AzureRateLimiter {
    final AtomicInteger acquires = new AtomicInteger();

    @Override
    protected long intervalMs(Kind kind) {
      return 1L;
    }

    @Override
    protected boolean enabled() {
      return true;
    }

    @Override
    protected long maxWaitMs() {
      return 5_000L;
    }

    @Override
    public long acquire(Kind kind) {
      acquires.incrementAndGet();
      return super.acquire(kind);
    }
  }

  private final CountingLimiter analyzeLimiter = new CountingLimiter();
  private final CountingLimiter pollLimiter = new CountingLimiter();

  /** Extractor pointed at the loopback server; ONLY the limiter and config are substituted. */
  private final class ServerExtractor extends AzureDocumentIntelligenceExtractor {
    @Override
    protected String endpoint() {
      return baseUrl;
    }

    @Override
    protected String key() {
      return "test-key";
    }

    @Override
    protected String model() {
      return "prebuilt-invoice";
    }

    @Override
    protected String apiVersion() {
      return "2024-11-30";
    }

    @Override
    protected int maxRetries() {
      return 2; // → 3 attempts in total
    }

    @Override
    protected int connectTimeout() {
      return 5;
    }

    @Override
    protected int requestTimeout() {
      return 5;
    }

    @Override
    protected int pollIntervalMs() {
      return 1;
    }

    @Override
    protected int pollMaxSeconds() {
      return 20;
    }

    @Override
    protected void backoff(int attempt, String retryAfter) {
      // no real sleeping; the pacing under test is the limiter, not the backoff
    }

    @Override
    protected void sleepBeforeNextPoll(String retryAfter) {}

    @Override
    protected AzureRateLimiter limiter(Kind kind) {
      return kind == Kind.ANALYZE ? analyzeLimiter : pollLimiter;
    }
  }

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            while (in.read() != -1) {
              // drain the request body so the client sees a clean response
            }
          }
          if ("POST".equals(exchange.getRequestMethod())) {
            // Two 429s, then accept — so the retry path is genuinely exercised.
            if (posts.incrementAndGet() < 3) {
              byte[] body = "{\"error\":{\"code\":\"429\"}}".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Retry-After", "1");
              exchange.sendResponseHeaders(429, body.length);
              exchange.getResponseBody().write(body);
            } else {
              exchange.getResponseHeaders().add("Operation-Location", baseUrl + "/op");
              exchange.sendResponseHeaders(202, -1);
            }
          } else {
            String json =
                gets.incrementAndGet() < 2
                    ? "{\"status\":\"running\"}"
                    : "{\"status\":\"succeeded\",\"analyzeResult\":{\"documents\":[]}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void everySubmitAttemptIncludingRetriesIsPaced() {
    ServerExtractor e = new ServerExtractor();

    AzureResponse resp = e.submitWithRetry(new byte[] {1, 2, 3}, "application/pdf");

    assertEquals(202, resp.status);
    assertEquals(3, posts.get(), "the server should have seen two 429s and one accept");
    // The decisive assertion: the limiter was consulted once per attempt, retries included —
    // a retry that skipped pacing would be the very request most likely to be throttled again.
    assertEquals(3, analyzeLimiter.acquires.get());
    assertEquals(0, pollLimiter.acquires.get(), "submits must not consume the poll allowance");
  }

  @Test
  void everyPollIsPaced() {
    ServerExtractor e = new ServerExtractor();

    AzureResponse resp = e.submitWithRetry(new byte[] {1, 2, 3}, "application/pdf");
    assertNotNull(resp.operationLocation);
    int acquiresAfterSubmit = analyzeLimiter.acquires.get();

    JsonNode analyzeResult = e.pollUntilDone(resp.operationLocation);

    assertTrue(analyzeResult.has("documents"));
    assertEquals(2, gets.get(), "one 'running' poll then one 'succeeded'");
    assertEquals(2, pollLimiter.acquires.get());
    assertEquals(
        acquiresAfterSubmit,
        analyzeLimiter.acquires.get(),
        "polling must not consume the analyze allowance");
  }
}
