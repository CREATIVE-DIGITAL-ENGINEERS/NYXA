package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class EncryptionKeyCheckTest {

  @Test
  void anEmptyFieldKeyStopsTheAppBecauseItSwitchesEncryptionOff() {
    assertThrows(IllegalStateException.class, () -> EncryptionKeyCheck.check(null, "x"));
    assertThrows(IllegalStateException.class, () -> EncryptionKeyCheck.check("", "x"));
    assertThrows(IllegalStateException.class, () -> EncryptionKeyCheck.check("   ", "x"));
  }

  @Test
  void ownSecretsPassWithoutComplaint() {
    assertTrue(EncryptionKeyCheck.check("q7Vh1s...random", "k2Xp9...random").isEmpty());
    assertTrue(
        EncryptionKeyCheck.check("q7Vh1s...random", null).isEmpty(),
        "an empty bank order key is AOS's to refuse, only when files are encrypted");
  }

  @Test
  void publicValuesAreReportedForBothKeys() {
    assertEquals(2, EncryptionKeyCheck.check("MySuperSecretKey", "BankOrderEncryptionKey").size());
    assertEquals(
        2,
        EncryptionKeyCheck.check(
                "changeme-field-encryption-key", "changeme-bank-order-encryption-key")
            .size(),
        "the .env.example placeholders");
    List<String> problems = EncryptionKeyCheck.check(" MySuperSecretKey ", "own-secret");
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).startsWith("encryption.password"));
  }

  /** The committed local-only values must be the ones the check knows are public. */
  @Test
  void theCommittedValuesAreRecognisedAsPublic() throws IOException {
    Path root = repositoryRoot();
    assertNotNull(root, "repository root (settings.gradle + modules/) not found");
    Properties config = new Properties();
    try (InputStream in =
        Files.newInputStream(root.resolve("src/main/resources/axelor-config.properties"))) {
      config.load(in);
    }
    assertTrue(EncryptionKeyCheck.isPublic(config.getProperty("encryption.password")));
    assertTrue(EncryptionKeyCheck.isPublic(config.getProperty(EncryptionKeyCheck.BANK_ORDER_KEY)));
  }

  /**
   * The committed fallback is the key every environment without its own keys encrypted its data
   * with. Changing it makes that data unreadable (Axelor has no runtime fallback to an old key) and
   * breaks first logins. Moving to a new key is a migration, not an edit.
   */
  @Test
  void theCommittedFallbackKeysNeverChange() throws IOException {
    Path root = repositoryRoot();
    assertNotNull(root, "repository root (settings.gradle + modules/) not found");
    Properties config = new Properties();
    try (InputStream in =
        Files.newInputStream(root.resolve("src/main/resources/axelor-config.properties"))) {
      config.load(in);
    }
    assertEquals("MySuperSecretKey", config.getProperty("encryption.password"));
    assertEquals("BankOrderEncryptionKey", config.getProperty(EncryptionKeyCheck.BANK_ORDER_KEY));
  }

  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("settings.gradle"))
          && Files.isDirectory(p.resolve("modules"))) {
        return p;
      }
    }
    return null;
  }
}
