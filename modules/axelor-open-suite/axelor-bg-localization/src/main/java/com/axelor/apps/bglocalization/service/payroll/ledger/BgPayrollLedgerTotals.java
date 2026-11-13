package com.axelor.apps.bglocalization.service.payroll.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * РЕДЪТ „ОБЩО“ — сборът на всяка колона, и нищо освен него.
 *
 * <p><b>⚠️ СБОР, А НЕ ВТОРА СМЕТКА, И ТОВА Е ЦЯЛАТА МУ СТОЙНОСТ.</b> Всяко от осемте числа тук е
 * получено от ЕДИН обход на ВЕЧЕ ПОСТРОЕНИТЕ редове — тоест то не може да не е сборът на колоната
 * над себе си. Алтернативата (да се пита базата за сбор по периода) изглежда еднакво и е различна:
 * тя брои по СВОЙ филтър и тогава „общо“ може да включва ред, който на листа го няма, или да
 * пропусне ред, който има. Точно това е дефектът, който човек не вижда — всяко число поотделно е
 * правдоподобно.
 *
 * <p>⚠️ БЕЗ ЗАКРЪГЛЯНЕ. Всяко перо е закръглено до евроцент при записа му (спецификация § 5.3), а
 * заглавните суми са сборове на закръглени пера. Сборът на сборове не иска трето закръгляне и
 * такова тук би внесло разлика, каквато няма откъде да дойде.
 */
public final class BgPayrollLedgerTotals {

  private final int rowCount;
  private final BigDecimal gross;
  private final BigDecimal employeeContributions;
  private final BigDecimal tax;
  private final BigDecimal advance;
  private final BigDecimal deductions;
  private final BigDecimal net;
  private final BigDecimal employerContributions;
  private final BigDecimal employerCost;

  private BgPayrollLedgerTotals(
      int rowCount,
      BigDecimal gross,
      BigDecimal employeeContributions,
      BigDecimal tax,
      BigDecimal advance,
      BigDecimal deductions,
      BigDecimal net,
      BigDecimal employerContributions,
      BigDecimal employerCost) {
    this.rowCount = rowCount;
    this.gross = gross;
    this.employeeContributions = employeeContributions;
    this.tax = tax;
    this.advance = advance;
    this.deductions = deductions;
    this.net = net;
    this.employerContributions = employerContributions;
    this.employerCost = employerCost;
  }

  /** Сборът на подадените редове. Празен списък дава нули — законен отговор за празен период. */
  public static BgPayrollLedgerTotals of(List<BgPayrollLedgerRow> rows) {
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal employeeContributions = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    BigDecimal advance = BigDecimal.ZERO;
    BigDecimal deductions = BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal employerContributions = BigDecimal.ZERO;
    BigDecimal employerCost = BigDecimal.ZERO;
    int count = 0;

    if (rows != null) {
      for (BgPayrollLedgerRow row : rows) {
        if (row == null) {
          continue;
        }
        count++;
        gross = gross.add(row.getGross());
        employeeContributions = employeeContributions.add(row.getEmployeeContributions());
        tax = tax.add(row.getTax());
        advance = advance.add(row.getAdvance());
        deductions = deductions.add(row.getDeductions());
        net = net.add(row.getNet());
        employerContributions = employerContributions.add(row.getEmployerContributions());
        employerCost = employerCost.add(row.getEmployerCost());
      }
    }

    return new BgPayrollLedgerTotals(
        count,
        gross,
        employeeContributions,
        tax,
        advance,
        deductions,
        net,
        employerContributions,
        employerCost);
  }

  public int getRowCount() {
    return rowCount;
  }

  public BigDecimal getGross() {
    return gross;
  }

  public BigDecimal getEmployeeContributions() {
    return employeeContributions;
  }

  public BigDecimal getTax() {
    return tax;
  }

  public BigDecimal getAdvance() {
    return advance;
  }

  public BigDecimal getDeductions() {
    return deductions;
  }

  public BigDecimal getNet() {
    return net;
  }

  public BigDecimal getEmployerContributions() {
    return employerContributions;
  }

  public BigDecimal getEmployerCost() {
    return employerCost;
  }
}
