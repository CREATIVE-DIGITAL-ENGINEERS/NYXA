package com.axelor.apps.bglocalization.service.zddfl;

/**
 * ЕДНА НАХОДКА НА ВАЛИДАТОР — код, тежест и текст за човека.
 *
 * <p><b>⚠️ СОБСТВЕН КЛАС, А НЕ {@code BgVatValidationErrorDto}.</b> Онзи носи и ДДС-специфична
 * котва (номер на документ, контрагент), а тукашните находки сочат КОЛОНА на образеца.
 * Преизползван, той би приканил колоната да се записва в поле за „документ“ и съобщението към
 * човека щеше да говори за фактура там, където образецът говори за ред и колона.
 *
 * <p>⚠️ Тежестта решава дали файлът СЕ ПРОИЗВЕЖДА, а не само как изглежда екранът: указание т. 6
 * към образец 5-2001, дословно — „Справка, която не съдържа всички изискуеми данни, не се счита за
 * достоверна и не се приема!“. Произведен въпреки грешка, файлът пътува до портала, за да бъде
 * отхвърлен там.
 *
 * <p><b>⚠️ ЗАПИСЪТ, КЪМ КОЙТО СОЧИ</b> ({@link #getTargetModel()} + {@link #getTargetId()}) е
 * незадължителен: находка за файла като цяло (празна таблица, липсваща настройка) няма такъв.
 * Където го има, екранът отваря записа, за да се поправи там — затова находката е ПО ЕДНА НА ЗАПИС,
 * а не списък от етикети, слети в текста (SAF-T G6a, решение от 14.10.2026).
 */
public final class BgDeclarationFileFinding {

  public static final String SEVERITY_ERROR = "ERROR";
  public static final String SEVERITY_WARNING = "WARNING";

  private final String code;
  private final String severity;
  private final String message;
  private final String targetModel;
  private final Long targetId;

  private BgDeclarationFileFinding(String code, String severity, String message) {
    this(code, severity, message, null, null);
  }

  private BgDeclarationFileFinding(
      String code, String severity, String message, String targetModel, Long targetId) {
    this.code = code;
    this.severity = severity;
    this.message = message;
    this.targetModel = targetModel;
    this.targetId = targetId;
  }

  /** Блокира произвеждането на файла. */
  public static BgDeclarationFileFinding error(String code, String message) {
    return new BgDeclarationFileFinding(code, SEVERITY_ERROR, message);
  }

  /** Не блокира — човекът решава. */
  public static BgDeclarationFileFinding warning(String code, String message) {
    return new BgDeclarationFileFinding(code, SEVERITY_WARNING, message);
  }

  /**
   * Същата находка, сочеща записа {@code model} #{@code id} — пълното име на класа (напр. {@code
   * com.axelor.apps.base.db.Partner}). {@code id} може да е {@code null}, когато е известен само
   * видът на записа.
   */
  public BgDeclarationFileFinding at(String model, Long id) {
    return new BgDeclarationFileFinding(code, severity, message, model, id);
  }

  public String getCode() {
    return code;
  }

  public String getSeverity() {
    return severity;
  }

  public String getMessage() {
    return message;
  }

  /** Пълното име на класа на записа; {@code null} за находка за файла като цяло. */
  public String getTargetModel() {
    return targetModel;
  }

  /** {@code null}, когато находката не сочи конкретен запис. */
  public Long getTargetId() {
    return targetId;
  }

  public boolean isBlocking() {
    return SEVERITY_ERROR.equals(severity);
  }

  @Override
  public String toString() {
    String target =
        targetModel == null
            ? ""
            : " → " + targetModel.substring(targetModel.lastIndexOf('.') + 1) + "#" + targetId;
    return "[" + severity + "] " + code + ": " + message + target;
  }
}
