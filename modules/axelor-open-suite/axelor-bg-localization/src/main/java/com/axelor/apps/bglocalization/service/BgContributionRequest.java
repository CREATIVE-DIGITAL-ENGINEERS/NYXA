package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable input for one social-insurance contribution calculation.
 *
 * <p>{@code insurableIncome} is already resolved — the caller has applied the МОД floor and the
 * maximum insurable income cap. The calculator does not touch it. Likewise {@code tzpbRate} is the
 * already-resolved ТЗПБ rate for the employer's economic activity (see {@code
 * BgInsuranceCalculationService#findTzpbRate}); it is a percent number (0.9 means 0.9%).
 *
 * <p>The УПФ cohort is not passed in — it is derived from {@code bornAfter1959} and {@code
 * pensionChoice} by {@link BgContributionResolver#deriveCohort}.
 */
public class BgContributionRequest {

  private final BigDecimal insurableIncome;
  private final String insuredTypeCode;
  private final String laborCategory;
  private final boolean bornAfter1959;
  private final String pensionChoice;
  private final String solScope;
  private final LocalDate referenceDate;
  private final BigDecimal tzpbRate;
  private final String compensationTypeCode;
  private final String teacherScope;

  /** {@code bg.insurance.teacher.scope.select} — стойността на заявката за неучителска длъжност. */
  public static final String NON_TEACHER = "NON_TEACHER";

  /** {@code bg.insurance.teacher.scope.select} — стойността на заявката за учителска длъжност. */
  public static final String TEACHER = "TEACHER";

  /**
   * Пълен конструктор. {@code compensationTypeCode} е кодът на вида обезщетение (напр.
   * comp_kt_222_1), или STANDARD когато плащането не е обезщетение — стойността идва от реда във
   * фиша, не от екрана. {@code teacherScope} е TEACHER, ако договорът е с учителска длъжност, и
   * NON_TEACHER във всички останали случаи — стойността ANY е само на редовете, не на заявката.
   */
  public BgContributionRequest(
      BigDecimal insurableIncome,
      String insuredTypeCode,
      String laborCategory,
      boolean bornAfter1959,
      String pensionChoice,
      String solScope,
      LocalDate referenceDate,
      BigDecimal tzpbRate,
      String compensationTypeCode,
      String teacherScope) {
    this.insurableIncome = insurableIncome;
    this.insuredTypeCode = insuredTypeCode;
    this.laborCategory = laborCategory;
    this.bornAfter1959 = bornAfter1959;
    this.pensionChoice = pensionChoice;
    this.solScope = solScope;
    this.referenceDate = referenceDate;
    this.tzpbRate = tzpbRate;
    this.compensationTypeCode =
        (compensationTypeCode == null || compensationTypeCode.isEmpty())
            ? "STANDARD"
            : compensationTypeCode;
    this.teacherScope =
        (teacherScope == null || teacherScope.isEmpty()) ? NON_TEACHER : teacherScope;
  }

  /**
   * Както пълния конструктор, с NON_TEACHER за учителския обхват.
   *
   * <p>Подразбирането е стеснение, не разширение: то само изключва реда за учителския фонд, който
   * днес съществува единствено под вид осигурен 04. Но щом такъв ред се зареди, викащ, който има
   * трудов договор и не подаде обхвата, ще пропусне УчПФ тихо — затова всеки викащ с договор в
   * ръцете подава изрично, а този конструктор е само за случаите без договор.
   */
  public BgContributionRequest(
      BigDecimal insurableIncome,
      String insuredTypeCode,
      String laborCategory,
      boolean bornAfter1959,
      String pensionChoice,
      String solScope,
      LocalDate referenceDate,
      BigDecimal tzpbRate,
      String compensationTypeCode) {
    this(
        insurableIncome,
        insuredTypeCode,
        laborCategory,
        bornAfter1959,
        pensionChoice,
        solScope,
        referenceDate,
        tzpbRate,
        compensationTypeCode,
        NON_TEACHER);
  }

  /**
   * Съкратен конструктор за обикновена заплата (не обезщетение) — {@code compensationTypeCode} по
   * подразбиране STANDARD, за да продължат съществуващите ползвания без промяна.
   */
  public BgContributionRequest(
      BigDecimal insurableIncome,
      String insuredTypeCode,
      String laborCategory,
      boolean bornAfter1959,
      String pensionChoice,
      String solScope,
      LocalDate referenceDate,
      BigDecimal tzpbRate) {
    this(
        insurableIncome,
        insuredTypeCode,
        laborCategory,
        bornAfter1959,
        pensionChoice,
        solScope,
        referenceDate,
        tzpbRate,
        "STANDARD");
  }

  public BigDecimal getInsurableIncome() {
    return insurableIncome;
  }

  public String getInsuredTypeCode() {
    return insuredTypeCode;
  }

  public String getLaborCategory() {
    return laborCategory;
  }

  public boolean isBornAfter1959() {
    return bornAfter1959;
  }

  public String getPensionChoice() {
    return pensionChoice;
  }

  public String getSolScope() {
    return solScope;
  }

  public LocalDate getReferenceDate() {
    return referenceDate;
  }

  public BigDecimal getTzpbRate() {
    return tzpbRate;
  }

  public String getCompensationTypeCode() {
    return compensationTypeCode;
  }

  /** TEACHER или NON_TEACHER — никога ANY: ANY е стойност на реда, не на заявката. */
  public String getTeacherScope() {
    return teacherScope;
  }
}
