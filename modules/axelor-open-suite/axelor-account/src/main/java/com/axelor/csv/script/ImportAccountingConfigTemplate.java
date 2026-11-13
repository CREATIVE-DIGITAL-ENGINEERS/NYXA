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
package com.axelor.csv.script;

import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.common.FileUtils;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.MetaScanner;
import com.axelor.meta.db.MetaFile;
import com.google.common.io.Files;
import com.google.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImportAccountingConfigTemplate {

  private final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Inject private MetaFiles metaFiles;

  /**
   * Collects every CSV the chart ships, by scanning the classpath directory it lives in.
   *
   * <p>This used to copy a hardcoded list of file names, which silently drifted from the import
   * configuration that consumes them: {@code l10n/chart-config.xml} declares an {@code <input
   * file="..."/>} per CSV, and only the files named in the array ever reached the ZIP. A file
   * declared there but absent from the array is never packaged, and nothing complains — {@code
   * CSVImporter.run()} logs "Error while accessing ..." for the missing input and carries on. That
   * is how {@code account_paymentCondition.csv}, {@code account_paymentConditionLine.csv} and
   * {@code base_sequenceVersion.csv} were declared, shipped under {@code l10n_bgr/BG_COA} and never
   * imported.
   *
   * <p>Scanning removes the second list: whatever a chart directory ships is what gets packaged, so
   * adding a CSV to a localisation needs no change here. The importer still only reads what the
   * config declares, so extra files in the ZIP are harmless.
   */
  protected File getDataFile(AccountingConfigTemplate accountingConfigTemplate) throws IOException {

    File tempDir = new File(Files.createTempDir(), accountingConfigTemplate.getCode());
    if (!tempDir.exists()) tempDir.mkdir();

    String chartPath =
        "l10n/l10n_"
            + accountingConfigTemplate.getCountryCode()
            + "/"
            + accountingConfigTemplate.getCode();

    // Resource names always use "/" (Guava's ClassPath.getResourceName), on every platform and
    // whether the module is an exploded directory or a jar inside the WAR. Matching is partial
    // (Matcher#find), so this anchors on the chart directory and takes only the CSVs directly in
    // it - a plain getResourceAsStream cannot enumerate a directory inside a jar.
    List<URL> resources = MetaScanner.findAll(chartPath + "/[^/]+\\.csv$");

    if (resources.isEmpty()) {
      LOG.warn(
          "No CSV found under {} for accountingConfigTemplate {} - the chart will import nothing.",
          chartPath,
          accountingConfigTemplate.getCode());
    }

    for (URL resource : resources) {
      String fileName = resource.getPath().substring(resource.getPath().lastIndexOf('/') + 1);
      File resourceFile = new File(tempDir, fileName);

      try (InputStream inputStream = resource.openStream();
          FileOutputStream outputStream = new FileOutputStream(resourceFile)) {
        int read = 0;
        byte[] bytes = new byte[1024];
        while ((read = inputStream.read(bytes)) != -1) {
          outputStream.write(bytes, 0, read);
        }
      }
    }
    return tempDir;
  }

  protected File getZipFile(AccountingConfigTemplate accountingConfigTemplate) throws IOException {

    File directory = this.getDataFile(accountingConfigTemplate);
    String fileName = accountingConfigTemplate.getCode() + ".zip";

    File zipFile = new File(directory.getParent(), fileName);

    this.zipIt(directory, zipFile);

    return zipFile;
  }

  protected void zipIt(File directory, File zipFile) throws IOException {

    byte[] buffer = new byte[1024];

    try (FileOutputStream fos = new FileOutputStream(zipFile);
        ZipOutputStream zos = new ZipOutputStream(fos)) {

      for (File file : directory.listFiles()) {

        ZipEntry ze = new ZipEntry(file.getName());
        zos.putNextEntry(ze);

        try (FileInputStream in = new FileInputStream(file)) {

          int len;
          while ((len = in.read(buffer)) > 0) {
            zos.write(buffer, 0, len);
          }
          in.close();
        }
        zos.closeEntry();
      }
      zos.close();
      fos.close();
    }
  }

  public Object importAccountingConfigTemplate(Object bean, Map<String, Object> values)
      throws IOException {

    assert bean instanceof AccountingConfigTemplate;
    AccountingConfigTemplate accountConfigTemplate = (AccountingConfigTemplate) bean;

    File zipFile = this.getZipFile(accountConfigTemplate);

    try {
      final MetaFile metaFile = metaFiles.upload(zipFile);
      accountConfigTemplate.setMetaFile(metaFile);

    } catch (Exception e) {
      e.printStackTrace();
      LOG.warn(
          "Can't load file {} for accountConfigTemplate {}",
          zipFile.getName(),
          accountConfigTemplate.getName());
    }

    FileUtils.deleteDirectory(zipFile.getParentFile());

    return accountConfigTemplate;
  }
}
