package com.axelor.apps.bglocalization.service.payroll.ledger;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1Carrier;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipPrintSummary;
import com.axelor.apps.bglocalization.service.payroll.BgPersonalIdLabel;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * ЕДИН РЕД ОТ ВЕДОМОСТТА — ЕДНО ЛИЦЕ, ЕДИН ФИШ. Чиста функция, нула заявки.
 *
 * <p><b>⚠️ НИТО ЕДНО ЧИСЛО ТУК НЕ Е НОВО.</b> Петте пари на реда идват оттам, откъдето ги взима и
 * печатният ФИШ: брутото, данъкът, нетото и разходът за работодателя се четат ПРЯКО от заглавната
 * част на фиша, а разделянето на вноските на две страни минава през същите функции на {@link
 * BgPaySlipResolver}, които пълнят {@link BgPaySlipPrintSummary}. Ведомостта е ДРУГ ЛИСТ, а не
 * друга сметка: ред от нея и фишът на същото лице трябва да носят едни и същи числа, и това е
 * проверимо, защото източникът е един.
 *
 * <p><b>⚠️ ЗАЩО СЕ ГРАДИ ВЪРХУ {@link BgPaySlipPrintSummary}, А НЕ ПОКРАЙ НЕГО.</b> Двете страни на
 * вноските са единственото, което печатът добавя към записаното. Пресметнати втори път тук, те биха
 * били второ мнение по въпрос, на който вече има отговор — и второто мнение се разминава тихо,
 * защото и двете числа изглеждат правдоподобни. Оттам и сверката ({@link #getReconciliation()}) е
 * СЪЩАТА: ред, чиито пера не се събират до заглавните му суми, го казва върху ведомостта, а не само
 * върху своя фиш.
 *
 * <p><b>⚠️ ДВЕТЕ КОЛОНИ, КОИТО ФИШЪТ НЯМА.</b> Авансът и другите удръжки не са на заглавната част и
 * не са в {@link BgPaySlipPrintSummary} — те съществуват заради ведомостта, защото тя отговаря на
 * въпроса „колко още дължим на този човек“. Изведени са така:
 *
 * <ul>
 *   <li><b>Удръжките</b> — през {@link BgPaySlipResolver#netBeforeSeizure} МИНУС {@link
 *       BgPaySlipResolver#netTotal}. Тоест НЕ е ново правило: групата 8xx вече има точно едно
 *       определение и то живее там, където чл. 446 ГПК се прилага.
 *   <li><b>Авансът</b> — перата 600 и 610 със знак DEBIT за собствения период. Това ПРАВИЛО ГО НЯМА
 *       другаде (нула функции сумират група 6xx), тоест тук няма с какво да се размине; ако утре се
 *       появи, мястото му е в {@link BgPaySlipResolver}, а този метод се изтрива.
 * </ul>
 *
 * <p><b>⚠️ И ЛИЧНИТЕ ДАННИ СЕ ЧЕТАТ ОТ ВЕЧЕ ЗАРЕДЕНИТЕ ОБЕКТИ</b>, точно както в {@code
 * BgD1RecordBuilder} и {@code BgPaySlipPrintService}: през {@link BgD1Carrier}, за да работи и за
 * двата носителя на фиша. Пряко четене на {@code getEmploymentContract()} би оставило всеки ред на
 * самоосигуряващо се лице безименен.
 */
public final class BgPayrollLedgerRow {

  /** Перо 600 — авансът по чл. 270 ал. 2 КТ. */
  public static final int LINE_ADVANCE = 600;

  /** Перо 610 — частично плащане по банков път, чл. 270 ал. 3 КТ. */
  public static final int LINE_PARTIAL_PAYMENT = 610;

  private final Long slipId;
  private final String reference;
  private final BgPayrollLedgerVariant variant;
  private final String employeeNames;
  private final String personalId;
  private final String insuredTypeCode;
  private final String status;
  private final int workedDays;
  private final BigDecimal gross;
  private final BigDecimal employeeContributions;
  private final BigDecimal tax;
  private final BigDecimal advance;
  private final BigDecimal deductions;
  private final BigDecimal net;
  private final BigDecimal employerContributions;
  private final BigDecimal employerCost;
  private final String reconciliation;

  private BgPayrollLedgerRow(
      BgPaySlip slip,
      BgD1Carrier carrier,
      BgPaySlipPrintSummary summary,
      List<BgPaySlipLine> lines) {
    this.slipId = slip.getId();
    this.reference = nz(slip.getReference());
    this.variant = BgPayrollLedgerVariant.of(slip);
    this.employeeNames = namesOf(carrier.employee());
    this.personalId = personalIdOf(carrier.employee());
    this.insuredTypeCode = nz(carrier.insuredTypeCode());
    this.status = nz(slip.getStatusSelect());
    this.workedDays = slip.getBgWorkedDays();
    this.gross = slip.getBgGrossTotal();
    this.employeeContributions = summary.getEmployeeContributions();
    this.tax = slip.getBgTaxAmount();
    this.advance = advanceOf(lines);
    this.deductions =
        BgPaySlipResolver.netBeforeSeizure(lines).subtract(BgPaySlipResolver.netTotal(lines));
    this.net = slip.getBgNetTotal();
    this.employerContributions = summary.getEmployerContributions();
    this.employerCost = slip.getBgEmployerCost();
    this.reconciliation = summary.report();
  }

  /** Редът на един фиш. */
  public static BgPayrollLedgerRow of(BgPaySlip slip) {
    List<BgPaySlipLine> lines =
        slip.getBgPaySlipLineList() == null ? Collections.emptyList() : slip.getBgPaySlipLineList();
    return new BgPayrollLedgerRow(
        slip, BgD1Carrier.of(slip), BgPaySlipPrintSummary.of(slip), lines);
  }

  public Long getSlipId() {
    return slipId;
  }

  public String getReference() {
    return reference;
  }

  public BgPayrollLedgerVariant getVariant() {
    return variant;
  }

  public String getEmployeeNames() {
    return employeeNames;
  }

  public String getPersonalId() {
    return personalId;
  }

  public String getInsuredTypeCode() {
    return insuredTypeCode;
  }

  public String getStatus() {
    return status;
  }

  public int getWorkedDays() {
    return workedDays;
  }

  /** Начисленията — брутото, както го носи заглавната част на фиша. */
  public BigDecimal getGross() {
    return gross;
  }

  /** Осигуровките, УДЪРЖАНИ от лицето. */
  public BigDecimal getEmployeeContributions() {
    return employeeContributions;
  }

  /** Данъкът по чл. 42 ЗДДФЛ, както го носи заглавната част. */
  public BigDecimal getTax() {
    return tax;
  }

  /** Аванс и частично плащане — вече предаденото извън плика. */
  public BigDecimal getAdvance() {
    return advance;
  }

  /** Другите удръжки — цялата група 8xx (глоби, заеми, запори, издръжка). */
  public BigDecimal getDeductions() {
    return deductions;
  }

  /** Нето за получаване, както го носи заглавната част. */
  public BigDecimal getNet() {
    return net;
  }

  /** Осигуровките ЗА СМЕТКА НА РАБОТОДАТЕЛЯ. */
  public BigDecimal getEmployerContributions() {
    return employerContributions;
  }

  /** Разходът за работодателя, както го носи заглавната част. */
  public BigDecimal getEmployerCost() {
    return employerCost;
  }

  /**
   * Какво на ТОЗИ ред не се сверява — празно, когато перата и заглавните суми казват едно и също.
   *
   * <p>⚠️ Същият доклад, който печатният фиш показва, и нарочно същият: ведомостта е листът, на
   * който се сверява цялото предприятие, и точно на него е най-евтино да се види, че един ред е
   * пипан след последното преизчисляване.
   */
  public String getReconciliation() {
    return reconciliation;
  }

  /** Има ли този ред какво да каже за себе си. */
  public boolean hasReconciliationFindings() {
    return !reconciliation.isEmpty();
  }

  // ── изведените две колони ────────────────────────────────────────────────

  /**
   * ⚠️ САМО 600 И 610, А НЕ ЦЯЛАТА ГРУПА 6xx. В нея стои и перо 630 („предадено в натура“ —
   * ваучери, премии, придобивки), което НЕ е плащане в пари: то е контра-перото на натурата и
   * излиза от нетото, без лицето да е получило и стотинка. Събрано с аванса, то би показало на
   * ведомостта „изплатено предварително“ число, което банката никога не е нареждала.
   */
  static BigDecimal advanceOf(List<BgPaySlipLine> lines) {
    BigDecimal out = BigDecimal.ZERO;
    if (lines == null) {
      return out;
    }
    for (BgPaySlipLine line : lines) {
      if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      if (!BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
        continue;
      }
      int number = line.getBgLineNumber();
      if (number == LINE_ADVANCE || number == LINE_PARTIAL_PAYMENT) {
        out = out.add(line.getBgAmount());
      }
    }
    return out;
  }

  // ── лицето ──────────────────────────────────────────────────────────────

  /** Трите имена, както ги носи партньорът на служителя — същият ред като на печатния фиш. */
  static String namesOf(Employee employee) {
    Partner person = employee == null ? null : employee.getContactPartner();
    if (person == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    append(out, person.getFirstName());
    append(out, person.getBgMiddleName());
    append(out, person.getName());
    return out.toString();
  }

  /** „ЕГН 8501014567“ — идентификаторът СЪС своя вид, защото ЛНЧ се чете различно. */
  static String personalIdOf(Employee employee) {
    // ⚠️ Видът е КОД („1“/„2“) — думата я дава BgPersonalIdLabel (напр. „1 7501011016“).
    return BgPersonalIdLabel.of(employee);
  }

  private static void append(StringBuilder out, String part) {
    if (part == null || part.trim().isEmpty()) {
      return;
    }
    if (out.length() > 0) {
      out.append(' ');
    }
    out.append(part.trim());
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
