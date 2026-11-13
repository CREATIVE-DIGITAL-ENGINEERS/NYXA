package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗБОРЪТ НА ДАНЪЧНА СТАВКА ПО ЧЛ. 42 АЛ. 4 ЗДДФЛ — базовата половина: „ДОСТАВЕНО ли е“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgSeafarerIncomeTaxRateTest} пита дали изборът е
 * ВЕРЕН и дали seed-ът е верен ЗАПИСАН; той тече винаги и без Postgres. Този пита нещо, което
 * файловете не могат да знаят: стигнал ли е признакът до базата, тоест ще се облага ли морското
 * лице с 1 на сто на ЖИВА среда. Слети в един тест, отговорът „App Install не е пускан“ би маскирал
 * отговора „изборът е сгрешен“.
 *
 * <p><b>⚠️ ПРАВИЛОТО НЕ СЕ ПРЕПИСВА.</b> Изборът минава през {@code
 * BgPaySlipService#incomeTaxRate}, който се вика ИСТИНСКИ. Подменени са само двата шева към базата
 * — те четат СЪЩИТЕ редове, но през JDBC, защото {@code fetchOne()} иска Hibernate, а тези тестове
 * нямат контейнер. Условията в SQL-а са дословно тези на JPQL-а горе; всяко разминаване между двете
 * би значело, че тестът гледа друг ред, а не че кодът бърка.
 *
 * <p><b>Пропуска се при ЧЕТИРИ състояния на средата, всяко с обявена причина</b>: няма достъпна
 * база; колоната {@code bg_is_seafarer} още я няма (миграцията {@code
 * db/migration/2026-08-22-bg-insured-type-is-seafarer.sql} не е пускана и {@code ddl=update} не е
 * минавал); колоната я има, но НИТО ЕДИН ред не е отбелязан (App Install не е пускан след този
 * комит — заварените редове носят NULL нарочно, миграцията не попълва); липсва ред за някой от
 * двата вида осигурен или за някоя от двете ставки за периода.
 *
 * <p>⚠️ Третото състояние Е нормалното веднага след миграцията и НЕ е дефект: до App Install
 * средата запазва ДНЕШНОТО си поведение — 10 на сто за всички. Червено там би карало читателя да
 * поправя код, който е верен.
 *
 * <p><b>⚠️ И ЕДНА ДУПКА В ТОВА ПРОПУСКАНЕ, ЗАПИСАНА, ЗА ДА НЕ СЕ РАЗЧИТА НА НЕЯ.</b> „Нито един
 * отбелязан ред“ значи и „App Install не е пускан“, и „флагът е СВАЛЕН от seed-а“ — двете са
 * неразличими оттук и второто би минало като пропускане, а не като червено. Второто обаче се лови
 * от файловата половина ({@code BgSeafarerIncomeTaxRateTest#exactlyOneInsuredTypeCarriesTheFlag}),
 * която тече ВИНАГИ и пада, щом кодът 92 престане да е единственият отбелязан. Тоест дупката е
 * покрита от другата половина, а не оставена отворена.
 *
 * <p><b>Очакваното.</b> След App Install върху локалната база двете твърдения тук минават: вид
 * осигурен 92 → 1,0000, вид осигурен 01 → 10,0000.
 *
 * <p><b>⚠️ И ЕДНО, КОЕТО ОПРЕДЕЛЯ КАК СА НАПИСАНИ ЗАЯВКИТЕ ТУК — ДВЕТЕ СЪСТОЯНИЯ НА КОЛОНАТА.</b>
 * След миграцията и преди App Install всичките 43 реда носят {@code NULL}. След App Install остават
 * 42 с {@code NULL}, а само код 92 получава {@code true} — нито един ред не става {@code false}.
 * Премерено: {@code NULL|42}, {@code true|1}, при това кодове 01 и 02 стоят с {@code version = 1} и
 * {@code updated_on = 2026-08-09}, тоест вносът изобщо не ги е докоснал.
 *
 * <p>⚠️ Причината НЕ е, че seed-ът мълчи: CSV-то носи изричен {@code false} и {@code
 * BooleanAdapter} го превръща в {@code Boolean.FALSE}. Спира се на {@code
 * com.axelor.db.mapper.Property#set}, който чете старата стойност през генерирания getter — връщащ
 * {@code Boolean.FALSE} и за непопълнено поле — и излиза по {@code if (old == value)}: един и същ
 * кеширан обект, setter-ът не се вика, UPDATE няма.
 *
 * <p>Затова заявките тук питат {@code WHERE bg_is_seafarer} и НИКОГА {@code = false} — второто
 * пропуска 42-та реда в ДВЕТЕ състояния — а четенето на стойността минава през {@code
 * coalesce(bg_is_seafarer, false)}. Прочитът на {@code NULL} като „не е морско лице“ има и свой
 * изолиран свидетел във файловата половина: {@code
 * BgSeafarerIncomeTaxRateTest#anInsuredTypeRowWithAnUnsetFlagIsNotASeafarer}.
 */
class BgSeafarerIncomeTaxRateDbTest {

  private static final String TYPES = "bglocalization_bg_insured_type";
  private static final String PARAMETERS = "bglocalization_bg_payroll_parameter";

  private static final String SEAFARER = "92";
  private static final String ORDINARY = "01";

  /** Март 2026 г. — период, за който и двете ставки са в сила от 01.01.2026 г. */
  private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);

  private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);

  // ═══ УСЛУГАТА С ЧЕТЦИ КЪМ ЖИВАТА БАЗА ═════════════════════════════════════

  /**
   * {@code incomeTaxRate} се вика истински; двата шева към базата четат през JDBC.
   *
   * <p>⚠️ Заявките са ОГЛЕДАЛО на JPQL-а в {@code BgPaySlipService}: същият код, същите три условия
   * по датата. {@code fetchOne()} на Axelor връща един ред — {@code LIMIT 1} тук прави същото.
   */
  private static final class JdbcService extends BgPaySlipService {

    private final Connection connection;

    JdbcService(Connection connection) {
      this.connection = connection;
    }

    /**
     * Врата към защитения избор — този тест живее в друг пакет.
     *
     * <p>⚠️ Делегира и нищо повече. Разширяване на видимостта на самия {@code incomeTaxRate} би
     * било промяна на производствен шев заради тест; делегатът оставя шева какъвто е.
     */
    BigDecimal rateFor(BgPaySlip slip) {
      return incomeTaxRate(slip);
    }

    @Override
    protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
      if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty()) {
        return null;
      }
      String sql =
          "SELECT code, name, coalesce(bg_is_seafarer, false), valid_from, valid_to FROM "
              + TYPES
              + " WHERE code = ? AND valid_from <= ?"
              + " AND (valid_to IS NULL OR valid_to >= ?) LIMIT 1";
      try (PreparedStatement st = connection.prepareStatement(sql)) {
        st.setString(1, insuredTypeCode);
        st.setObject(2, date);
        st.setObject(3, date);
        try (ResultSet rs = st.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          BgInsuredType type = new BgInsuredType();
          type.setCode(rs.getString(1));
          type.setName(rs.getString(2));
          // ⚠️ getBoolean, не getString: JDBC дава на boolean колона „t“/„f“, а не „true“/„false“.
          type.setBgIsSeafarer(rs.getBoolean(3));
          type.setValidFrom(rs.getObject(4, LocalDate.class));
          type.setValidTo(rs.getObject(5, LocalDate.class));
          return type;
        }
      } catch (Exception e) {
        throw new IllegalStateException("не се чете " + TYPES + ": " + e.getMessage(), e);
      }
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      String sql =
          "SELECT param_value FROM "
              + PARAMETERS
              + " WHERE code = ? AND valid_from <= ?"
              + " AND (valid_to IS NULL OR valid_to >= ?) LIMIT 1";
      try (PreparedStatement st = connection.prepareStatement(sql)) {
        st.setString(1, code);
        st.setObject(2, date);
        st.setObject(3, date);
        try (ResultSet rs = st.executeQuery()) {
          return rs.next() ? rs.getBigDecimal(1) : null;
        }
      } catch (Exception e) {
        throw new IllegalStateException("не се чете " + PARAMETERS + ": " + e.getMessage(), e);
      }
    }
  }

  // ═══ СЪСТОЯНИЯТА НА СРЕДАТА ═══════════════════════════════════════════════

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
              + " — твърдението „морското лице се облага с 1 на сто на жива среда“ НЕ е проверено."
              + " Файловата половина (BgSeafarerIncomeTaxRateTest) тече и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  /**
   * Колоната, редовете и двете ставки — трите неща, без които въпросът е за средата, не за кода.
   */
  private static void requireDeliveredSeed(Connection c) throws Exception {
    String column =
        one(
            c,
            "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                + TYPES
                + "' AND column_name = 'bg_is_seafarer'");
    if ("0".equals(column)) {
      BgSkip.because(
          "колоната "
              + TYPES
              + ".bg_is_seafarer още я НЯМА — тоест нито"
              + " db/migration/2026-08-22-bg-insured-type-is-seafarer.sql е пускан, нито ddl=update"
              + " е минавал с новия домейн. Състояние на средата, не на кода.");
    }

    String marked = one(c, "SELECT count(*) FROM " + TYPES + " WHERE bg_is_seafarer");
    if ("0".equals(marked)) {
      BgSkip.because(
          "колоната "
              + TYPES
              + ".bg_is_seafarer я има, но НИТО ЕДИН ред не е отбелязан — App Install не е пускан"
              + " след този комит. Миграцията НЕ попълва нарочно (източникът е"
              + " bg_insured_type.csv), а заварените редове носят NULL, което се чете като „не е"
              + " морско лице“. Тоест средата запазва днешното си поведение — 10 на сто за"
              + " всички — и това не е дефект. Пусни App Install.");
    }

    for (String code : new String[] {SEAFARER, ORDINARY}) {
      String found =
          one(
              c,
              "SELECT count(*) FROM "
                  + TYPES
                  + " WHERE code = '"
                  + code
                  + "' AND valid_from <= DATE '2026-03-01'"
                  + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')");
      if ("0".equals(found)) {
        BgSkip.because(
            "видът осигурен "
                + code
                + " няма ред, валиден за 01.03.2026 г., в "
                + TYPES
                + " — номенклатурата не е заредена. Пусни App Install.");
      }
    }

    for (String code : new String[] {"INCOME_TAX_RATE", "INCOME_TAX_RATE_SEAFARER"}) {
      String found =
          one(
              c,
              "SELECT count(*) FROM "
                  + PARAMETERS
                  + " WHERE code = '"
                  + code
                  + "' AND valid_from <= DATE '2026-03-01'"
                  + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')");
      if ("0".equals(found)) {
        BgSkip.because(
            "параметърът "
                + code
                + " няма ред, валиден за 01.03.2026 г., в "
                + PARAMETERS
                + " — каталогът с параметри не е зареден. Пусни App Install.");
      }
    }
  }

  private static BgPaySlip slipFor(String insuredTypeCode) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(MARCH_FROM);
    period.setToDate(MARCH_TO);
    slip.setPeriod(period);
    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode(insuredTypeCode);
    slip.setEmploymentContract(contract);
    return slip;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("БАЗА · вид осигурен 92, март 2026 → 1,0000; вид осигурен 01 → 10,0000")
  void theDeliveredSeedTaxesTheSeafarerAtOnePercent() throws Exception {
    try (Connection c = open()) {
      requireDeliveredSeed(c);
      JdbcService service = new JdbcService(c);

      BigDecimal seafarer = service.rateFor(slipFor(SEAFARER));
      BigDecimal ordinary = service.rateFor(slipFor(ORDINARY));

      assertEquals(
          0,
          new BigDecimal("1.0000").compareTo(seafarer),
          "⚠️ чл. 42 ал. 4 ЗДДФЛ — 1 на сто за морско лице. Намерено в базата: " + seafarer);
      assertEquals(
          0,
          new BigDecimal("10.0000").compareTo(ordinary),
          "⚠️ общата ставка трябва да остане непокътната. Намерено в базата: " + ordinary);
    }
  }

  /**
   * ⚠️ ТОЧНО ЕДИН ОТБЕЛЯЗАН РЕД — и в БАЗАТА, не само във файла.
   *
   * <p>Вторият вдигнат флаг би облагал цял вид осигурен с 1 на сто вместо 10, а на фиша това
   * изглежда като нормално сметнат данък. Файловата половина пази seed-а; тук се пази това, което
   * App Install е ОСТАВИЛ — включително ръчна промяна по таблицата, която никой файл не знае.
   */
  @Test
  @DisplayName("БАЗА · точно ЕДИН вид осигурен носи bg_is_seafarer — и той е 92")
  void exactlyOneStoredInsuredTypeCarriesTheFlag() throws Exception {
    try (Connection c = open()) {
      requireDeliveredSeed(c);

      String codes =
          one(
              c,
              "SELECT string_agg(DISTINCT code, ',' ORDER BY code) FROM "
                  + TYPES
                  + " WHERE bg_is_seafarer");

      assertEquals(
          SEAFARER,
          codes,
          "⚠️ отбелязан друг код значи цял вид осигурен, обложен с 1 на сто вместо 10");
    }
  }
}
