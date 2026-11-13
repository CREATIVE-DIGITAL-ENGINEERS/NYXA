package com.axelor.apps.aiocr.service.ocr;

import com.axelor.app.AppSettings;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports the local Bulgarian settlement dataset (resources/postal-codes/bgplaces.csv, columns
 * name,name_en,region,region_en,municipality,municipality_en,postcode,ekatte) into the stock {@link
 * City} table, so City is the single source of truth: name (BG/EN), region + municipality (BG/EN),
 * zip (= postcode) and the EKATTE code. The CSV is canonical — no normalized columns are stored;
 * OCR lookup normalizes its input in memory.
 *
 * <p>Runs on startup, in ONE transaction under a Postgres advisory lock (multi-instance safe); any
 * failure rolls the whole operation back and it retries on the next startup.
 *
 * <ul>
 *   <li><b>Idempotent / resumable import</b>: keyed per row on the unique {@code EKATTE} code —
 *       only the still-missing settlements are inserted, so a partial/failed run completes next
 *       startup and same-name/same-region settlements (distinct municipality) are all kept. There
 *       is no "already imported" flag that could skip an incomplete dataset.
 *   <li><b>Optional DEV-ONLY reset</b> ({@code aiocr.city.reset.on.import}, default false): when
 *       enabled AND the DB is "dirty" (any BG city carries a {@code bgDatasetVersion} other than
 *       the bundled {@link #DATASET_VERSION} — i.e. the CSV changed and the constant was bumped),
 *       it first DELETES ALL City and Address rows (FK-safe) and reimports cleanly — for disposable
 *       dev/test data only. Once every BG city carries the current version this is false, so it
 *       stops (the flag may stay on harmlessly). In production the flag stays false, so startups
 *       never delete operational data; they only add missing dataset rows.
 * </ul>
 */
@Singleton
public class BgCityImportService {

  private static final Logger LOG = LoggerFactory.getLogger(BgCityImportService.class);
  private static final String CSV_RESOURCE = "/postal-codes/bgplaces.csv";
  private static final String BG_ISO = "BG";
  private static final String RESET_FLAG = "aiocr.city.reset.on.import";
  // Bump this whenever bgplaces.csv changes (new columns OR corrected values). Existing City rows
  // then carry an older version → the dev reset wipes and reloads. Kept in sync with the CSV.
  private static final String DATASET_VERSION = "2026-07-31-en-regions";
  // Arbitrary constant key for the advisory lock (unique to this importer).
  private static final long LOCK_KEY = 728_413_001L;

  @Transactional
  public int importAll() {
    // Serialize across instances: only one holds the lock and works; the other waits, then finds
    // the
    // data already present and inserts nothing. Released automatically at transaction end. Run via
    // raw JDBC because pg_advisory_xact_lock() returns SQL "void" (JDBC type 1111), which
    // Hibernate's
    // result mapping cannot handle.
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

    Country bg = resolveBg();
    if (bg == null) {
      LOG.warn("Country {} is not configured; BG city dataset not imported.", BG_ISO);
      return 0;
    }
    // "Dirty" = any BG city carries a dataset version other than the bundled DATASET_VERSION (or
    // none). That happens after DATASET_VERSION is bumped for a new/corrected CSV, so a clean
    // reload
    // is needed. After a clean import every BG city has the current version → false → no further
    // wipe
    // (the flag can stay on harmlessly).
    boolean datasetDirty =
        Beans.get(CityRepository.class)
                .all()
                .filter(
                    "self.country = :co"
                        + " AND (self.bgDatasetVersion IS NULL OR self.bgDatasetVersion != :ver)")
                .bind("co", bg)
                .bind("ver", DATASET_VERSION)
                .count()
            > 0;

    // DEV-ONLY: wipe the disposable City/Address data before a (re)import. Gated by an explicit
    // flag
    // so this can NEVER delete operational data on a production startup. It runs only while the
    // dataset is dirty (rows carry an older DATASET_VERSION), so it self-corrects then stops.
    if (datasetDirty && AppSettings.get().getBoolean(RESET_FLAG, false)) {
      wipeCitiesAndAddresses();
      JPA.clear(); // discard any entities loaded before the raw delete
      bg = resolveBg(); // re-fetch: the session was cleared
    }

    // Existing EKATTE codes for BG → idempotent/resumable insert of only the missing settlements.
    // EKATTE is the unique settlement identity, so same-name/same-region rows (distinct
    // municipality) are all imported instead of collapsing on a (name, region) key.
    Set<String> existing = new HashSet<>();
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT c.ekatte, c.name, c.region, c.municipality FROM City c"
                    + " WHERE c.country = :co")
            .setParameter("co", bg)
            .getResultList();
    for (Object[] r : rows) {
      existing.add(rowKey((String) r[0], (String) r[1], (String) r[2], (String) r[3]));
    }

    int inserted = 0;
    CityRepository cityRepo = Beans.get(CityRepository.class);
    try (InputStream in = BgCityImportService.class.getResourceAsStream(CSV_RESOURCE)) {
      if (in == null) {
        LOG.warn("BG settlement dataset not found on classpath: {}", CSV_RESOURCE);
        return 0;
      }
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        br.readLine(); // header (also consumes the UTF-8 BOM)
        String line;
        while ((line = br.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          String[] c = line.split(",", -1);
          if (c.length < 8) {
            continue;
          }
          // Columns: name,name_en,region,region_en,municipality,municipality_en,postcode,ekatte
          String name = c[0].trim();
          String region = c[2].trim();
          String municipality = c[4].trim();
          String ekatte = c[7].trim();
          if (name.isEmpty()) {
            continue;
          }
          if (!existing.add(rowKey(ekatte, name, region, municipality))) {
            continue; // already present (prior run or duplicate CSV row)
          }
          City city = new City();
          city.setName(name);
          city.setNameEn(c[1].trim());
          city.setRegion(region);
          city.setRegionEn(c[3].trim());
          city.setMunicipality(municipality);
          city.setMunicipalityEn(c[5].trim());
          city.setZip(c[6].trim());
          city.setEkatte(ekatte); // 5-char string (may be blank for a few settlements)
          city.setBgDatasetVersion(DATASET_VERSION);
          city.setCountry(bg);
          cityRepo.save(city);
          if (++inserted % 500 == 0) {
            JPA.flush();
          }
        }
      }
      LOG.info("Imported {} new Bulgarian settlements into City.", inserted);
    } catch (Exception e) {
      // Roll the whole transaction back so a failed run leaves NO partial state; the next startup
      // re-runs and completes. Rethrow unchecked so the @Transactional interceptor rolls back.
      throw new IllegalStateException("Failed to import BG settlement dataset into City.", e);
    }
    return inserted;
  }

  private Country resolveBg() {
    return Beans.get(CountryRepository.class)
        .all()
        .filter("upper(self.alpha2Code) = :iso")
        .bind("iso", BG_ISO)
        .fetchOne();
  }

  /**
   * DEV-ONLY disposable-data reset: delete the KNOWN test records that reference addresses, then
   * all Address rows, then all City rows — in an explicit, controlled order. It only touches the
   * tables that actually hold disposable dev data here (partner↔address links, the partner main
   * address, and the OCR invoice addresses); it never mutates unknown tables. If any OTHER foreign
   * key blocks the Address/City delete, it STOPS and reports the exact table/constraint (the
   * transaction rolls back) rather than silently nulling or deleting data from another module.
   */
  protected void wipeCitiesAndAddresses() {
    var em = JPA.em();
    // 1) Known disposable records referencing addresses in this dev DB.
    em.createNativeQuery("DELETE FROM base_partner_address").executeUpdate();
    em.createNativeQuery(
            "UPDATE base_partner SET main_address = NULL WHERE main_address IS NOT NULL")
        .executeUpdate();
    em.createNativeQuery(
            "UPDATE account_invoice SET address = NULL, delivery_address = NULL"
                + " WHERE address IS NOT NULL OR delivery_address IS NOT NULL")
        .executeUpdate();
    // 2) Addresses, then 3) Cities. Any remaining FK is NOT handled automatically — stop + report.
    deleteAllOrReport("base_address");
    deleteAllOrReport("base_city");
    LOG.warn(
        "DEV reset: deleted all City and Address records (and the known referencing dev rows)"
            + " before the BG dataset import.");
  }

  private void deleteAllOrReport(String table) {
    try {
      JPA.em().createNativeQuery("DELETE FROM " + table).executeUpdate();
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "DEV city/address reset stopped: deleting "
              + table
              + " is blocked by a foreign key that the controlled reset does not handle — "
              + rootMessage(e)
              + ". Clear that record manually (or extend the reset explicitly) and retry; nothing"
              + " was deleted or nulled automatically.",
          e);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    return c.getMessage();
  }

  /**
   * Idempotency key: the EKATTE code (unique settlement identity) when present, otherwise a
   * defensive fallback of name|region|municipality (no row in the shipped dataset lacks EKATTE).
   */
  private static String rowKey(String ekatte, String name, String region, String municipality) {
    if (ekatte != null && !ekatte.trim().isEmpty()) {
      return "e:" + ekatte.trim().toLowerCase();
    }
    return "nrm:" + norm(name) + "|" + norm(region) + "|" + norm(municipality);
  }

  private static String norm(String s) {
    return s == null ? "" : s.trim().toLowerCase();
  }
}
