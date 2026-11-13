/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.l10n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.csv.script.ImportAccountingConfigTemplate;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Guards that every CSV a chart ships is a CSV some import config actually asks for.
 *
 * <p><b>What went wrong.</b> {@code ImportAccountingConfigTemplate.getDataFile()} used to copy a
 * hardcoded array of file names into the ZIP that becomes the template's data file. The import
 * config ({@code chart-config.xml}) declares the files it wants independently, so the two lists had
 * to be kept in step by hand and silently drifted: three inputs added with the Bulgarian chart
 * ({@code account_paymentCondition.csv}, {@code account_paymentConditionLine.csv}, {@code
 * base_sequenceVersion.csv}) were never added to the array, so they were declared, shipped on disk,
 * and never packaged.
 *
 * <p><b>Why nothing went red.</b> Neither end of that mismatch raises: {@code CSVImporter.run()}
 * catches the {@code FileNotFoundException} for a declared-but-absent input and logs "Error while
 * accessing {}" before moving on, and a shipped-but-undeclared CSV is simply never read. The chart
 * imported, reported success, and quietly lacked its payment conditions.
 *
 * <p>Packaging is now a classpath scan of the chart directory, so the array - and with it the
 * "declared but not packaged" failure - is gone by construction. What a scan cannot notice is the
 * mirror image: a CSV added to a chart directory that no config declares. It gets packaged into the
 * ZIP, is never read, and the data silently does not arrive. That is what this test catches.
 *
 * <p><b>Deliberately not asserted:</b> that every chart ships every declared file. It does not, and
 * should not - {@code chart-config.xml} is a superset covering all localisations (FRA ships no
 * {@code base_productFamily.csv}, GBR no {@code account_serviceType.csv}), and the importer
 * skipping inputs a given chart lacks is the intended mechanism for that.
 *
 * <p>Needs no database and no App Install: both sides are files on the classpath.
 */
public class ChartPackagingCoverageTest {

  /**
   * Per chart directory, the config that is used against it. {@code chart-config.xml} is the full
   * template install; {@code chart-config-account.xml} is the chart-only variant (see {@code
   * AccountingConfigTemplateService.installProcess}). A chart must satisfy both, because the ZIP is
   * built once at data-init, before the user chooses between them.
   */
  private static final String[] CONFIGS = {
    "/l10n/chart-config.xml", "/l10n/chart-config-account.xml"
  };

  private static final String[] CHART_DIRS = {
    "/l10n/l10n_bgr/BG_COA", "/l10n/l10n_fra/FRA_PCG", "/l10n/l10n_gbr/GBR_COA"
  };

  @Test
  @DisplayName("every CSV a chart ships is declared by an import config, so it actually gets read")
  void everyShippedCsvIsDeclaredBySomeConfig() throws Exception {
    Set<String> declared = new LinkedHashSet<>();
    for (String config : CONFIGS) {
      Set<String> ofConfig = declaredFiles(config);
      if (ofConfig.isEmpty()) {
        fail("no <input file=\"...\"/> found in " + config + " - has the config format changed?");
      }
      declared.addAll(ofConfig);
    }

    List<String> orphans = new ArrayList<>();
    for (String chartDir : CHART_DIRS) {
      List<String> shipped = shippedCsvNames(chartDir);
      if (shipped.isEmpty()) {
        fail("no CSV found under " + chartDir + " - is the chart still there?");
      }
      for (String fileName : shipped) {
        if (!declared.contains(fileName)) {
          orphans.add(chartDir + "/" + fileName);
        }
      }
    }

    if (!orphans.isEmpty()) {
      fail(
          "chart ships CSVs that no import config declares:\n  "
              + String.join("\n  ", orphans)
              + "\n  ⚠️ These are packaged into the template ZIP and never read: the importer only"
              + " processes the <input/> entries the config lists, so the rows silently do not"
              + " arrive. Either add an <input/> to l10n/chart-config.xml, or delete the CSV.");
    }
  }

  /** The {@code file=} attribute of every {@code <input/>} in the given config. */
  private static Set<String> declaredFiles(String config) throws Exception {
    Document doc = parse(config);
    Set<String> files = new LinkedHashSet<>();
    NodeList inputs = doc.getElementsByTagName("input");
    for (int i = 0; i < inputs.getLength(); i++) {
      String file = ((Element) inputs.item(i)).getAttribute("file");
      if (file != null && !file.trim().isEmpty()) {
        files.add(file.trim());
      }
    }
    return files;
  }

  @Test
  @DisplayName("the packaging scan actually collects every CSV each chart ships")
  void theScanCollectsEveryShippedCsv() throws Exception {
    for (String chartDir : CHART_DIRS) {
      // "/l10n/l10n_bgr/BG_COA" -> countryCode "bgr", code "BG_COA"
      String[] parts = chartDir.split("/");
      String countryCode = parts[2].substring("l10n_".length());
      String code = parts[3];

      AccountingConfigTemplate template = new AccountingConfigTemplate();
      template.setCode(code);
      template.setCountryCode(countryCode);

      File packaged = new PackagingProbe().pack(template);
      try {
        Set<String> collected = new LinkedHashSet<>();
        File[] entries = packaged.listFiles();
        if (entries != null) {
          for (File entry : entries) {
            collected.add(entry.getName());
          }
        }

        Set<String> expected = new LinkedHashSet<>(shippedCsvNames(chartDir));
        // Guards the whole mechanism, not just the count: a scan that matched nothing, matched the
        // wrong directory, or silently dropped files would land here rather than in production as
        // data that quietly fails to arrive.
        assertEquals(
            expected,
            collected,
            "packaging scan did not collect exactly what " + chartDir + " ships");
      } finally {
        deleteRecursively(packaged.getParentFile());
      }
    }
  }

  /**
   * Reaches {@code getDataFile}, which is {@code protected} and lives in another package. A
   * subclass keeps the production visibility as it is - the method is not part of any public API
   * and should not be widened just to be observed.
   */
  private static class PackagingProbe extends ImportAccountingConfigTemplate {
    File pack(AccountingConfigTemplate template) throws IOException {
      return getDataFile(template);
    }
  }

  private static void deleteRecursively(File file) {
    if (file == null) {
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }

  /**
   * The CSV file names a chart directory ships, read off the classpath.
   *
   * <p>Resources are exploded directories when this module's own tests run, so the directory can be
   * listed directly. If that ever stops being true the test fails loudly rather than silently
   * finding nothing and passing.
   */
  private static List<String> shippedCsvNames(String chartDir) throws Exception {
    URL dir = ChartPackagingCoverageTest.class.getResource(chartDir);
    assertNotNull(dir, "missing chart directory " + chartDir);
    if (!"file".equals(dir.getProtocol())) {
      fail(
          "chart directory "
              + chartDir
              + " is not an exploded directory ("
              + dir.getProtocol()
              + ") - this test can no longer enumerate it by listing.");
    }

    File[] entries = new File(dir.toURI()).listFiles((d, name) -> name.endsWith(".csv"));
    List<String> names = new ArrayList<>();
    if (entries != null) {
      for (File entry : entries) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  private static Document parse(String resource) throws Exception {
    try (InputStream in = ChartPackagingCoverageTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "missing resource " + resource);
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(false);
      return dbf.newDocumentBuilder().parse(in);
    }
  }
}
