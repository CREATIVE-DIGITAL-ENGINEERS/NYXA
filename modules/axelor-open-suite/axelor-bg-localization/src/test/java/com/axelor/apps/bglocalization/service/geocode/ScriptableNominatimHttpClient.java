package com.axelor.apps.bglocalization.service.geocode;

import com.google.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double bound in place of the real Nominatim client for {@link
 * BgCoordinatePrecisionPersistenceTest}: NEVER makes a real HTTP call. The canned XML response is
 * set by the test right before the call it drives, so the same Guice-managed singleton can be
 * reused across scenarios in one test class without any network I/O.
 */
@Singleton
public class ScriptableNominatimHttpClient implements NominatimHttpClient {

  private volatile String nextResponseXml;
  private final AtomicInteger callCount = new AtomicInteger();
  private volatile String lastQuery;

  public void setNextResponseXml(String xml) {
    this.nextResponseXml = xml;
  }

  public int callCount() {
    return callCount.get();
  }

  public String lastQuery() {
    return lastQuery;
  }

  @Override
  public Optional<String> search(String query, String countryCodeLower) {
    callCount.incrementAndGet();
    lastQuery = query;
    return Optional.ofNullable(nextResponseXml);
  }
}
