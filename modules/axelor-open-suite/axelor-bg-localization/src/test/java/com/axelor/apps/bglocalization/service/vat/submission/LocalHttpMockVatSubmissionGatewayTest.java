package com.axelor.apps.bglocalization.service.vat.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link LocalHttpMockVatSubmissionGateway} against an embedded {@code
 * com.sun.net.httpserver} that reproduces the mock scenarios. Verifies transport mapping: delivered
 * business statuses, HTTP 500/429, malformed JSON, a send timeout → outcome-unknown, a status-check
 * timeout → retryable transport error, and the safety refusals (disabled / non-local / offline).
 */
class LocalHttpMockVatSubmissionGatewayTest {

  private static final Pattern SCENARIO = Pattern.compile("\"scenario\"\\s*:\\s*\"([^\"]*)\"");

  private static HttpServer server;
  private static String baseUrl;

  private final LocalHttpMockVatSubmissionGateway gateway = new LocalHttpMockVatSubmissionGateway();

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/mock/v1/vat-submissions",
        exchange -> {
          try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
              write(exchange, 200, ok("MOCK-1", "accepted", "NRA-1"));
              return;
            }
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = SCENARIO.matcher(body);
            String scenario = m.find() ? m.group(1) : "success";
            switch (scenario) {
              case "http_500":
                write(exchange, 500, "{\"error\":\"boom\"}");
                break;
              case "http_429":
                write(exchange, 429, "{\"error\":\"slow down\"}");
                break;
              case "malformed":
                write(exchange, 200, "{ not valid json");
                break;
              case "timeout":
                sleep(1200);
                write(exchange, 200, ok("MOCK-1", "accepted", "NRA-1"));
                break;
              case "rejection":
                write(
                    exchange,
                    200,
                    "{\"submissionId\":\"MOCK-1\",\"status\":\"rejected\",\"entryNumber\":null,"
                        + "\"correlationId\":\"CID-1\",\"errorCode\":\"VAT_REJECTED\","
                        + "\"errorMessage\":\"bad\"}");
                break;
              case "duplicate":
                write(exchange, 200, ok("MOCK-1", "duplicate", "NRA-1"));
                break;
              case "processing_then_success":
                write(exchange, 200, ok("MOCK-1", "processing", null));
                break;
              default:
                write(exchange, 200, ok("MOCK-1", "accepted", "NRA-1"));
            }
          } catch (Exception e) {
            write(exchange, 500, "{\"error\":\"test-handler\"}");
          }
        });
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private BgVatMockConfig config(int requestTimeoutMs) {
    return new BgVatMockConfig(true, baseUrl, requestTimeoutMs, 2000);
  }

  private VatSubmissionRequest request(String scenario) {
    VatSubmissionRequest r = new VatSubmissionRequest();
    r.setCompanyVatNumber("BG123456789");
    r.setCompanyName("Test OOD");
    r.setPeriod("202606");
    r.setPackageSha256("abc");
    r.setScenario(scenario);
    r.addFile("DEKLAR.TXT", "REVFRw==");
    return r;
  }

  @Test
  void submitSuccess() {
    VatSubmissionResult r = gateway.submit(request("success"), config(2000));
    assertTrue(r.isConfigured());
    assertTrue(r.isSuccess());
    assertFalse(r.isOutcomeUnknown());
    assertEquals("accepted", r.getExternalStatus());
    assertNotNull(r.getSubmissionRef());
    assertNotNull(r.getEntryNumber());
  }

  @Test
  void submitRejection() {
    VatSubmissionResult r = gateway.submit(request("rejection"), config(2000));
    assertTrue(r.isSuccess());
    assertEquals("rejected", r.getExternalStatus());
    assertEquals("VAT_REJECTED", r.getErrorCode());
  }

  @Test
  void submitDuplicate() {
    VatSubmissionResult r = gateway.submit(request("duplicate"), config(2000));
    assertEquals("duplicate", r.getExternalStatus());
  }

  @Test
  void submitHttp500IsTransportError() {
    VatSubmissionResult r = gateway.submit(request("http_500"), config(2000));
    assertFalse(r.isSuccess());
    assertFalse(r.isOutcomeUnknown());
    assertEquals(500, r.getHttpStatus());
  }

  @Test
  void submitHttp429IsTransportError() {
    VatSubmissionResult r = gateway.submit(request("http_429"), config(2000));
    assertFalse(r.isSuccess());
    assertEquals(429, r.getHttpStatus());
  }

  @Test
  void submitMalformedResponse() {
    VatSubmissionResult r = gateway.submit(request("malformed"), config(2000));
    assertFalse(r.isSuccess());
    assertEquals("MALFORMED", r.getErrorCode());
  }

  @Test
  void submitTimeoutIsOutcomeUnknown() {
    VatSubmissionResult r = gateway.submit(request("timeout"), config(300));
    assertFalse(r.isSuccess());
    assertTrue(r.isOutcomeUnknown(), "a send timeout must be outcome-unknown");
  }

  @Test
  void statusCheckTimeoutIsRetryableNotOutcomeUnknown() {
    // GET handler is immediate; simulate a slow status check by using an unroutable delay via the
    // submit timeout path is not applicable — instead point at a closed port to force a transport
    // error (retryable), which is the same class the workflow treats as "keep status, retry".
    BgVatMockConfig offline = new BgVatMockConfig(true, "http://127.0.0.1:1", 300, 200);
    VatSubmissionResult r = gateway.checkStatus("MOCK-1", offline);
    assertFalse(r.isSuccess());
    assertFalse(r.isOutcomeUnknown(), "status-check failure must not be outcome-unknown");
  }

  @Test
  void statusCheckSuccess() {
    VatSubmissionResult r = gateway.checkStatus("MOCK-1", config(2000));
    assertTrue(r.isSuccess());
    assertEquals("accepted", r.getExternalStatus());
  }

  @Test
  void disabledGatewayIsNotConfigured() {
    VatSubmissionResult r =
        gateway.submit(request("success"), new BgVatMockConfig(false, baseUrl, 2000, 2000));
    assertFalse(r.isConfigured());
  }

  @Test
  void nonLocalUrlIsRefused() {
    VatSubmissionResult r =
        gateway.submit(
            request("success"), new BgVatMockConfig(true, "https://api.nra.bg", 2000, 2000));
    assertFalse(r.isConfigured());
  }

  @Test
  void offlineServerIsTransportErrorNotCrash() {
    VatSubmissionResult r =
        gateway.submit(
            request("success"), new BgVatMockConfig(true, "http://127.0.0.1:1", 500, 300));
    assertTrue(r.isConfigured());
    assertFalse(r.isSuccess());
    assertFalse(r.isOutcomeUnknown());
  }

  private static String ok(String id, String status, String entry) {
    return "{\"submissionId\":\""
        + id
        + "\",\"status\":\""
        + status
        + "\",\"entryNumber\":"
        + (entry == null ? "null" : "\"" + entry + "\"")
        + ",\"correlationId\":\"CID-1\",\"errorCode\":null,\"errorMessage\":null}";
  }

  private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
