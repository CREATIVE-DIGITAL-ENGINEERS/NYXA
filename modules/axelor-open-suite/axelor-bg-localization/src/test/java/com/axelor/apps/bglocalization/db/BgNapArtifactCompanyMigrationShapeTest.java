package com.axelor.apps.bglocalization.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МИГРАЦИЯТА НА NapArtifact.company ОСТАВА ЕДНОКРАТНА, ИДЕМПОТЕНТНА И ЕДНОЗНАЧНА — свидетел върху
 * текста.
 *
 * <p><b>Какво този тест НЕ е.</b> Той не пуска SQL. Пробегът е направен срещу пробна Postgres база
 * ({@code CREATE DATABASE … TEMPLATE nyxa} + фикстура от 14 файла, после DROP): първо пускане —
 * записани 10 (отчет 2, подаване 4, документ 4), двусмислен 1, празни 3; второ пускане — записани
 * 0, редовете и {@code version} непроменени. Резултатът е в {@code the migration notes}.
 *
 * <p><b>Какво Е.</b> Пазач срещу тихо изгубване на четирите неща, които правят файла безопасен за
 * повторно пускане и верен: пипа само празните, пише само еднозначното, не нулира {@code version} и
 * сам добавя колоната (за да може да тръгне преди старта). Миграцията се пуска НА РЪКА — няма кой
 * да я спре, ако някой махне пазача при редакция.
 */
class BgNapArtifactCompanyMigrationShapeTest {

  private static final String FILE =
      "src/main/resources/db/migration/2026-10-17-nap-artifact-company.sql";

  private static String sql() throws IOException {
    Path path = Paths.get(FILE);
    Assumptions.assumeTrue(Files.isRegularFile(path), FILE + " не е намерен от тази директория");
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Пипа само празните — второто пускане не прави нищо")
  void touchesOnlyBlankRows() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("WHERE a.company IS NULL"), "изборът не е само по празните");
    assertTrue(
        sql.contains("WHERE p.artifact = a.id AND p.companies = 1 AND a.company IS NULL"),
        "UPDATE-ът не е пазен с company IS NULL — второ пускане би презаписало");
  }

  @Test
  @DisplayName("Пише само еднозначното: различни дружества за един файл — остава празен")
  void writesOnlyUnambiguous() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("count(DISTINCT c.company) AS companies"), sql);
    assertTrue(sql.contains("p.companies = 1"), "двусмислен файл би получил произволно дружество");
  }

  @Test
  @DisplayName("version се увеличава, не се нулира")
  void bumpsVersion() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("version = coalesce(a.version, 0) + 1"), sql);
    assertFalse(sql.contains("version = 0"), "нулиран version чупи оптимистичното заключване");
  }

  @Test
  @DisplayName("Сама добавя колоната — пуска се преди старта, без прозорец с празно условие")
  void addsTheColumnItself() throws IOException {
    assertTrue(
        sql().contains("ADD COLUMN IF NOT EXISTS company bigint"),
        "без колоната файлът не може да тръгне преди старта");
  }
}
