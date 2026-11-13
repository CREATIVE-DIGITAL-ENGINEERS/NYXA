package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import java.math.BigDecimal;

/**
 * ЕДИН РЕД ОТ ЧАСТ ІІ НА ОБРАЗЕЦ 5-2001 — едно лице, един код на дохода, ЦЯЛАТА година.
 *
 * <p>Указание т. 7 към справката, дословно: „Ако на едно физическо лице са изплатени доходи с
 * различен код, данните се записват на отделни редове.“ Обратното следва от същото изречение:
 * няколко плащания по ЕДИН код се сливат в един ред с годишната сума.
 *
 * <p><b>⚠️ ТРИТЕ СУМИ СА {@code null}-ЕВИ, А НЕ НУЛЕВИ, И ТОВА Е ЦЯЛАТА ПРИЧИНА ТОЗИ КЛАС ДА
 * СЪЩЕСТВУВА.</b> Указание т. 2: при заличаване („8“) се попълват САМО колони 1, 2, 3, 4 или 5, 6,
 * 7, 8 и 10 — тоест колони 11, 12, 13, 14 и 15 остават ПРАЗНИ. Празно и нула са различни твърдения:
 * нула в колона 13 значи „данък не е удържан“ (законният отговор за необлагаемите доходи по Част
 * ІV), а празно значи „тази колона не се попълва“. Върху генерирания ентитет двете са неразличими —
 * {@code getGrossAmount()} на Axelor връща {@code ZERO} и за непопълнено поле. Затова разликата се
 * пази ТУК, в обикновен обект, където {@code null} оцелява, и се проверява от свидетел.
 *
 * <p><b>⚠️ И КОЛОНА 11 Е БРУТО.</b> Указание т. 8: „брутният размер на дохода, т.е. без приспадане
 * на признатите разходи и/или направените удръжки от платеца на дохода“. Нормативните разходи и
 * данъчната основа седят в регистъра и НЯМАТ място на този ред — те са за декларацията по чл. 55 и
 * за ГДД на лицето.
 */
public final class BgArt73Para1Line {

  private final String correctionCode;
  private final boolean foreign;
  private final String identifierType;
  private final String countryCode;
  private final Partner partner;
  private final BgIncomeType incomeType;
  private final BigDecimal grossAmount;
  private final BigDecimal insuranceAmount;
  private final BigDecimal taxAmount;
  private final Integer personCount;
  private final int paymentCount;
  private final boolean identityDisagreement;

  BgArt73Para1Line(
      String correctionCode,
      boolean foreign,
      String identifierType,
      String countryCode,
      Partner partner,
      BgIncomeType incomeType,
      BigDecimal grossAmount,
      BigDecimal insuranceAmount,
      BigDecimal taxAmount,
      Integer personCount,
      int paymentCount,
      boolean identityDisagreement) {
    this.correctionCode = correctionCode;
    this.foreign = foreign;
    this.identifierType = identifierType;
    this.countryCode = countryCode;
    this.partner = partner;
    this.incomeType = incomeType;
    this.grossAmount = grossAmount;
    this.insuranceAmount = insuranceAmount;
    this.taxAmount = taxAmount;
    this.personCount = personCount;
    this.paymentCount = paymentCount;
    this.identityDisagreement = identityDisagreement;
  }

  /** Колона 1 — „0“ основни, „1“ коригиращи, „8“ заличаващи. */
  public String getCorrectionCode() {
    return correctionCode;
  }

  /**
   * Носи ли редът суми изобщо.
   *
   * <p>⚠️ Чете се ОТ КОДА НА КОРЕКЦИЯТА, а не от сумите: заличаващият ред няма суми по указание т.
   * 2, а необлагаемият доход има бруто и нулев данък. Питани „сумата нула ли е“, двата случая биха
   * дали един и същ отговор.
   */
  public boolean carriesAmounts() {
    return !BgArt73Para1Aggregator.CORRECTION_DELETE.equals(correctionCode);
  }

  /** Колона 2 — „1“ за чуждестранно физическо лице, „0“ иначе. */
  public boolean isForeign() {
    return foreign;
  }

  /** Колона 3 — „0“ ЕГН, „1“ ЛН/ЛНЧ/Сл. №, „2“ БУЛСТАТ, „3“ чуждестранно лице без идентификатор. */
  public String getIdentifierType() {
    return identifierType;
  }

  /** Колона 9 — попълва се само за чуждестранни физически лица (указание т. 6). */
  public String getCountryCode() {
    return countryCode;
  }

  /**
   * Лицето — за колони 4 до 8.
   *
   * <p>⚠️ {@code null} ЗА СБОРНИТЕ РЕДОВЕ, и това е същото твърдение, което прави образецът:
   * указание т. 5 оставя колони 2–9 непопълнени за кодове 5031, 8141 и 820, защото такъв ред
   * обединява всички лица и няма ЕДНО, към което да сочи.
   */
  public Partner getPartner() {
    return partner;
  }

  /** Колона 10 — редът от каталога, с който доходът е подаден. */
  public BgIncomeType getIncomeType() {
    return incomeType;
  }

  /** Колона 10, само кодът — за четимост на свидетелите. */
  public String getCode() {
    return incomeType == null ? null : incomeType.getCode();
  }

  /** Сборен ли е редът — {@code isAggregate} на кода. Колони 2–9 и 15 не се попълват. */
  public boolean isAggregate() {
    return incomeType != null && Boolean.TRUE.equals(incomeType.getIsAggregate());
  }

  /** Колона 11 — брутният размер за годината. {@code null} на заличаващ ред. */
  public BigDecimal getGrossAmount() {
    return grossAmount;
  }

  /** Колона 12 — удържаните ЗОВ за годината. {@code null} на заличаващ ред. */
  public BigDecimal getInsuranceAmount() {
    return insuranceAmount;
  }

  /** Колона 13 — удържаният данък за годината. {@code null} на заличаващ ред. */
  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  /**
   * Колона 14 — брой лица.
   *
   * <p>⚠️ {@code null} НАВСЯКЪДЕ ОСВЕН ПРИ КОДОВЕ С {@code reportsPersonCount} — тоест 5031 и 8141
   * по образеца за 2025 г. Код 820 е СБОРЕН, но указание т. 5 не му дава брой лица: „Колона 14
   * „Брой лица“ се попълва само за доходи с кодове 5031 и 8141.“
   */
  public Integer getPersonCount() {
    return personCount;
  }

  /**
   * Колко реда на регистъра са слети тук.
   *
   * <p>⚠️ НЕ Е КОЛОНА НА ОБРАЗЕЦА и не е колона 14: там са ЛИЦА, тук са ПЛАЩАНИЯ. Две плащания към
   * едно лице дават едно лице и две плащания.
   */
  public int getPaymentCount() {
    return paymentCount;
  }

  /**
   * Слетите плащания се разминават по колона 2, 3 или 9 — тоест по признак на ЛИЦЕТО.
   *
   * <p>⚠️ Редът все пак се строи, с признаците на ПЪРВОТО плащане, а разминаването се съобщава на
   * човек. Отказ да се строи би скрил и останалите редове на справката заради една сгрешена клетка;
   * тихо избран признак би подал ЕДИН от двата, без никой да знае, че е имало избор.
   */
  public boolean hasIdentityDisagreement() {
    return identityDisagreement;
  }

  @Override
  public String toString() {
    return "код "
        + getCode()
        + " (корекция "
        + correctionCode
        + "): к.11="
        + grossAmount
        + " к.12="
        + insuranceAmount
        + " к.13="
        + taxAmount
        + " к.14="
        + personCount;
  }
}
