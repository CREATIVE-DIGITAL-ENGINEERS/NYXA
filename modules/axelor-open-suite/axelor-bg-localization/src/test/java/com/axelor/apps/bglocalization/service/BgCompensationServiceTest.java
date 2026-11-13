package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Деветте случая от заданието на WS-1 стъпка 3, плюс тези, които те подсказват.
 *
 * <p>Без база и без Guice: правилата живеят в {@link BgCompensationResolver}, а услугата се ползва
 * със запис и проверка за прикачен файл, подменени в наследник. Ако някой ден за тези тестове
 * потрябва Hibernate, значи правило е изтекло от резолвера в услугата.
 */
class BgCompensationServiceTest {

  /** Услугата без база: записът е без ефект, а „има ли прикачен файл“ се задава от теста. */
  private static class Service extends BgCompensationService {
    private final boolean attachment;

    Service(boolean attachment) {
      this.attachment = attachment;
    }

    @Override
    protected boolean hasAttachment(BgCompensation doc) {
      return attachment;
    }

    @Override
    protected void save(BgCompensation doc) {
      // без база
    }

    /** Календарът, както е засят: 2026 и 2027 г., без база. */
    @Override
    protected BgWorkingDayService workingDayService() {
      return SEEDED_CALENDAR;
    }
  }

  /**
   * Производственият календар от seed-а, без Hibernate.
   *
   * <p>Датите са ПРЕПИСАНИ от {@code bg_eventsPlanningLine.csv} — ако някога се разминат, {@code
   * BgHolidayManifestTest} сверява същите числа срещу базата.
   */
  private static final BgWorkingDayService SEEDED_CALENDAR =
      new BgWorkingDayService() {

        private final java.util.Set<LocalDate> holidays =
            new java.util.HashSet<>(
                java.util.Arrays.asList(
                    // 2026
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 3, 3),
                    LocalDate.of(2026, 4, 10),
                    LocalDate.of(2026, 4, 12),
                    LocalDate.of(2026, 4, 13),
                    LocalDate.of(2026, 5, 1),
                    LocalDate.of(2026, 5, 6),
                    LocalDate.of(2026, 5, 25),
                    LocalDate.of(2026, 9, 7),
                    LocalDate.of(2026, 9, 22),
                    LocalDate.of(2026, 12, 24),
                    LocalDate.of(2026, 12, 25),
                    LocalDate.of(2026, 12, 28),
                    // 2027
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 3),
                    LocalDate.of(2027, 4, 30),
                    LocalDate.of(2027, 5, 2),
                    LocalDate.of(2027, 5, 3),
                    LocalDate.of(2027, 5, 4),
                    LocalDate.of(2027, 5, 6),
                    LocalDate.of(2027, 5, 24),
                    LocalDate.of(2027, 9, 6),
                    LocalDate.of(2027, 9, 22),
                    LocalDate.of(2027, 12, 24),
                    LocalDate.of(2027, 12, 27),
                    LocalDate.of(2027, 12, 28)));

        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(java.util.Arrays.asList(2026, 2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          java.util.Set<LocalDate> in = new java.util.HashSet<>(holidays);
          in.removeIf(d -> d.isBefore(from) || d.isAfter(to));
          return in;
        }
      };

  private static Service service() {
    return new Service(false);
  }

  // ── фикстури, огледални на заредения каталог ────────────────────────────

  private static BgCompensationType type(
      String code, String legalBasis, String baseKind, String months) {
    BgCompensationType t = new BgCompensationType();
    t.setCode(code);
    t.setName(code);
    t.setLegalBasis(legalBasis);
    t.setBaseKind(baseKind);
    t.setBaseMonths(months == null ? null : new BigDecimal(months));
    t.setBgDirection("EMPLOYER_TO_EMPLOYEE");
    t.setIsAmountAdjustable(true);
    t.setRequiresProof(false);
    t.setIsTaxable(true);
    t.setSocialContributionsDue(true);
    return t;
  }

  private static BgCompensation doc(BgCompensationType type, String gross, LocalDate event) {
    BgCompensation d = new BgCompensation();
    d.setStatusSelect(BgCompensationResolver.DRAFT);
    d.setBgCompensationType(type);
    d.setBgEventDate(event);
    d.setBgBaseMonthGross(gross == null ? null : new BigDecimal(gross));
    return d;
  }

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · чл. 222 ал. 1 — едно брутно възнаграждение")
  void oneMonthlyGross() {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);

    assertEquals(new BigDecimal("1.00"), d.getBgQuantity(), "количеството идва от каталога");
    assertEquals(new BigDecimal("1450.00"), d.getBgComputedAmount());
    assertEquals(new BigDecimal("1450.00"), d.getBgFinalAmount(), "без корекция двете съвпадат");
  }

  // ── 2 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("2 · чл. 222 ал. 3 — две брутни възнаграждения по подразбиране")
  void twoMonthlyGrossByDefault() {
    BgCompensationType t = type("comp_kt_222_3", "чл. 222 ал. 3 КТ", "BTV_MONTHS", "2.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);

    assertEquals(new BigDecimal("2.00"), d.getBgQuantity());
    assertEquals(new BigDecimal("2900.00"), d.getBgComputedAmount());
    // Шестте заплати при 10 години при същия работодател НЕ се предлагат: условието е непроверимо.
    assertNotNull(BgCompensationResolver.seniorityWarning(t), "иска предупреждение, не блокировка");
    assertTrue(BgCompensationResolver.seniorityWarning(t).contains("СЪЩИЯ работодател"));
  }

  // ── 3 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("3 · чл. 224 — неползван отпуск в дни, през среднодневно БТВ")
  void unusedLeaveDays() {
    BgCompensationType t = type("comp_kt_224_1", "чл. 224 ал. 1 КТ", "UNUSED_LEAVE_DAYS", null);
    BgCompensation d = doc(t, "1050.00", LocalDate.of(2026, 3, 15));
    d.setBgQuantity(new BigDecimal("5"));
    d.setBgBaseWorkingDays(21);

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);

    assertEquals(new BigDecimal("250.00"), d.getBgComputedAmount(), "1050 / 21 × 5");
  }

  @Test
  @DisplayName("3б · без брой работни дни сумата в дни е НЕИЗВЕСТНА, не нула")
  void dayBasedWithoutDivisorIsUnknown() {
    BgCompensationType t = type("comp_kt_224_1", "чл. 224 ал. 1 КТ", "UNUSED_LEAVE_DAYS", null);
    BgCompensation d = doc(t, "1050.00", LocalDate.of(2026, 3, 15));
    d.setBgQuantity(new BigDecimal("5"));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);

    // Самото правило връща null — „не знам“, а не нула.
    assertNull(
        BgCompensationResolver.compute(
            "UNUSED_LEAVE_DAYS", new BigDecimal("5"), new BigDecimal("1050.00"), null));
    // ⚠️ Но върху ЕНТИТЕТА двете не се различават: генерираният getter за decimal връща ZERO, а не
    // null (виж axelor-decimal-getter-zero). Затова липсващата сума се лови при потвърждаване, а не
    // с проверка за null — на екрана „не знам“ изглежда като 0,00 и това не може да се поправи тук.
    assertEquals(0, d.getBgComputedAmount().signum(), "неизвестното се показва като нула");
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("празна"), e.getMessage());
  }

  // ── 4 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4 · чл. 220 — неспазено предизвестие в РАБОТНИ дни")
  void noticeDays() {
    BgCompensationType t = type("comp_kt_220_1", "чл. 220 ал. 1 КТ", "NOTICE_DAYS", null);
    BgCompensation d = doc(t, "1050.00", LocalDate.of(2026, 3, 15));
    // Едномесечно предизвестие, изразено в работните дни на месеца — СЪЩАТА единица като делителя.
    d.setBgQuantity(new BigDecimal("21"));
    d.setBgBaseWorkingDays(21);

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);

    assertEquals(
        new BigDecimal("1050.00"),
        d.getBgComputedAmount(),
        "едномесечно предизвестие дава РОВНО едно месечно възнаграждение");
  }

  @Test
  @DisplayName("4б · смесени единици плащат 143 на сто — затова единицата е обявена")
  void mixingWorkingAndCalendarDaysOverpays() {
    // 30 КАЛЕНДАРНИ дни срещу делител в 21 РАБОТНИ дни. Числото изглежда правдоподобно и е грешно:
    // едномесечно предизвестие при месечно БТВ 1050 не струва 1500.
    BigDecimal mixed =
        BgCompensationResolver.compute(
            "NOTICE_DAYS", new BigDecimal("30"), new BigDecimal("1050.00"), 21);
    assertEquals(new BigDecimal("1500.00"), mixed, "ето какво дава смесването");

    BigDecimal consistent =
        BgCompensationResolver.compute(
            "NOTICE_DAYS", new BigDecimal("21"), new BigDecimal("1050.00"), 21);
    assertEquals(new BigDecimal("1050.00"), consistent);
    assertTrue(
        mixed.compareTo(consistent) > 0,
        "смесените единици ВИНАГИ надплащат — работните дни са по-малко от календарните");
  }

  // ── 4в · двустъпковото правило по чл. 220 ───────────────────────────────

  @Test
  @DisplayName("4в · един и същ срок дава РАЗЛИЧНИ работни дни според това кога тече")
  void theSameNoticePeriodIsWorthDifferentAmountsDependingOnWhenItRuns() {
    BgCompensationType t = type("comp_kt_220_1", "чл. 220 ал. 1 КТ", "NOTICE_DAYS", null);
    Service s = new Service(false);

    // 01.04.2026 + 30 календарни дни → до 30.04. Двайсет и два делника, минус Велики петък (10.04)
    // и Велики понеделник (13.04) — Великден (12.04) е неделя и без това не се брои.
    BgCompensation april = doc(t, "1050.00", LocalDate.of(2026, 5, 15));
    april.setBgNoticeStartDate(LocalDate.of(2026, 4, 1));
    s.prepare(april);
    assertEquals(30, april.getBgNoticeCalendarDays(), "чл. 326 ал. 2 — 30 дни по подразбиране");
    assertEquals(new BigDecimal("20"), april.getBgQuantity(), "работните дни в срока, а не 30");

    // 01.12.2026 + 30 календарни дни → до 30.12. Същите 22 делника, но три празника: 24, 25 и 28.
    BgCompensation december = doc(t, "1050.00", LocalDate.of(2027, 1, 15));
    december.setBgNoticeStartDate(LocalDate.of(2026, 12, 1));
    s.prepare(december);
    assertEquals(new BigDecimal("19"), december.getBgQuantity());

    assertNotEquals(
        april.getBgQuantity(),
        december.getBgQuantity(),
        "ако двете съвпадат, изводът е счупен — срокът е календарен, работните дни в него не са");

    // и оттам различни суми за един и същ срок. Делителят е ДРУГО число: отработените дни в месеца
    // на базата (НСОРЗ чл. 18 ал. 1), а не работните дни в срока (чл. 19 ал. 1).
    april.setBgBaseWorkingDays(21);
    december.setBgBaseWorkingDays(21);
    s.recompute(april);
    s.recompute(december);
    assertEquals(new BigDecimal("1000.00"), april.getBgComputedAmount(), "1050 / 21 × 20");
    assertEquals(new BigDecimal("950.00"), december.getBgComputedAmount(), "1050 / 21 × 19");
  }

  @Test
  @DisplayName("4г · без календар за годината резултатът е само пн–пт, тоест завишен")
  void withoutACalendarTheResultIsOnlyWeekdays() {
    // Празниците ги няма → 22 делника вместо 20. Числото е ЗАВИШЕНО и това е видимо.
    assertEquals(
        22,
        BgCompensationResolver.workingDaysIn(
            LocalDate.of(2026, 4, 1), 30, java.util.Collections.emptySet()));
    assertEquals(
        20,
        BgCompensationResolver.workingDaysIn(
            LocalDate.of(2026, 4, 1),
            30,
            new java.util.HashSet<>(
                java.util.Arrays.asList(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 13)))));

    // Срок, който минава в следваща година: 15.12.2026 + 30 дни свършва на 13.01.2027. ⚠️ ТОВА
    // ЧИСЛО ЗАВИСИ ОТ КАЛЕНДАРА ЗА 2027 г.: без него излизат 19, защото 01.01.2027 (петък, Нова
    // година) не се приспада. Верните са 18 — и разликата от един ден е тихо надплащане.
    Service s = new Service(false);
    BgCompensationType t = type("comp_kt_220_1", "чл. 220 ал. 1 КТ", "NOTICE_DAYS", null);
    BgCompensation crossing = doc(t, "1050.00", LocalDate.of(2027, 2, 15));
    crossing.setBgNoticeStartDate(LocalDate.of(2026, 12, 15));
    s.prepare(crossing);
    assertEquals(
        new BigDecimal("18"),
        crossing.getBgQuantity(),
        "18 — 01.01.2027 вече е в календара и се приспада");
  }

  @Test
  @DisplayName("4д · въведеното количество бие изведеното, но разминаването се казва")
  void aManualQuantityWinsButTheMismatchIsAnnounced() {
    BgCompensationType t = type("comp_kt_220_1", "чл. 220 ал. 1 КТ", "NOTICE_DAYS", null);
    Service s = new Service(false);
    BgCompensation d = doc(t, "1050.00", LocalDate.of(2026, 5, 15));
    d.setBgNoticeStartDate(LocalDate.of(2026, 4, 1));
    d.setBgQuantity(new BigDecimal("15")); // ТРЗ знае за неплатен отпуск в срока
    s.prepare(d);

    assertEquals(new BigDecimal("15"), d.getBgQuantity(), "въведеното НЕ се презаписва");
    String warning = BgCompensationResolver.noticeQuantityWarning(d.getBgQuantity(), 20);
    assertNotNull(warning);
    assertTrue(warning.contains("15") && warning.contains("20"), warning);
    assertNull(BgCompensationResolver.noticeQuantityWarning(new BigDecimal("20"), 20));
  }

  // ── 5 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5 · ЗАМРАЗЯВАНЕ: промяна в каталога след потвърждаване не пипа документа")
  void confirmingFreezesTheDocument() throws Exception {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);
    s.confirm(d);

    assertEquals(BgCompensationResolver.CONFIRMED, d.getStatusSelect());
    assertTrue(d.getBgIsTaxable(), "данъчното третиране е ПРЕПИСАНО при потвърждаване");
    assertTrue(d.getBgSocialContributionsDue());

    // Каталогът се променя СЛЕД потвърждаването: два месеца вместо един, и вече необлагаемо.
    t.setBaseMonths(new BigDecimal("2.00"));
    t.setIsTaxable(false);
    t.setSocialContributionsDue(false);
    s.prepare(d);
    s.recompute(d);

    assertEquals(new BigDecimal("1450.00"), d.getBgComputedAmount(), "сумата НЕ се преизчислява");
    assertEquals(new BigDecimal("1450.00"), d.getBgFinalAmount());
    assertEquals(new BigDecimal("1.00"), d.getBgQuantity(), "количеството също е замразено");
    assertTrue(d.getBgIsTaxable(), "режимът е този от деня на подписа");
    assertTrue(d.getBgSocialContributionsDue());
  }

  // ── 6 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("6 · корекция НАДОЛУ по чл. 222 се блокира — чл. 228 ал. 2 КТ")
  void downwardAdjustmentIsRefusedWhereTheLawSetsAMinimum() {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);
    d.setBgFinalAmount(new BigDecimal("1000.00"));
    d.setBgAdjustmentReason("по договорка");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("чл. 228 ал. 2"), e.getMessage());

    // Нагоре е позволено — законовият размер е минимум, не таван.
    d.setBgFinalAmount(new BigDecimal("2000.00"));
    assertNull(
        BgCompensationResolver.refuseAdjustment(
            t, d.getBgComputedAmount(), d.getBgFinalAmount(), "уговорено в трудовия договор"));
  }

  // ── 7 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("7 · корекция без причина се блокира")
  void adjustmentWithoutReasonIsRefused() {
    BgCompensationType t = type("comp_kt_331", "чл. 331 КТ", "BTV_MONTHS", "4.00");
    BgCompensation d = doc(t, "1000.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);
    d.setBgFinalAmount(new BigDecimal("5000.00")); // нагоре, но без причина

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("причината за корекцията"), e.getMessage());

    d.setBgAdjustmentReason("договорено при прекратяването по чл. 331");
    assertNull(
        BgCompensationResolver.refuseAdjustment(
            t, d.getBgComputedAmount(), d.getBgFinalAmount(), d.getBgAdjustmentReason()));
  }

  // ── 8 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("8 · чл. 228 ал. 3 — 15.03.2026 дава срок 30.04.2026")
  void dueDateIsTheLastDayOfTheFollowingMonth() {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    service().prepare(d);

    assertEquals(LocalDate.of(2026, 4, 30), d.getBgDueDate());
    assertEquals(LocalDate.of(2026, 2, 1), d.getBgBaseMonth(), "чл. 228 ал. 1 — предходният месец");

    // февруари е късият месец, а декември мени и годината
    assertEquals(
        LocalDate.of(2026, 2, 28), BgCompensationResolver.dueDateFor(LocalDate.of(2026, 1, 10)));
    assertEquals(
        LocalDate.of(2027, 1, 31), BgCompensationResolver.dueDateFor(LocalDate.of(2026, 12, 31)));
    assertEquals(
        LocalDate.of(2025, 12, 1), BgCompensationResolver.baseMonthFor(LocalDate.of(2026, 1, 10)));

    assertFalse(
        BgCompensationResolver.isOverdue(d.getBgDueDate(), null, LocalDate.of(2026, 4, 30)));
    assertTrue(BgCompensationResolver.isOverdue(d.getBgDueDate(), null, LocalDate.of(2026, 5, 1)));
    assertFalse(
        BgCompensationResolver.isOverdue(
            d.getBgDueDate(), LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1)),
        "платеният документ не е просрочен");
  }

  // ── 9 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("9 · CONFIRMED → DRAFT се отказва")
  void thereIsNoWayBackFromConfirmed() {
    String refusal =
        BgCompensationResolver.refuseTransition(
            BgCompensationResolver.CONFIRMED, BgCompensationResolver.DRAFT);
    assertNotNull(refusal);
    assertTrue(refusal.contains("откажете го и направете нов"), refusal);

    assertNull(
        BgCompensationResolver.refuseTransition(
            BgCompensationResolver.CONFIRMED, BgCompensationResolver.CANCELLED),
        "отказът е единственият изход");
    assertNotNull(
        BgCompensationResolver.refuseTransition(
            BgCompensationResolver.CANCELLED, BgCompensationResolver.DRAFT),
        "отказаният не се връща в работа");
  }

  // ── допълнителни, подсказани от заданието ───────────────────────────────

  @Test
  @DisplayName("Отказът иска причина, а изплащането — дата")
  void cancellingNeedsAReasonAndPayingNeedsADate() throws Exception {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService s = service();
    s.prepare(d);
    s.recompute(d);
    s.confirm(d);

    AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(d));
    assertTrue(e.getMessage().contains("причина"), e.getMessage());

    AxelorException p = assertThrows(AxelorException.class, () -> s.pay(d));
    assertTrue(p.getMessage().contains("дата"), p.getMessage());

    d.setBgPaidDate(LocalDate.of(2026, 4, 20));
    s.pay(d);
    assertEquals(BgCompensationResolver.PAID, d.getStatusSelect());
  }

  @Test
  @DisplayName("requiresProof иска файл или бележка")
  void proofIsRequiredWhereTheCatalogueSaysSo() throws Exception {
    BgCompensationType t = type("comp_kt_222_1", "чл. 222 ал. 1 КТ", "BTV_MONTHS", "1.00");
    t.setRequiresProof(true);
    BgCompensation d = doc(t, "1450.00", LocalDate.of(2026, 3, 15));

    BgCompensationService noFile = new Service(false);
    noFile.prepare(d);
    noFile.recompute(d);

    AxelorException e = assertThrows(AxelorException.class, () -> noFile.confirm(d));
    assertTrue(e.getMessage().contains("доказване"), e.getMessage());

    d.setNote("Представена е декларация за оставане без работа от 20.04.2026 г.");
    noFile.confirm(d);
    assertEquals(BgCompensationResolver.CONFIRMED, d.getStatusSelect());
  }

  @Test
  @DisplayName("Актът не може да твърди друго число от плащанията по правото")
  void theActMustAgreeWithThePaymentsOnTheEntitlement() {
    // Право без плащания не казва нищо за размера — то е „полага се“, не „платено е“.
    assertNull(BgCompensationResolver.refuseAgainstEntitlement(new BigDecimal("1450.00"), null));
    assertNull(
        BgCompensationResolver.refuseAgainstEntitlement(
            new BigDecimal("1450.00"), new BigDecimal("1450.00")));

    String refusal =
        BgCompensationResolver.refuseAgainstEntitlement(
            new BigDecimal("1450.00"), new BigDecimal("1200.00"));
    assertNotNull(refusal);
    assertTrue(refusal.contains("1450.00") && refusal.contains("1200.00"), refusal);

    // Частичното плащане също спира потвърждаването: две числа за едни пари не се изглаждат тихо.
    assertNotNull(
        BgCompensationResolver.refuseAgainstEntitlement(
            new BigDecimal("1450.00"), new BigDecimal("725.00")));
  }

  @Test
  @DisplayName("Некоригируема сума не се коригира, дори нагоре")
  void nonAdjustableAmountStaysAsComputed() {
    BgCompensationType t = type("comp_kt_224_1", "чл. 224 ал. 1 КТ", "BTV_MONTHS", "1.00");
    t.setIsAmountAdjustable(false);

    String refusal =
        BgCompensationResolver.refuseAdjustment(
            t, new BigDecimal("100.00"), new BigDecimal("120.00"), "искам така");
    assertNotNull(refusal);
    assertTrue(refusal.contains("не подлежи на ръчна корекция"), refusal);
  }
}
