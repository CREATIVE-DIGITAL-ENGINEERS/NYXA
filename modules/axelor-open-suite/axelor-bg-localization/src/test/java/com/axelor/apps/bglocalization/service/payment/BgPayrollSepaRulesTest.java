package com.axelor.apps.bglocalization.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛИТЕ НА ПЛАТЕЖНИЯ ПРОБЕГ.
 *
 * <p>Без база, без Guice: кандидатите се сглобяват в Java, точно както {@code
 * BgSocialExpenseCapResolverTest} сглобява капачките. Оттам следва, че тестът тече на всяка машина
 * и НЕ се пропуска.
 *
 * <p><b>⚠️ ПРОВЕРЯВА СЕ ТЕКСТЪТ, А НЕ САМО БРОЯТ.</b> Цялото упражнение е човек без банкова сметка
 * да НЕ бъде изпуснат тихо; тест, който брои „един отказ“, минава и когато отказът не казва КОЙ.
 * Затова асерциите търсят ИМЕТО в съобщението.
 */
class BgPayrollSepaRulesTest {

  private static BgPayrollSepaCandidate ok(String person, String amount) {
    return BgPayrollSepaCandidate.payable(
        1L, "FISH-0001", person, "BG18RZBB91550123456789", new BigDecimal(amount));
  }

  private static BgPayrollSepaCandidate broken(String person, String amount, String problem) {
    return BgPayrollSepaCandidate.withoutAccount(
        2L, "FISH-0002", person, problem, new BigDecimal(amount));
  }

  // ═══════════════════════ ОСНОВАНИЕТО ═══════════════════════

  @Test
  @DisplayName("Основанието носи месеца на периода, а не името му")
  void remittanceUsesPeriodMonth() {
    assertEquals(
        "Работна заплата 09.2026", BgPayrollSepaRules.remittance(LocalDate.of(2026, 9, 1)));
    assertEquals(
        "Работна заплата 01.2027", BgPayrollSepaRules.remittance(LocalDate.of(2027, 1, 31)));
  }

  @Test
  @DisplayName("Период без начална дата не поваля пробега — основанието остава четимо")
  void remittanceSurvivesMissingDate() {
    assertEquals("Работна заплата", BgPayrollSepaRules.remittance(null));
  }

  // ═══════════════════════ ЛИПСВАЩА СМЕТКА ═══════════════════════

  @Test
  @DisplayName("Лице без сметка ОТКАЗВА целия файл и се назовава ПОИМЕННО")
  void missingIbanRefusesTheWholeFileByName() {
    List<BgPayrollSepaCandidate> candidates =
        Arrays.asList(
            ok("Иван Петров Георгиев", "1234.56"),
            broken("Мария Стоянова Илиева", "987.65", "партньорът няма сметка по подразбиране"),
            ok("Георги Димитров Николов", "800.00"));

    String refusal = BgPayrollSepaRules.refuseMissingIban(candidates);

    assertNotNull(refusal, "лице без сметка трябва да откаже файла, а не да бъде изпуснато");
    assertTrue(refusal.contains("Мария Стоянова Илиева"), "отказът трябва да назовава лицето");
    assertTrue(refusal.contains("FISH-0002"), "отказът трябва да сочи фиша");
    assertTrue(refusal.contains("987.65"), "отказът трябва да казва каква сума виси");
    assertTrue(
        refusal.contains("партньорът няма сметка по подразбиране"),
        "отказът трябва да казва КОЕ поле се попълва");
    // ⚠️ И НЕ НАЗОВАВА ГОДНИТЕ: списък с всички трима би бил еднакво дълъг и неизползваем.
    assertTrue(!refusal.contains("Иван Петров Георгиев"), "годните лица не влизат в отказа");
  }

  @Test
  @DisplayName("Годни сметки навсякъде — нула откази")
  void allAccountsUsableMeansNoRefusal() {
    assertNull(
        BgPayrollSepaRules.refuseMissingIban(
            Arrays.asList(ok("Иван", "10.00"), ok("Мария", "20.00"))));
  }

  @Test
  @DisplayName("НУЛЕВО нето не иска сметка — иначе неплатен отпуск би отказвал цяла ведомост")
  void zeroNetDoesNotRequireAnAccount() {
    List<BgPayrollSepaCandidate> candidates =
        Arrays.asList(
            ok("Иван Петров", "1234.56"),
            broken("Мария Стоянова", "0.00", "партньорът няма сметка по подразбиране"));

    assertNull(BgPayrollSepaRules.refuseMissingIban(candidates));
    assertEquals(1, BgPayrollSepaRules.payableLines(candidates).size());
    assertEquals(1, BgPayrollSepaRules.zeroNetLines(candidates).size());
    assertEquals(
        "Мария Стоянова", BgPayrollSepaRules.zeroNetLines(candidates).get(0).getPersonName());
  }

  @Test
  @DisplayName("Празно нето (null) се чете като нула, не като липса на сметка")
  void nullNetIsTreatedAsZero() {
    BgPayrollSepaCandidate empty =
        BgPayrollSepaCandidate.withoutAccount(3L, "FISH-0003", "Петър", "няма партньор", null);
    List<BgPayrollSepaCandidate> candidates = Collections.singletonList(empty);

    assertNull(BgPayrollSepaRules.refuseMissingIban(candidates));
    assertEquals(1, BgPayrollSepaRules.zeroNetLines(candidates).size());
    assertTrue(BgPayrollSepaRules.payableLines(candidates).isEmpty());
  }

  // ═══════════════════════ ОТРИЦАТЕЛНО НЕТО ═══════════════════════

  @Test
  @DisplayName("Отрицателно нето ОТКАЗВА файла поименно, вместо да изчезне от него")
  void negativeNetRefusesByName() {
    BgPayrollSepaCandidate owing =
        BgPayrollSepaCandidate.payable(
            4L, "FISH-0004", "Димитър Иванов", "BG18RZBB91550123456789", new BigDecimal("-45.20"));

    String refusal =
        BgPayrollSepaRules.refuseNegativeNet(Arrays.asList(ok("Иван", "100.00"), owing));

    assertNotNull(refusal);
    assertTrue(refusal.contains("Димитър Иванов"));
    assertTrue(refusal.contains("-45.20"));
    // ⚠️ И НЕ влиза в редовете: кредитен превод с отрицателна сума няма.
    assertTrue(BgPayrollSepaRules.payableLines(Collections.singletonList(owing)).isEmpty());
    assertTrue(BgPayrollSepaRules.zeroNetLines(Collections.singletonList(owing)).isEmpty());
  }

  @Test
  @DisplayName("Само положителни нета — нула откази")
  void positiveNetsMeanNoRefusal() {
    assertNull(BgPayrollSepaRules.refuseNegativeNet(Arrays.asList(ok("Иван", "0.01"))));
  }

  // ═══════════════════════ СБОРЪТ ═══════════════════════

  @Test
  @DisplayName("CtrlSum е ПРОСТ сбор на нетата, без ново закръгляне")
  void totalIsThePlainSumOfNets() {
    List<BgPayrollSepaCandidate> lines =
        Arrays.asList(ok("Иван", "1234.56"), ok("Мария", "987.65"), ok("Георги", "0.01"));

    assertEquals(new BigDecimal("2222.22"), BgPayrollSepaRules.arithmeticTotal(lines));
  }

  @Test
  @DisplayName("Празен списък дава нула, а не изключение")
  void emptyTotalIsZero() {
    assertEquals(BigDecimal.ZERO, BgPayrollSepaRules.arithmeticTotal(null));
    assertEquals(BigDecimal.ZERO, BgPayrollSepaRules.arithmeticTotal(Collections.emptyList()));
  }

  // ═══════════════════════ НАСТРОЙКИТЕ ═══════════════════════

  @Test
  @DisplayName("Липсващите настройки се назовават ПО ПОЛЕ, а не като „липсва настройка“")
  void missingConfigNamesTheFields() {
    String both = BgPayrollSepaRules.refuseMissingConfig("Нукса ЕООД", false, false);
    assertNotNull(both);
    assertTrue(both.contains("Нукса ЕООД"));
    assertTrue(both.contains("AccountConfig.bgPayrollBankDetails"));
    assertTrue(both.contains("AccountConfig.bgPayrollPaymentMode"));

    String onlyMode = BgPayrollSepaRules.refuseMissingConfig("Нукса ЕООД", true, false);
    assertNotNull(onlyMode);
    assertTrue(!onlyMode.contains("AccountConfig.bgPayrollBankDetails"));
    assertTrue(onlyMode.contains("AccountConfig.bgPayrollPaymentMode"));

    assertNull(BgPayrollSepaRules.refuseMissingConfig("Нукса ЕООД", true, true));
  }

  @Test
  @DisplayName("Чужд формат на платежния способ отказва ПРЕДИ да се строи нещо")
  void wrongFileFormatIsRefused() {
    assertNull(BgPayrollSepaRules.refuseFileFormat("SEPA", "pain.001.001.03.sct"));

    String wrong = BgPayrollSepaRules.refuseFileFormat("CFONB", "pain.xxx.cfonb320.xct");
    assertNotNull(wrong);
    assertTrue(wrong.contains("pain.001.001.03.sct"));
    assertTrue(wrong.contains("pain.xxx.cfonb320.xct"));

    String missing = BgPayrollSepaRules.refuseFileFormat("Без формат", null);
    assertNotNull(missing);
    assertTrue(missing.contains("BankOrderFileFormat не е избран"));
  }

  @Test
  @DisplayName("Валута, различна от EUR, е ОТКАЗ, а не превалутиране")
  void nonEuroIsRefused() {
    assertNull(BgPayrollSepaRules.refuseCurrency("Нукса ЕООД", "EUR"));

    String bgn = BgPayrollSepaRules.refuseCurrency("Нукса ЕООД", "BGN");
    assertNotNull(bgn);
    assertTrue(bgn.contains("BGN"));
    assertTrue(bgn.contains("EUR"));

    String none = BgPayrollSepaRules.refuseCurrency("Нукса ЕООД", null);
    assertNotNull(none);
    assertTrue(none.contains("Company.currency"));
  }

  // ═══════════════════════ ПРАЗНИЯТ ПРОБЕГ ═══════════════════════

  @Test
  @DisplayName("Втори пробег за същия период отказва и казва, че фишовете вече са платени")
  void secondRunRefusesAndSaysWhy() {
    String refusal = BgPayrollSepaRules.refuseNothingToPay(0, 3, 0, 0);

    assertNotNull(refusal);
    assertTrue(refusal.contains("3 фиша вече са в платежно нареждане"));
  }

  @Test
  @DisplayName("Период без нито един фиш казва точно това, а не „вече са платени“")
  void emptyPeriodSaysSo() {
    String refusal = BgPayrollSepaRules.refuseNothingToPay(0, 0, 0, 0);

    assertNotNull(refusal);
    assertTrue(refusal.contains("няма нито един фиш"));
  }

  @Test
  @DisplayName("Непотвърдените и нулевите се броят ПО ПРИЧИНА в отказа")
  void refusalCountsEachReason() {
    String refusal = BgPayrollSepaRules.refuseNothingToPay(0, 1, 2, 4);

    assertTrue(refusal.contains("1 фиша вече са в платежно нареждане"));
    assertTrue(refusal.contains("2 фиша не са в състояние „Потвърден“"));
    assertTrue(refusal.contains("4 фиша са с нулево нето"));
  }

  @Test
  @DisplayName("Има ли поне един платим ред, пробегът НЕ се отказва")
  void onePayableLineIsEnough() {
    assertNull(BgPayrollSepaRules.refuseNothingToPay(1, 99, 99, 99));
  }
}
