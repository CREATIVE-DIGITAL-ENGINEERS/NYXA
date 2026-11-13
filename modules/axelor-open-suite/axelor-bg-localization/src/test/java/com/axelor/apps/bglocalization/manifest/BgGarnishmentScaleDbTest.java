package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.service.BgGarnishmentOutcome;
import com.axelor.apps.bglocalization.service.BgGarnishmentResolver;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НЕСЕКВЕСТИРУЕМИЯТ ДОХОД — базовата половина: „ДОСТАВЕНА ли е скалата и работи ли върху нея“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgGarnishmentScaleSeedFileTest} пита дали
 * ДАННИТЕ са верни, {@code BgGarnishmentResolverTest} — дали АЛГОРИТЪМЪТ ги прилага вярно; и двата
 * текат без Postgres. Този пита третото: стигнали ли са редовете до базата, тоест ще се ограничи ли
 * запор на ЖИВА среда. Слети, отговорът „App Install не е пускан“ би маскирал отговора „скалата е
 * сгрешена“.
 *
 * <p><b>⚠️ АЛГОРИТЪМЪТ НЕ СЕ ПРЕПИСВА.</b> {@code BgGarnishmentResolver#assess} се вика ИСТИНСКИ —
 * тук се четат само редовете и МРЗ, през JDBC, защото тези тестове нямат контейнер.
 *
 * <p><b>Пропуска се при четири състояния на средата</b>, всяко с обявена причина: няма достъпна
 * база; таблицата {@code bglocalization_bg_garnishment_scale} още я няма ({@code ddl=update} не е
 * минавал с новия домейн); таблицата я има, но е празна (App Install не е пускан след този комит);
 * липсва МРЗ за периода.
 */
class BgGarnishmentScaleDbTest {

  private static final String SCALE = "bglocalization_bg_garnishment_scale";
  private static final String PARAMETERS = "bglocalization_bg_payroll_parameter";

  /** Март 2026 г. — периодът, за който скалата и МРЗ са в сила. */
  static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

  /**
   * ⚠️ НУЛА ЗА ПРОСРОЧЕНАТА ИЗДРЪЖКА, НАЗОВАНА. Твърденията тук са за фиш БЕЗ перо 831 — целият
   * вход за издръжка е ТЕКУЩ. Разграничението има свой базов свидетел: {@code
   * BgMaintenancePastDueDbTest}.
   */
  private static final BigDecimal NO_PAST_DUE = BigDecimal.ZERO;

  static Connection open() {
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
              + " — твърдението „скалата по чл. 446 е доставена и ограничава запор на жива среда“ НЕ"
              + " е проверено. Файловите половини (BgGarnishmentScaleSeedFileTest и"
              + " BgGarnishmentResolverTest) текат и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  static void requireDeliveredScale(Connection c) throws Exception {
    String table =
        one(
            c,
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = '"
                + SCALE
                + "'");
    if ("0".equals(table)) {
      BgSkip.because(
          "таблицата "
              + SCALE
              + " още я НЯМА — ddl=update не е минавал с новия домейн BgGarnishmentScale. Нова"
              + " таблица Hibernate създава цяла при първото вдигане на приложението с този код;"
              + " миграционен файл за нея НЯМА нарочно. Състояние на средата, не на кода.");
    }
    String rows = one(c, "SELECT count(*) FROM " + SCALE);
    if ("0".equals(rows)) {
      BgSkip.because(
          "таблицата "
              + SCALE
              + " я има, но е ПРАЗНА — App Install не е пускан след този комит. Дотогава всеки фиш"
              + " със запор ще се ОТКАЗВА с назована причина, а не ще удържа без граница — тоест"
              + " средата е безопасна, просто твърдението тук не е проверено. Пусни App Install.");
    }
    String wage =
        one(
            c,
            "SELECT count(*) FROM "
                + PARAMETERS
                + " WHERE code = 'MIN_WAGE' AND valid_from <= DATE '2026-03-01'"
                + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')");
    if ("0".equals(wage)) {
      BgSkip.because(
          "параметърът MIN_WAGE няма ред, валиден за 01.03.2026 г. — цялата скала е в КРАТНИ на"
              + " МРЗ и без нея нито един праг не може да се намери. Пусни App Install.");
    }
  }

  /** Редовете, В СИЛА за датата — същият темпорален филтър като в BgPaySlipService. */
  static List<BgGarnishmentScale> scaleFromDb(Connection c, LocalDate date) throws Exception {
    String sql =
        "SELECT bg_threshold, coalesce(bg_with_children, false), bg_from_multiple, bg_to_multiple,"
            + " bg_numerator, bg_denominator, bg_keep_multiple FROM "
            + SCALE
            + " WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)"
            + " ORDER BY bg_threshold";
    List<BgGarnishmentScale> out = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setObject(1, date);
      st.setObject(2, date);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          BgGarnishmentScale band = new BgGarnishmentScale();
          band.setBgThreshold(rs.getInt(1));
          // ⚠️ getBoolean, не getString: JDBC дава на boolean колона „t“/„f“, не „true“/„false“.
          band.setBgWithChildren(rs.getBoolean(2));
          band.setBgFromMultiple(rs.getBigDecimal(3));
          band.setBgToMultiple(rs.getBigDecimal(4));
          band.setBgNumerator((Integer) rs.getObject(5));
          band.setBgDenominator((Integer) rs.getObject(6));
          band.setBgKeepMultiple(rs.getBigDecimal(7));
          out.add(band);
        }
      }
    }
    return out;
  }

  static BigDecimal minWageFromDb(Connection c, LocalDate date) throws Exception {
    String sql =
        "SELECT param_value FROM "
            + PARAMETERS
            + " WHERE code = 'MIN_WAGE' AND valid_from <= ?"
            + " AND (valid_to IS NULL OR valid_to >= ?) LIMIT 1";
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setObject(1, date);
      st.setObject(2, date);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : null;
      }
    }
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  /**
   * ЗЛАТНИЯТ СЛУЧАЙ ВЪРХУ ДОСТАВЕНИТЕ РЕДОВЕ: нето 1431,00, издръжка 150,00, запор 900,00.
   *
   * <p>Издръжката излиза първа и цяла → основа 1281,00. Тя е между 2× и 4× МРЗ (1240,40 и 2480,80),
   * тоест праг 2 без деца → 1/2 от 1281,00 = 640,50, а подът по ал. 3 разрешава 660,80 — обвързва
   * дялът. Запорът се клампва до 640,50 и 259,50 остават изискуеми.
   */
  @Test
  @DisplayName("БАЗА · нето 1431,00 − издръжка 150,00 → таван 640,50, несъбрано 259,50")
  void theDeliveredScaleLimitsARealGarnishment() throws Exception {
    try (Connection c = open()) {
      requireDeliveredScale(c);
      BigDecimal minWage = minWageFromDb(c, MARCH);
      List<BgGarnishmentScale> scale = scaleFromDb(c, MARCH);

      assertEquals(
          0,
          new BigDecimal("620.20").compareTo(minWage),
          "⚠️ котвата: МРЗ за март 2026 г. трябва да е 620,20 EUR. Намерено: " + minWage);
      assertEquals(6, scale.size(), "три прага по две редакции — доставени: " + scale.size());

      BgGarnishmentOutcome outcome =
          BgGarnishmentResolver.assess(
              new BigDecimal("1431.00"),
              new BigDecimal("150.00"),
              NO_PAST_DUE,
              new BigDecimal("900.00"),
              BigDecimal.ZERO,
              minWage,
              false,
              scale);

      assertNull(outcome.refusal, String.valueOf(outcome.refusal));
      assertEquals(
          0,
          new BigDecimal("1281.00").compareTo(outcome.base),
          "⚠️ издръжката излиза ПЪРВА и ЦЯЛА — основата е нетото минус нея");
      assertEquals(
          0,
          new BigDecimal("640.50").compareTo(outcome.limit),
          "1/2 от 1281,00 по праг 2. Намерено: " + outcome.limit);
      assertEquals(
          0,
          new BigDecimal("640.50").compareTo(outcome.allowedGarnishment),
          "запорът се клампва до тавана");
      assertEquals(
          0,
          new BigDecimal("259.50").compareTo(outcome.uncollected),
          "⚠️ 900,00 − 640,50 остават ИЗИСКУЕМИ, не отпадат");
    }
  }

  /** ⚠️ Същият случай с деца: скалата е друга (1/3) и таванът пада на 427,00. */
  @Test
  @DisplayName("БАЗА · същият фиш с деца → таван 427,00, защото скалата е 1/3, не 1/2")
  void theDeliveredScaleDistinguishesDebtorsWithChildren() throws Exception {
    try (Connection c = open()) {
      requireDeliveredScale(c);
      BigDecimal minWage = minWageFromDb(c, MARCH);
      List<BgGarnishmentScale> scale = scaleFromDb(c, MARCH);

      BgGarnishmentOutcome outcome =
          BgGarnishmentResolver.assess(
              new BigDecimal("1431.00"),
              new BigDecimal("150.00"),
              NO_PAST_DUE,
              new BigDecimal("900.00"),
              BigDecimal.ZERO,
              minWage,
              true,
              scale);

      assertEquals(
          0,
          new BigDecimal("427.00").compareTo(outcome.limit),
          "1/3 от 1281,00. Намерено: " + outcome.limit);
      assertTrue(
          outcome.limit.compareTo(new BigDecimal("640.50")) < 0,
          "⚠️ признакът „с деца“ трябва да СВАЛЯ тавана — инак избира скала наужким");
    }
  }

  /**
   * ⚠️ ОТКАЗЪТ ПО КТ ЧЛ. 272 АЛ. 2 РАБОТИ И ВЪРХУ ДОСТАВЕНИТЕ РЕДОВЕ. Заемът по съгласие изкарва
   * сбора над тавана и това е отказ с назована причина, а не тиха клампа на доброволната удръжка.
   */
  @Test
  @DisplayName("БАЗА · запор 600,00 + заем 100,00 над таван 640,50 → ОТКАЗ по КТ чл. 272 ал. 2")
  void theCeilingRefusalNamesItsReasonOnDeliveredRows() throws Exception {
    try (Connection c = open()) {
      requireDeliveredScale(c);

      BgGarnishmentOutcome outcome =
          BgGarnishmentResolver.assess(
              new BigDecimal("1431.00"),
              new BigDecimal("150.00"),
              NO_PAST_DUE,
              new BigDecimal("600.00"),
              new BigDecimal("100.00"),
              minWageFromDb(c, MARCH),
              false,
              scaleFromDb(c, MARCH));

      assertNotNull(outcome.refusal, "700,00 надхвърлят 640,50 и това трябва да спре фиша");
      assertTrue(outcome.refusal.contains("272"), outcome.refusal);
      assertTrue(outcome.refusal.contains("640.50"), outcome.refusal);
      assertTrue(
          outcome.refusal.contains("100.00"),
          "⚠️ отказът трябва да каже колко идва от доброволното. " + outcome.refusal);
    }
  }

  /**
   * ⚠️ ПРАЗНАТА КЛЕТКА СТИГА КАТО НУЛА, НЕ КАТО NULL — и това е ЗАКОВАНО, защото от него зависи как
   * се чете кодът.
   *
   * <p>След App Install праг 3 носи {@code bg_to_multiple = 0.00} и нулев дял, а прагове 1 и 2
   * носят {@code bg_keep_multiple = 0.00}. Полетата за decimal и integer се раждат със {@code ZERO}
   * / {@code 0}, тоест „празно“ и „нула“ съвпадат и NULL няма.
   *
   * <p>Оттам следва, че {@code BgGarnishmentResolver} пита {@code signum() > 0} и {@code == 0},
   * никога за {@code null} — проверка за null там би била мъртъв код. Обърне ли се това някога
   * (нови подразбирания на генератора, ръчно попълнен NULL), тестът се обажда ТУК, а не с грешно
   * сметнат запор на жив фиш.
   */
  @Test
  @DisplayName("⚠️ БАЗА · празните клетки са НУЛА, не NULL — оттам идва прочитът със signum()")
  void theBlankCellsArriveAsZeroNotNull() throws Exception {
    try (Connection c = open()) {
      requireDeliveredScale(c);

      assertEquals(
          "0",
          one(c, "SELECT count(*) FROM " + SCALE + " WHERE bg_to_multiple IS NULL"),
          "⚠️ NULL тук би значел, че прочитът със signum() вече не покрива всички редове");
      assertEquals(
          "0",
          one(c, "SELECT count(*) FROM " + SCALE + " WHERE bg_keep_multiple IS NULL"),
          "същото за оставащото кратно");
      assertEquals(
          "1",
          one(
              c,
              "SELECT count(*) FROM "
                  + SCALE
                  + " WHERE bg_threshold = 3 AND bg_with_children = false"
                  + " AND bg_to_multiple = 0 AND coalesce(bg_denominator, 0) = 0"),
          "⚠️ праг 3 без деца: без горна граница и без дял — и двете записани като нула");
      assertEquals(
          "4",
          one(
              c,
              "SELECT count(*) FROM " + SCALE + " WHERE bg_threshold < 3 AND bg_keep_multiple = 0"),
          "⚠️ ЧЕТИРИ реда: прагове 1 и 2, всеки в двете си редакции. Всички без оставащо кратно,"
              + " записано като нула");
    }
  }

  /** ⚠️ Доставените редове трябва да са ТОЧНО шест — нито дублирани, нито изчезнали. */
  @Test
  @DisplayName("БАЗА · доставени са точно шест реда, по два за всеки от трите прага")
  void exactlySixRowsAreDelivered() throws Exception {
    try (Connection c = open()) {
      requireDeliveredScale(c);

      assertEquals(
          "3",
          one(c, "SELECT count(DISTINCT bg_threshold) FROM " + SCALE),
          "три прага по чл. 446 ал. 1");
      assertEquals(
          "6",
          one(c, "SELECT count(*) FROM " + SCALE),
          "⚠️ дублиран ред би дал ДРУГ отговор според подредбата на заявката");
      assertEquals(
          "0",
          one(
              c,
              "SELECT count(*) FROM (SELECT bg_threshold, bg_with_children FROM "
                  + SCALE
                  + " GROUP BY 1, 2 HAVING count(*) > 1) d"),
          "всяка двойка (праг, с деца) трябва да е точно веднъж");
    }
  }
}
