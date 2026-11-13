package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.CityRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CITY-tier fallback: a trusted centroid coordinate for a City, safe to show even when nothing
 * else is available. No authoritative EKATTE-keyed coordinate dataset is available locally today
 * (bgplaces.csv carries no lat/long columns) -- see the City.xml domain extension javadoc. Until
 * one is added, this resolves it lazily and once: on first actual need, from City name + Country
 * ONLY (never street/address text, so it cannot be pulled off course the way a full-address query
 * can), validates the country before trusting it, and caches the result on {@code City.latit}/
 * {@code City.longit} forever after -- never re-geocoded, never bulk-run, never fuzzy-matched.
 *
 * <p>TODO: replace this fallback with an import from an official EKATTE-keyed centroid dataset
 * (mirroring {@code BgCityPostcodeImportService}'s pattern) if/when one becomes available; this
 * class's cache format (City.latit/longit) is exactly what such an importer would populate too.
 */
@Singleton
public class BgCityCentroidService {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final NominatimHttpClient httpClient;
  private final CityRepository cityRepository;
  private final BgCityPostcodeService cityPostcodeService;

  @Inject
  public BgCityCentroidService(
      NominatimHttpClient httpClient,
      CityRepository cityRepository,
      BgCityPostcodeService cityPostcodeService) {
    this.httpClient = httpClient;
    this.cityRepository = cityRepository;
    this.cityPostcodeService = cityPostcodeService;
  }

  /**
   * Returns the City's trusted centroid, resolving and caching it first if this City has never been
   * resolved before. Empty if it cannot be safely established (e.g. the geocoder has nothing for
   * it, or the result fails validation) -- callers must not fall back to anything else here.
   */
  public Optional<double[]> getOrResolveCentroid(City city, Country country) {
    if (city == null || country == null) {
      return Optional.empty();
    }
    if (city.getLatit() != null && city.getLongit() != null) {
      return Optional.of(
          new double[] {city.getLatit().doubleValue(), city.getLongit().doubleValue()});
    }

    String query = BgGeocodeQueryBuilder.buildCityOnlyQuery(city, country);
    String countryCode =
        country.getAlpha2Code() == null ? "" : country.getAlpha2Code().toLowerCase();
    Optional<String> response = httpClient.search(query, countryCode);
    if (response.isEmpty()) {
      LOG.info("City centroid lookup returned no response for City id={}", city.getId());
      return Optional.empty();
    }

    Optional<NominatimPlace> place = NominatimXmlParser.parseFirstResult(response.get());
    List<String> allowedPostcodes = cityPostcodeService.getValidPostcodes(city);
    if (place.isEmpty()
        || !BgGeocodeResultValidator.isSafeCityCentroid(
            place.get(), countryCode, city, allowedPostcodes)) {
      LOG.info(
          "City centroid lookup rejected (country/name/region/postcode mismatch) for City id={}",
          city.getId());
      return Optional.empty();
    }

    NominatimPlace resolved = place.get();
    Optional<BigDecimal> latitude =
        BgCoordinateNormalizer.normalizeLatitude(resolved.getLatitude());
    Optional<BigDecimal> longitude =
        BgCoordinateNormalizer.normalizeLongitude(resolved.getLongitude());
    if (latitude.isEmpty() || longitude.isEmpty()) {
      LOG.info(
          "City centroid lookup rejected (coordinate out of range/precision) for City id={}",
          city.getId());
      return Optional.empty();
    }

    persist(city, latitude.get(), longitude.get());
    LOG.info("City centroid resolved and cached for City id={}", city.getId());
    return Optional.of(new double[] {latitude.get().doubleValue(), longitude.get().doubleValue()});
  }

  @Transactional
  protected void persist(City city, BigDecimal latitude, BigDecimal longitude) {
    city.setLatit(latitude);
    city.setLongit(longitude);
    cityRepository.save(city);
  }
}
