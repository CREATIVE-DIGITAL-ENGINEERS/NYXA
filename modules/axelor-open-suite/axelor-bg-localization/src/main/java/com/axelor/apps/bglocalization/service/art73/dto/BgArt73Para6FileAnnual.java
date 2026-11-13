package com.axelor.apps.bglocalization.service.art73.dto;

import java.math.BigDecimal;

/**
 * БЛОКЪТ ЗА ГОДИШНОТО ИЗРАВНЯВАНЕ ПО ЧЛ. 49 — {@code incomedata/taxbase49} по {@code dec736}.
 *
 * <p><b>⚠️ САМО ЗА РАБОТОДАТЕЛЯ ПО ОСНОВНО ТРУДОВО ПРАВООТНОШЕНИЕ.</b> „Ред и формат“ (заповед №
 * З-ЦУ-1877/05.12.2019 г.) прави блока условно задължителен при {@code ismainemployer=1}, а
 * правилото е ДВУПОСОЧНО: основен работодател ⟺ наличен {@code taxbase49}. Затова присъствието на
 * този обект НЕ се решава поотделно от флага — виж {@code BgArt73Para6FileValidator}.
 *
 * <p><b>⚠️ ТРИ НОМЕРАЦИИ ОПИСВАТ ЕДНО И СЪЩО, И ТОВА ОБЪРКВА ВСЕКИ, КОЙТО ГИ СВЕРЯВА.</b>
 *
 * <ul>
 *   <li><b>тагът на {@code dec736}</b> — {@code diff18}, {@code sum19deducted}, {@code
 *       sum19refund}; името му носи номера на колоната по образеца от 2019 г.;
 *   <li><b>колоните на образеца за 2025 г.</b> — същите три са к. 19, к. 20 и к. 21 (виж {@code
 *       BgArt73Para6Annual.xml});
 *   <li><b>„ред 8.3, колона N“</b> — вътрешната номерация на самия ред за изравняване.
 * </ul>
 *
 * <p>МЕРОДАВЕН ЗА ФАЙЛА Е ТАГЪТ. Разминаването е историческо (образецът е добавил колона), не
 * дефект — записано е тук, за да не „поправи“ някой мапинга към по-новите номера.
 */
public final class BgArt73Para6FileAnnual {

  private BigDecimal taxBase;
  private BigDecimal reliefDisability;
  private BigDecimal reliefVoluntaryPension;
  private BigDecimal reliefVoluntaryHealthLife;
  private BigDecimal reliefServiceYears;
  private BigDecimal reliefDonation01;
  private BigDecimal reliefDonation02;
  private BigDecimal reliefDonation03;
  private BigDecimal reliefChildren;
  private BigDecimal reliefDisabledChildren;
  private BigDecimal taxBaseAfterReliefs;
  private BigDecimal tax;
  private BigDecimal settlement;
  private BigDecimal withheldByJanuary;
  private BigDecimal refundedByJanuary;

  /** {@code taxbase} — годишната данъчна основа (ред 8.1 + ред 8.2). */
  public BigDecimal getTaxBase() {
    return taxBase;
  }

  public void setTaxBase(BigDecimal taxBase) {
    this.taxBase = taxBase;
  }

  /** {@code taxreductiondisabled} — чл. 18 ал. 1 или ал. 2 (намалена работоспособност). */
  public BigDecimal getReliefDisability() {
    return reliefDisability;
  }

  public void setReliefDisability(BigDecimal reliefDisability) {
    this.reliefDisability = reliefDisability;
  }

  /** {@code taxreductionins} — чл. 19 ал. 1 или ал. 2 (лични вноски, доброволно осигуряване). */
  public BigDecimal getReliefVoluntaryPension() {
    return reliefVoluntaryPension;
  }

  public void setReliefVoluntaryPension(BigDecimal reliefVoluntaryPension) {
    this.reliefVoluntaryPension = reliefVoluntaryPension;
  }

  /** {@code taxreductionhealthins} — чл. 19: доброволно ЗДРАВНО осигуряване и „Живот“. */
  public BigDecimal getReliefVoluntaryHealthLife() {
    return reliefVoluntaryHealthLife;
  }

  public void setReliefVoluntaryHealthLife(BigDecimal reliefVoluntaryHealthLife) {
    this.reliefVoluntaryHealthLife = reliefVoluntaryHealthLife;
  }

  /** {@code taxreductionretire} — чл. 20 (осигурителен стаж при пенсиониране). */
  public BigDecimal getReliefServiceYears() {
    return reliefServiceYears;
  }

  public void setReliefServiceYears(BigDecimal reliefServiceYears) {
    this.reliefServiceYears = reliefServiceYears;
  }

  /** {@code taxreductiondonation01} — чл. 22, дарения в полза на лица с код 01. */
  public BigDecimal getReliefDonation01() {
    return reliefDonation01;
  }

  public void setReliefDonation01(BigDecimal reliefDonation01) {
    this.reliefDonation01 = reliefDonation01;
  }

  /** {@code taxreductiondonation02} — чл. 22, дарения в полза на лица с код 02. */
  public BigDecimal getReliefDonation02() {
    return reliefDonation02;
  }

  public void setReliefDonation02(BigDecimal reliefDonation02) {
    this.reliefDonation02 = reliefDonation02;
  }

  /** {@code taxreductiondonation03} — чл. 22, дарения в полза на лица с код 03. */
  public BigDecimal getReliefDonation03() {
    return reliefDonation03;
  }

  public void setReliefDonation03(BigDecimal reliefDonation03) {
    this.reliefDonation03 = reliefDonation03;
  }

  /** {@code taxreductionchildren} — чл. 22в (деца). */
  public BigDecimal getReliefChildren() {
    return reliefChildren;
  }

  public void setReliefChildren(BigDecimal reliefChildren) {
    this.reliefChildren = reliefChildren;
  }

  /** {@code taxreductionchildrendisab} — чл. 22г (деца с увреждания). */
  public BigDecimal getReliefDisabledChildren() {
    return reliefDisabledChildren;
  }

  public void setReliefDisabledChildren(BigDecimal reliefDisabledChildren) {
    this.reliefDisabledChildren = reliefDisabledChildren;
  }

  /** {@code taxbase491} — основата по чл. 49 ал. 1 СЛЕД намаленията. */
  public BigDecimal getTaxBaseAfterReliefs() {
    return taxBaseAfterReliefs;
  }

  public void setTaxBaseAfterReliefs(BigDecimal taxBaseAfterReliefs) {
    this.taxBaseAfterReliefs = taxBaseAfterReliefs;
  }

  /** {@code tax} — дължимият годишен данък по реда на чл. 49. */
  public BigDecimal getTax() {
    return tax;
  }

  public void setTax(BigDecimal tax) {
    this.tax = tax;
  }

  /**
   * {@code diff18} — разликата за изравняване.
   *
   * <p><b>⚠️ ЕДИНСТВЕНОТО ПОЛЕ, КОЕТО ПРИЕМА ОТРИЦАТЕЛНА СТОЙНОСТ.</b> „Ред и формат“, изисквания
   * т. 6: „не се допуска прием на отрицателни стойности с ИЗКЛЮЧЕНИЕ на поле {@code diff18}“. И то
   * е логично: полето е РАЗЛИКА (дължимият годишен данък минус вече удържаното), тоест знакът ѝ
   * казва дали се доудържа, или се възстановява. Блокиран, той щеше да прави невъзможно точно
   * възстановяването по чл. 49 ал. 6.
   */
  public BigDecimal getSettlement() {
    return settlement;
  }

  public void setSettlement(BigDecimal settlement) {
    this.settlement = settlement;
  }

  /** {@code sum19deducted} — от разликата: УДЪРЖАНОТО от лицето до 31 януари (чл. 49 ал. 5). */
  public BigDecimal getWithheldByJanuary() {
    return withheldByJanuary;
  }

  public void setWithheldByJanuary(BigDecimal withheldByJanuary) {
    this.withheldByJanuary = withheldByJanuary;
  }

  /** {@code sum19refund} — от разликата: ВЪЗСТАНОВЕНОТО на лицето до 31 януари (чл. 49 ал. 6). */
  public BigDecimal getRefundedByJanuary() {
    return refundedByJanuary;
  }

  public void setRefundedByJanuary(BigDecimal refundedByJanuary) {
    this.refundedByJanuary = refundedByJanuary;
  }
}
