package com.axelor.apps.bglocalization.service.art73.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ЕДНО ФИЗИЧЕСКО ЛИЦЕ В ЧАСТ ІІ — {@code part2/rowsenum} по {@code dec736}.
 *
 * <p><b>⚠️ ЕДИН ЗАПИС НА ЛИЦЕ, А НЕ НА ПЛАЩАНЕ — ТОВА Е СТРУКТУРНАТА РАЗЛИКА С АЛ. 1.</b> При ал. 1
 * ({@code dec731}) записът е плащане и лицето се повтаря на всеки ред. Тук лицето е записът, а
 * плащанията му са ВЛОЖЕНИ в {@link #getIncomeRows()}. Затова и „Ред и формат“ т. 14 казва, че
 * корекцията е за ЦЯЛАТА Част ІІ на лицето: коригира се записът, не отделно плащане.
 *
 * <p><b>⚠️ {@code ismainemployer} Е НА ЛИЦЕТО, А НЕ НА РЕД.</b> В базата признакът стои на всеки
 * {@code BgArt73Para6Record}, тоест едно лице може да дойде с противоречиви редове. Мълчаливо
 * избран (пръв срещнат, „или“, „и“), той щеше да реши вместо човека дали се подава годишно
 * изравняване — и разликата се вижда чак при засичане от НАП. Събирачът го изважда ВЕДНЪЖ, а
 * несъгласието е находка на валидатора.
 *
 * <p><b>⚠️ ЧАСТТА „ДОХОДИ“ Е ЦЯЛА ИЛИ НИКАКВА.</b> {@code incomedata} е {@code minOccurs="0"} по
 * схемата, но носи задължителния {@code sumtaxdeducted}. Тоест лице без нито един ред доход е
 * структурно валидно и елементът просто се пропуска; има ли поне един ред — общият удържан данък е
 * задължителен.
 */
public final class BgArt73Para6FilePersonRow {

  private String correctionCode;
  private String firstName;
  private String middleName;
  private String lastName;
  private String identifierType;
  private String identifier;
  private boolean mainEmployer;
  private boolean mainEmployerConflict;

  private final List<BgArt73Para6FileIncomeRow> incomeRows = new ArrayList<>();

  private BigDecimal taxBaseArt25Para1;
  private BigDecimal taxBaseArt25Para3;
  private BgArt73Para6FileAnnual annual;
  private BigDecimal totalTaxWithheld;

  /** {@code correctioncode} — 0 основни, 1 коригиращи, 8 заличаващи. */
  public String getCorrectionCode() {
    return correctionCode;
  }

  public void setCorrectionCode(String correctionCode) {
    this.correctionCode = correctionCode;
  }

  /** {@code firstname} — собствено име. ЗАДЪЛЖИТЕЛНО и по схемата ({@code minOccurs="1"}). */
  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  /** {@code secondname} — презиме. Незадължително: не всяко лице има презиме. */
  public String getMiddleName() {
    return middleName;
  }

  public void setMiddleName(String middleName) {
    this.middleName = middleName;
  }

  /** {@code thirdname} — фамилно име. ЗАДЪЛЖИТЕЛНО и по схемата. */
  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  /**
   * {@code identtype} — САМО „0“ (ЕГН) или „1“ (ЛН/ЛНЧ/Сл. № от регистъра на НАП).
   *
   * <p>⚠️ ДВЕ стойности, не четири: ал. 1 познава и „2“ (БУЛСТАТ), и „3“ (без идентификатор), но по
   * трудово правоотношение лице без идентификатор няма. Преведено с общ превод, „2“ би паднало на
   * схемата.
   */
  public String getIdentifierType() {
    return identifierType;
  }

  public void setIdentifierType(String identifierType) {
    this.identifierType = identifierType;
  }

  /** {@code ident} — ЕГН/ЛН/ЛНЧ/Сл. №. ЗАДЪЛЖИТЕЛЕН и по схемата. */
  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }

  /** {@code ismainemployer} — работодател по ОСНОВНО трудово правоотношение към 31 декември. */
  public boolean isMainEmployer() {
    return mainEmployer;
  }

  public void setMainEmployer(boolean mainEmployer) {
    this.mainEmployer = mainEmployer;
  }

  /**
   * РАЗМИНАХА ЛИ СЕ ЗАПИСАНИТЕ РЕДОВЕ НА ЛИЦЕТО ПО {@code ismainemployer}.
   *
   * <p><b>⚠️ ОТКРИВА СЕ ОТ СЪБИРАЧА, НАЗОВАВА СЕ ОТ ВАЛИДАТОРА.</b> Признакът е на ЛИЦЕ във файла и
   * на РЕД в базата; два реда на едно лице могат да си противоречат. Събирачът е единственият,
   * който още вижда отделните редове — стигне ли до валидатора, лицето вече е един запис с една
   * стойност и противоречието е невъзстановимо. Затова се пренася като ФАКТ, а решението „какво
   * значи това“ остава на валидатора.
   */
  public boolean isMainEmployerConflict() {
    return mainEmployerConflict;
  }

  public void setMainEmployerConflict(boolean mainEmployerConflict) {
    this.mainEmployerConflict = mainEmployerConflict;
  }

  /** Вложените редове доход — {@code incomedata/incomerows/rowsenum}, поне един, ако има блок. */
  public List<BgArt73Para6FileIncomeRow> getIncomeRows() {
    return incomeRows;
  }

  /**
   * {@code taxbase251} — годишна данъчна основа по чл. 25 ал. 1 (за доходите с код <b>101</b>).
   *
   * <p>⚠️ Попълва се САМО от работодател по основно трудово правоотношение.
   */
  public BigDecimal getTaxBaseArt25Para1() {
    return taxBaseArt25Para1;
  }

  public void setTaxBaseArt25Para1(BigDecimal taxBaseArt25Para1) {
    this.taxBaseArt25Para1 = taxBaseArt25Para1;
  }

  /**
   * {@code taxbase253} — годишна данъчна основа по чл. 25 ал. 3 (за доходите с код <b>103</b>,
   * морско лице; основата е 10 на сто).
   */
  public BigDecimal getTaxBaseArt25Para3() {
    return taxBaseArt25Para3;
  }

  public void setTaxBaseArt25Para3(BigDecimal taxBaseArt25Para3) {
    this.taxBaseArt25Para3 = taxBaseArt25Para3;
  }

  /** {@code taxbase49} — блокът за годишното изравняване; {@code null}, ако лицето няма такъв. */
  public BgArt73Para6FileAnnual getAnnual() {
    return annual;
  }

  public void setAnnual(BgArt73Para6FileAnnual annual) {
    this.annual = annual;
  }

  /**
   * {@code sumtaxdeducted} — общият удържан данък за доходите от трудови правоотношения.
   *
   * <p>⚠️ Сборът в колона 7 + колона 20 − колона 21. ЗАДЪЛЖИТЕЛЕН, когато има {@code incomedata}.
   */
  public BigDecimal getTotalTaxWithheld() {
    return totalTaxWithheld;
  }

  public void setTotalTaxWithheld(BigDecimal totalTaxWithheld) {
    this.totalTaxWithheld = totalTaxWithheld;
  }

  /** Има ли изобщо какво да се сложи в {@code incomedata}. */
  public boolean hasIncomeData() {
    return !incomeRows.isEmpty();
  }
}
