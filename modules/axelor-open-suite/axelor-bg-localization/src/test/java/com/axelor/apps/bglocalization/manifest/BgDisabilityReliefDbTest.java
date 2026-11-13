package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.axelor.apps.hr.db.Employee;
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
 * ДАНЪЧНОТО ОБЛЕКЧЕНИЕ ЗА НАМАЛЕНА РАБОТОСПОСОБНОСТ — базовата половина: „ДОСТАВЕН ли е параметърът
 * и полага ли се облекчението на жива среда“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgDisabilityReliefTest} пита дали РЕШЕНИЕТО е
 * вярно и тече винаги, без Postgres. Този пита нещо, което файловете не могат да знаят: стигнала ли
 * е сумата до базата. Слети, отговорът „App Install не е пускан“ би маскирал отговора „решението е
 * сгрешено“.
 *
 * <p><b>⚠️ ПРАВИЛОТО НЕ СЕ ПРЕПИСВА.</b> {@code BgPaySlipService#disabilityRelief} се вика
 * ИСТИНСКИ; подменен е само четецът на параметъра — той чете СЪЩИЯ ред, но през JDBC, защото тези
 * тестове нямат контейнер.
 *
 * <p><b>Пропуска се при две състояния на средата</b>, всяко с обявена причина: няма достъпна база;
 * параметърът {@code DISABILITY_RELIEF_MONTHLY} няма ред, валиден за периода (App Install не е
 * пускан). ⚠️ Второто НЕ е дефект в кода: дотогава всеки фиш на лице с вдигнат флаг ще се ОТКАЗВА с
 * назована причина, а не ще удържа пълен данък — тоест средата е безопасна.
 *
 * <p>Основание: ЗДДФЛ чл. 18 ал. 2 и чл. 42 ал. 3 т. 1; сверено срещу официалния ЗДДФЛ на
 * 23.08.2026 г.
 */
class BgDisabilityReliefDbTest {

  private static final String PARAMETERS = "bglocalization_bg_payroll_parameter";
  private static final String CODE = "DISABILITY_RELIEF_MONTHLY";

  private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);

  // ═══ УСЛУГАТА С ЧЕТЕЦ КЪМ ЖИВАТА БАЗА ═════════════════════════════════════

  private static final class JdbcService extends BgPaySlipService {

    private final Connection connection;

    JdbcService(Connection connection) {
      this.connection = connection;
    }

    /** Врата към защитеното решение — този тест живее в друг пакет. */
    BigDecimal reliefFor(BgPaySlip slip) {
      return disabilityRelief(slip);
    }

    /** ⚠️ Огледало на JPQL-а в BgPaySlipService: същият код, същите три условия по датата. */
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
              + " — твърдението „облекчението по чл. 18 ал. 2 се полага на жива среда“ НЕ е"
              + " проверено. Файловата половина (BgDisabilityReliefTest) тече и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static void requireDeliveredParameter(Connection c) throws Exception {
    String found =
        one(
            c,
            "SELECT count(*) FROM "
                + PARAMETERS
                + " WHERE code = '"
                + CODE
                + "' AND valid_from <= DATE '2026-03-01'"
                + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')");
    if ("0".equals(found)) {
      BgSkip.because(
          "параметърът "
              + CODE
              + " няма ред, валиден за 01.03.2026 г. — App Install не е пускан. Дотогава фишът на"
              + " лице с вдигнат флаг ще се ОТКАЗВА с назована причина, а не ще удържа пълен данък,"
              + " тоест средата е безопасна. Пусни App Install.");
    }
  }

  private static BgPaySlip slipFor(boolean flag, LocalDate validUntil) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(MARCH_FROM);
    period.setToDate(MARCH_TO);
    slip.setPeriod(period);
    Employee employee = new Employee();
    employee.setBgDisabilityRelief(flag);
    employee.setBgDisabilityValidUntil(validUntil);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);
    return slip;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("БАЗА · лице с валидно решение, март 2026 → 337,45; без решение → 0,00")
  void theDeliveredParameterGivesTheRelief() throws Exception {
    try (Connection c = open()) {
      requireDeliveredParameter(c);
      JdbcService service = new JdbcService(c);

      BigDecimal withDecision = service.reliefFor(slipFor(true, LocalDate.of(2026, 12, 31)));
      BigDecimal openEnded = service.reliefFor(slipFor(true, null));
      BigDecimal without = service.reliefFor(slipFor(false, null));

      assertNotNull(withDecision, "вдигнат флаг при зареден параметър не бива да е отказ");
      assertEquals(
          0,
          new BigDecimal("337.45").compareTo(withDecision),
          "⚠️ чл. 18 ал. 2 — 660 лв. / 1,95583 = 337,45 EUR. Намерено в базата: " + withDecision);
      assertEquals(
          0,
          new BigDecimal("337.45").compareTo(openEnded),
          "⚠️ пожизненото решение (празна дата) дава същото. Намерено: " + openEnded);
      assertEquals(
          0,
          BigDecimal.ZERO.compareTo(without),
          "⚠️ без решение облекчение не се полага. Намерено: " + without);
    }
  }

  /** ⚠️ Изтекло решение не се полага и на жива среда — границата се пази и тук. */
  @Test
  @DisplayName("БАЗА · решение, изтекло през февруари → 0,00; изтичащо през март → 337,45")
  void theMonthOfExpiryIsIncludedOnDeliveredRows() throws Exception {
    try (Connection c = open()) {
      requireDeliveredParameter(c);
      JdbcService service = new JdbcService(c);

      assertEquals(
          0,
          BigDecimal.ZERO.compareTo(service.reliefFor(slipFor(true, LocalDate.of(2026, 2, 28)))),
          "февруари е преди март");
      assertEquals(
          0,
          new BigDecimal("337.45")
              .compareTo(service.reliefFor(slipFor(true, LocalDate.of(2026, 3, 15)))),
          "⚠️ „включително за месеца на изтичане срока на валидност на решението“");
    }
  }

  /**
   * Доставената стойност трябва да е ТОЧНО една за периода — два реда биха дали случаен отговор.
   */
  @Test
  @DisplayName("БАЗА · за 01.03.2026 има точно ЕДИН валиден ред за DISABILITY_RELIEF_MONTHLY")
  void exactlyOneRowIsInForce() throws Exception {
    try (Connection c = open()) {
      requireDeliveredParameter(c);

      assertEquals(
          "1",
          one(
              c,
              "SELECT count(*) FROM "
                  + PARAMETERS
                  + " WHERE code = '"
                  + CODE
                  + "' AND valid_from <= DATE '2026-03-01'"
                  + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')"),
          "⚠️ два валидни реда значат отговор според подредбата на заявката, не според нормата");
    }
  }
}
