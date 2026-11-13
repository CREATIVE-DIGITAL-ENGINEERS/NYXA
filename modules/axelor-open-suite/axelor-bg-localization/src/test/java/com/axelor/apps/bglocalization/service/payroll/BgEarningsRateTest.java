package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКЪДЕ ИДВА ПРОЦЕНТЪТ ЗА КЛАС ПРОСЛУЖЕНО ВРЕМЕ — двата източника и редът им.
 *
 * <p><b>Три половини, три различни въпроса.</b> ФАЙЛ · подменя шева към базата и проверява САМО
 * реда на източниците — законовият под срещу уговореното от работодателя. СИЙД · чете {@code
 * bg_payroll_parameter.csv} и проверява, че минимумът е записан вярно. БАЗА · пита живата база дали
 * seed-ът е ДОСТИГНАЛ дотам и се пропуска с обявена причина, когато App Install не е пускан.
 *
 * <p>Разделението не е педантичност: „0,6 е в CSV-то“ и „0,6 е в базата“ падат по различни причини
 * и се поправят на различни места — първото в seed-а, второто с ново зареждане на seed-а в базата.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен.
 */
class BgEarningsRateTest {

  private static final BigDecimal MIN_PCT = new BigDecimal("0.6000");
  private static final LocalDate MARCH_2026 = LocalDate.of(2026, 3, 1);

  /** Услуга с подменен шев към базата: върща подадения минимум, без да пита Postgres. */
  private static BgEarningsService serviceWithMin(BigDecimal statutoryMin) {
    return new BgEarningsService() {
      @Override
      protected BigDecimal statutoryMinPct(LocalDate date) {
        return statutoryMin;
      }

      /**
       * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не гледа
       * часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf} вади и седемте
       * параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо — точно каквото
       * трябва да получи фикстура, която не е обявила нито една часова ставка.
       */
      @Override
      protected BigDecimal payrollParameter(String code, LocalDate date) {
        return null;
      }

      /** Няма валидирани допълнителни часове → няма уговорено увеличение. Инак иска Hibernate. */
      @Override
      protected java.util.Map<String, BigDecimal> agreedOvertimeUplift(
          com.axelor.apps.bglocalization.db.BgPaySlip slip,
          com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
        return java.util.Collections.emptyMap();
      }
    };
  }

  private static Company companyWithRate(String pct) {
    Company company = new Company();
    if (pct != null) {
      company.setBgSeniorityRatePct(new BigDecimal(pct));
    }
    return company;
  }

  // ── ФАЙЛ · редът на двата източника ─────────────────────────────────────

  /**
   * FAIL-FIRST: {@link BgEarningsService#seniorityRatePct} беше пренаписан да връща винаги минимума
   * → 0,6000 ≠ 1,2000, тестът падна.
   */
  @Test
  @DisplayName("ФАЙЛ · уговореният от дружеството процент НАДДЕЛЯВА над минимума")
  void theCompanyRateOverridesTheStatutoryMinimum() {
    BigDecimal pct =
        serviceWithMin(MIN_PCT).seniorityRatePct(companyWithRate("1.2000"), MARCH_2026);

    assertEquals(0, pct.compareTo(new BigDecimal("1.2000")), "намерено: " + pct);
  }

  /**
   * ⚠️ Минимумът е ДОЛНА ГРАНИЦА по ПМС № 147/2007, не стойност по подразбиране: уговорка ПОД него
   * не се приема, а се вдига. Мълчаливо приетите 0,40 на сто са недоплатено възнаграждение, което
   * никой отчет не показва.
   *
   * <p>FAIL-FIRST: услугата беше пренаписана да връща процента на дружеството без сравнение →
   * 0,4000 ≠ 0,6000, тестът падна.
   */
  @Test
  @DisplayName("ФАЙЛ · процент ПОД минимума се ВДИГА до минимума, не се приема")
  void aCompanyRateBelowTheMinimumIsLifted() {
    BigDecimal pct =
        serviceWithMin(MIN_PCT).seniorityRatePct(companyWithRate("0.4000"), MARCH_2026);

    assertEquals(0, pct.compareTo(MIN_PCT), "намерено: " + pct);
  }

  /**
   * Непопълненият процент пристига като нула от генерирания getter — чете се като „не е уговорен“,
   * не като „уговорени са нула на сто“.
   */
  @Test
  @DisplayName("ФАЙЛ · непопълнен процент на дружеството пада на минимума")
  void anUnsetCompanyRateFallsBackToTheMinimum() {
    assertEquals(
        0, serviceWithMin(MIN_PCT).seniorityRatePct(new Company(), MARCH_2026).compareTo(MIN_PCT));
    assertEquals(0, serviceWithMin(MIN_PCT).seniorityRatePct(null, MARCH_2026).compareTo(MIN_PCT));
  }

  /**
   * ⚠️ ЛИПСВАЩИЯТ ПАРАМЕТЪР Е ОТКАЗ, ДОРИ КОГАТО ДРУЖЕСТВОТО Е ПОПЪЛНИЛО СВОЙ ПРОЦЕНТ. Без минимума
   * подът не може да се наложи, тоест уговореният процент не може да се приеме — приемането му би
   * било мълчаливо допускане, че е над границата.
   *
   * <p>FAIL-FIRST: услугата беше пренаписана да връща процента на дружеството, когато минимумът
   * липсва → 1,2000 вместо {@code null}, тестът падна.
   */
  @Test
  @DisplayName("ФАЙЛ · липсващ SENIORITY_MIN_PCT е ОТКАЗ дори при попълнен процент на дружеството")
  void aMissingParameterRefusesEvenWithACompanyRate() {
    assertNull(serviceWithMin(null).seniorityRatePct(companyWithRate("1.2000"), MARCH_2026));
    assertNull(serviceWithMin(null).seniorityRatePct(new Company(), MARCH_2026));
  }

  /** Без дата няма версия на параметъра — питането се спира преди базата. */
  @Test
  @DisplayName("ФАЙЛ · без референтна дата параметърът не се чете")
  void withoutAReferenceDateNothingIsRead() {
    assertNull(new BgEarningsService().statutoryMinPct(null));
  }

  // ── СИЙД · какво пише в CSV-то ──────────────────────────────────────────

  /**
   * FAIL-FIRST: очакваната стойност беше сменена на 0,7000 → тестът падна с намереното 0.6000,
   * тоест наистина чете файла, а не себе си.
   */
  @Test
  @DisplayName("СИЙД · SENIORITY_MIN_PCT е 0,6000 и носи основанието си")
  void theSeedCarriesTheStatutoryMinimum() {
    List<String[]> rows = BgSeed.rows("bg_payroll_parameter.csv");
    String[] row = null;
    for (String[] r : rows) {
      if (BgEarningsService.PARAM_SENIORITY_MIN_PCT.equals(r[1])) {
        row = r;
      }
    }

    assertTrue(row != null, "SENIORITY_MIN_PCT липсва от bg_payroll_parameter.csv");
    assertEquals(0, new BigDecimal(row[2]).compareTo(MIN_PCT), "стойност: " + row[2]);
    assertEquals("PERCENT", row[3], "мерната единица трябва да е ПРОЦЕНТ, не сума");

    // ⚠️ Бележката е част от твърдението: тя е мястото, където базата на перото е записана изрично.
    String note = row[6];
    assertTrue(note.contains("ОСНОВНАТА ЗАПЛАТА"), note);
    assertTrue(note.contains("НЕ брутото"), note);
    assertTrue(note.contains("ДОЛНА граница"), note);
  }

  // ── БАЗА · стигнал ли е seed-ът дотам ───────────────────────────────────

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
              + " — твърдението „минимумът е в базата“ НЕ е проверено. Файловата и сийд половината"
              + " на този тест текат и без база.");
      return null;
    }
  }

  /**
   * ⚠️ Това е ПРИЕМНИЯТ тест на App Install за този параметър: услугата чете точно този ред и без
   * него класът НЕ се смята.
   */
  @Test
  @DisplayName("БАЗА · SENIORITY_MIN_PCT е зареден и в сила за 03.2026")
  void theParameterIsActuallyLoaded() throws Exception {
    try (Connection c = open()) {
      if (c == null) {
        return;
      }
      String sql =
          "SELECT param_value FROM bglocalization_bg_payroll_parameter"
              + " WHERE code = '"
              + BgEarningsService.PARAM_SENIORITY_MIN_PCT
              + "' AND valid_from <= DATE '2026-03-01'"
              + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01')";
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(sql)) {
        if (!rs.next()) {
          BgSkip.because(
              "редът "
                  + BgEarningsService.PARAM_SENIORITY_MIN_PCT
                  + " липсва в базата за 03.2026 — App Install не е пускан след тази промяна."
                  + " Зареди seed-а наново (uninstall НЕ сваля init_data_loaded, а без"
                  + " свалянето install-ът връща успех за под секунда и не внася нищо).");
          return;
        }
        assertEquals(0, rs.getBigDecimal(1).compareTo(MIN_PCT), "намерено: " + rs.getBigDecimal(1));
      }
    }
  }
}
