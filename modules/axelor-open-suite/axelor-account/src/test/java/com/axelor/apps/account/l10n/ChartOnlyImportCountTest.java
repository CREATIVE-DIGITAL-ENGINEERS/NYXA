/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.module.AccountTest;
import com.axelor.apps.base.db.Company;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the chart-only import path actually persists accounts.
 *
 * <p>Runs the real {@link CSVImporter} over the real l10n CSV packages against the module's
 * in-memory HSQLDB test unit (fresh schema per run — the shared databases are never touched). This
 * is the same code path {@code AccountingConfigTemplateService.installProcess} drives; only the
 * MetaFile unzip step is skipped, the CSVs are read from the classpath package directly.
 *
 * <p>Before the missing m2m binds were mirrored into {@code chart-config-account.xml}, every single
 * account row was rejected here (empty nested Tax → flush violation) while the import still
 * reported success.
 */
public class ChartOnlyImportCountTest extends AccountTest {

  private static final String CHART_ONLY_CONFIG = "/l10n/chart-config-account.xml";
  private static final String FULL_CONFIG = "/l10n/chart-config.xml";

  private static final String BG_COA = "/l10n/l10n_bgr/BG_COA";
  private static final String FRA_PCG = "/l10n/l10n_fra/FRA_PCG";

  /** Collects per-file totals and every per-row failure the importer swallows. */
  private static class Recorder implements Listener {
    final List<String> failures = new ArrayList<>();
    int total;
    int success;

    @Override
    public void imported(Model bean) {}

    @Override
    public void imported(Integer total, Integer success) {
      this.total += total;
      this.success += success;
    }

    @Override
    public void handle(Model bean, Exception e) {
      failures.add(
          (bean == null ? "null" : bean.getClass().getSimpleName())
              + ": "
              + (e == null ? "?" : e.toString()));
    }
  }

  /**
   * A company with an AccountConfig, as production always has: installProcess is invoked from one.
   * Without it {@code ImportAccount} would trace a configuration error for every single row.
   */
  private Company createCompany(String code) {
    Company[] holder = new Company[1];
    inTransaction(
        () -> {
          Company company = new Company();
          company.setName("Test " + code);
          company.setCode(code);
          JPA.em().persist(company);

          AccountConfig config = new AccountConfig();
          config.setCompany(company);
          JPA.em().persist(config);

          // Company.accountConfig is mappedBy="company"; keep the in-memory graph consistent.
          company.setAccountConfig(config);
          holder[0] = company;
        });
    assertNotNull(holder[0].getId(), "company was not persisted");
    return holder[0];
  }

  /** Copies the classpath config to a temp file, exactly like installProcess does. */
  private File configFile(String resource) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      assertNotNull(in, "missing config " + resource);
      Path tmp = Files.createTempFile("input-config", ".xml");
      Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return tmp.toFile();
    }
  }

  /** Resolves the on-disk directory holding an l10n CSV package. */
  private File dataDir(String resourceDir) {
    URL marker = getClass().getResource(resourceDir + "/account_account.csv");
    assertNotNull(marker, "missing chart package " + resourceDir);
    File dir = new File(marker.getFile()).getParentFile();
    assertTrue(dir.isDirectory(), "not a directory: " + dir);
    return dir;
  }

  private Recorder runImport(String config, String chartDir, Company company) throws Exception {
    File cfg = configFile(config);
    try {
      CSVImporter importer = new CSVImporter(cfg.getAbsolutePath(), dataDir(chartDir).getPath());

      Map<String, Object> context = new HashMap<>();
      context.put("_companyId", company.getId());
      context.put("_companyName", company.getName());
      context.put("_companyCode", company.getCode());
      context.put("_defaultBankDetailsId", 0);
      context.put("_dataFileName", chartDir.substring(chartDir.lastIndexOf('/') + 1));
      importer.setContext(context);

      Recorder recorder = new Recorder();
      importer.addListener(recorder);
      importer.run();
      return recorder;
    } finally {
      Files.deleteIfExists(cfg.toPath());
    }
  }

  private long accountCount(Company company) {
    JPA.em().clear();
    return all(Account.class).filter("self.company.id = ?1", company.getId()).count();
  }

  private long expectedRows(String chartDir) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(chartDir + "/account_account.csv")) {
      assertNotNull(in, chartDir);
      // header line excluded
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .lines()
              .filter(l -> !l.isBlank())
              .count()
          - 1;
    }
  }

  @Test
  public void aChartOnlyImportsTheWholeBulgarianChart() throws Exception {
    Company company = createCompany("BGONLY");
    long expected = expectedRows(BG_COA);

    Recorder r = runImport(CHART_ONLY_CONFIG, BG_COA, company);

    assertEquals(
        List.of(), r.failures, "chart-only import rejected rows for BG_COA: " + r.failures);
    assertEquals(
        expected,
        accountCount(company),
        "chart-only BG_COA must persist every account row (expected " + expected + ")");
  }

  @Test
  public void bChartOnlyImportsTheWholeFrenchChart() throws Exception {
    Company company = createCompany("FRONLY");
    long expected = expectedRows(FRA_PCG);

    Recorder r = runImport(CHART_ONLY_CONFIG, FRA_PCG, company);

    assertEquals(
        List.of(), r.failures, "chart-only import rejected rows for FRA_PCG: " + r.failures);
    assertEquals(
        expected,
        accountCount(company),
        "chart-only FRA_PCG must persist every account row (expected " + expected + ")");
  }

  /** The untouched full path must keep importing the same chart. */
  @Test
  public void cFullConfigStillImportsTheWholeBulgarianChart() throws Exception {
    Company company = createCompany("BGFULL");
    long expected = expectedRows(BG_COA);

    Recorder r = runImport(FULL_CONFIG, BG_COA, company);

    List<String> accountFailures =
        r.failures.stream()
            .filter(f -> f.startsWith("Account:"))
            .collect(java.util.stream.Collectors.toList());
    assertEquals(
        List.of(), accountFailures, "full import rejected account rows: " + accountFailures);
    assertEquals(
        expected,
        accountCount(company),
        "full config BG_COA must persist every account row (expected " + expected + ")");
  }
}
