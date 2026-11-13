package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.aiocr.db.BgCityPostcode;
import com.axelor.apps.aiocr.db.repo.BgCityPostcodeRepository;
import com.axelor.apps.base.db.City;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports the exact BG postcode validation dataset (resources/postal-codes/bg_valid_postcodes.csv,
 * columns {@code ekatte,postcode}) into {@link BgCityPostcode}, resolving each row's City by its
 * (already-imported) EKATTE code. Purely additive: never deletes or modifies City or Address rows,
 * and a row whose EKATTE does not resolve to a City is skipped and logged rather than failing the
 * whole import — the CSV can be incomplete or ahead of the City dataset without blocking startup.
 *
 * <p>Runs after {@link BgCityImportService} on every boot, under its own Postgres advisory lock.
 * Idempotent/resumable: keyed on the unique {@code (city, postcode)} pair, so only missing rows are
 * inserted and a partial/failed run completes on the next startup.
 */
@Singleton
public class BgCityPostcodeImportService {

  private static final Logger LOG = LoggerFactory.getLogger(BgCityPostcodeImportService.class);
  private static final String CSV_RESOURCE = "/postal-codes/bg_valid_postcodes.csv";
  // Arbitrary constant key for the advisory lock (unique to this importer; distinct from
  // BgCityImportService.LOCK_KEY so the two can run independently).
  private static final long LOCK_KEY = 728_413_002L;

  @Transactional
  public int importAll() {
    JPA.em()
        .unwrap(Session.class)
        .doWork(
            conn -> {
              try (PreparedStatement ps =
                  conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                ps.execute();
              }
            });

    Map<String, Long> cityIdByEkatte = new HashMap<>();
    @SuppressWarnings("unchecked")
    List<Object[]> cities =
        JPA.em()
            .createQuery(
                "SELECT c.ekatte, c.id FROM City c WHERE c.ekatte IS NOT NULL AND c.ekatte <> ''")
            .getResultList();
    for (Object[] r : cities) {
      cityIdByEkatte.put((String) r[0], (Long) r[1]);
    }

    Set<String> existing = new HashSet<>();
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        JPA.em().createQuery("SELECT p.city.id, p.postcode FROM BgCityPostcode p").getResultList();
    for (Object[] r : rows) {
      existing.add(dedupKey((Long) r[0], (String) r[1]));
    }

    int inserted = 0;
    int unresolved = 0;
    BgCityPostcodeRepository repo = Beans.get(BgCityPostcodeRepository.class);
    try (InputStream in = BgCityPostcodeImportService.class.getResourceAsStream(CSV_RESOURCE)) {
      if (in == null) {
        LOG.warn("BG postcode validation dataset not found on classpath: {}", CSV_RESOURCE);
        return 0;
      }
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        br.readLine(); // header (also consumes the UTF-8 BOM)
        String line;
        while ((line = br.readLine()) != null) {
          String[] row = parseRow(line);
          if (row == null) {
            continue;
          }
          String ekatte = row[0];
          String postcode = row[1]; // kept as String: preserves leading zeroes
          Long cityId = cityIdByEkatte.get(ekatte);
          if (cityId == null) {
            unresolved++;
            LOG.warn("BG postcode CSV row skipped: no City with EKATTE '{}'.", ekatte);
            continue;
          }
          if (!existing.add(dedupKey(cityId, postcode))) {
            continue; // already present (prior run or duplicate CSV row)
          }
          BgCityPostcode p = new BgCityPostcode();
          p.setCity(JPA.em().getReference(City.class, cityId));
          p.setPostcode(postcode);
          repo.save(p);
          if (++inserted % 500 == 0) {
            JPA.flush();
          }
        }
      }
      LOG.info(
          "Imported {} new BG city postcode rows ({} unresolved EKATTE skipped).",
          inserted,
          unresolved);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to import BG postcode validation dataset.", e);
    }
    return inserted;
  }

  /**
   * Parses one {@code ekatte,postcode} CSV line, or returns {@code null} for a blank/malformed line
   * (fewer than 2 columns, or either column empty after trimming). Pure/no I/O so it can be
   * unit-tested directly.
   */
  static String[] parseRow(String line) {
    if (line == null || line.trim().isEmpty()) {
      return null;
    }
    String[] c = line.split(",", -1);
    if (c.length < 2) {
      return null;
    }
    String ekatte = c[0].trim();
    String postcode = c[1].trim();
    if (ekatte.isEmpty() || postcode.isEmpty()) {
      return null;
    }
    return new String[] {ekatte, postcode};
  }

  /** Idempotency key for a (city, postcode) pair — same shape regardless of source (DB or CSV). */
  static String dedupKey(Long cityId, String postcode) {
    return cityId + "|" + postcode;
  }
}
