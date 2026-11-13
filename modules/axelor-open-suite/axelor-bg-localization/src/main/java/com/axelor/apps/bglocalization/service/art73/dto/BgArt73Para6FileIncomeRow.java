package com.axelor.apps.bglocalization.service.art73.dto;

import java.math.BigDecimal;

/**
 * ЕДИН РЕД ДОХОД НА ЕДНО ЛИЦЕ — {@code incomedata/incomerows/rowsenum} по {@code dec736}.
 *
 * <p><b>⚠️ РЕДЪТ Е НА (КОД, РАБОТОДАТЕЛ), А НЕ НА ЛИЦЕ — И ТОВА Е ЦЯЛАТА РАЗЛИКА С АЛ. 1.</b> При
 * ал. 1 един {@code rowsenum} е едно плащане към едно лице. Тук {@code part2/rowsenum} е ЛИЦЕТО, а
 * плащанията му висят вложени под него. Ред, който сумира два работодателя, би загубил колона 23
 * („ЕИК на преобразуващото се предприятие“) и не би могъл да се сверява срещу хартията.
 *
 * <p><b>⚠️ ТРИТЕ ОБЛЕКЧЕНИЯ НА РЕД ГИ НЯМА НАРОЧНО.</b> {@code dec736} описва на този слой и {@code
 * taxreductiondisabled}, {@code taxreductionins} и {@code taxreductionhealthins} — месечните
 * облекчения по чл. 42 ал. 3, различни от годишните в {@code taxbase49}. „Ред и формат“ (заповед №
 * З-ЦУ-1877/05.12.2019 г.) ги дава като <b>редове 8/9/10 = N</b>, тоест НЕЗАДЪЛЖИТЕЛНИ ЗА ПРИЕМ.
 * Модулът не ги събира днес; добавени тук като вечно празни полета, те щяха да твърдят, че въпросът
 * е отговорен. Появят ли се в агрегатора — мястото им е точно тук.
 */
public final class BgArt73Para6FileIncomeRow {

  private String incomeCode;
  private String employerIdentifier;
  private String employerName;
  private BigDecimal income;
  private BigDecimal insuranceBg;
  private BigDecimal insuranceForeign;
  private BigDecimal advanceTax;
  private String predecessorIdentifier;

  /**
   * Колона 2 — кодът на дохода.
   *
   * <p>⚠️ ТОЧНО 3 ЗНАКА по схемата ({@code xs:string length=3}) — за разлика от ал. 1, където са
   * 3–5. Кодът идва от каталога ({@code BgIncomeType.code}), не се пише на ръка.
   */
  public String getIncomeCode() {
    return incomeCode;
  }

  public void setIncomeCode(String incomeCode) {
    this.incomeCode = incomeCode;
  }

  /** Колона 3 — ЕИК/Сл. № на работодателя, ИЗПЛАТИЛ дохода. Задължителен за прием. */
  public String getEmployerIdentifier() {
    return employerIdentifier;
  }

  public void setEmployerIdentifier(String employerIdentifier) {
    this.employerIdentifier = employerIdentifier;
  }

  /** Колона 4 — име/наименование на работодателя. Задължително за прием. */
  public String getEmployerName() {
    return employerName;
  }

  public void setEmployerName(String employerName) {
    this.employerName = employerName;
  }

  /** Колона 5 — доходът. Задължителен за прием; отрицателен не се приема. */
  public BigDecimal getIncome() {
    return income;
  }

  public void setIncome(BigDecimal income) {
    this.income = income;
  }

  /** Колона 6 — ЗОВ за сметка на лицето, удържани по реда на КСО и ЗЗО (или авансово чрез СОЛ). */
  public BigDecimal getInsuranceBg() {
    return insuranceBg;
  }

  public void setInsuranceBg(BigDecimal insuranceBg) {
    this.insuranceBg = insuranceBg;
  }

  /** Колона 7 по схемата — ЗОВ, ВНЕСЕНИ В ЧУЖБИНА. */
  public BigDecimal getInsuranceForeign() {
    return insuranceForeign;
  }

  public void setInsuranceForeign(BigDecimal insuranceForeign) {
    this.insuranceForeign = insuranceForeign;
  }

  /** Авансовият данък по чл. 42 ЗДДФЛ. Задължителен за прием; отрицателен не се приема. */
  public BigDecimal getAdvanceTax() {
    return advanceTax;
  }

  public void setAdvanceTax(BigDecimal advanceTax) {
    this.advanceTax = advanceTax;
  }

  /**
   * Колона 23 — ЕИК на ПРЕОБРАЗУВАЩОТО СЕ предприятие.
   *
   * <p>⚠️ Попълнена, тя е част от ключа на реда: „Ред и формат“ т. 16 иска доходите от
   * преобразуващото се дружество на ОТДЕЛЕН ред. Платил ли е и правоприемникът по същия код, редът
   * става ДВА — с един и същ код, различени точно по това поле.
   */
  public String getPredecessorIdentifier() {
    return predecessorIdentifier;
  }

  public void setPredecessorIdentifier(String predecessorIdentifier) {
    this.predecessorIdentifier = predecessorIdentifier;
  }
}
