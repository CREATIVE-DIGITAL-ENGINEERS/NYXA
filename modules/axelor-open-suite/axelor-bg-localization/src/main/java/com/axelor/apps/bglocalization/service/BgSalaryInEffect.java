package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgContractAmendment;
import java.math.BigDecimal;

/**
 * ОСНОВНОТО ВЪЗНАГРАЖДЕНИЕ ЗА ЕДИН ПЕРИОД — число ИЛИ обявена причина да няма число.
 *
 * <p>Същият довод като при {@code BgArt177Outcome}: „договорът носи 1000“ и „анексът мени заплатата
 * насред месеца и не мога да кажа коя е“ са РАЗЛИЧНИ факта. Свити до едно число, вторият става
 * неразличим от първия — тоест половин месец платен по чужда заплата, който изглежда като нормален
 * фиш.
 *
 * <p><b>⚠️ ИЗТОЧНИКЪТ СЕ НОСИ, А НЕ САМО СУМАТА.</b> {@link #source} е анексът, дал числото, и
 * влиза в присъдата на фиша. Без него 1200,00 от анекс е неразличимо на екрана от 1200,00, въведени
 * на ръка — а второто НЕ бива да се презаписва при следващото {@code prepare()}.
 */
public final class BgSalaryInEffect {

  /** Трите изхода. Виж класовия javadoc за адресата на всеки. */
  public enum Status {
    /** Няма анекс в сила за периода — важи базата по договора. Нормалното състояние. */
    CONTRACT_BASE,
    /** Анекс в сила дава заплатата. {@link #source} казва кой. */
    FROM_AMENDMENT,
    /**
     * Има анекс и НЕ може да се каже коя е заплатата. {@link #detail} казва какво да се поправи.
     */
    REFUSED
  }

  /** Кой от трите изхода. Никога {@code null}. */
  public final Status status;

  /**
   * Заплатата за периода. {@code null} САМО при {@link Status#REFUSED} — нулата е валидна заплата
   * колкото и празното поле, тоест липсата не бива да се пише с число.
   */
  public final BigDecimal salary;

  /** Анексът, дал числото. Непразен само при {@link Status#FROM_AMENDMENT}. */
  public final BgContractAmendment source;

  /** ЦЯЛО ИЗРЕЧЕНИЕ за човек. Празно е само при {@link Status#CONTRACT_BASE}. */
  public final String detail;

  private BgSalaryInEffect(
      Status status, BigDecimal salary, BgContractAmendment source, String detail) {
    this.status = status;
    this.salary = salary;
    this.source = source;
    this.detail = detail;
  }

  static BgSalaryInEffect contractBase(BigDecimal salary) {
    return new BgSalaryInEffect(Status.CONTRACT_BASE, salary, null, null);
  }

  static BgSalaryInEffect fromAmendment(
      BigDecimal salary, BgContractAmendment source, String detail) {
    return new BgSalaryInEffect(Status.FROM_AMENDMENT, salary, source, detail);
  }

  static BgSalaryInEffect refused(String detail) {
    return new BgSalaryInEffect(Status.REFUSED, null, null, detail);
  }

  /**
   * Няма отговор коя е заплатата.
   *
   * <p><b>⚠️ НЕ {@code isRefused()}, И ИМЕТО НЕ Е СВОБОДНО.</b> Това име вече е заето в модула от
   * {@code BgPayrollWorkTime.Decision#isRefused()} и {@code BgWorkTimeGateTest} пази да НЕ се чете
   * голо: работно-времевият отказ трябва да мине през {@code blocks(prorates)}, защото при видовете
   * осигурени с {@code bgZeroWorkTime} той не бива да блокира нищо. Пазачът е текстов и не може да
   * различи двата типа — а съвпадащо име тук би го карало да пада при всяка промяна по заплатата и
   * рано или късно някой би го отслабил. Различното име пази и двата въпроса цели.
   */
  public boolean salaryUnknown() {
    return status == Status.REFUSED;
  }
}
