package com.axelor.apps.aiocr.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Gives each test JVM its own physical Axelor upload root so DB-backed tests never share the
 * on-disk attachment/quarantine tree.
 *
 * <p>Why a system property and not {@code axelor-config.properties}: Axelor only expands a fixed
 * set of tokens in config values ({@code {java.io.tmpdir}}, {@code {user.home}}, …) — not an
 * arbitrary per-worker id — so the static file can only name one shared directory. A system
 * property prefixed {@code axelor.config.} is read by {@code SystemSettingSource}, the last
 * settings source applied, so it overrides whatever the file set. Setting it here, before {@code
 * GuiceExtension} builds the injector (this extension is registered first on {@link AiOcrTest}, and
 * JUnit runs {@code BeforeAllCallback}s in registration order), means {@code AppSettings} — a
 * lazily-built, per-JVM singleton — reads the isolated path.
 *
 * <p>The directory is suffixed with Gradle's {@code org.gradle.test.worker} id when present: with
 * {@code maxParallelForks > 1} each concurrent worker holds a distinct id, so two workers can never
 * resolve the same upload root. Outside Gradle (IDE runs) a random suffix is used. The quarantine
 * directory resolves under this root via {@code MetaFiles.getPath}, so the same-filesystem atomic
 * move that rollback/quarantine relies on is preserved.
 */
public class UploadDirIsolationExtension implements BeforeAllCallback, AfterAllCallback {

  static final String UPLOAD_DIR_PROPERTY = "axelor.config.data.upload.dir";

  private Path uploadRoot;

  @Override
  public void beforeAll(ExtensionContext context) {
    String worker = System.getProperty("org.gradle.test.worker");
    String suffix =
        (worker != null && !worker.trim().isEmpty())
            ? "w" + worker.trim()
            : UUID.randomUUID().toString();
    uploadRoot = Path.of(System.getProperty("java.io.tmpdir"), "aiocr-it-attachments-" + suffix);
    System.setProperty(UPLOAD_DIR_PROPERTY, uploadRoot.toString());
  }

  @Override
  public void afterAll(ExtensionContext context) throws IOException {
    // Runs after GuiceExtension.afterAll (extension teardown is reverse of setup), so the EMF and
    // any file handles are already closed. Best-effort: a leftover temp tree is harmless.
    if (uploadRoot != null && Files.exists(uploadRoot)) {
      try (var paths = Files.walk(uploadRoot)) {
        paths
            .sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                    // leave it for the OS temp sweep
                  }
                });
      }
    }
    System.clearProperty(UPLOAD_DIR_PROPERTY);
  }
}
