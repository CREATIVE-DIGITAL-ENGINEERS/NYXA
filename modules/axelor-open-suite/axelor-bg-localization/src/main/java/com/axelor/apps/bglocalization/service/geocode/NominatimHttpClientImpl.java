package com.axelor.apps.bglocalization.service.geocode;

import com.google.inject.Singleton;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real Nominatim client: sets {@code countrycodes}, {@code Accept-Language}, the fixed identifying
 * {@code User-Agent} required by the Nominatim usage policy and a small result {@code limit}, and
 * enforces a process-wide minimum gap between requests (Nominatim's public instance is a shared,
 * rate-limited, best-effort service -- one request in flight at a time, no automatic retries here;
 * BgAddressGeocodeService is the only thing that decides whether/when to try again).
 *
 * <p><b>The User-Agent is a compile-time constant and nothing can change it at runtime.</b> It was
 * previously read from {@code AppBgLocalization.nominatimUserAgent}, edited through an extension of
 * the stock Preferences dialog. That configuration path was removed: it could not be saved
 * reliably, and while it was empty this client refused every geocode call -- so a broken settings
 * screen silently disabled address geocoding altogether. A constant cannot be blank, cannot be
 * misconfigured, and satisfies the usage policy (real application name and a real contact address)
 * on every install. The database column is left in place but is no longer read by anything.
 */
@Singleton
public class NominatimHttpClientImpl implements NominatimHttpClient {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String BASE_URL = "https://nominatim.openstreetmap.org/search";
  private static final int RESULT_LIMIT = 1;
  private static final long MIN_GAP_MILLIS = 1100L; // Nominatim policy: max ~1 req/sec.

  /**
   * The identifying header Nominatim's usage policy requires: application name, version, and a real
   * contact address for this service. Hardcoded on purpose -- see the class javadoc. It contains no
   * control characters, so no caller can turn it into a second header; that is a property of the
   * literal itself and is asserted by {@code NominatimHttpClientImplTest}.
   */
  static final String USER_AGENT = "NyxaERP/0.5 (+https://github.com/)";

  // Static/JVM-wide on purpose: the rate limit is a property of the shared external service, not of
  // any one instance -- must hold even if Guice ever produced more than one client.
  private static final AtomicLong lastCallAtMillis = new AtomicLong(0L);

  private final HttpClient httpClient;

  public NominatimHttpClientImpl() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public Optional<String> search(String query, String countryCodeLower) {
    if (query == null || query.trim().isEmpty()) {
      return Optional.empty();
    }
    awaitRateLimitSlot();

    HttpRequest request = buildRequest(query, countryCodeLower);

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        LOG.warn("Nominatim request failed: HTTP {}", response.statusCode());
        return Optional.empty();
      }
      return Optional.of(response.body());
    } catch (Exception e) {
      // Network/timeout error: treated as "no result", never as a reason to retry immediately.
      LOG.warn("Nominatim request failed: {}", e.toString());
      return Optional.empty();
    }
  }

  /**
   * Builds the exact request {@link #search} sends, without sending it and without waiting for a
   * rate-limit slot.
   *
   * <p>Package-private only so a test can read the headers off a real {@link HttpRequest} and prove
   * the {@code User-Agent} that goes on the wire is {@link #USER_AGENT}. Asserting on the constant
   * alone would prove nothing -- it would still pass if {@code search()} stopped attaching it.
   */
  HttpRequest buildRequest(String query, String countryCodeLower) {
    String url =
        BASE_URL
            + "?format=xml&addressdetails=1&limit="
            + RESULT_LIMIT
            + "&countrycodes="
            + urlEncode(countryCodeLower == null ? "" : countryCodeLower)
            + "&q="
            + urlEncode(query);

    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "bg")
        .GET()
        .build();
  }

  private static void awaitRateLimitSlot() {
    long now = System.currentTimeMillis();
    long previous = lastCallAtMillis.getAndUpdate(prev -> Math.max(now, prev + MIN_GAP_MILLIS));
    long waitMillis = previous + MIN_GAP_MILLIS - now;
    if (waitMillis > 0) {
      try {
        Thread.sleep(waitMillis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static String urlEncode(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
