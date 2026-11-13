package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * КАКВО КАЗВАТ КАПАЧКАТА И УСЛОВИЯТА ЗА ЕДНА ГРУПА ПЕРА — резултатът на {@link
 * BgSocialExpenseCapResolver#apply}.
 *
 * <p>Ръчен клас, не запис — по образеца на {@link BgGarnishmentOutcome}. Носи ТРИ обобщени числа,
 * разбивката по позиция и една назована причина, и всяко от тях е отговор на различен въпрос.
 *
 * <p><b>⚠️ {@link #companyTaxBase} НЕ Е {@link #excess}, И ТОЧНО ТАМ Е СМИСЪЛЪТ НА ТОЗИ КЛАС.</b>
 * При изпълнени условия двете съвпадат (ЗКПО чл. 213 ал. 2, чл. 214 ал. 2 — основата Е превишението
 * над капачката). При ПАДНАЛО условие основата е ЦЕЛИЯТ размер на начислените разходи за месеца
 * (чл. 213 ал. 3, чл. 214 ал. 3), докато превишението си остава превишение. Свити в едно число, те
 * биха дали при ваучер 150,00 EUR данъчна основа 47,74 вместо 150,00 — тоест 4,77 вместо 15,00
 * данък, и нищо на фиша не изглежда сгрешено.
 *
 * <p><b>⚠️ {@link #refusal} НЕ Е ГРЕШКА В СМЕТКАТА.</b> Той е състояние, в което сметката НЕ БИВА
 * да се прилага: липсваща капачка за периода, или ред с празна лична капачка. При него останалите
 * числа са {@code null} и повикващият пита ПЪРВО него — същата семантика като при {@link
 * BgGarnishmentOutcome}.
 */
public final class BgSocialExpenseOutcome {

  /** Каква част от подадената сума за ЕДНА позиция е освободена и каква е превишение. */
  public static final class Share {

    /** Кодът на перото-позиция, както е подаден. Носи се непокътнат, за да се върне на реда. */
    public final String code;

    /** Подадената сума за тази позиция. Единица: ЕВРО. */
    public final BigDecimal amount;

    /** „В РАМКИТЕ НА КАПАЧКАТА“ — необлагаемата част за лицето. Единица: ЕВРО. */
    public final BigDecimal exempt;

    /** „ПРЕВИШЕНИЕ“. Единица: ЕВРО. Винаги {@code amount − exempt}, без второ закръгляне. */
    public final BigDecimal excess;

    Share(String code, BigDecimal amount, BigDecimal exempt, BigDecimal excess) {
      this.code = code;
      this.amount = amount;
      this.exempt = exempt;
      this.excess = excess;
    }
  }

  /**
   * По позиция — освободена част и превишение, В РЕДА, В КОЙТО СА ПОДАДЕНИ.
   *
   * <p>⚠️ Редът НЕ е козметика: остатъкът от закръглянето при пропорционалното разпределение отива
   * по този ред (виж {@link BgSocialExpenseCapResolver#EXCESS_SPLIT_FOLLOWS_SHARE}). Подадени в
   * друга последователност, същите три суми дават същите сборове и различни стотинки по позиция.
   */
  public final List<Share> shares;

  /** ОБЩО освободено за лицето. Никога над капачката. Единица: ЕВРО. */
  public final BigDecimal exempt;

  /** ОБЩО превишение за лицето. Единица: ЕВРО. */
  public final BigDecimal excess;

  /**
   * МЕСЕЧНАТА ДАНЪЧНА ОСНОВА ЗА ДАНЪКА ВЪРХУ РАЗХОДИТЕ за това лице — ЗКПО чл. 213 и чл. 214.
   *
   * <p>Нула, когато данък върху разходите не се дължи изобщо ({@code bgCompanyTaxApplies = false} —
   * социален разход, който не е в натура и се облага по реда на ЗДДФЛ, чл. 205).
   */
  public final BigDecimal companyTaxBase;

  /**
   * ИМЕТО на условието, което е паднало, или {@code null}.
   *
   * <p>⚠️ НАЗОВАНО, А НЕ БУЛЕВО. Трите условия водят до един и същ резултат — цялата сума в
   * основата — и точно затова резултатът не казва КОЕ да се поправи. Свито до „условие падна“,
   * съобщението праща счетоводителя да търси лиценз на оператор, когато проблемът е една заплата
   * под средната.
   */
  public final String failedCondition;

  /** Назованата причина сметката да НЕ се прилага, или {@code null}. */
  public final String refusal;

  private BgSocialExpenseOutcome(
      List<Share> shares,
      BigDecimal exempt,
      BigDecimal excess,
      BigDecimal companyTaxBase,
      String failedCondition,
      String refusal) {
    this.shares = shares;
    this.exempt = exempt;
    this.excess = excess;
    this.companyTaxBase = companyTaxBase;
    this.failedCondition = failedCondition;
    this.refusal = refusal;
  }

  static BgSocialExpenseOutcome computed(
      List<Share> shares,
      BigDecimal exempt,
      BigDecimal excess,
      BigDecimal companyTaxBase,
      String failedCondition) {
    return new BgSocialExpenseOutcome(
        Collections.unmodifiableList(shares),
        exempt,
        excess,
        companyTaxBase,
        failedCondition,
        null);
  }

  static BgSocialExpenseOutcome refused(String reason) {
    return new BgSocialExpenseOutcome(Collections.emptyList(), null, null, null, null, reason);
  }

  /**
   * ⚠️ {@code hasRefusal}, А НЕ {@code isRefused} — по същия довод като при {@link
   * BgGarnishmentOutcome#hasRefusal()}: {@code BgWorkTimeGateTest} брои реда {@code .isRefused()}
   * за гол прочит на работно-времевия отказ във всеки файл, който споменава {@code
   * BgPayrollWorkTime}, и едноименен метод тук би задействал чужд пазач по СЪВПАДЕНИЕ НА ИМЕ.
   */
  public boolean hasRefusal() {
    return refusal != null;
  }

  /** Паднало ли е някое от трите условия. Пита се преди {@link #excess}, не вместо него. */
  public boolean hasFailedCondition() {
    return failedCondition != null;
  }
}
