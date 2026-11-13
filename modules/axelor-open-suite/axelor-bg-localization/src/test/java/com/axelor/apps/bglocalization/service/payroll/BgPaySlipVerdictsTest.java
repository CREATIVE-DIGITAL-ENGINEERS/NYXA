package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕВОДЪТ НА ПРИСЪДИТЕ И ШЕВЪТ ИМ КЪМ ФИША — чист тест, без база и без Guice.
 *
 * <p><b>Какво пази този клас.</b> Четирите генератора смятат откази с назована причина и {@code
 * BgPaySlipService} ги изкарва навън; тук се заковава, че преводът НЕ ГИ СЛИВА обратно. Най-лесната
 * грешка е точно тази: „законна нула“, „не се дължи“, „няма източник“ и „ОТКАЗ“ да получат един и
 * същ вид на екрана, защото и четирите не създават перо. Тогава списъкът „какво липсва“ или се
 * пълни с нормални състояния (и никой не го чете), или мълчи за отказите (и не казва нищо).
 *
 * <p>⚠️ ЧИСТ ТЕСТ, БЕЗ ПОДМЯНА: викат се истинските фабрики на четирите {@code Outcome} класа.
 * Падне ли, паднал е преводът, а не средата.
 */
class BgPaySlipVerdictsTest {

  private static final String STEP = BgPaySlipVerdicts.STEP_EARNINGS;

  private static BgPaySlip slip() {
    return new BgPaySlip();
  }

  // ═══ 1 · ОТКАЗЪТ ОЦЕЛЯВА ПРЕЗ ЧЕТИРИТЕ РЕЧНИКА ════════════════════════════

  @Test
  @DisplayName("⚠️ ОТКАЗ · и четирите речника дават refusal=true с ПРИЧИНАТА, и без сума")
  void aRefusalSurvivesTranslationFromEveryDictionary() {
    BgPaySlipVerdict earnings =
        BgPaySlipVerdict.of(STEP, BgEarningsOutcome.refused("pl_osnovna", "основната заплата"));
    BgPaySlipVerdict line =
        BgPaySlipVerdict.of(STEP, BgPaySlipLineOutcome.refused("pl_dod", "ставката на данъка"));
    BgPaySlipVerdict sick =
        BgPaySlipVerdict.of(
            STEP, BgSickLeaveOutcome.refused("pl_bolnichen", "осигурителният стаж"));
    BgPaySlipVerdict unpaid =
        BgPaySlipVerdict.of(
            STEP, "чл. 40 ал. 1", BgUnpaidLeaveHealthResolver.Outcome.refused("два повода"));

    for (BgPaySlipVerdict verdict : Arrays.asList(earnings, line, sick, unpaid)) {
      assertTrue(verdict.refusal, verdict.asNote());
      assertEquals(BgPaySlipVerdict.MARK_REFUSED, verdict.marker, verdict.asNote());
      assertNull(
          verdict.amount,
          "⚠️ сума при отказ рано или късно се сумира от някого заедно с истинските: "
              + verdict.asNote());
      assertNotNull(verdict.detail, "отказ без причина е тиха нула с маркер");
    }
    assertTrue(earnings.asNote().contains("основната заплата"), earnings.asNote());
    assertTrue(sick.asNote().contains("осигурителният стаж"), sick.asNote());
  }

  /**
   * ⚠️ СЪРЦЕВИНАТА НА ПРЕВОДА. И шестте състояния по-долу НЕ създават перо, точно както отказът.
   * Слети с него, списъкът „какво липсва“ щеше да носи по трийсет реда нормално състояние на всеки
   * фиш — тоест да стане нечетим, тоест отказите пак да са невидими.
   *
   * <p>FAIL-FIRST: върне ли се {@code refusal} като {@code true} за {@code LEGAL_ZERO} → списъкът
   * „какво липсва“ се пълни с ГВРС по ЗБДОО чл. 15 ал. 1 и тестът пада.
   */
  @Test
  @DisplayName(
      "⚠️ „не се създава перо“ НЕ Е отказ · законната нула и петте ѝ роднини не искат нищо")
  void theOtherEmptyOutcomesAreNotRefusals() {
    assertFalse(
        BgPaySlipVerdict.of(STEP, BgPaySlipLineOutcome.legalZero("pl_gvrs", "ставка 0,0000"))
            .refusal,
        "ГВРС с нулева ставка е ВЕРНИЯТ фиш");
    assertFalse(
        BgPaySlipVerdict.of(STEP, BgEarningsOutcome.notEntitled("pl_tspo", "НСОРЗ чл. 12 ал. 1"))
            .refusal,
        "правото го няма — никой не поправя нищо");
    assertFalse(
        BgPaySlipVerdict.of(STEP, BgEarningsOutcome.sourceMissing("pl_noshten", "WS-5")).refusal,
        "адресат е WS-5, не операторът");
    assertFalse(
        BgPaySlipVerdict.of(STEP, BgEarningsOutcome.notThisEngine("pl_rachno", "ръчно перо"))
            .refusal,
        "друг генератор пише това перо");
    assertFalse(
        BgPaySlipVerdict.of(STEP, BgSickLeaveOutcome.noEmployerDays("pl_bolnichen", "няма епизоди"))
            .refusal,
        "нула дни за осигурителя е нормалното състояние");
    assertFalse(
        BgPaySlipVerdict.of(
                STEP,
                "чл. 40 ал. 1",
                BgUnpaidLeaveHealthResolver.Outcome.notApplicable("няма неплатен отпуск"))
            .refusal,
        "хипотезата не се отнася — това е отговор, не липса");
  }

  /**
   * ⚠️ РАЗМИНАВАНЕТО НЕ Е ОТКАЗ И НОСИ СУМАТА СИ. Перото СЕ ЗАПИСВА по речника — прочетено като
   * отказ, то би пратило човек да поправя фиш, който е верен.
   */
  @Test
  @DisplayName("разминаването се обявява, но перото го има — и сумата стои в присъдата")
  void aDivergentLineKeepsItsAmountAndIsNotARefusal() {
    BgPaySlipVerdict verdict =
        BgPaySlipVerdict.of(
            STEP,
            BgPaySlipLineOutcome.divergent(
                "pl_obez_kt_222_1",
                new BigDecimal("100.00"),
                null,
                null,
                "документът твърди друго"));

    assertFalse(verdict.refusal);
    assertEquals(BgPaySlipVerdict.MARK_DIVERGENT, verdict.marker);
    assertEquals(new BigDecimal("100.00"), verdict.amount);
    assertTrue(verdict.asNote().contains("100.00"), verdict.asNote());
  }

  // ═══ 2 · ДВАТА СПИСЪКА ════════════════════════════════════════════════════

  /**
   * ⚠️ ДВАТА СПИСЪКА ОТГОВАРЯТ НА РАЗЛИЧНИ ВЪПРОСИ и точно затова са два.
   *
   * <p>FAIL-FIRST: напише ли се {@code asGaps()} като {@code asNotes()} → списъкът „какво липсва“
   * връща и трите реда и тестът пада на броя.
   */
  @Test
  @DisplayName("⚠️ „какво липсва“ носи САМО отказите, пълният доклад — всичко")
  void gapsCarryOnlyRefusalsWhileNotesCarryEverything() {
    BgPaySlipVerdicts verdicts =
        new BgPaySlipVerdicts()
            .addEarnings(
                BgPaySlipVerdicts.STEP_EARNINGS,
                Arrays.asList(
                    BgEarningsOutcome.computed("pl_osnovna", new BigDecimal("1500.00"), null, null),
                    BgEarningsOutcome.notEntitled("pl_tspo", "НСОРЗ чл. 12 ал. 1"),
                    BgEarningsOutcome.refused("pl_osnovna_2", "основната заплата не е попълнена")));

    assertEquals(3, verdicts.size());
    assertEquals(1, verdicts.refusalCount());
    assertEquals(3, verdicts.asNotes().split("\n").length, "пълният доклад носи всяка присъда");
    assertEquals(1, verdicts.asGaps().split("\n").length, "„какво липсва“ носи само отказа");
    assertTrue(verdicts.asGaps().contains("основната заплата не е попълнена"), verdicts.asGaps());
    assertFalse(verdicts.asGaps().contains("НСОРЗ"), "законното състояние няма работа тук");
    assertTrue(verdicts.asNotes().contains("НСОРЗ"), "но има работа в пълния доклад");
  }

  @Test
  @DisplayName("нула присъди дават празни текстове, а не реда „няма нищо“")
  void noVerdictsGiveEmptyText() {
    BgPaySlipVerdicts empty = new BgPaySlipVerdicts();
    assertEquals("", empty.asNotes());
    assertEquals("", empty.asGaps());
    assertEquals(0, empty.refusalCount());
  }

  // ═══ 3 · ШЕВЪТ КЪМ ФИША ═══════════════════════════════════════════════════

  /**
   * ⚠️ ШЕСТТЕ СТЪПКИ ПИШАТ ЕДНА СЛЕД ДРУГА В ЕДИН ОБХОД. Презапис вместо долепяне би оставил на
   * фиша доклада на ПОСЛЕДНАТА стъпка — тоест данъкът щеше да изтрива отказа на болничния.
   *
   * <p>FAIL-FIRST: напише ли се {@code appendInto} със {@code setBgGeneratorNotes(asNotes())} →
   * присъдата на болничния изчезва и тестът пада.
   */
  @Test
  @DisplayName("⚠️ втората стъпка ДОЛЕПЯ, не презаписва — и броячът се сумира")
  void stepsAppendRatherThanOverwrite() {
    BgPaySlip slip = slip();
    BgPaySlipVerdicts.reset(slip);

    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE,
            Collections.singletonList(
                BgSickLeaveOutcome.refused("pl_bolnichen", "осигурителният стаж не е подаден")))
        .appendInto(slip);
    new BgPaySlipVerdicts()
        .addLines(
            BgPaySlipVerdicts.STEP_INCOME_TAX,
            Collections.singletonList(
                BgPaySlipLineOutcome.refused("pl_dod", "ставката липсва в каталога")))
        .appendInto(slip);

    assertEquals(2, slip.getBgGeneratorRefusalCount().intValue());
    assertTrue(
        slip.getBgGeneratorNotes().contains("осигурителният стаж"), slip.getBgGeneratorNotes());
    assertTrue(slip.getBgGeneratorNotes().contains("ставката липсва"), slip.getBgGeneratorNotes());
    assertEquals(2, slip.getBgGeneratorGaps().split("\n").length, slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().contains(BgPaySlipVerdicts.STEP_SICK_LEAVE),
        "отказът НАЗОВАВА стъпката — иначе не се знае кое перо липсва: "
            + slip.getBgGeneratorGaps());
  }

  /**
   * ⚠️ ВТОРО {@code prepare()} НЕ НАТРУПВА. Оставен, старият отказ щеше да стои под новите присъди
   * — текст, който днешните данни вече отричат, до число, което вече е вярно.
   *
   * <p>FAIL-FIRST: махне ли се {@code reset} от {@code prepare()} → броячът става 2 и тестът пада.
   */
  @Test
  @DisplayName("⚠️ второ извеждане дава СЪЩИЯ доклад, не двоен")
  void aSecondPassResetsRatherThanAccumulates() {
    BgPaySlip slip = slip();

    BgPaySlipVerdicts.reset(slip);
    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE,
            Collections.singletonList(BgSickLeaveOutcome.refused("pl_bolnichen", "стажът")))
        .appendInto(slip);
    BgPaySlipVerdicts.seal(slip);
    String first = slip.getBgGeneratorNotes();

    BgPaySlipVerdicts.reset(slip);
    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE,
            Collections.singletonList(BgSickLeaveOutcome.refused("pl_bolnichen", "стажът")))
        .appendInto(slip);
    BgPaySlipVerdicts.seal(slip);

    assertEquals(first, slip.getBgGeneratorNotes(), "докладът е ИЗХОД, не дневник");
    assertEquals(1, slip.getBgGeneratorRefusalCount().intValue());
  }

  /**
   * ⚠️ ДАТАТА Е ОНОВА, КОЕТО РАЗЛИЧАВА „НУЛА ОТКАЗА“ ОТ „ГЕНЕРАТОРИТЕ НЕ СА ПУСКАНИ“. Броячът е
   * {@code Integer} и генерираният му getter връща 0 и за {@code null} — сам той не може да каже
   * кое от двете е.
   */
  @Test
  @DisplayName("⚠️ нула откази СЛЕД обход се различава от необходен фиш само по датата")
  void theTimestampIsWhatSeparatesCleanFromNeverRun() {
    BgPaySlip untouched = slip();
    assertNull(untouched.getBgGeneratedAt(), "необходен фиш няма възраст на присъдите");
    assertEquals(0, untouched.getBgGeneratorRefusalCount().intValue(), "и брояч 0 — същото число");

    BgPaySlip clean = slip();
    BgPaySlipVerdicts.reset(clean);
    new BgPaySlipVerdicts()
        .addEarnings(
            BgPaySlipVerdicts.STEP_EARNINGS,
            Collections.singletonList(
                BgEarningsOutcome.computed("pl_osnovna", new BigDecimal("1500.00"), null, null)))
        .appendInto(clean);
    BgPaySlipVerdicts.seal(clean);

    assertNotNull(clean.getBgGeneratedAt(), "минал обход ОСТАВЯ дата");
    assertEquals(0, clean.getBgGeneratorRefusalCount().intValue());
    assertEquals("", clean.getBgGeneratorGaps() == null ? "" : clean.getBgGeneratorGaps());
  }

  @Test
  @DisplayName("reset изтрива и текста, и брояча, и датата")
  void resetClearsEverything() {
    BgPaySlip slip = slip();
    new BgPaySlipVerdicts()
        .addSickLeave(
            BgPaySlipVerdicts.STEP_SICK_LEAVE,
            Collections.singletonList(BgSickLeaveOutcome.refused("pl_bolnichen", "стажът")))
        .appendInto(slip);
    BgPaySlipVerdicts.seal(slip);

    BgPaySlipVerdicts.reset(slip);

    assertNull(slip.getBgGeneratorNotes());
    assertNull(slip.getBgGeneratorGaps());
    assertNull(slip.getBgGeneratedAt());
    assertEquals(0, slip.getBgGeneratorRefusalCount().intValue());
  }
}
