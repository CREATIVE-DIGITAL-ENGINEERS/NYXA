import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone LOCAL MOCK of an NRA VAT submission endpoint for Nyxa development/testing.
 *
 * <p><b>This is an internal Nyxa mock contract, NOT an NRA specification.</b> The real NRA VAT API
 * does not exist yet; this server only exercises the Nyxa VAT submission workflow end to end. No
 * authentication, no real endpoints, no real error codes.
 *
 * <p>Java 11, no external dependencies (uses the JDK {@code com.sun.net.httpserver}). Build/run:
 *
 * <pre>
 *   cd tools/mock-nra-vat-server
 *   javac MockNraVatServer.java
 *   java MockNraVatServer            # port 8899 (default)
 *   java MockNraVatServer 9100       # custom port
 *   # or: MOCK_VAT_PORT=9100 java MockNraVatServer
 * </pre>
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /mock/v1/vat-submissions} — internal request JSON may contain
 *       {@code companyVatNumber}, {@code companyName}, {@code period}, {@code packageSha256},
 *       {@code scenario} and a {@code files} object of Base64 strings.
 *   <li>{@code GET  /mock/v1/vat-submissions/{id}} — status of a mock submission.
 * </ul>
 *
 * <p>Deterministic scenarios (from the request {@code scenario} field): {@code success},
 * {@code processing_then_success}, {@code rejection}, {@code duplicate}, {@code http_500},
 * {@code http_429}, {@code timeout}, {@code malformed}. Default is {@code success}.
 */
public class MockNraVatServer {

  private static final Pattern SCENARIO = Pattern.compile("\"scenario\"\\s*:\\s*\"([^\"]*)\"");
  private static final AtomicLong COUNTER = new AtomicLong(1000);
  private static final ConcurrentHashMap<String, State> STORE = new ConcurrentHashMap<>();

  /** Delay for the "timeout" scenario (longer than any sane client timeout). */
  private static final long TIMEOUT_DELAY_MS = 60_000L;

  static final class State {
    String status;
    String entryNumber;
    String correlationId;
    String scenario;
    int pollsUntilSuccess;
  }

  public static void main(String[] args) throws IOException {
    int port = resolvePort(args);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/mock/v1/vat-submissions", MockNraVatServer::handle);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.println("[mock-nra-vat] listening on http://127.0.0.1:" + port);
    System.out.println("[mock-nra-vat] POST /mock/v1/vat-submissions , GET /mock/v1/vat-submissions/{id}");
    System.out.println("[mock-nra-vat] INTERNAL MOCK CONTRACT — not an NRA specification.");
  }

  private static int resolvePort(String[] args) {
    if (args != null && args.length > 0) {
      try {
        return Integer.parseInt(args[0].trim());
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    String env = System.getenv("MOCK_VAT_PORT");
    if (env != null && !env.trim().isEmpty()) {
      try {
        return Integer.parseInt(env.trim());
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    return 8899;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    try {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      if ("POST".equalsIgnoreCase(method) && path.endsWith("/vat-submissions")) {
        handleSubmit(exchange);
      } else if ("GET".equalsIgnoreCase(method) && path.contains("/vat-submissions/")) {
        handleStatus(exchange, path.substring(path.lastIndexOf('/') + 1));
      } else {
        respond(exchange, 404, "{\"error\":\"not found\"}");
      }
    } catch (Exception e) {
      respond(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
    }
  }

  private static void handleSubmit(HttpExchange exchange) throws IOException, InterruptedException {
    String body = read(exchange.getRequestBody());
    String scenario = extract(SCENARIO, body, "success");
    String id = "MOCK-" + COUNTER.incrementAndGet();
    String correlationId = "CID-" + COUNTER.get();
    String entryNumber = "NRA-MOCK-" + COUNTER.get();

    switch (scenario) {
      case "http_500":
        respond(exchange, 500, "{\"error\":\"mock internal error\"}");
        return;
      case "http_429":
        respond(exchange, 429, "{\"error\":\"too many requests\"}");
        return;
      case "malformed":
        respond(exchange, 200, "{ this is intentionally not valid json ");
        return;
      case "timeout":
        Thread.sleep(TIMEOUT_DELAY_MS);
        store(id, "accepted", entryNumber, correlationId, scenario, 0);
        respond(exchange, 200, ok(id, "accepted", entryNumber, correlationId));
        return;
      case "rejection":
        store(id, "rejected", null, correlationId, scenario, 0);
        respond(
            exchange,
            200,
            body("rejected", id, null, correlationId, "VAT_REJECTED", "Mock rejection"));
        return;
      case "duplicate":
        store(id, "duplicate", entryNumber, correlationId, scenario, 0);
        respond(
            exchange,
            200,
            body("duplicate", id, entryNumber, correlationId, "DUPLICATE", "Mock duplicate"));
        return;
      case "processing_then_success":
        store(id, "processing", entryNumber, correlationId, scenario, 1);
        respond(exchange, 200, ok(id, "processing", null, correlationId));
        return;
      case "success":
      default:
        store(id, "accepted", entryNumber, correlationId, scenario, 0);
        respond(exchange, 200, ok(id, "accepted", entryNumber, correlationId));
    }
  }

  private static void handleStatus(HttpExchange exchange, String id) throws IOException {
    State state = STORE.get(id);
    if (state == null) {
      respond(exchange, 404, "{\"error\":\"unknown submission " + escape(id) + "\"}");
      return;
    }
    // processing_then_success: flip to accepted after the configured number of polls.
    if ("processing".equals(state.status) && state.pollsUntilSuccess > 0) {
      state.pollsUntilSuccess--;
      if (state.pollsUntilSuccess <= 0) {
        state.status = "accepted";
      }
    }
    respond(exchange, 200, ok(id, state.status, state.entryNumber, state.correlationId));
  }

  private static void store(
      String id, String status, String entry, String cid, String scenario, int polls) {
    State s = new State();
    s.status = status;
    s.entryNumber = entry;
    s.correlationId = cid;
    s.scenario = scenario;
    s.pollsUntilSuccess = polls;
    STORE.put(id, s);
  }

  private static String ok(String id, String status, String entry, String cid) {
    return body(status, id, entry, cid, null, null);
  }

  private static String body(
      String status, String id, String entry, String cid, String errorCode, String errorMessage) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"submissionId\":\"").append(escape(id)).append("\",");
    sb.append("\"status\":\"").append(escape(status)).append("\",");
    sb.append("\"entryNumber\":").append(entry == null ? "null" : "\"" + escape(entry) + "\"").append(",");
    sb.append("\"correlationId\":").append(cid == null ? "null" : "\"" + escape(cid) + "\"").append(",");
    sb.append("\"errorCode\":").append(errorCode == null ? "null" : "\"" + escape(errorCode) + "\"").append(",");
    sb.append("\"errorMessage\":")
        .append(errorMessage == null ? "null" : "\"" + escape(errorMessage) + "\"");
    sb.append("}");
    return sb.toString();
  }

  private static String extract(Pattern pattern, String body, String fallback) {
    if (body == null) {
      return fallback;
    }
    Matcher m = pattern.matcher(body);
    return m.find() ? m.group(1) : fallback;
  }

  private static String read(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static String escape(String v) {
    if (v == null) {
      return "";
    }
    return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
  }
}
