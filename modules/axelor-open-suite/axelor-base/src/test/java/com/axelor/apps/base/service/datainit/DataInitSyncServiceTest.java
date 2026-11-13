package com.axelor.apps.base.service.datainit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DataInitSyncService#runSync} must pick the importer by the config's own root element:
 * always building a {@code CSVImporter} would make an {@code <xml-inputs>} config (like {@code
 * bg-localization-leave-reason-config.xml}) unusable, and its rows would never sync to an
 * already-installed database. {@link DataInitSyncService#isConfig} is the sniff that picks the
 * importer the same way {@code com.axelor.meta.loader.DataLoader#isConfig} does at fresh-install
 * time -- pure file-read logic, no Guice/DB needed.
 */
class DataInitSyncServiceTest {

  @Test
  @DisplayName("an <xml-inputs>-rooted config is detected as XML, not CSV")
  void detectsXmlInputsConfig(@TempDir Path dir) throws Exception {
    Path config = dir.resolve("bg-localization-leave-reason-config.xml");
    Files.writeString(
        config,
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xml-inputs xmlns=\"http://axelor.com/xml/ns/data-import\">\n"
            + "  <input file=\"leave_reason.xml\" root=\"leave-reasons\"/>\n"
            + "</xml-inputs>\n",
        StandardCharsets.UTF_8);

    assertTrue(DataInitSyncService.isConfig(config.toFile(), DataInitSyncService.XML_ROOT_PATTERN));
  }

  @Test
  @DisplayName("a <csv-inputs>-rooted config (the common case) is NOT detected as XML")
  void doesNotMisdetectCsvInputsConfigAsXml(@TempDir Path dir) throws Exception {
    Path config = dir.resolve("bg-localization-config.xml");
    Files.writeString(
        config,
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<csv-inputs xmlns=\"http://axelor.com/xml/ns/data-import\">\n"
            + "  <input file=\"bg_contribution_fund.csv\"/>\n"
            + "</csv-inputs>\n",
        StandardCharsets.UTF_8);

    assertFalse(
        DataInitSyncService.isConfig(config.toFile(), DataInitSyncService.XML_ROOT_PATTERN));
  }
}
