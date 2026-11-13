package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ДВЕТЕ ЧИСЛА, КОИТО ФИШЪТ НЕ ПАЗИ, И ДОКЛАДЪТ ЗА СВЕРКАТА — чиста функция върху един фиш.
 *
 * <p><b>⚠️ ТУК НЕ СЕ СМЯТА НИЩО НОВО.</b> Печатният образец рендерира вече сметнатите стойности:
 * шестте заглавни суми се четат направо от реда на фиша. Разделянето на вноските на две страни не е
 * нова сметка, а СБОР НА СОБСТВЕНИТЕ МУ ПЕРА — през същите {@link BgPaySlipResolver} функции, които
 * пълнят заглавната част. Затова класът няма нито една своя ставка и нито един свой закръглящ
 * режим.
 *
 * <p><b>⚠️ ЗАЩО ОТДЕЛЕН КЛАС, А НЕ МЕТОД НА УСЛУГАТА ЗА ПЕЧАТ.</b> Услугата вика {@code
 * ReportFactory}, тоест иска BIRT и жива инсталация. Свидетелят за ЧИСЛАТА трябва да тече без тях —
 * инак единственото твърдение за сумите на фиша щеше да се пропуска на всяка машина без база. Тук
 * няма нито един внос от {@code com.axelor.apps.report}.
 *
 * <p><b>⚠️ И ЗАЩО ДОКЛАДЪТ ЗА СВЕРКАТА СЕ ПЕЧАТА, А НЕ СЕ МЪЛЧИ.</b> Три от шестте заглавни суми
 * (бруто, нето, разход за работодателя) са ПО УСТРОЙСТВО сборове на перата — {@code
 * BgPaySlipService#recompute} ги пише точно така. Разминаване значи, че перата са пипани след
 * последното преизчисляване, тоест че хартията и екранът твърдят различни неща. Разпечатка, която
 * показва само заглавните суми, би скрила точно този случай: всяко число поотделно изглежда вярно.
 * Затова несъответствието влиза В САМИЯ ОТЧЕТ, с ДВЕТЕ числа, а не в журнал, който никой не чете.
 *
 * <p>Осигурителният доход и данъчната основа НЕ се сверяват срещу перата и това е нарочно: първият
 * минава през МОД и тавана, втората — през месечното данъчно облекчение. И двете са заглавни
 * РЕЗУЛТАТИ, не сборове, и „разминаване“ там би светвало червено на всеки верен фиш.
 */
public final class BgPaySlipPrintSummary {

  private final BigDecimal employeeContributions;
  private final BigDecimal employerContributions;
  private final BigDecimal taxFromLines;
  private final List<String> findings;

  private BgPaySlipPrintSummary(
      BigDecimal employeeContributions,
      BigDecimal employerContributions,
      BigDecimal taxFromLines,
      List<String> findings) {
    this.employeeContributions = employeeContributions;
    this.employerContributions = employerContributions;
    this.taxFromLines = taxFromLines;
    this.findings = findings;
  }

  public static BgPaySlipPrintSummary of(BgPaySlip slip) {
    List<BgPaySlipLine> lines =
        slip == null || slip.getBgPaySlipLineList() == null
            ? Collections.emptyList()
            : slip.getBgPaySlipLineList();

    return new BgPaySlipPrintSummary(
        BgPaySlipResolver.employeeContributions(lines),
        BgPaySlipResolver.employerContributions(lines),
        BgPaySlipResolver.taxFromLines(lines),
        findingsOf(slip, lines));
  }

  /** Вноските, УДЪРЖАНИ от лицето — перата 5xx със знак DEBIT за собствения период. */
  public BigDecimal getEmployeeContributions() {
    return employeeContributions;
  }

  /** Вноските ЗА СМЕТКА НА РАБОТОДАТЕЛЯ — перата 5xx със знак CREDIT. */
  public BigDecimal getEmployerContributions() {
    return employerContributions;
  }

  /** Данъкът, както го носят ПЕРАТА (група 7xx, DEBIT, собствен период). */
  public BigDecimal getTaxFromLines() {
    return taxFromLines;
  }

  /** По един ред на несъответствие. Празен списък значи „хартията и перата казват едно и също“. */
  public List<String> getFindings() {
    return Collections.unmodifiableList(findings);
  }

  /** Докладът за печат — редовете, слепени с нов ред. Празен низ, когато няма какво да се каже. */
  public String report() {
    return String.join("\n", findings);
  }

  // ── сверката ────────────────────────────────────────────────────────────

  private static List<String> findingsOf(BgPaySlip slip, List<BgPaySlipLine> lines) {
    List<String> out = new ArrayList<>();
    if (slip == null) {
      return out;
    }

    compare(out, "Бруто", slip.getBgGrossTotal(), BgPaySlipResolver.grossTotal(lines));
    compare(out, "Нето", slip.getBgNetTotal(), BgPaySlipResolver.netTotal(lines));
    compare(
        out,
        "Разход за работодателя",
        slip.getBgEmployerCost(),
        BgPaySlipResolver.employerCost(lines));

    // ⚠️ Данъкът се сверява през СЪЩИЯ пазач, който блокира потвърждаването — не през втора,
    // преписана тук проверка. Две формулировки на едно правило се разминават мълчаливо, а тази
    // разлика е точно колкото данъка.
    String taxMismatch = BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), lines);
    if (taxMismatch != null) {
      out.add(taxMismatch);
    }

    for (BgPaySlipLine line : BgPaySlipResolver.ordered(lines)) {
      if (line == null) {
        continue;
      }
      // ⚠️ Перо с непознат знак НЕ влиза в нито един сбор — нито в нетото, нито в разхода. Скрито,
      // то прави разпечатка, на която редовете не се събират до тоталите, без нито една причина
      // върху хартията.
      if (!BgPaySlipResolver.CREDIT.equals(line.getBgSign())
          && !BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
        out.add(
            "Перо "
                + line.getBgLineNumber()
                + " ("
                + nz(line.getName())
                + ") няма разпознат знак („"
                + nz(line.getBgSign())
                + "“) — сумата му НЕ влиза в нито един тотал на този фиш.");
      }
      // ⚠️ Номерът решава ГРУПАТА, а групата решава в кой тотал влиза перото. Нула значи „никоя“ —
      // и това НЕ е същото като „никъде“: нетото събира всяко начисление извън 5xx, тоест
      // безномерното перо мени парите на лицето, без да се вижда в брутото.
      if (BgPaySlipResolver.groupOf(line) == 0) {
        out.add(
            "Перо без номер („"
                + nz(line.getName())
                + "“) не принадлежи на никоя група: то НЕ влиза в брутото, в осигурителния доход, в"
                + " данъчната основа и в разхода за работодателя, но МЕНИ нетото.");
      }
    }

    return out;
  }

  /**
   * ⚠️ Сравнява СЪС СКАЛА 2 и от двете страни. Заглавната сума е записана закръглена; сборът на
   * перата излиза закръглен от {@code BgPaySlipResolver}. Сравнение без изравняване на скалата би
   * обявило 1163,97 и 1163,970 за различни — тоест би светвало червено за нищо.
   */
  private static void compare(
      List<String> out, String what, BigDecimal header, BigDecimal fromLines) {
    BigDecimal left = scale(header);
    BigDecimal right = scale(fromLines);
    if (left.compareTo(right) == 0) {
      return;
    }
    out.add(
        what
            + ": заглавната сума ("
            + left
            + ") не съвпада със сбора на перата ("
            + right
            + "). Преизчислете фиша — перата са пипани след последното смятане.");
  }

  private static BigDecimal scale(BigDecimal value) {
    // ⚠️ Без проверка за null: генерираният getter за BigDecimal връща ZERO.
    return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
