package com.axelor.apps.bglocalization.service.art73;

import java.math.BigDecimal;

/**
 * ЕДИН РЕД ОТ Т. 8 НА ЧАСТ ІІ — един код на дохода за едно лице, за цялата година.
 *
 * <p>Носи ТРИТЕ числа, които образецът иска от платеца и които фишът може да отговори:
 *
 * <ul>
 *   <li><b>колона 3</b> — общата сума на изплатения през годината облагаем доход от този код;
 *   <li><b>колона 4</b> — задължителните осигурителни вноски ЗА СМЕТКА НА ЛИЦЕТО;
 *   <li><b>колона 7</b> — удържаният авансово данък по реда на чл. 42 ЗДДФЛ.
 * </ul>
 *
 * <p><b>⚠️ И НЕ НОСИ КОЛОНА 6, НИТО КОЛОНИ 8–22 — НАРОЧНО, А НЕ ПО ПРОПУСК.</b> Указание т. 7 към
 * справката, дословно: „данни в колона 6 на редове 8.1, 8.2 и 8.3, както и в колони от 11 до 14
 * включително, както и колони от 17 до 21 на ред 8.3 се попълват само от работодател по основно
 * трудово правоотношение“. Тези числа са НА ЛИЦЕТО, не на кода на дохода, и живеят в {@code
 * BgArt73Para6Annual} — по един ред за човек, и само за човек с основно ТПО към 31 декември.
 * Сложени тук, те щяха да се повторят по веднъж за всеки код и нищо нямаше да казва кое от
 * повторенията е вярното.
 *
 * <p><b>⚠️ КОЛОНА 5 СЪЩО Я НЯМА, И ПО ДРУГА ПРИЧИНА.</b> Тя е за осигурителни вноски, ВНЕСЕНИ В
 * ЧУЖБИНА за сметка на лицето. Фишът не носи такова число и не може да го измисли: то идва от
 * документ на чуждестранна осигурителна институция. Вход за него е Фаза 2.
 */
public final class BgArt73Para6Line {

  private final String code;
  private final BigDecimal income;
  private final BigDecimal insurance;
  private final BigDecimal advanceTax;

  BgArt73Para6Line(String code, BigDecimal income, BigDecimal insurance, BigDecimal advanceTax) {
    this.code = code;
    this.income = income;
    this.insurance = insurance;
    this.advanceTax = advanceTax;
  }

  /** Кодът на дохода по номенклатурата към справката: „101“, „102“ или „103“. */
  public String getCode() {
    return code;
  }

  /** Колона 3 — облагаемият доход, изплатен през годината по този код. */
  public BigDecimal getIncome() {
    return income;
  }

  /** Колона 4 — удържаните ЗОВ за сметка на физическото лице. */
  public BigDecimal getInsurance() {
    return insurance;
  }

  /** Колона 7 — удържаният авансов данък по чл. 42 ЗДДФЛ. */
  public BigDecimal getAdvanceTax() {
    return advanceTax;
  }

  @Override
  public String toString() {
    return "код " + code + ": к.3=" + income + " к.4=" + insurance + " к.7=" + advanceTax;
  }
}
