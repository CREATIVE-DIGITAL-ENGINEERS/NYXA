package com.axelor.apps.nyxasecurity.service;

import com.axelor.app.AppSettings;
import com.axelor.app.AvailableAppSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the encryption keys at startup.
 *
 * <ul>
 *   <li><b>Empty {@code encryption.password} stops the app.</b> Axelor treats it as "no
 *       encryption": encrypted="true" fields (ЕГН in the QES profile, certificate and connector
 *       passwords) are then saved in plain text, and values encrypted earlier come back as
 *       ciphertext.
 *   <li><b>A public value is logged as an error</b>, not refused, so local runs keep working: the
 *       Axelor sample keys, the local-only values committed in axelor-config.properties and the
 *       {@code changeme} placeholders of the sample environment files. Anyone with the source can
 *       decrypt data protected by them.
 * </ul>
 *
 * An empty {@code encryption.bankorder.password} is left alone: AOS already refuses to encrypt a
 * bank order file without it, with its own message.
 */
public final class EncryptionKeyCheck {

  private static final Logger LOG = LoggerFactory.getLogger(EncryptionKeyCheck.class);

  static final String BANK_ORDER_KEY = "encryption.bankorder.password";

  static final Set<String> PUBLIC_VALUES =
      Set.of(
          "MySuperSecretKey",
          "BankOrderEncryptionKey",
          "local-dev-only-not-a-secret",
          "local-dev-only-not-a-secret-bankorder");

  private EncryptionKeyCheck() {}

  public static void checkOnStartup() {
    AppSettings settings = AppSettings.get();
    for (String problem :
        check(
            settings.get(AvailableAppSettings.ENCRYPTION_PASSWORD), settings.get(BANK_ORDER_KEY))) {
      LOG.error(problem);
    }
  }

  /**
   * Returns the problems to log; throws when the field key is empty.
   *
   * @throws IllegalStateException when {@code encryption.password} is missing or blank
   */
  static List<String> check(String fieldKey, String bankOrderKey) {
    if (fieldKey == null || fieldKey.isBlank()) {
      throw new IllegalStateException(
          "encryption.password is empty, which switches field encryption off. Set "
              + "AXELOR_CONFIG_ENCRYPTION_PASSWORD (or encryption.password) and restart.");
    }
    List<String> problems = new ArrayList<>();
    if (isPublic(fieldKey)) {
      problems.add(
          "encryption.password is a public value; encrypted fields are readable by anyone with "
              + "the source. Set AXELOR_CONFIG_ENCRYPTION_PASSWORD to a secret of your own.");
    }
    if (bankOrderKey != null && !bankOrderKey.isBlank() && isPublic(bankOrderKey)) {
      problems.add(
          "encryption.bankorder.password is a public value. Set "
              + "AXELOR_CONFIG_ENCRYPTION_BANKORDER_PASSWORD to a secret of your own.");
    }
    return problems;
  }

  static boolean isPublic(String key) {
    String value = key.trim();
    return PUBLIC_VALUES.contains(value) || value.toLowerCase(Locale.ROOT).startsWith("changeme");
  }
}
