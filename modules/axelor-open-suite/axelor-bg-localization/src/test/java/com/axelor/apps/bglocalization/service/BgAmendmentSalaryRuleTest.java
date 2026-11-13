package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContractAmendment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОЯ ЗАПЛАТА ВЛИЗА В КОЙ МЕСЕЦ — свидетелят с числа.
 *
 * <p>Чист тест: правилото не докосва базата и не иска Guice, затова тринайсетте случая се
 * проверяват със сглобени наум обекти. Точно за това правилото е отделено от прочита — виж класовия
 * му javadoc.
 *
 * <p><b>⚠️ ЧИСЛАТА СА ДОГОВОРКАТА, НЕ ИЛЮСТРАЦИЯ.</b> 1000,00 е базата по договора; 1200,00 е
 * увеличението от 01.10.2026 г.; 1000,00 обратно от 01.11.2026 г. Всеки ред отговаря на един въпрос
 * от картирането и падането му значи, че точно онзи месец се плаща с чужда заплата.
 */
class BgAmendmentSalaryRuleTest {

  private static final BigDecimal BASE = new BigDecimal("1000.00");
  private static final BigDecimal RAISED = new BigDecimal("1200.00");

  private static final LocalDate SEP_FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate SEP_TO = LocalDate.of(2026, 9, 30);
  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);
  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);
  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Анексът за увеличение: подписан 20.09, в сила 01.10, основание 019. */
  private static BgContractAmendment raiseFromOctober() {
    return amendment(LocalDate.of(2026, 9, 20), OCT_FROM, "019", RAISED);
  }

  /** Анексът за намаление: подписан 25.10, в сила 01.11, основание 019. */
  private static BgContractAmendment cutFromNovember() {
    return amendment(LocalDate.of(2026, 10, 25), NOV_FROM, "019", BASE);
  }

  private static BgContractAmendment amendment(
      LocalDate changeDate, LocalDate entryDate, String ground, BigDecimal newSalary) {
    BgContractAmendment a = new BgContractAmendment();
    a.setChangeDate(changeDate);
    a.setEntryDate(entryDate);
    a.setBgChangeGround(ground);
    a.setNewSalary(newSalary);
    return a;
  }

  // ═══ A · 1000 → 1200 от 01.10 ═══════════════════════════════════════════

  @Test
  @DisplayName("A1 · септември е ПРЕДИ датата на действие → 1000,00 по договора")
  void septemberKeepsTheContractBase() {
    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(
            Collections.singletonList(raiseFromOctober()), BASE, SEP_FROM, SEP_TO);

    assertEquals(BgSalaryInEffect.Status.CONTRACT_BASE, r.status);
    assertEquals(0, BASE.compareTo(r.salary), "септември се дължи по 1000,00: " + r.salary);
  }

  @Test
  @DisplayName("A2 · октомври започва В деня на действие → 1200,00 от анекса")
  void octoberTakesTheAmendment() {
    BgContractAmendment raise = raiseFromOctober();
    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(raise), BASE, OCT_FROM, OCT_TO);

    assertEquals(BgSalaryInEffect.Status.FROM_AMENDMENT, r.status);
    assertEquals(0, RAISED.compareTo(r.salary), "октомври се дължи по 1200,00: " + r.salary);
    assertSame(raise, r.source, "присъдата трябва да каже КОЙ анекс е дал числото");
    assertNotNull(r.detail);
  }

  @Test
  @DisplayName("A3 · ноември — същият анекс още е последният → 1200,00")
  void novemberKeepsTheOnlyAmendment() {
    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(
            Collections.singletonList(raiseFromOctober()), BASE, NOV_FROM, NOV_TO);

    assertEquals(BgSalaryInEffect.Status.FROM_AMENDMENT, r.status);
    assertEquals(0, RAISED.compareTo(r.salary));
  }

  // ═══ B · намаление 1200 → 1000 от 01.11 ═════════════════════════════════

  @Test
  @DisplayName("B1 · октомври вижда САМО първия анекс → 1200,00")
  void octoberIgnoresTheLaterCut() {
    List<BgContractAmendment> both = Arrays.asList(raiseFromOctober(), cutFromNovember());

    BgSalaryInEffect r = BgAmendmentSalaryRule.inEffect(both, BASE, OCT_FROM, OCT_TO);

    assertEquals(BgSalaryInEffect.Status.FROM_AMENDMENT, r.status);
    assertEquals(0, RAISED.compareTo(r.salary), "октомври остава 1200,00: " + r.salary);
  }

  @Test
  @DisplayName("B2 · ноември взема ПОСЛЕДНИЯ по дата на действие → 1000,00")
  void novemberTakesTheLatestAmendment() {
    List<BgContractAmendment> both = Arrays.asList(raiseFromOctober(), cutFromNovember());

    BgSalaryInEffect r = BgAmendmentSalaryRule.inEffect(both, BASE, NOV_FROM, NOV_TO);

    assertEquals(BgSalaryInEffect.Status.FROM_AMENDMENT, r.status);
    assertEquals(0, BASE.compareTo(r.salary), "ноември пада на 1000,00: " + r.salary);
  }

  @Test
  @DisplayName("B3 · РЕДЪТ НА СПИСЪКА НЕ РЕШАВА — обърнат, декември пак е 1000,00")
  void listOrderDoesNotDecide() {
    List<BgContractAmendment> reversed = Arrays.asList(cutFromNovember(), raiseFromOctober());

    BgSalaryInEffect r = BgAmendmentSalaryRule.inEffect(reversed, BASE, DEC_FROM, DEC_TO);

    assertEquals(0, BASE.compareTo(r.salary), "редът на fetch() не е обещан: " + r.salary);
  }

  // ═══ C · договор без анекси ═════════════════════════════════════════════

  @Test
  @DisplayName("C1 · няма анекси → базата, бит по бит както преди датирания прочит")
  void noAmendmentsMeansTheContractBase() {
    assertSame(
        BASE,
        BgAmendmentSalaryRule.inEffect(Collections.emptyList(), BASE, OCT_FROM, OCT_TO).salary,
        "връща се СЪЩИЯТ обект — нула преизчисляване");
    assertSame(BASE, BgAmendmentSalaryRule.inEffect(null, BASE, OCT_FROM, OCT_TO).salary);
  }

  @Test
  @DisplayName("C2 · посятият от миграцията ред (ground 000, БЕЗ дата на действие) не мести нищо")
  void migrationSeededRowIsSkipped() {
    // Точно както 2026-09-12-bg-contract-amendment.sql ги посява: new_salary от договора,
    // entry_date NULL, служебен маркер за основание. Хванати като „в сила“, те биха обявили
    // днешната заплата за валидна и за всеки минал месец.
    BgContractAmendment seeded =
        amendment(LocalDate.of(2026, 3, 15), null, "000", new BigDecimal("1750.00"));

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(seeded), BASE, SEP_FROM, SEP_TO);

    assertEquals(BgSalaryInEffect.Status.CONTRACT_BASE, r.status);
    assertEquals(0, BASE.compareTo(r.salary), "посятият ред не бива да мести заплатата");
  }

  @Test
  @DisplayName("C3 · периодът още не е избран → базата, без догадка по днешна дата")
  void unknownPeriodFallsBackToTheBase() {
    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(
            Collections.singletonList(raiseFromOctober()), BASE, null, null);

    assertEquals(BgSalaryInEffect.Status.CONTRACT_BASE, r.status);
    assertEquals(0, BASE.compareTo(r.salary));
  }

  // ═══ D · заплатен анекс НАСРЕД месеца ═══════════════════════════════════

  @Test
  @DisplayName("D1 · заплата от 15.10 РАЗПОЛОВЯВА октомври → ОТКАЗ с назована причина")
  void midMonthSalaryRefusesThatMonth() {
    BgContractAmendment mid =
        amendment(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), "019", RAISED);

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(mid), BASE, OCT_FROM, OCT_TO);

    assertTrue(r.salaryUnknown(), "октомври не може да се плати по две заплати");
    assertNull(r.salary, "отказът НЕ бива да носи число");
    assertTrue(r.detail.contains("15.10.2026"), r.detail);
    assertTrue(r.detail.contains("НАСРЕД"), r.detail);
  }

  @Test
  @DisplayName("D2 · СЪЩИЯТ анекс за ноември е нормален кандидат → 1200,00, не отказ завинаги")
  void midMonthAmendmentStillAppliesToLaterMonths() {
    BgContractAmendment mid =
        amendment(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), "019", RAISED);

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(mid), BASE, NOV_FROM, NOV_TO);

    assertEquals(BgSalaryInEffect.Status.FROM_AMENDMENT, r.status);
    assertEquals(0, RAISED.compareTo(r.salary), "ноември е цял по новата заплата: " + r.salary);
  }

  @Test
  @DisplayName("D3 · и за септември той изобщо не е в сила → 1000,00, без отказ")
  void midMonthAmendmentDoesNotReachEarlierMonths() {
    BgContractAmendment mid =
        amendment(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), "019", RAISED);

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(mid), BASE, SEP_FROM, SEP_TO);

    assertEquals(BgSalaryInEffect.Status.CONTRACT_BASE, r.status);
    assertEquals(0, BASE.compareTo(r.salary));
  }

  // ═══ E · анекс БЕЗ заплата ══════════════════════════════════════════════

  @Test
  @DisplayName("E · анекс само за ДЛЪЖНОСТ от 15.10 не отказва и не мени нищо")
  void positionOnlyAmendmentIsInvisibleToTheSalary() {
    // Основание 021 — срок, длъжност, отпуск или място на работа. newSalary остава непопълнено;
    // ⚠️ генерираният getter за decimal връща ZERO, не null — точно затова отсяването е по
    // signum().
    BgContractAmendment position = new BgContractAmendment();
    position.setChangeDate(LocalDate.of(2026, 10, 10));
    position.setEntryDate(LocalDate.of(2026, 10, 15));
    position.setBgChangeGround("021");

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Collections.singletonList(position), BASE, OCT_FROM, OCT_TO);

    assertEquals(BgSalaryInEffect.Status.CONTRACT_BASE, r.status, "не е кандидат за заплатата");
    assertEquals(0, BASE.compareTo(r.salary));
  }

  // ═══ F · двусмислие ═════════════════════════════════════════════════════

  @Test
  @DisplayName("F · два анекса в сила от ЕДИН ден → ОТКАЗ, а не монета по реда на списъка")
  void twoAmendmentsOnTheSameEntryDateRefuse() {
    // Законно по уникалния ключ (договор, дата на споразумението, основание): различна дата на
    // подписване, една и съща дата на влизане в сила.
    BgContractAmendment first = amendment(LocalDate.of(2026, 9, 20), OCT_FROM, "019", RAISED);
    BgContractAmendment second =
        amendment(LocalDate.of(2026, 9, 25), OCT_FROM, "019", new BigDecimal("1250.00"));

    BgSalaryInEffect r =
        BgAmendmentSalaryRule.inEffect(Arrays.asList(first, second), BASE, OCT_FROM, OCT_TO);

    assertTrue(r.salaryUnknown(), "не може да се каже коя е заплатата");
    assertTrue(r.detail.contains("1200.00") && r.detail.contains("1250.00"), r.detail);
  }

  // ═══ Пазачът при запис на договора ══════════════════════════════════════

  @Test
  @DisplayName("G1 · база, ПОВТАРЯЩА сумата на анекс → отказ при запис на договора")
  void baseRestatingAnAmendmentIsRefused() {
    String refusal =
        BgAmendmentSalaryRule.refuseBaseRestatingAmendment(
            RAISED, Collections.singletonList(raiseFromOctober()));

    assertNotNull(refusal, "точно този навик изяжда защитата на анекса");
    assertTrue(refusal.contains("КАКТО Е СКЛЮЧЕНА"), refusal);
  }

  @Test
  @DisplayName("G2 · поправка на базата на ТРЕТО число минава безпрепятствено")
  void correctingTheBaseToAThirdNumberPasses() {
    assertNull(
        BgAmendmentSalaryRule.refuseBaseRestatingAmendment(
            new BigDecimal("980.00"), Collections.singletonList(raiseFromOctober())));
    assertNull(BgAmendmentSalaryRule.refuseBaseRestatingAmendment(RAISED, null));
  }
}
