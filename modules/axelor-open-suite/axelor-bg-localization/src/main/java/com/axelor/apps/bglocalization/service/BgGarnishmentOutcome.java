package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;

/**
 * КАКВО КАЗВА ЧЛ. 446 ЗА ЕДИН ФИШ — резултатът на {@link BgGarnishmentResolver#assess}.
 *
 * <p>Ръчен клас, не запис: тук няма таблица и няма да има. Носи СЕДЕМ числа и една причина, и всяко
 * от тях е отговор на различен въпрос — затова не са слети в едно.
 *
 * <p>⚠️ {@link #refusal} НЕ Е ГРЕШКА В СМЕТКАТА. Той е състояние, в което сметката НЕ БИВА да се
 * прилага: липсваща скала, липсваща МРЗ, или доброволни удръжки, които избутват сбора над тавана.
 * При него останалите числа могат да са попълнени и въпреки това да не важат — затова повикващият
 * пита ПЪРВО него.
 */
public final class BgGarnishmentOutcome {

  /**
   * Остатъкът, върху който чл. 446 работи: нето МИНУС ТЕКУЩАТА издръжка (ал. 4).
   *
   * <p>⚠️ Издръжката за МИНАЛО ВРЕМЕ НЕ е вадена тук. Тя е сред „другите задължения“ по ал. 1 и се
   * бори за място ВЪТРЕ в тавана — виж {@link #allowedPastDueMaintenance}.
   */
  public final BigDecimal base;

  /** Таванът по чл. 446 върху този остатък, или {@code null} при отказ. */
  public final BigDecimal limit;

  /**
   * Колко от ИЗДРЪЖКАТА ЗА МИНАЛО ВРЕМЕ (перо 831) МОЖЕ да се удържи този месец.
   *
   * <p>⚠️ ПЪРВА В ТАВАНА, преди запора — виж реда в {@link BgGarnishmentResolver#assess}. Числото е
   * различно от {@link #base}: просрочената НЕ намалява основата, тя се вмества в тавана.
   */
  public final BigDecimal allowedPastDueMaintenance;

  /** Колко от запора МОЖЕ да се удържи този месец — клампнатото, СЛЕД просрочената издръжка. */
  public final BigDecimal allowedGarnishment;

  /** Колко от просрочената издръжка НЕ се събира този месец. Остава ИЗИСКУЕМА. */
  public final BigDecimal uncollectedPastDueMaintenance;

  /** Колко от наложения запор НЕ се събира този месец. Остава ИЗИСКУЕМ. */
  public final BigDecimal uncollectedGarnishment;

  /**
   * СБОРЪТ на двете несъбрани суми — едно число, срещу което пазачът пита „има ли клампа“.
   *
   * <p>⚠️ Не се губи и не се прощава: остатъкът остава ИЗИСКУЕМ по общия ред и се събира следващия
   * месец. Записано е и в бележките на перата PLT-821 и PLT-831.
   *
   * <p>⚠️ СБОРЪТ НЕ КАЗВА КОЕ ПЕРО да се поправи и затова НЕ е единственото число тук: отказът
   * назовава поотделно {@link #uncollectedPastDueMaintenance} и {@link #uncollectedGarnishment}.
   * Свит до едно число, той би пратил оператора да маха от запора сума, която идва от издръжката.
   */
  public final BigDecimal uncollected;

  /** Назованата причина да НЕ се потвърждава фишът, или {@code null}. */
  public final String refusal;

  private BgGarnishmentOutcome(
      BigDecimal base,
      BigDecimal limit,
      BigDecimal allowedPastDueMaintenance,
      BigDecimal allowedGarnishment,
      BigDecimal uncollectedPastDueMaintenance,
      BigDecimal uncollectedGarnishment,
      BigDecimal uncollected,
      String refusal) {
    this.base = base;
    this.limit = limit;
    this.allowedPastDueMaintenance = allowedPastDueMaintenance;
    this.allowedGarnishment = allowedGarnishment;
    this.uncollectedPastDueMaintenance = uncollectedPastDueMaintenance;
    this.uncollectedGarnishment = uncollectedGarnishment;
    this.uncollected = uncollected;
    this.refusal = refusal;
  }

  static BgGarnishmentOutcome computed(
      BigDecimal base,
      BigDecimal limit,
      BigDecimal allowedPastDueMaintenance,
      BigDecimal allowedGarnishment,
      BigDecimal uncollectedPastDueMaintenance,
      BigDecimal uncollectedGarnishment) {
    return new BgGarnishmentOutcome(
        base,
        limit,
        allowedPastDueMaintenance,
        allowedGarnishment,
        uncollectedPastDueMaintenance,
        uncollectedGarnishment,
        uncollectedPastDueMaintenance.add(uncollectedGarnishment),
        null);
  }

  static BgGarnishmentOutcome refused(BigDecimal base, BigDecimal limit, String reason) {
    return new BgGarnishmentOutcome(base, limit, null, null, null, null, null, reason);
  }

  /**
   * ⚠️ {@code hasRefusal}, А НЕ {@code isRefused}, И ИМЕТО Е НАРОЧНО.
   *
   * <p>{@code BgWorkTimeGateTest} пази инвариант върху ИЗХОДНИЯ КОД: в файл, който споменава {@code
   * BgPayrollWorkTime}, редът {@code .isRefused()} се брои за ГОЛ прочит на работно-времевия отказ
   * и вали билда: такъв гол прочит блокира фиша на морското лице. {@code BgPaySlipService}
   * споменава {@code BgPayrollWorkTime} и същевременно чете ТОЗИ резултат — тоест едноименен метод
   * тук би задействал чужд пазач по СЪВПАДЕНИЕ НА ИМЕ.
   *
   * <p>Изборът е между отслабване на чуждия пазач и различно име тук. Второто не струва нищо, а
   * първото би отслабило проверка, която пази реален отказ.
   */
  public boolean hasRefusal() {
    return refusal != null;
  }
}
