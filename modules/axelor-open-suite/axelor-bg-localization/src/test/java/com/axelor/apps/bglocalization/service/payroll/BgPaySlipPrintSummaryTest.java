package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЪБОВЕТЕ НА ПЕЧАТНИЯ ФИШ — какво излиза на хартия, когато фишът НЕ е образцов.
 *
 * <p><b>⚠️ ЧИСЛАТА НА ПРАВИЛНИЯ ФИШ СЕ СВЕРЯВАТ ДРУГАДЕ.</b> {@code
 * BgPaySlipLinesGoldenTest#thePrintedSlipSplitsTheTwoContributionSides} ги проверява върху фиш,
 * сглобен от {@code prepare()} — там 206,70 и 285,30 идват от генераторите, не от теста. Тук
 * фишовете са построени НА РЪКА нарочно: въпросът не е колко е сборът, а какво прави разпечатката,
 * когато редът е непълен, неразпознат или чужд на периода.
 *
 * <p><b>⚠️ И ЗАЩО ВСЯКО ОТ ТЕЗИ СЛУЧАЙ ЗАСЛУЖАВА СВИДЕТЕЛ.</b> Всеки от тях днес рендира БЕЗ
 * грешка. Разпечатка, която мълчи за тях, е лист, на който редовете не се събират до тоталите — и
 * човекът, който я чете, няма как да разбере защо.
 */
class BgPaySlipPrintSummaryTest {

  // ── ръбът: нулево перо ──────────────────────────────────────────────────

  /**
   * ⚠️ НУЛАТА Е СТОЙНОСТ, А НЕ ЛИПСА. Перо със сума нула е законно (вноска, която не се дължи този
   * месец) и трябва да мине през сверката, без да я поваля и без да я задейства.
   */
  @Test
  @DisplayName("⚠️ РЪБ · перо с нула минава без находка и без срив")
  void aZeroAmountLineIsNotAFinding() {
    BgPaySlip slip = slip("1000.00", "900.00", "1000.00", "100.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 501, "Пенсии — лице", BgPaySlipResolver.DEBIT, "0.00");
    add(slip, 700, "Данък по чл. 42 ЗДДФЛ", BgPaySlipResolver.DEBIT, "100.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(summary.getEmployeeContributions()),
        "нулевата вноска дава нула, а не липсваща стойност");
    assertTrue(
        summary.getFindings().isEmpty(),
        "⚠️ Находка върху нулево перо би направила всяка законна нула червена — тоест би научила"
            + " окото да прескача предупреждението.\n  "
            + String.join("\n  ", summary.getFindings()));
    assertEquals("", summary.report(), "празният доклад е празен низ, не „null“ върху хартията");
  }

  /** ⚠️ Фиш без нито едно перо и фиш, който изобщо го няма — разпечатката не гърми. */
  @Test
  @DisplayName("⚠️ РЪБ · празен фиш и липсващ фиш не хвърлят")
  void theEmptyAndTheMissingSlipDoNotThrow() {
    BgPaySlipPrintSummary empty = BgPaySlipPrintSummary.of(new BgPaySlip());
    assertEquals(0, BigDecimal.ZERO.compareTo(empty.getEmployeeContributions()));
    assertEquals(0, BigDecimal.ZERO.compareTo(empty.getEmployerContributions()));
    assertTrue(empty.getFindings().isEmpty(), "три нули си съвпадат — няма какво да се докладва");

    BgPaySlipPrintSummary none = BgPaySlipPrintSummary.of(null);
    assertEquals(0, BigDecimal.ZERO.compareTo(none.getTaxFromLines()));
    assertTrue(none.getFindings().isEmpty());
  }

  // ── неразпознатият ред ──────────────────────────────────────────────────

  /**
   * ⚠️ ПЕРО БЕЗ ЗНАК НЕ ВЛИЗА В НИТО ЕДИН СБОР. {@code BgPaySlipResolver} пита знака, а не гледа
   * сумата — тоест редът се печата, а тоталите не го знаят. Скрит, той прави лист, на който
   * колоната „Сума“ не се събира до нетото.
   */
  @Test
  @DisplayName("⚠️ РЪБ · перо с неразпознат знак се НАЗОВАВА, а не се крие")
  void anUnknownSignIsNamed() {
    BgPaySlip slip = slip("1000.00", "1000.00", "1000.00", "0.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 505, "Общо заболяване — лице", null, "50.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(1, summary.getFindings().size(), String.join("\n  ", summary.getFindings()));
    String finding = summary.getFindings().get(0);
    assertTrue(finding.contains("505"), "находката НАЗОВАВА перото: " + finding);
    assertTrue(finding.contains("няма разпознат знак"), finding);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(summary.getEmployeeContributions()),
        "⚠️ И НЕ СЕ БРОИ ТИХО: 50,00 тук значи, че знакът се е познал по номера на перото, тоест че"
            + " удръжка се извежда от нещо, което не я казва.");
  }

  /** ⚠️ Перо без номер: групата му е „никоя“, а нетото пак го брои — точно това се пише. */
  @Test
  @DisplayName("⚠️ РЪБ · перо без номер МЕНИ нетото, без да е в брутото — и листът го казва")
  void aLineWithoutANumberIsNamed() {
    BgPaySlip slip = slip("0.00", "25.00", "0.00", "0.00");
    add(slip, 0, "Непознато начисление", BgPaySlipResolver.CREDIT, "25.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(1, summary.getFindings().size(), String.join("\n  ", summary.getFindings()));
    assertTrue(summary.getFindings().get(0).contains("Перо без номер"));
    assertEquals(
        0,
        new BigDecimal("0.00").compareTo(BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList())),
        "безномерното перо НЕ е в брутото");
    assertEquals(
        0,
        new BigDecimal("25.00").compareTo(BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList())),
        "⚠️ но Е в нетото — разликата между двете е точно причината находката да съществува");
  }

  // ── ретро-редът ─────────────────────────────────────────────────────────

  /**
   * ⚠️ ДЕЛТА-РЕДЪТ ОТ РЕТРО-КОРЕКЦИЯ Е ПАРИ ЗА ДРУГ МЕСЕЦ. Той влиза в нетото (лицето ги получава
   * сега), но не в осигурителния доход и не в данъка на този фиш — те са декларирани с коригиращата
   * Д1/Д6 за ОНЗИ месец. Затова редът се маркира върху листа, а сверката НЕ се дразни от него.
   */
  @Test
  @DisplayName("⚠️ РЪБ · ретро-перото е в нетото, но не е вноска — и не поваля сверката")
  void aRetroLineIsMoneyForAnotherMonth() {
    BgPaySlip slip = slip("1000.00", "1100.00", "1000.00", "0.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 501, "Пенсии — лице", BgPaySlipResolver.DEBIT, "100.00");
    BgPaySlipLine retro =
        add(slip, 620, "Доплащане по корекция", BgPaySlipResolver.CREDIT, "200.00");
    retro.setBgCorrectsPeriod(period());

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertTrue(
        summary.getFindings().isEmpty(),
        "⚠️ Ретро-редът е НОРМАЛНО устройство, не дефект — находка тук би направила всеки коригиран"
            + " фиш червен.\n  "
            + String.join("\n  ", summary.getFindings()));
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(summary.getEmployeeContributions()),
        "доплащането не е вноска");
    assertFalse(
        BgPaySlipResolver.isOwnPeriod(retro),
        "⚠️ И маркерът трябва да ОСТАНЕ върху перото: от него колоната „Забележка“ пише „за минал"
            + " период“, а без надпис лицето вижда 200,00, за които този месец не е работило.");
  }

  // ── разминаването ───────────────────────────────────────────────────────

  /**
   * ⚠️ ЕДИНСТВЕНИЯТ СЛУЧАЙ, КОЙТО РАЗПЕЧАТКА САМО НА ЗАГЛАВНИТЕ СУМИ БИ СКРИЛА. Брутото, нетото и
   * разходът се пишат от {@code recompute()} като сборове на перата. Разминаване значи, че някой е
   * пипал перата и не е преизчислил — тоест че екранът и хартията твърдят различни неща, а всяко
   * число поотделно изглежда вярно.
   */
  @Test
  @DisplayName("⚠️ разминаване заглавна сума ↔ пера: ДВЕТЕ числа излизат върху листа")
  void aStaleHeaderTotalIsPrintedWithBothNumbers() {
    BgPaySlip slip = slip("1200.00", "900.00", "1000.00", "100.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 700, "Данък по чл. 42 ЗДДФЛ", BgPaySlipResolver.DEBIT, "100.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(1, summary.getFindings().size(), String.join("\n  ", summary.getFindings()));
    String finding = summary.getFindings().get(0);
    assertTrue(finding.startsWith("Бруто"), finding);
    assertTrue(finding.contains("1200.00"), "заглавната сума е НА ЛИСТА: " + finding);
    assertTrue(
        finding.contains("1000.00"),
        "⚠️ и сборът на перата ДО НЕЯ. Доклад с едно число не казва накъде е разликата: "
            + finding);
  }

  /**
   * ⚠️ ДАНЪКЪТ Е ЕДИНСТВЕНАТА ЗАГЛАВНА СУМА, КОЯТО НЕ Е СБОР НА ПЕРАТА — той се смята от основата и
   * ставката. Затова се сверява през СЪЩИЯ пазач, който блокира потвърждаването, а не през втора
   * проверка, преписана в отчета.
   */
  @Test
  @DisplayName("⚠️ данъкът се сверява през refuseTaxMismatch, не през преписано правило")
  void theTaxIsCheckedThroughTheSameGuard() {
    BgPaySlip slip = slip("1000.00", "900.00", "1000.00", "150.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 700, "Данък по чл. 42 ЗДДФЛ", BgPaySlipResolver.DEBIT, "100.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(1, summary.getFindings().size(), String.join("\n  ", summary.getFindings()));
    assertEquals(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        summary.getFindings().get(0),
        "⚠️ ДОСЛОВНО текстът на пазача. Собствена формулировка тук значи две описания на едно правило"
            + " — и те се разминават мълчаливо при първата поправка само на едното.");
  }

  /** ⚠️ Няколко находки се слепват с нов ред — така отчетът ги изписва една под друга. */
  @Test
  @DisplayName("докладът слепва находките с нов ред")
  void theReportJoinsFindingsWithNewlines() {
    BgPaySlip slip = slip("1200.00", "900.00", "1000.00", "150.00");
    add(slip, 100, "Основно възнаграждение", BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, 700, "Данък по чл. 42 ЗДДФЛ", BgPaySlipResolver.DEBIT, "100.00");

    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(2, summary.getFindings().size(), String.join("\n  ", summary.getFindings()));
    assertEquals(String.join("\n", summary.getFindings()), summary.report());
  }

  // ── помощници ───────────────────────────────────────────────────────────

  private static BgPaySlip slip(String gross, String net, String employerCost, String tax) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    slip.setBgGrossTotal(new BigDecimal(gross));
    slip.setBgNetTotal(new BigDecimal(net));
    slip.setBgEmployerCost(new BigDecimal(employerCost));
    slip.setBgTaxAmount(new BigDecimal(tax));
    return slip;
  }

  private static BgPaySlipLine add(
      BgPaySlip slip, int number, String name, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    lines.add(line);
    return line;
  }

  private static Period period() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 3, 1));
    period.setToDate(LocalDate.of(2026, 3, 31));
    return period;
  }
}
