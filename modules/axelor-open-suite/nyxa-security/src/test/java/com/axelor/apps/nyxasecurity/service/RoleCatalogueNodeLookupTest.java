package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@link RoleCatalogueGeneratorSyncTest} looks for the node Gradle downloads: the Linux/macOS
 * distribution keeps it in {@code bin/node}, the Windows one as {@code node.exe} at the top.
 * Looking only in {@code bin/} missed it on Windows.
 */
class RoleCatalogueNodeLookupTest {

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.createFile(file);
  }

  @Test
  void findsBothLayoutsNewestFirstAndIgnoresEmptyVersions(@TempDir Path repo) throws IOException {
    Path windows =
        touch(repo.resolve("modules/a/front/.gradle/nodejs/node-v20.15.1-win-x64/node.exe"));
    Path linux =
        touch(repo.resolve("modules/b/hr/.gradle/nodejs/node-v16.17.0-linux-x64/bin/node"));
    Files.createDirectories(repo.resolve("modules/b/hr/.gradle/nodejs/node-v18.0.0-linux-x64"));
    Files.createDirectories(repo.resolve("modules/c/plain"));

    assertEquals(List.of(windows, linux), RoleCatalogueGeneratorSyncTest.downloadedNodes(repo));
  }
}
