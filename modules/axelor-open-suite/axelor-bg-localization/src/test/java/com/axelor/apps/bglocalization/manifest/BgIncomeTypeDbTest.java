package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НОМЕНКЛАТУРАТА НА ВИДА ДОХОД — базовата половина: „ДОСТАВЕНО ЛИ Е“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgIncomeTypeSeedFileTest} пита дали каталогът е
 * ВЯРНО ЗАПИСАН — той тече винаги и без Postgres. Този пита нещо, което файлът не може да знае:
 * стигнали ли са деветдесетте реда до базата, тоест ще намери ли строителят на справката код 101,
 * когато потрябва. Слети в един тест, отговорът „App Install не е пускан“ би маскирал отговора
 * „каталогът е сгрешен“.
 *
 * <p><b>⚠️ ПРОПУСКА СЕ ПРИ ТРИ СЪСТОЯНИЯ НА СРЕДАТА, ВСЯКО С ОБЯВЕНА ПРИЧИНА</b>: няма достъпна
 * база; таблицата още я няма (модулът не е минавал през {@code ddl=update} на тази база); таблицата
 * я има, но е празна (App Install не е пускан след този комит).
 *
 * <p><b>⚠️ ТРЕТОТО Е НОРМАЛНОТО СЪСТОЯНИЕ НА ТОЗИ КЛОН И НЕ Е ДЕФЕКТ.</b> Клонът НЕ пуска App
 * Install — общата база се чете и от други работни копия, а нов ред в нея мести манифеста им. Тоест
 * докато човек не презареди, тази таблица е празна НАРОЧНО. Червено тук би карало читателя да
 * поправя seed, който е верен.
 *
 * <p><b>⚠️ ИМЕТО НА ТАБЛИЦАТА НЕ СЕ ПИШЕ НА РЪКА</b> — чете се от {@code @Table} на генерирания
 * клас. Правилото на Axelor не е „главна буква → долна черта“: след ЦИФРА черта не се слага.
 * Написано на ръка, името щеше да е догадка, която пада като „липсваща таблица“ — тоест тестът щеше
 * да докладва собствената си грешка като състояние на средата.
 */
class BgIncomeTypeDbTest {

  /**
   * ⚠️ ПРЕЗ {@link BgDomainColumns#tableName}, А НЕ ГОЛОТО {@code @Table.name()}. Анотацията носи
   * името С ГЛАВНИ БУКВИ ({@code BGLOCALIZATION_BG_INCOME_TYPE}), а {@code
   * information_schema.table_name} в Postgres е с малки. Сравнени направо, двете НЕ съвпадат НИКОГА
   * — тоест тестът щеше да се пропуска с „таблицата още я няма“ и СЛЕД като таблицата вече
   * съществува. Тихо пропускане, неразличимо от вярната си причина. Затова и заявките питат {@code
   * lower(table_name)}.
   */
  private static final String TABLE =
      BgDomainColumns.tableName(BgIncomeType.class.getAnnotation(Table.class).name())
          .toLowerCase(java.util.Locale.ROOT);

  /** 79 кода по чл. 73 ал. 1 плюс 11 по ал. 6 — преброени в образците за 2025 г. */
  private static final int EXPECTED_ROWS = 90;

  @Test
  @DisplayName("БАЗА · деветдесетте кода са в базата, всеки с член и шаблон")
  void theCatalogueReachedTheDatabase() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c)) {
        return;
      }
      assertEquals(
          EXPECTED_ROWS,
          count(c, "SELECT count(*) FROM " + TABLE),
          "редове в каталога — образците за 2025 г. носят " + EXPECTED_ROWS);
      assertEquals(
          0,
          count(c, "SELECT count(*) FROM " + TABLE + " WHERE article IS NULL OR article = ''"),
          "код без разпоредба — членът идва от описанието в образеца и не бива да е празен");
      assertEquals(
          0,
          count(c, "SELECT count(*) FROM " + TABLE + " WHERE tax_pattern IS NULL"),
          "код без шаблон на облагане");
    }
  }

  /**
   * ⚠️ СВИДЕТЕЛИТЕ СЕ ЧЕТАТ ПООТДЕЛНО, А НЕ КАТО СБОР. Сборна проверка („средните разходи са Х“)
   * минава зелена и при две разменени стойности; тези четири реда падат точно там, където е
   * грешката. Избрани са двойките, които е най-лесно да се разменят: 3061 и 306 са съседни кодове с
   * РАЗЛИЧНИ разходи, а 814 и 8171 са двата, чиито ставки не са десетте на сто.
   */
  @Test
  @DisplayName("БАЗА · свидетелите носят своите ставки — 306/3061, 814 и 8171")
  void theWitnessesCarryTheirOwnRates() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c)) {
        return;
      }
      assertRates(c, "306", "25.00", "0.00", "10.00");
      assertRates(c, "3061", "40.00", "0.00", "10.00");
      assertRates(c, "401", "10.00", "0.00", "10.00");
      assertRates(c, "814", "0.00", "5.00", "0.00");
      // ⚠️ 7 на сто по чл. 46 ал. 2 — единственият код с тази ставка в целия каталог.
      assertRates(c, "8171", "0.00", "7.00", "0.00");
      assertRates(c, "103", "0.00", "0.00", "1.00");
    }
  }

  /**
   * ⚠️ ЗАЯВКАТА ПИТА {@code WHERE is_aggregate}, А НЕ {@code = true}. Булевата колона на нова
   * таблица се ражда с {@code false} за неотбелязаните редове, но същият идиом на този модул пази и
   * от другото състояние — {@code NULL} върху заварена колона, където {@code = true} и {@code =
   * false} пропускат ЕДНИ И СЪЩИ редове.
   */
  @Test
  @DisplayName("БАЗА · сборните редове са точно три — 5031, 8141 и 820")
  void exactlyThreeAggregateCodesAreStored() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c)) {
        return;
      }
      assertEquals(
          3,
          count(c, "SELECT count(*) FROM " + TABLE + " WHERE is_aggregate"),
          "сборните кодове по указание т. 5 са 5031, 8141 и 820");
      assertEquals(
          3,
          count(
              c,
              "SELECT count(*) FROM "
                  + TABLE
                  + " WHERE is_aggregate AND code IN ('5031','8141','820')"),
          "сборен е обявен код, който не е сред трите на указанието");
    }
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  private static void assertRates(
      Connection c, String code, String expense, String finalTax, String advance) throws Exception {
    String sql =
        "SELECT expense_rate, final_tax_rate, advance_tax_rate FROM "
            + TABLE
            + " WHERE code = ? ORDER BY valid_from DESC LIMIT 1";
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setString(1, code);
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          BgSkip.because("код " + code + " още го няма в базата — App Install не е пускан.");
          return;
        }
        assertEquals(
            0,
            new java.math.BigDecimal(expense).compareTo(rs.getBigDecimal(1)),
            "код " + code + ": нормативни разходи");
        assertEquals(
            0,
            new java.math.BigDecimal(finalTax).compareTo(rs.getBigDecimal(2)),
            "код " + code + ": окончателен данък");
        assertEquals(
            0,
            new java.math.BigDecimal(advance).compareTo(rs.getBigDecimal(3)),
            "код " + code + ": авансов данък");
      }
    }
  }

  /** Има ли изобщо какво да се проверява — таблица и редове. */
  private static boolean ready(Connection c) throws Exception {
    if (!tableExists(c, TABLE)) {
      BgSkip.because(
          "таблицата "
              + TABLE
              + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
              + " средата, не на кода. Файловата половина (BgIncomeTypeSeedFileTest) тече и без"
              + " база.");
      return false;
    }
    if (count(c, "SELECT count(*) FROM " + TABLE) == 0) {
      BgSkip.because(
          "таблицата "
              + TABLE
              + " е празна — App Install не е пускан след този комит. ⚠️ ТОВА Е НОРМАЛНОТО"
              + " състояние на клона: той нарочно не пуска App Install, защото общата база се чете"
              + " и от други работни копия.");
      return false;
    }
    return true;
  }

  private static boolean tableExists(Connection c, String table) throws Exception {
    try (PreparedStatement st =
        c.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = ?")) {
      st.setString(1, table);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  private static int count(Connection c, String sql) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  private static Connection open() {
    String url = BgTestDb.url();
    String user = BgTestDb.user();
    String pass = BgTestDb.password();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, user, pass);
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — доставката на каталога НЕ е проверена. Файловата половина"
              + " (BgIncomeTypeSeedFileTest) тече и без база.");
      return null;
    }
  }
}
