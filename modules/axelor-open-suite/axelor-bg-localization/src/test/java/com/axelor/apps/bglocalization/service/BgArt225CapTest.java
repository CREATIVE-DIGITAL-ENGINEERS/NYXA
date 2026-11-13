package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * WS-3c · ШЕСТТЕ МЕСЕЦА ПРИ ЧЛ. 225 АЛ. 1 КТ СА ТАВАН, А НЕ БРОЙ ВНОСКИ.
 *
 * <p>ЦИТАТ, КТ чл. 225 ал. 1: „При незаконно уволнение работникът или служителят има право на
 * обезщетение от работодателя в размер на брутното му трудово възнаграждение за времето, през което
 * е останал без работа поради това уволнение, <b>но за не повече от 6 месеца</b>.“
 *
 * <p><b>Дефектът, който този клас заключва, има ДВЕ половини и никоя от двете не се обажда.</b>
 *
 * <ul>
 *   <li>{@code prepare()}, който предлага {@code baseMonths} = 6.00 като КОЛИЧЕСТВО за всеки нов
 *       документ, праща най-голямото възможно число към плащане, ако никой не го пипне;
 *   <li>без ограничение отгоре на въведеното количество 8 месеца дават 8 БТВ мълчаливо.
 * </ul>
 *
 * <p><b>⚠️ И ТРЕТА, ПО-КОВАРНА: чл. 225 ал. 1 е в {@code STATUTORY_MINIMUM}.</b> Тоест сваляне на
 * сумата под изчислената се отказва с довод по чл. 228 ал. 2 КТ — „законовият размер е МИНИМУМ“ —
 * дори когато изчислената сума сама е сгрешена. Операторът, останал без работа три месеца, би
 * получил 6 БТВ и НЕ БИ МОГЪЛ да свали числото, без да разбере, че истинската поправка е в ПЕРИОДА.
 * Затова двата пазача на тавана се питат ПРЕДИ него.
 *
 * <p>⚠️ Самото число 6.00 не се пише в кода — то си остава в каталога. В кода стои само знанието,
 * че при този вид {@code baseMonths} е ТАВАН, защото моделът няма как да го изрази: {@code baseKind
 * = BTV_MONTHS} значи „baseMonths е размерът“, а бележката на реда CT-10 казва обратното.
 */
class BgArt225CapTest {

  private static final BigDecimal GROSS = new BigDecimal("2000.00");
  private static final LocalDate EVENT = LocalDate.of(2026, 3, 15);

  // ── 1 · таванът се разпознава само там, където го има ───────────────────

  @Test
  @DisplayName("1 · чл. 225 ал. 1 има таван 6; чл. 222 ал. 2 и ал. 3 са ФИКСИРАН срок, не таван")
  void onlyArticleTwoTwentyFiveCarriesACap() {
    assertEquals(new BigDecimal("6.00"), BgCompensationResolver.statutoryMaximumMonths(art225()));

    // ⚠️ РАЗЛИКАТА Е В САМИЯ ТЕКСТ: „но за не повече от N месеца“ е таван, „за срок от N месеца“ е
    // размер. Чл. 222 ал. 2 и ал. 3 са второто, тоест техните 2.00 и 6.00 са верни както са.
    assertNull(
        BgCompensationResolver.statutoryMaximumMonths(
            type("comp_kt_222_2", "чл. 222 ал. 2 КТ", "2.00")),
        "„за срок от 2 месеца“ е фиксиран размер");
    assertNull(
        BgCompensationResolver.statutoryMaximumMonths(
            type("comp_kt_222_3", "чл. 222 ал. 3 КТ", "2.00")));
    assertNull(
        BgCompensationResolver.statutoryMaximumMonths(type("comp_kt_331", "чл. 331 КТ", "4.00")),
        "чл. 331 ал. 2 е „не по-малко от“ — ДОЛНА граница, тя живее в STATUTORY_MINIMUM");
    assertNull(BgCompensationResolver.statutoryMaximumMonths(null));
  }

  // ── 2 · количеството не се предлага, когато е таван ─────────────────────

  @Test
  @DisplayName("⚠️ 2 · ГЕЙТ · prepare() НЕ предлага 6.00 при чл. 225 — таванът не е стойност")
  void prepareDoesNotPrefillACapAsAQuantity() {
    BgCompensation d = doc(art225());
    service().prepare(d);

    assertEquals(
        0,
        d.getBgQuantity().signum(),
        "⚠️ количеството остава празно: колко месеца лицето е било без работа е ФАКТ, който модулът"
            + " не знае, а предложеният таван е най-голямото възможно число");

    // а при фиксиран размер предложението си остава — това е заварено поведение и не се пипа
    BgCompensation fixed = doc(type("comp_kt_222_3", "чл. 222 ал. 3 КТ", "2.00"));
    service().prepare(fixed);
    assertEquals(new BigDecimal("2.00"), fixed.getBgQuantity(), "чл. 222 ал. 3 не е таван");
  }

  // ── 3 · три месеца → 3 БТВ, не 6 ────────────────────────────────────────

  @Test
  @DisplayName("⚠️ 3 · ГЕЙТ · три месеца без работа дават 3 × БТВ, а не 6 × БТВ")
  void threeMonthsWithoutWorkPayThreeGrossSalariesAndNotSix() throws Exception {
    BgCompensationService s = service();
    BgCompensation d = doc(art225());
    s.prepare(d);
    d.setBgQuantity(new BigDecimal("3")); // действителният период, въведен от ТРЗ
    s.recompute(d);

    assertEquals(new BigDecimal("6000.00"), d.getBgComputedAmount(), "3 × 2000,00");
    assertEquals(new BigDecimal("6000.00"), d.getBgFinalAmount());
    assertEquals(
        1,
        new BigDecimal("12000.00").compareTo(d.getBgFinalAmount()),
        "⚠️ 12000,00 е числото, което старото подразбиране произвеждаше — 6 × БТВ. Ако някога"
            + " съвпадне с изчисленото, подразбирането се е върнало.");

    s.confirm(d);
    assertEquals(BgCompensationResolver.CONFIRMED, d.getStatusSelect());
  }

  // ── 4 · осем месеца → отказ, не 8 БТВ ───────────────────────────────────

  @Test
  @DisplayName("⚠️ 4 · ГЕЙТ · осем месеца се ОТКАЗВАТ — таванът е 6, а не отправна точка")
  void eightMonthsAreRefusedInsteadOfSilentlyPayingEight() {
    BgCompensationService s = service();
    BgCompensation d = doc(art225());
    s.prepare(d);
    d.setBgQuantity(new BigDecimal("8"));
    s.recompute(d);

    // ⚠️ Сумата СЕ ИЗЧИСЛЯВА — 16000,00. Точно затова отказът е нужен: числото е правдоподобно и
    // нищо друго не го оспорва.
    assertEquals(new BigDecimal("16000.00"), d.getBgComputedAmount());

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("надхвърля законовия таван"), e.getMessage());
    assertTrue(e.getMessage().contains("чл. 225 ал. 1 КТ"), e.getMessage());
    assertTrue(e.getMessage().contains("не повече от 6 месеца"), e.getMessage());
    assertTrue(
        e.getMessage().contains("по-ГОЛЯМ размер по КТД, но не и по-ДЪЛЪГ срок"),
        "⚠️ казва и защо чл. 228 ал. 2 НЕ е насрещен довод: " + e.getMessage());
    assertEquals(BgCompensationResolver.DRAFT, d.getStatusSelect(), "остава чернова");

    // ровно на тавана минава — границата е включваща („не повече от 6“)
    assertNull(
        BgCompensationResolver.refuseAboveStatutoryMaximum(
            art225(), BgCompensationResolver.BTV_MONTHS, new BigDecimal("6.00")));
    assertNotNull(
        BgCompensationResolver.refuseAboveStatutoryMaximum(
            art225(), BgCompensationResolver.BTV_MONTHS, new BigDecimal("6.01")));
  }

  // ── 5 · празното количество се отказва с ИМЕТО на липсващото ────────────

  @Test
  @DisplayName("5 · празният период се отказва с обяснение, а не със „сумата е празна“")
  void theMissingActualPeriodIsRefusedByName() {
    BgCompensationService s = service();
    BgCompensation d = doc(art225());
    s.prepare(d);
    s.recompute(d);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("Количеството е празно"), e.getMessage());
    assertTrue(e.getMessage().contains("ДЕЙСТВИТЕЛНОТО време без работа"), e.getMessage());
    assertTrue(
        e.getMessage().contains("най-много 6"),
        "⚠️ казва и тавана — иначе най-естественото, което операторът ще направи, е да върне 6.00"
            + " обратно в полето: "
            + e.getMessage());
  }

  // ── 6 · единиците не се смесват ─────────────────────────────────────────

  @Test
  @DisplayName("6 · таванът в МЕСЕЦИ не съди количество в РАБОТНИ ДНИ")
  void theCapInMonthsNeverJudgesAQuantityInWorkingDays() {
    // 20 работни дни неспазено предизвестие не са „20 месеца“. Пазачът се пита само при BTV_MONTHS.
    assertNull(
        BgCompensationResolver.refuseAboveStatutoryMaximum(
            art225(), BgCompensationResolver.NOTICE_DAYS, new BigDecimal("20")));
    assertNull(
        BgCompensationResolver.refuseAboveStatutoryMaximum(
            type("comp_kt_220_1", "чл. 220 ал. 1 КТ", null),
            BgCompensationResolver.NOTICE_DAYS,
            new BigDecimal("20")));
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgCompensationType art225() {
    return type("comp_kt_225_1", "чл. 225 ал. 1 КТ", "6.00");
  }

  private static BgCompensationType type(String code, String legalBasis, String months) {
    BgCompensationType t = new BgCompensationType();
    t.setCode(code);
    t.setName(legalBasis);
    t.setLegalBasis(legalBasis);
    t.setBaseKind(BgCompensationResolver.BTV_MONTHS);
    t.setBaseMonths(months == null ? null : new BigDecimal(months));
    t.setBgDirection("EMPLOYER_TO_EMPLOYEE");
    t.setIsAmountAdjustable(true);
    t.setRequiresProof(false);
    t.setIsTaxable(true);
    t.setSocialContributionsDue(true);
    return t;
  }

  private static BgCompensation doc(BgCompensationType type) {
    BgCompensation d = new BgCompensation();
    d.setStatusSelect(BgCompensationResolver.DRAFT);
    d.setBgCompensationType(type);
    d.setBgEventDate(EVENT);
    d.setBgBaseMonthGross(GROSS);
    // Чл. 225 ал. 1 се признава по влязло в сила решение — фактът е налице, за да се стигне до
    // тавана, който тук се проверява (BgCompensationResolver#refuseMissingFacts).
    d.setBgCourtDecisionNumber("1234/2026");
    d.setBgCourtDecisionEffectiveDate(EVENT.plusMonths(2));
    return d;
  }

  /** Услугата без база — записът е без ефект, прикачен файл няма. */
  private static BgCompensationService service() {
    return new BgCompensationService() {
      @Override
      protected boolean hasAttachment(BgCompensation doc) {
        return false;
      }

      @Override
      protected BigDecimal paidOnEntitlement(BgCompensation doc) {
        return null;
      }

      @Override
      protected void save(BgCompensation doc) {}
    };
  }
}
