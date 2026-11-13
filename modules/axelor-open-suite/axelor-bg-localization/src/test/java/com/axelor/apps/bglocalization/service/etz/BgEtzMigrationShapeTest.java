package com.axelor.apps.bglocalization.service.etz;

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
 * МИГРАЦИЯТА ОСТАВА ИДЕМПОТЕНТНА — <b>свидетел върху текста, защото цената на обратното е
 * висока</b>.
 *
 * <p><b>Какво този тест НЕ е.</b> Той не пуска SQL. Истинската проверка е направена срещу жива
 * Postgres база (отделна, за еднократна употреба): първо пускане — 2 реда от 3 договора; второ
 * пускане — пак 2; ограничението приема две основания на една дата и отхвърля същото основание
 * втори път.
 *
 * <p><b>Какво Е.</b> Пазач срещу тихо изгубване на трите неща, които правят файла безопасен за
 * повторно пускане. Миграцията се пуска НА РЪКА (проектът няма Flyway — виж {@code бележките към
 * миграцията}), тоест няма кой да я спре, ако някой махне пазача при редакция; а вторият INSERT без
 * него удвоява историята на всеки договор с анекс.
 */
class BgEtzMigrationShapeTest {

  private static final String FILE =
      "src/main/resources/db/migration/2026-09-12-bg-contract-amendment.sql";

  private static String sql() throws IOException {
    Path path = Paths.get(FILE);
    // ⚠️ Пропуска се, вместо да мине празен: пътят е относителен и при пускане от друга директория
    // файлът просто няма да се намери — мълчаливо зелено би било по-лошо от пропуснат тест.
    Assumptions.assumeTrue(Files.isRegularFile(path), FILE + " не е намерен от тази директория");
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Преносът е пазен с NOT EXISTS — второто пускане не удвоява историята")
  void seedIsGuarded() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("INSERT INTO bg_contract_amendment"), "преносът е изчезнал");
    assertTrue(
        sql.contains("NOT EXISTS")
            && sql.contains("a.employment_contract = c.id")
            && sql.contains("a.change_date = c.amendment_date"),
        "пазачът по (договор, дата) липсва — повторното пускане ще дублира");
  }

  @Test
  @DisplayName("Таблиците се създават с IF NOT EXISTS — безразлично към ddl=update")
  void tablesAreCreatedIdempotently() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS bg_contract_amendment"), sql);
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS bg_contract_transfer"), sql);
  }

  @Test
  @DisplayName("Ограниченията се добавят само ако ги няма — инак вторият пробег пада")
  void constraintsAreAddedOnlyOnce() throws IOException {
    String sql = sql();
    for (String name :
        new String[] {
          "uk_bg_amendment_contract_date_ground",
          "uk_bg_transfer_contract_date_reason",
          "fk_bg_amendment_contract",
          "fk_bg_transfer_contract"
        }) {
      assertTrue(
          sql.contains("conname = '" + name + "'"),
          "ограничението " + name + " се добавя без проверка дали вече съществува");
    }
  }

  @Test
  @DisplayName("Ключът на анекса носи ОСНОВАНИЕТО, а не номера на акта")
  void amendmentKeyCarriesTheGround() throws IOException {
    String sql = sql();
    assertTrue(
        sql.contains("UNIQUE (employment_contract, change_date, bg_change_ground)"),
        "ключът не е (договор, дата, основание) — виж Приложение № 1, ключови полета при тип 2");
    assertTrue(
        !sql.contains("UNIQUE (employment_contract, change_date, change_no)"),
        "change_no е в ключа: то е NULL при трудов договор и NULL-ите в Postgres са различни");
  }

  @Test
  @DisplayName("Посятият ред носи служебния маркер „000“, а не измислено основание")
  void seedUsesAnInvalidPlaceholderOnPurpose() throws IOException {
    String sql = sql();
    assertTrue(sql.contains("'000'"), "преносът слага някакво друго основание");
    // ⚠️ Свидетелят е в двете посоки: маркерът трябва да е НЕВАЛИДЕН код, за да откаже подаването.
    assertTrue(
        !BgEtzGrounds.isContractCode("000", "01") && !BgEtzGrounds.isContractCode("000", "02"),
        "„000“ е станал валиден код по т. 12 — маркерът вече не отказва и преносът мълчи");
  }

  @Test
  @DisplayName("Нула промени по съществуващи колони — само CREATE и INSERT")
  void touchesNoExistingColumn() throws IOException {
    String sql = sql();
    assertTrue(
        !sql.contains("ALTER TABLE employment_contract")
            && !sql.contains("UPDATE employment_contract")
            && !sql.contains("DROP "),
        "миграцията пипа заварена таблица — трябваше да е само адитивна");
  }
}
