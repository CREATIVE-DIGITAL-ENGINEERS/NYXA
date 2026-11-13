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
package com.axelor.apps.base.service.datainit;

import com.axelor.app.AppSettings;
import com.axelor.common.FileUtils;
import com.axelor.data.csv.CSVImporter;
import com.axelor.data.xml.XMLImporter;
import com.axelor.db.JPA;
import com.axelor.meta.MetaScanner;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.inject.Singleton;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-runs a module's {@code apps/init-data} import (the same {@link CSVImporter}/{@link
 * XMLImporter} + {@code search="self.importId = :importId"} config Axelor already uses at module
 * install time) on every application startup, so corrections/additions to the shipped reference
 * data reach an already-installed database without a reinstall.
 *
 * <p>Dispatches to {@link CSVImporter} or {@link XMLImporter} by sniffing the config's root element
 * ({@code <csv-inputs>} vs {@code <xml-inputs>}) -- the exact same detection {@link
 * com.axelor.meta.loader.DataLoader#isConfig} uses at fresh-install time, so a module's XML-rooted
 * config (e.g. one importing a non-CSV-shaped nested structure) re-syncs here exactly the way it
 * installs, instead of silently only ever running once. Before this, {@code runSync} always
 * constructed a {@link CSVImporter} regardless of the config's own root, which does not fail loudly
 * on an XML-rooted config -- CSVImporter's XML parsing is lenient enough to find no {@code <input>}
 * elements at all. {@code bg-localization-leave-reason-config.xml} (an {@code <xml-inputs>} config)
 * was never even passed to a {@link #syncModule} call in the first place -- the primary fix is
 * adding that call; this dispatch is what makes the call actually work once added, instead of
 * handing an XML-rooted config to an importer built to read {@code <csv-inputs>}.
 *
 * <p>Non-destructive by construction: both importers only find-or-update rows matched by the
 * config's {@code search=} predicate — neither ever deletes a row whose key is absent from the
 * input. A row created after install, or one whose {@code importId} was later removed from the
 * input, is left untouched.
 *
 * <p><b>Not called directly by modules.</b> {@code ModuleResourceSyncService} is the single startup
 * coordinator that drives this service; a module adds a line to {@code ModuleResourceSyncRegistry}
 * (or the {@code datainit.sync.<module>.configs} property) and its name to {@code
 * datainit.sync.modules}, rather than adding a listener of its own.
 */
@Singleton
public class DataInitSyncService {

  private static final Logger LOG = LoggerFactory.getLogger(DataInitSyncService.class);
  private static final String ENABLED_MODULES_PROPERTY = "datainit.sync.modules";

  // Same detection as com.axelor.meta.loader.DataLoader#isConfig -- matched against the config
  // file's own root element so this service picks the SAME importer the framework's own
  // fresh-install loader would for that exact config, instead of assuming CSV always. CSV is the
  // default (most configs are csv-inputs); only a confirmed xml-inputs root switches importer.
  static final Pattern XML_ROOT_PATTERN = Pattern.compile("^\\<\\s*xml-inputs");

  /**
   * Syncs one module's data-init CSVs, provided {@code moduleName} is listed in the {@code
   * datainit.sync.modules} property. No-op otherwise (default: disabled everywhere).
   *
   * @param moduleName the Axelor module name (as declared in its {@code META-INF/axelor-module.
   *     properties}, e.g. {@code "axelor-bg-localization"}) — also used to scope the classpath
   *     resource lookup and the advisory lock
   * @param configResourcePath the importer config XML (either a {@code <csv-inputs>} or an {@code
   *     <xml-inputs>} config -- detected automatically, see class javadoc), relative to the
   *     module's resources (e.g. {@code "apps/init-data/bg-localization-config.xml"})
   * @param csvDirResourceName the directory (relative to the module's resources) containing the
   *     input files referenced by the config (e.g. {@code "apps/init-data/en"})
   */
  public void syncModule(String moduleName, String configResourcePath, String csvDirResourceName) {
    if (!AppSettings.get().getList(ENABLED_MODULES_PROPERTY).contains(moduleName)) {
      return;
    }
    try {
      runSync(moduleName, configResourcePath, csvDirResourceName);
    } catch (Exception e) {
      // Never block application startup on a bad/missing CSV or a locked table — log and retry
      // on the next boot.
      LOG.error("Data-init CSV sync failed for module {}.", moduleName, e);
    }
  }

  // Deliberately NOT @Transactional: CSVImporter.run() manages its own transaction, beginning
  // and committing one per file/batch (CSVImporter.java:277). If this method were transactional,
  // Guice-persist would already have an active transaction on JPA.em() by the time run() starts,
  // and CSVImporter's own `getTransaction().begin()` would throw IllegalStateException on the
  // very first input — which then cascades into every subsequent file failing too.
  protected void runSync(String moduleName, String configResourcePath, String csvDirResourceName)
      throws SQLException {
    long lockKey = lockKey(moduleName);
    ConnectionProvider connections = connectionProvider();
    Connection lockConnection = connections.getConnection();
    try {
      runSyncLocked(moduleName, configResourcePath, csvDirResourceName, lockConnection, lockKey);
    } finally {
      connections.closeConnection(lockConnection);
    }
  }

  private void runSyncLocked(
      String moduleName,
      String configResourcePath,
      String csvDirResourceName,
      Connection lockConnection,
      long lockKey)
      throws SQLException {
    execute(lockConnection, "SELECT pg_advisory_lock(?)", lockKey);
    try {
      File tmp = extract(moduleName, configResourcePath, csvDirResourceName);
      if (tmp == null) {
        LOG.warn(
            "Data-init CSV sync skipped for module {}: no resources found under {} / {}.",
            moduleName,
            configResourcePath,
            csvDirResourceName);
        return;
      }

      try {
        File config = new File(tmp, configResourcePath);
        File dataDir = new File(tmp, csvDirResourceName);
        if (!config.isFile() || !dataDir.isDirectory()) {
          LOG.warn(
              "Data-init CSV sync skipped for module {}: extracted config/data directory missing.",
              moduleName);
          return;
        }
        LOG.info(
            "Syncing data-init for module {} (upsert by importId, never deletes).", moduleName);
        if (isConfig(config, XML_ROOT_PATTERN)) {
          new XMLImporter(config.getAbsolutePath(), dataDir.getAbsolutePath()).run();
        } else {
          new CSVImporter(config.getAbsolutePath(), dataDir.getAbsolutePath(), null).run();
        }
      } finally {
        clean(tmp);
      }
    } finally {
      execute(lockConnection, "SELECT pg_advisory_unlock(?)", lockKey);
    }
  }

  // Arbitrary but stable per-module key: distinct modules never block each other, while
  // concurrent app instances syncing the SAME module serialize on it.
  private long lockKey(String moduleName) {
    return 900_000_000_000L + (moduleName + "-datainit-sync").hashCode();
  }

  /**
   * Serializes the sync across app instances via a session-level Postgres advisory lock, so a
   * concurrent boot elsewhere waits instead of racing the same CSVs. Session-level, unlike {@code
   * pg_advisory_xact_lock}, so {@link CSVImporter} can manage its own transactions while the lock
   * is held.
   *
   * <p>A session-level lock belongs to one database connection, so it is taken and released on one
   * dedicated connection held for the whole sync. It used to be taken and released through {@code
   * JPA.em()}, each in its own transaction, which borrows whichever pooled connection is free: when
   * the release landed on another connection, {@code pg_advisory_unlock} returned false and did
   * nothing, the lock stayed with the first connection back in the pool, and the next sync of the
   * same key waited forever, hanging startup.
   */
  private static ConnectionProvider connectionProvider() {
    return JPA.em()
        .getEntityManagerFactory()
        .unwrap(SessionFactoryImplementor.class)
        .getServiceRegistry()
        .getService(ConnectionProvider.class);
  }

  private static void execute(Connection connection, String sql, long lockKey) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setLong(1, lockKey);
      ps.execute();
    }
  }

  // Mirrors com.axelor.meta.loader.DataLoader#isConfig exactly (same pattern, same first-matching-
  // line read) -- a plain root-element sniff, not a full parse, so it works before either importer
  // has touched the file.
  static boolean isConfig(File file, Pattern pattern) {
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (pattern.matcher(line).find()) {
          return true;
        }
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }

  /**
   * Extracts every classpath resource under {@code configResourcePath}'s and {@code
   * csvDirResourceName}'s common parent, scoped to the given module only, into a temp directory —
   * mirrors {@code com.axelor.meta.loader.DataLoader.extract()}, needed because the app is deployed
   * as a WAR with each module's resources packaged inside its own jar under {@code WEB-INF/lib/},
   * where a plain classloader resource URL is not a usable filesystem path.
   */
  private File extract(String moduleName, String configResourcePath, String csvDirResourceName) {
    String rootDir = commonParent(configResourcePath, csvDirResourceName);
    List<URL> files = MetaScanner.findAll(moduleName, rootDir, "(.+?)");
    if (files.isEmpty()) {
      return null;
    }

    File tmp = Files.createTempDir();
    for (URL file : files) {
      String name = file.toString();
      name = name.substring(name.lastIndexOf(rootDir));
      try (InputStream is = file.openStream()) {
        copy(is, tmp, name);
      } catch (IOException e) {
        clean(tmp);
        throw new RuntimeException(e);
      }
    }
    return tmp;
  }

  private static String commonParent(String a, String b) {
    String[] pa = a.split("/");
    String[] pb = b.split("/");
    StringBuilder common = new StringBuilder();
    for (int i = 0; i < pa.length - 1 && i < pb.length - 1; i++) {
      if (!pa[i].equals(pb[i])) {
        break;
      }
      if (common.length() > 0) {
        common.append('/');
      }
      common.append(pa[i]);
    }
    return common.toString();
  }

  private void copy(InputStream in, File toDir, String name) throws IOException {
    File dst = FileUtils.getFile(toDir, name);
    Files.createParentDirs(dst);
    try (OutputStream out = new FileOutputStream(dst)) {
      ByteStreams.copy(in, out);
    }
  }

  private void clean(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          clean(child);
        }
      }
    }
    file.delete();
  }
}
