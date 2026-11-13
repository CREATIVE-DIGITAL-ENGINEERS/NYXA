package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the tiered, safe BG geocoding flow for one Address: reuse a cached safe result;
 * otherwise try the structured full-address query and validate it (EXACT/STREET); if that fails or
 * is rejected, fall back to the selected City's own trusted centroid (CITY); if even that is
 * unavailable, resolve to nothing (NONE) rather than ever showing/caching an unsafe guess.
 *
 * <p>BG-only by design (see {@code BgAddressMapController}, which is the one place that decides
 * whether an Address is routed here or to the stock, unchanged {@code AddressService} flow for
 * every other country).
 */
@Singleton
public class BgAddressGeocodeService {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final NominatimHttpClient httpClient;
  private final BgCityCentroidService centroidService;
  private final BgCityPostcodeService cityPostcodeService;

  @Inject
  public BgAddressGeocodeService(
      NominatimHttpClient httpClient,
      BgCityCentroidService centroidService,
      BgCityPostcodeService cityPostcodeService) {
    this.httpClient = httpClient;
    this.centroidService = centroidService;
    this.cityPostcodeService = cityPostcodeService;
  }

  @Transactional
  public GeocodeResult resolve(Address address, boolean forceRefresh) {
    if (address == null) {
      return GeocodeResult.none(I18n.get("Location could not be resolved safely."));
    }
    if (!forceRefresh && hasSafeCachedResult(address)) {
      return GeocodeResult.of(
          accuracyFromSelection(address.getBgGeocodeAccuracySelect()),
          address.getLatit(),
          address.getLongit());
    }

    City city = address.getCity();
    Country country = address.getCountry();
    if (city == null || country == null) {
      clear(address);
      return GeocodeResult.none(I18n.get("A City is required to resolve a safe map location."));
    }

    GeocodeResult fullAddressResult = tryFullAddress(address, city, country);
    if (fullAddressResult != null && fullAddressResult.isSafe()) {
      persist(address, fullAddressResult, "nominatim");
      LOG.info(
          "BG address geocode resolved: id={} accuracy={}",
          address.getId(),
          fullAddressResult.getAccuracy());
      return fullAddressResult;
    }

    Optional<double[]> centroid = centroidService.getOrResolveCentroid(city, country);
    if (centroid.isPresent()) {
      GeocodeResult cityResult =
          GeocodeResult.of(
              GeocodeAccuracy.CITY,
              java.math.BigDecimal.valueOf(centroid.get()[0]),
              java.math.BigDecimal.valueOf(centroid.get()[1]));
      persist(address, cityResult, "city-centroid");
      LOG.info("BG address geocode fell back to City centroid: id={}", address.getId());
      return cityResult;
    }

    clear(address);
    LOG.info("BG address geocode found no safe location: id={}", address.getId());
    return GeocodeResult.none(I18n.get("Location could not be resolved safely."));
  }

  private GeocodeResult tryFullAddress(Address address, City city, Country country) {
    String query = BgGeocodeQueryBuilder.buildAddressQuery(address);
    if (query == null) {
      return null;
    }
    String countryCode = countryCodeLower(country);
    Optional<String> response = httpClient.search(query, countryCode);
    if (response.isEmpty()) {
      return null;
    }
    Optional<NominatimPlace> place = NominatimXmlParser.parseFirstResult(response.get());
    if (place.isEmpty()) {
      return null;
    }

    double[] trustedCentroid =
        (city.getLatit() != null && city.getLongit() != null)
            ? new double[] {city.getLatit().doubleValue(), city.getLongit().doubleValue()}
            : null;
    List<String> allowedPostcodes = cityPostcodeService.getValidPostcodes(city);

    GeocodeAccuracy accuracy =
        BgGeocodeResultValidator.validate(
            place.get(),
            countryCode,
            city.getName(),
            city.getNameEn(),
            address.getZip(),
            allowedPostcodes,
            trustedCentroid);
    if (accuracy == GeocodeAccuracy.NONE) {
      return null;
    }

    Optional<BigDecimal> latitude =
        BgCoordinateNormalizer.normalizeLatitude(place.get().getLatitude());
    Optional<BigDecimal> longitude =
        BgCoordinateNormalizer.normalizeLongitude(place.get().getLongitude());
    if (latitude.isEmpty() || longitude.isEmpty()) {
      LOG.info(
          "BG address geocode rejected (coordinate out of range/precision): id={}",
          address.getId());
      return null;
    }
    return GeocodeResult.of(accuracy, latitude.get(), longitude.get());
  }

  private boolean hasSafeCachedResult(Address address) {
    return address.getLatit() != null
        && address.getLongit() != null
        && address.getBgGeocodeAccuracySelect() != null
        && !GeocodeAccuracy.NONE.getSelectionValue().equals(address.getBgGeocodeAccuracySelect());
  }

  private void persist(Address address, GeocodeResult result, String provider) {
    address.setLatit(result.getLatitude());
    address.setLongit(result.getLongitude());
    address.setIsValidLatLong(true);
    address.setBgGeocodeAccuracySelect(result.getAccuracy().getSelectionValue());
    address.setBgGeocodeProvider(provider);
    address.setBgGeocodedOn(LocalDateTime.now());
  }

  /** A rejected/failed attempt must never leave a stale coordinate behind pretending to be safe. */
  private void clear(Address address) {
    address.setLatit(null);
    address.setLongit(null);
    address.setIsValidLatLong(false);
    address.setBgGeocodeAccuracySelect(GeocodeAccuracy.NONE.getSelectionValue());
    address.setBgGeocodeProvider(null);
    address.setBgGeocodedOn(LocalDateTime.now());
  }

  private static String countryCodeLower(Country country) {
    return country.getAlpha2Code() == null ? "" : country.getAlpha2Code().toLowerCase();
  }

  private static GeocodeAccuracy accuracyFromSelection(String selectionValue) {
    for (GeocodeAccuracy accuracy : GeocodeAccuracy.values()) {
      if (accuracy.getSelectionValue().equals(selectionValue)) {
        return accuracy;
      }
    }
    return GeocodeAccuracy.NONE;
  }
}
