package com.axelor.apps.bglocalization.service.vat.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The only {@link VatSubmissionGateway} implementation: a thin HTTP client for the LOCAL MOCK NRA
 * VAT server (see {@code tools/mock-nra-vat-server/}). It is transport-only — it never interprets
 * business meaning beyond mapping the wire outcome (delivered / timeout / HTTP error / malformed).
 *
 * <p>Safety: it refuses to send unless {@link BgVatMockConfig#isUsable()} (enabled AND a local
 * URL). A send timeout is reported as {@code outcomeUnknown} so the workflow never auto re-POSTs; a
 * status-check timeout is a plain transport error (safe to retry).
 */
@Singleton
public class LocalHttpMockVatSubmissionGateway implements VatSubmissionGateway {

  static final String SUBMIT_PATH = "/mock/v1/vat-submissions";

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String targetCode() {
    return "LOCAL_MOCK";
  }

  @Override
  public boolean isUsable() {
    return BgVatMockConfig.fromEnvironment().isUsable();
  }

  @Override
  public String describeTarget() {
    BgVatMockConfig config = BgVatMockConfig.fromEnvironment();
    return "LOCAL MOCK @ " + config.getBaseUrl() + (config.isEnabled() ? "" : " (изключено)");
  }

  @Override
  public VatSubmissionResult submit(VatSubmissionRequest request) {
    return submit(request, BgVatMockConfig.fromEnvironment());
  }

  @Override
  public VatSubmissionResult checkStatus(String submissionRef) {
    return checkStatus(submissionRef, BgVatMockConfig.fromEnvironment());
  }

  VatSubmissionResult submit(VatSubmissionRequest request, BgVatMockConfig config) {
    if (!config.isUsable()) {
      return VatSubmissionResult.notConfigured(
          "Локалното mock подаване е изключено или URL адресът не е локален: "
              + config.getBaseUrl());
    }
    String body;
    try {
      body = mapper.writeValueAsString(toJson(request));
    } catch (Exception e) {
      return VatSubmissionResult.transportError(0, "REQUEST_BUILD", e.getMessage());
    }
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + SUBMIT_PATH))
            .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response = client(config).send(httpRequest, bodyHandler());
      return mapResponse(response.statusCode(), response.body());
    } catch (HttpConnectTimeoutException e) {
      // Connect failed: the request never reached the server — a plain transport failure (the
      // outcome is NOT unknown, so it is safe to treat as failed rather than blocking re-send).
      return VatSubmissionResult.transportError(
          0, "CONNECT_TIMEOUT", "Неуспешно свързване с mock сървъра (timeout).");
    } catch (HttpTimeoutException e) {
      // A response timeout: the request was sent but no response arrived — the outcome is genuinely
      // unknown, so the workflow must NOT auto re-POST.
      return VatSubmissionResult.outcomeUnknown(
          "Timeout при изпращане — изходът е неизвестен. Проверете статуса, не изпращайте повторно.");
    } catch (IOException e) {
      return VatSubmissionResult.transportError(
          0, "TRANSPORT", "Mock сървърът е недостъпен: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return VatSubmissionResult.transportError(0, "INTERRUPTED", e.getMessage());
    }
  }

  VatSubmissionResult checkStatus(String submissionRef, BgVatMockConfig config) {
    if (!config.isUsable()) {
      return VatSubmissionResult.notConfigured(
          "Локалното mock подаване е изключено или URL адресът не е локален.");
    }
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + SUBMIT_PATH + "/" + submissionRef))
            .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
            .GET()
            .build();
    try {
      HttpResponse<String> response = client(config).send(httpRequest, bodyHandler());
      return mapResponse(response.statusCode(), response.body());
    } catch (HttpTimeoutException e) {
      // A status-check timeout is NOT outcome-unknown — it is safe to retry.
      return VatSubmissionResult.transportError(0, "TIMEOUT", "Timeout при проверка на статус.");
    } catch (IOException e) {
      return VatSubmissionResult.transportError(
          0, "TRANSPORT", "Mock сървърът е недостъпен: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return VatSubmissionResult.transportError(0, "INTERRUPTED", e.getMessage());
    }
  }

  protected HttpClient client(BgVatMockConfig config) {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
        .build();
  }

  protected HttpResponse.BodyHandler<String> bodyHandler() {
    return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
  }

  private ObjectNode toJson(VatSubmissionRequest request) {
    ObjectNode root = mapper.createObjectNode();
    root.put("companyVatNumber", request.getCompanyVatNumber());
    root.put("companyName", request.getCompanyName());
    root.put("period", request.getPeriod());
    root.put("packageSha256", request.getPackageSha256());
    root.put("scenario", request.getScenario());
    ObjectNode files = root.putObject("files");
    for (Map.Entry<String, String> e : request.getFilesBase64().entrySet()) {
      files.put(e.getKey(), e.getValue());
    }
    return root;
  }

  /**
   * Map the HTTP status + body onto a transport result. Business status stays as the raw string.
   */
  VatSubmissionResult mapResponse(int status, String body) {
    if (status == 429) {
      return VatSubmissionResult.transportError(429, "HTTP_429", "Твърде много заявки (mock).");
    }
    if (status >= 500) {
      return VatSubmissionResult.transportError(
          status, "HTTP_" + status, "Сървърна грешка (mock).");
    }
    if (status != 200) {
      return VatSubmissionResult.transportError(status, "HTTP_" + status, "Неочакван HTTP статус.");
    }
    JsonNode node;
    try {
      node = mapper.readTree(body);
    } catch (Exception e) {
      return VatSubmissionResult.transportError(
          200, "MALFORMED", "Невалиден JSON отговор от mock.");
    }
    if (node == null || !node.isObject()) {
      return VatSubmissionResult.transportError(200, "MALFORMED", "Невалиден отговор от mock.");
    }
    return VatSubmissionResult.builder()
        .configured(true)
        .success(true)
        .httpStatus(200)
        .externalStatus(text(node, "status"))
        .submissionRef(text(node, "submissionId"))
        .entryNumber(text(node, "entryNumber"))
        .correlationId(text(node, "correlationId"))
        .errorCode(text(node, "errorCode"))
        .errorMessage(text(node, "errorMessage"))
        .rawBody(body)
        .build();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
