package com.axelor.apps.bglocalization.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.UnitConversionRepository;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationTest;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DB-backed (in-memory HSQLDB) proof for the fix wired into {@link
 * BgLocalizationServerStartListener}: the stock {@code data-init/input-config.xml} pipeline
 * (Bulgarian units + unit conversions) is the same {@link CSVImporter}, {@code
 * search="self.importId = :importId"} config Axelor already uses at install time, so re-running it
 * on an already-populated database is exactly as safe as {@code DataInitSyncService} assumes:
 * upsert-by-importId, no deletes, no duplicates.
 */
class BgUnitDataInitPersistenceTest extends BgLocalizationTest {

  private static final String CONFIG_RESOURCE = "/data-init/input-config.xml";
  private static final String CSV_DIR_RESOURCE = "/data-init/input/base_unit.csv";

  /**
   * The in-memory HSQLDB instance is shared across every test method in this class (one Guice
   * injector per class, not per method), so a Unit/UnitConversion left behind by one test would
   * otherwise leak into the next and skew its "expected CSV row count" assertions. Each test must
   * start from zero pre-existing rows to make those counts meaningful.
   */
  @BeforeEach
  void cleanUnitsAndConversions() {
    JPA.runInTransaction(
        () -> {
          Beans.get(UnitConversionRepository.class).all().remove();
          Beans.get(UnitRepository.class).all().remove();
        });
  }

  /** Copies the classpath config to a temp file, same as CSVImporter needs an on-disk path. */
  private File configFile() throws Exception {
    try (InputStream in = getClass().getResourceAsStream(CONFIG_RESOURCE)) {
      assertNotNull(in, "missing " + CONFIG_RESOURCE);
      Path tmp = Files.createTempFile("bg-unit-input-config", ".xml");
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
      return tmp.toFile();
    }
  }

  private File dataDir() {
    URL marker = getClass().getResource(CSV_DIR_RESOURCE);
    assertNotNull(marker, "missing " + CSV_DIR_RESOURCE);
    return new File(marker.getFile()).getParentFile();
  }

  private long expectedUnitRows() throws Exception {
    try (InputStream in = getClass().getResourceAsStream(CSV_DIR_RESOURCE)) {
      assertNotNull(in);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(l -> !l.isBlank())
              .count()
          - 1; // header
    }
  }

  private void runImport() throws Exception {
    File cfg = configFile();
    try {
      new CSVImporter(cfg.getAbsolutePath(), dataDir().getAbsolutePath(), null).run();
    } finally {
      Files.deleteIfExists(cfg.toPath());
    }
  }

  @Test
  void firstImportCreatesAllUnitsAndConversions_andRerunIsIdempotent() throws Exception {
    long expectedUnits = expectedUnitRows();

    // First import — the fresh-install path, proven here to actually persist every CSV row.
    runImport();
    JPA.em().clear();
    assertEquals(expectedUnits, Beans.get(UnitRepository.class).all().count());
    long conversionsAfterFirst = Beans.get(UnitConversionRepository.class).all().count();
    assertTrue(conversionsAfterFirst > 0, "expected at least one unit conversion to be imported");

    Unit единица = Beans.get(UnitRepository.class).all().filter("self.importId = '1'").fetchOne();
    assertNotNull(единица, "expected the base unit (importId=1) to be imported");
    assertEquals("Единица", единица.getName());

    // ⚠️ КОДЪТ ПО НАП СЕ СВЕРЯВА ТУК, А НЕ САМО ВЪВ ФАЙЛА. bgUomCode идва от разширение на чуждото
    // entity (bg-localization/domains/Unit.xml) и се пълни по AUTOBIND — вносителят няма изричен
    // <bind> за него. Autobind мълчи, когато колоната не се върже: редът влиза, единицата
    // съществува, броячите излизат и BgUnitSeedTest (който чете ФАЙЛА) също е зелен. Единственото
    // място, където „колоната наистина стига до базата“ има witness, е точно тук.
    assertEquals("XUN", единица.getBgUomCode(), "bgUomCode не се върза при вноса (autobind)");

    Unit килограм =
        Beans.get(UnitRepository.class).all().filter("self.importId = '306'").fetchOne();
    assertNotNull(килограм, "expected the kilogram (importId=306) to be imported");
    assertEquals("KGM", килограм.getBgUomCode());

    long безКод =
        Beans.get(UnitRepository.class)
            .all()
            .filter("self.bgUomCode is null or self.bgUomCode = ''")
            .count();
    assertEquals(0, безКод, "всяка внесена единица трябва да носи код по НАП");

    // Second import — same CSVs, already-installed database. Must upsert, not duplicate.
    runImport();
    JPA.em().clear();
    assertEquals(
        expectedUnits,
        Beans.get(UnitRepository.class).all().count(),
        "rerunning the import must not create duplicate units");
    assertEquals(
        conversionsAfterFirst,
        Beans.get(UnitConversionRepository.class).all().count(),
        "rerunning the import must not create duplicate unit conversions");
  }

  @Test
  void customizedExistingUnitSurvivesImport() throws Exception {
    Long customUnitId =
        JPA.withTransaction(
            () -> {
              Unit custom = new Unit();
              custom.setName("Custom Pallet");
              custom.setLabelToPrinting("pallet");
              custom.setImportId("custom-pallet-not-in-csv");
              Beans.get(UnitRepository.class).save(custom);
              return custom.getId();
            });

    runImport();
    JPA.em().clear();

    Unit reloaded = Beans.get(UnitRepository.class).find(customUnitId);
    assertNotNull(reloaded, "a hand-added unit not present in the CSV must survive the import");
    assertEquals("Custom Pallet", reloaded.getName());
    assertEquals("pallet", reloaded.getLabelToPrinting());

    long expectedUnits = expectedUnitRows();
    assertEquals(
        expectedUnits + 1,
        Beans.get(UnitRepository.class).all().count(),
        "import must add the CSV units alongside the pre-existing customized one");
  }
}
