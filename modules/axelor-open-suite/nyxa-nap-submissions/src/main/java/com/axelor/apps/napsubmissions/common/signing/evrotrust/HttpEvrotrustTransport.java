package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.config.NapSecretProvider;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REAL Evrotrust vendor API transport. JDK {@link HttpClient} + Jackson, mirroring {@code
 * NraDeclarationsApiClient}. Pure transport: it builds the documented JSON bodies, computes the
 * HMAC {@code Authorization} header, applies timeouts, and maps HTTP/transport errors to {@link
 * QesException}. It embeds no signing business rules.
 *
 * <p><b>Authentication — CONFIRMED against docs.evrotrust.com.</b> The {@code Authorization} header
 * is {@code HMAC-SHA256(body)} keyed by the raw SHA-256 digest of the API key, emitted as a
 * 64-character lower-case hex string, over the request body exactly as sent (raw UTF-8 JSON,
 * serialized once). The API key is resolved at call time from a NON-SECRET reference via {@link
 * NapSecretProvider}; it is never logged, never placed in an exception, and never stored by Nyxa.
 * Without a resolvable key the call fails closed ({@link QesException.Category#CONFIG}).
 *
 * <p><b>The download is decrypted, not guessed.</b> {@code /document/download} answers with a ZIP
 * encrypted to the {@code publicKey} sent at start; {@link EvrotrustDownloadDecryptor} performs the
 * documented procedure (RSA PKCS#1 v1.5 → Rijndael-256-CBC chunks → inner ZIP) with OUR private key
 * from {@link EvrotrustPrivateKeyProvider}, and only a detached PKCS#7 is allowed out of it. Any
 * deviation is a refusal naming the concrete cause — never a silently forwarded artifact.
 *
 * <p>Everything here (paths, request bodies, response fields, status codes) matches the published
 * reference, and the ONLINE endpoints are kept strictly apart from the OFFLINE ones.
 */
@Singleton
public class HttpEvrotrustTransport implements EvrotrustTransport {

  private static final Logger LOG = LoggerFactory.getLogger(HttpEvrotrustTransport.class);

  // ⚠️ ONLINE FLOW ONLY. The documented single-hash ONLINE sequence is
  //   /user/check/extended → /document/hash/online → /document/status → /document/download
  // (the group variants are /document/group/hash/online, /document/group/status,
  // /document/group/download). The OFFLINE flow has its own endpoints, among them
  // /hash/offline/download — which is NOT interchangeable with /document/download just because
  // both names mention "hash". Nothing here is chosen by name similarity.
  static final String PATH_USER_CHECK = "/user/check/extended";
  static final String PATH_INIT_HASH = "/document/hash/online";
  static final String PATH_STATUS = "/document/status";
  static final String PATH_DOWNLOAD = "/document/download";

  /**
   * ⚠️ NOT part of the documented online-hash sequence. {@code /user/certificate/get} is a real
   * documented endpoint that returns a user's qualified eSign certificate, and it is the only
   * documented way to obtain the signer certificate НАП needs and the {@code serialNumber} that
   * {@code /document/hash/online} requires as {@code certificateSerialNumber}; the provider calls
   * it right before starting. Confirm with Evrotrust before PROD.
   */
  static final String PATH_CERTIFICATE = "/user/certificate/get";

  /** Documented {@code certificate.type}: 1 = Qualified eSign (the only kind НАП accepts). */
  static final int CERTIFICATE_TYPE_QUALIFIED = 1;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  protected final EvrotrustConfig config;
  protected final NapSecretProvider secretProvider;
  protected final EvrotrustDownloadDecryptor decryptor;
  protected final EvrotrustPrivateKeyProvider privateKeyProvider;
  protected final ObjectMapper mapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Inject
  public HttpEvrotrustTransport(
      EvrotrustConfig config,
      NapSecretProvider secretProvider,
      EvrotrustDownloadDecryptor decryptor,
      EvrotrustPrivateKeyProvider privateKeyProvider) {
    this.config = config;
    this.secretProvider = secretProvider;
    this.decryptor = decryptor;
    this.privateKeyProvider = privateKeyProvider;
  }

  @Override
  public UserCheck userCheck(EvrotrustSigner signer) {
    // Documented body: { vendorNumber, user: { ...one identity... } } — the identity is NESTED.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("vendorNumber", vendorNumber());
    body.put("user", identity(signer));
    JsonNode json = postJson(PATH_USER_CHECK, body, "user/check");
    boolean ready = asBool(json, "isReadyToSign");
    boolean rejected = asBool(json, "isRejected");
    return new UserCheck(ready, rejected, text(json, "rejectReason"));
  }

  @Override
  public InitResult initHashSign(InitRequest request) {
    EvrotrustSigner signer = request.getSigner();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("hash", request.getHashBase64());
    document.put("description", request.getDescription());
    document.put("dateExpire", request.getDateExpireEpochSeconds());

    Map<String, Object> signInfo = new LinkedHashMap<>();
    signInfo.put("algorithm", request.getAlgorithm());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("document", document);
    body.put("signInfo", signInfo);
    body.put("user", identity(signer));
    if (signer.getCertificateSerialNumber() != null) {
      body.put("certificateSerialNumber", signer.getCertificateSerialNumber());
    }
    body.put("vendorNumber", vendorNumber());
    // REQUIRED by the reference: our own Base64 public key, "necessary for encryption process".
    // The provider refuses before reaching here when it is not configured, so this is never blank.
    body.put("publicKey", request.getPublicKeyBase64());

    JsonNode json = postJson(PATH_INIT_HASH, body, "hash/online");
    return new InitResult(text(json, "transactionID"), text(json, "threadID"));
  }

  @Override
  public StatusResult status(String transactionId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionID", transactionId);
    body.put("vendorNumber", vendorNumber());
    JsonNode json = postJson(PATH_STATUS, body, "status");
    int code = json.hasNonNull("status") ? json.get("status").asInt() : -1;
    boolean processing = asBool(json, "isProcessing");
    return new StatusResult(code, processing, text(json, "rejectReason"));
  }

  @Override
  public DownloadResult downloadSignature(String transactionId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionID", transactionId);
    body.put("vendorNumber", vendorNumber());
    // Документираният отговор е ZIP (.enc.key/.enc.iv/.enc). Разшифрова се по документираната
    // процедура с НАШИЯ частен ключ, а от вътрешния ZIP се допуска само детачен PKCS#7. Всяко
    // отклонение е ясен отказ с конкретна причина — никога мълчаливо „UNKNOWN“.
    byte[] responseBody = postForBytes(PATH_DOWNLOAD, body, "document/download");
    byte[] innerZip = decryptor.decrypt(responseBody, privateKeyProvider.requirePrivateKey());
    byte[] detached = decryptor.detachedPkcs7From(innerZip);
    LOG.info("Evrotrust document/download decrypted; a detached PKCS#7 was extracted.");
    return new DownloadResult(detached, EvrotrustSignatureFormat.DETACHED_PKCS7);
  }

  @Override
  public CertificateResult getCertificate(EvrotrustSigner signer) {
    // Documented body: { vendorNumber, user: {...}, certificate: { type } } — no transaction id.
    Map<String, Object> certificateRequest = new LinkedHashMap<>();
    certificateRequest.put("type", CERTIFICATE_TYPE_QUALIFIED);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("vendorNumber", vendorNumber());
    body.put("user", identity(signer));
    body.put("certificate", certificateRequest);

    JsonNode json = postJson(PATH_CERTIFICATE, body, "user/certificate/get");
    String certificate = text(json, "certificate");
    byte[] der = new byte[0];
    if (certificate != null && !certificate.isBlank()) {
      try {
        der = Base64.getDecoder().decode(certificate.trim());
      } catch (IllegalArgumentException e) {
        // The docs call the field "the encoded signer certificate" without naming the encoding;
        // do not guess a non-base64 decoding.
        throw new QesException(
            QesException.Category.TRANSPORT,
            "Evrotrust certificate was not Base64 as assumed; confirm the encoding.");
      }
    }
    return new CertificateResult(der, text(json, "serialNumber"));
  }

  // --- transport --------------------------------------------------------------------------------

  protected JsonNode postJson(String path, Map<String, Object> body, String operation) {
    String base = baseUrl();
    String apiKey = apiKey();
    String json = serialize(body, operation);
    String url = base + path;
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", hmacHex(apiKey, json))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> resp =
          client().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      LOG.info("Evrotrust {} -> HTTP {}", operation, resp.statusCode());
      if (resp.statusCode() != 200) {
        throw new QesException(
            QesException.Category.TRANSPORT,
            "Evrotrust " + operation + " failed: HTTP " + resp.statusCode());
      }
      return parse(resp.body(), operation);
    } catch (java.io.IOException e) {
      throw new QesException(
          QesException.Category.TRANSPORT, "Transport error calling Evrotrust " + operation, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QesException(
          QesException.Category.TRANSPORT, "Interrupted calling Evrotrust " + operation, e);
    }
  }

  /**
   * Same signed request as {@link #postJson}, but the response is taken as RAW BYTES — the
   * documented {@code /document/download} answer is a ZIP, not JSON.
   */
  protected byte[] postForBytes(String path, Map<String, Object> body, String operation) {
    String json = serialize(body, operation);
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", hmacHex(apiKey(), json))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<byte[]> resp =
          client().send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      LOG.info("Evrotrust {} -> HTTP {}", operation, resp.statusCode());
      if (resp.statusCode() != 200) {
        throw new QesException(
            QesException.Category.TRANSPORT,
            "Evrotrust " + operation + " failed: HTTP " + resp.statusCode());
      }
      return resp.body() == null ? new byte[0] : resp.body();
    } catch (java.io.IOException e) {
      throw new QesException(
          QesException.Category.TRANSPORT, "Transport error calling Evrotrust " + operation, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QesException(
          QesException.Category.TRANSPORT, "Interrupted calling Evrotrust " + operation, e);
    }
  }

  protected HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
  }

  /**
   * {@code HMAC-SHA256(body)} keyed by {@code SHA-256(apiKey)}, lower-case hex. ⚠️ The exact bytes
   * Evrotrust canonicalizes before signing are not spelled out publicly; this HMACs the exact JSON
   * string sent. Confirm against a live sandbox before PROD.
   */
  protected static String hmacHex(String apiKey, String body) {
    try {
      byte[] key =
          MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(sig.length * 2);
      for (byte b : sig) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  protected String serialize(Object body, String operation) {
    try {
      return mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Failed to serialize Evrotrust " + operation + " request",
          e);
    }
  }

  protected JsonNode parse(String body, String operation) {
    try {
      return mapper.readTree(body == null ? "{}" : body);
    } catch (Exception e) {
      throw new QesException(
          QesException.Category.TRANSPORT,
          "Failed to parse Evrotrust " + operation + " response",
          e);
    }
  }

  // --- configuration ----------------------------------------------------------------------------

  protected String baseUrl() {
    EvrotrustEnvironment env = config.getEnvironment();
    if (env == null) {
      throw new QesException(
          QesException.Category.CONFIG,
          "Evrotrust environment must be explicitly TEST or PROD; none configured.");
    }
    return env.getBaseUrl();
  }

  protected String vendorNumber() {
    String vendor = config.getVendorNumber();
    if (vendor == null) {
      throw new QesException(
          QesException.Category.CONFIG, "Evrotrust vendor number is not configured.");
    }
    return vendor;
  }

  protected String apiKey() {
    String ref = config.getApiKeySecretRef();
    if (ref == null) {
      throw new QesException(
          QesException.Category.CONFIG, "No Evrotrust API-key secret reference configured.");
    }
    return secretProvider
        .resolveSecret(ref)
        .filter(k -> !k.isBlank())
        .orElseThrow(
            () ->
                new QesException(
                    QesException.Category.CONFIG,
                    "Evrotrust API key could not be resolved from the secret reference."));
  }

  /** The documented {@code user} object: EXACTLY ONE identity, plus country for an id-number. */
  private static Map<String, Object> identity(EvrotrustSigner signer) {
    Map<String, Object> user = new LinkedHashMap<>();
    if (signer.getIdentificationNumber() != null) {
      user.put("identificationNumber", signer.getIdentificationNumber());
      user.put("country", signer.getCountry());
    } else if (signer.getEmail() != null) {
      user.put("email", signer.getEmail());
    } else if (signer.getPhone() != null) {
      user.put("phone", signer.getPhone());
    }
    return user;
  }

  private static boolean asBool(JsonNode json, String field) {
    JsonNode node = json.get(field);
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    // Evrotrust uses 0/1 integers for the user-check flags.
    return node.asInt(0) == 1;
  }

  private static String text(JsonNode json, String field) {
    JsonNode node = json.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }
}
