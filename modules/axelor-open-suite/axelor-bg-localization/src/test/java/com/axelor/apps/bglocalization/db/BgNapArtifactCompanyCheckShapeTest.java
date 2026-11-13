package com.axelor.apps.bglocalization.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОВЕРКАТА {@code db/checks/2026-10-18-nap-artifact-company.sql} САМО ЧЕТЕ И САМО БРОИ.
 *
 * <p>Пуска се само за четене под потребителя на приложението, а изходът отива в лога на средата.
 * Затова: една транзакция {@code BEGIN READ ONLY … ROLLBACK}, никакъв запис или CREATE (в read-only
 * дори TEMP VIEW пада), и в изхода — само тип и бройки. Без имена на файлове, без съдържание, без
 * ЕГН.
 *
 * <p>⚠️ Не се пропуска при липсващ файл: относителният път е от директорията на модула, откъдето
 * gradle пуска тестовете, и липса тук значи липса на проверката.
 */
class BgNapArtifactCompanyCheckShapeTest {

  private static final Path FILE =
      Paths.get("src/main/resources/db/checks/2026-10-18-nap-artifact-company.sql");

  private static String sql() throws IOException {
    assertTrue(Files.isRegularFile(FILE), FILE + " липсва");
    return new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8);
  }

  /** Текстът без коментарите — проверяват се само изпълнимите редове. */
  private static String code() throws IOException {
    return sql().replaceAll("(?m)--.*$", "").toLowerCase(Locale.ROOT);
  }

  @Test
  @DisplayName("Една транзакция BEGIN READ ONLY … ROLLBACK")
  void readOnlyTransaction() throws IOException {
    String code = code().trim();
    assertTrue(code.startsWith("begin read only;"), "първата команда е BEGIN READ ONLY");
    assertTrue(code.endsWith("rollback;"), "последната команда е ROLLBACK");
    assertEquals(1, count(code, "\\bbegin\\b"), "една транзакция");
  }

  @Test
  @DisplayName("Нищо не пише и нищо не създава")
  void writesNothing() throws IOException {
    String code = code();
    for (String verb :
        new String[] {
          "insert", "update", "delete", "create", "alter", "drop", "truncate", "commit"
        }) {
      assertFalse(Pattern.compile("\\b" + verb + "\\b").matcher(code).find(), verb);
    }
  }

  @Test
  @DisplayName("В изхода — само тип и бройки: без имена, файлове, съдържание, ЕГН")
  void outputsOnlyCounts() throws IOException {
    String code = code();
    for (String column :
        new String[] {
          "a.name",
          "original_file_name",
          "meta_file",
          "file_name",
          "file_path",
          "sha256",
          "egn",
          "registration_code",
          "base_company.name"
        }) {
      assertFalse(code.contains(column), column);
    }
    assertTrue(code.contains("artifact_type_select"), "разбивка по тип");
    assertTrue(code.contains("count("), "бройки");
  }

  private static int count(String text, String regex) {
    Matcher m = Pattern.compile(regex).matcher(text);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }
}
