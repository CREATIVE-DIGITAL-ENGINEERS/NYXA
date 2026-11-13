package com.axelor.apps.bglocalization.service.art73.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ЕДИН РЕД ОТ ЧАСТ ІІ НА ОБРАЗЕЦ 5-2001, КАКТО ЩЕ ВЛЕЗЕ ВЪВ ФАЙЛА — колони 1 до 15.
 *
 * <p><b>⚠️ ЛОГИЧЕСКАТА структура, НЕ физическата.</b> Тук живее КАКВО носи колоната; КАК се записва
 * (ширина, разделител, подравняване, формат на датата) е работа на {@code BgArt73Para1FileLayout} и
 * идва със заповедта на изпълнителния директор на НАП. Затова полетата са типизирани — {@code
 * LocalDate}, {@code BigDecimal}, {@code Integer} — а не низове: низ тук би означавал, че някой
 * вече е решил формата, и решението щеше да е невидимо.
 *
 * <p><b>⚠️ КОЛОНА 10 Е КОДЪТ, А НЕ {@code BgIncomeType}.</b> Каталогът е жив обект с връзки към
 * базата; файлът носи низа от номенклатурата (указание за попълване т. 7). Пренесен като обект,
 * редът не би могъл да се провери без Postgres — а точно това разделение целият слой пази.
 *
 * <p><b>⚠️ {@code personCount} Е {@code Integer}, НЕ {@code int}.</b> Указание т. 5, дословно:
 * „Колона 14 „Брой лица“ се попълва САМО за доходи с кодове 5031 и 8141.“ Празно и нула са РАЗЛИЧНИ
 * твърдения; {@code int} няма как да запише първото.
 */
public final class BgArt73Para1FileRow {

  /** Код корекция „8“ — заличаващи данни; указание за попълване т. 2. */
  public static final String CORRECTION_DELETION = "8";

  private String correctionCode;
  private boolean foreignPerson;
  private String identifierType;
  private String identifier;
  private LocalDate birthDate;
  private String firstName;
  private String middleName;
  private String lastName;
  private String countryCode;
  private String incomeCode;
  private BigDecimal grossAmount;
  private BigDecimal insuranceAmount;
  private BigDecimal taxAmount;
  private Integer personCount;
  private String predecessorIdentifier;

  private boolean aggregateRow;
  private boolean reportsPersonCount;

  /**
   * Носи ли редът суми.
   *
   * <p>⚠️ Чете се от КОЛОНА 1, а не от самите суми: указание за попълване т. 2 — „При заличаване на
   * данни се попълват само колони 1, 2, 3, 4 или 5, 6, 7, 8 и 10“. Нула в колона 11 е законен
   * отговор за необлагаем доход, тоест „нула“ и „не се попълва“ не се различават по стойността.
   */
  public boolean carriesAmounts() {
    return !CORRECTION_DELETION.equals(correctionCode);
  }

  /** Колона 1 — „0“ основни, „1“ коригиращи, „8“ заличаващи. */
  public String getCorrectionCode() {
    return correctionCode;
  }

  public void setCorrectionCode(String correctionCode) {
    this.correctionCode = correctionCode;
  }

  /** Колона 2 — чуждестранно физическо лице. */
  public boolean isForeignPerson() {
    return foreignPerson;
  }

  public void setForeignPerson(boolean foreignPerson) {
    this.foreignPerson = foreignPerson;
  }

  /** Колона 3 — „0“ ЕГН, „1“ ЛН/ЛНЧ/Сл. №, „2“ БУЛСТАТ, „3“ чуждестранно без идентификатор. */
  public String getIdentifierType() {
    return identifierType;
  }

  public void setIdentifierType(String identifierType) {
    this.identifierType = identifierType;
  }

  /** Колона 4 — ⚠️ НЕ се попълва при тип „3“ (указание за попълване т. 4). */
  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }

  /** Колона 5 — дата на раждане; попълва се САМО при тип „3“. */
  public LocalDate getBirthDate() {
    return birthDate;
  }

  public void setBirthDate(LocalDate birthDate) {
    this.birthDate = birthDate;
  }

  /** Колона 6. */
  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  /** Колона 7. */
  public String getMiddleName() {
    return middleName;
  }

  public void setMiddleName(String middleName) {
    this.middleName = middleName;
  }

  /** Колона 8. */
  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  /** Колона 9 — попълва се САМО за чуждестранни физически лица (указание за попълване т. 6). */
  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  /** Колона 10 — кодът по номенклатурата след справката. */
  public String getIncomeCode() {
    return incomeCode;
  }

  public void setIncomeCode(String incomeCode) {
    this.incomeCode = incomeCode;
  }

  /** Колона 11 — БРУТНИЯТ размер, без признати разходи и без удръжки (указание т. 8). */
  public BigDecimal getGrossAmount() {
    return grossAmount;
  }

  public void setGrossAmount(BigDecimal grossAmount) {
    this.grossAmount = grossAmount;
  }

  /** Колона 12 — удържани задължителни осигурителни вноски. */
  public BigDecimal getInsuranceAmount() {
    return insuranceAmount;
  }

  public void setInsuranceAmount(BigDecimal insuranceAmount) {
    this.insuranceAmount = insuranceAmount;
  }

  /** Колона 13 — удържан данък по ЗДДФЛ. */
  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  public void setTaxAmount(BigDecimal taxAmount) {
    this.taxAmount = taxAmount;
  }

  /** Колона 14 — брой лица; {@code null} значи „не се попълва за този код“. */
  public Integer getPersonCount() {
    return personCount;
  }

  public void setPersonCount(Integer personCount) {
    this.personCount = personCount;
  }

  /** Колона 15 — ЕИК на преобразуващото се предприятие. */
  public String getPredecessorIdentifier() {
    return predecessorIdentifier;
  }

  public void setPredecessorIdentifier(String predecessorIdentifier) {
    this.predecessorIdentifier = predecessorIdentifier;
  }

  /**
   * СБОРЕН ЛИ Е РЕДЪТ — ⚠️ НЕ Е КОЛОНА, а признакът, по който указание за попълване т. 5 решава кои
   * колони мълчат: „Колони от 2 до 9, Част ІІ НЕ се попълват за изплатени доходи с кодове 5031,
   * 8141 и 820.“
   *
   * <p><b>⚠️ ИДВА ОТ КАТАЛОГА ({@code BgIncomeType.isAggregate}), А НЕ ОТ СПИСЪК В КОД.</b> Кои
   * кодове са сборни се сменя със ЗАПОВЕД на изпълнителния директор на НАП заедно с номенклатурата.
   * Изброени тук, трите кода биха оцелели следващата заповед мълчаливо — тоест валидаторът щеше да
   * изисква имена за код, който вече е сборен, и да ги забранява за код, който вече не е.
   */
  public boolean isAggregateRow() {
    return aggregateRow;
  }

  public void setAggregateRow(boolean aggregateRow) {
    this.aggregateRow = aggregateRow;
  }

  /**
   * ИСКА ЛИ КОДЪТ КОЛОНА 14 — указание за попълване т. 5: „Колона 14 „Брой лица“ се попълва САМО за
   * доходи с кодове 5031 и 8141.“
   *
   * <p>⚠️ ВТОРИ ПРИЗНАК, А НЕ ВТОРО ЧЕТЕНЕ НА {@link #isAggregateRow()}: код 820 е сборен, но брой
   * лица НЕ носи. Изведена от „сборен“, колона 14 би получила число точно там, където образецът я
   * оставя празна. Същото разграничение, което пази и самият каталог.
   */
  public boolean isReportsPersonCount() {
    return reportsPersonCount;
  }

  public void setReportsPersonCount(boolean reportsPersonCount) {
    this.reportsPersonCount = reportsPersonCount;
  }
}
