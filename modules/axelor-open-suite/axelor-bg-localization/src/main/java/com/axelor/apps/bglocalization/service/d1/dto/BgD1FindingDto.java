package com.axelor.apps.bglocalization.service.d1.dto;

/**
 * Една находка на емитера или на валидатора — <b>с назована причина</b>.
 *
 * <p><b>Защо своя, а не {@code BgVatValidationErrorDto}.</b> ДДС-находката носи {@code documentRef}
 * (номер на фактура) и говори за документ. Тази носи фиш, вид осигурен и ТОЧКА от Приложение № 1.
 * Общ клас за двете би значел четири полета, от които две са празни при всяка употреба, и въпрос
 * „кое поле важи сега“ на всяко място, което ги чете.
 *
 * <p><b>Трите тежести не са степени на едно и също нещо.</b> {@link #SEVERITY_REFUSAL} значи „запис
 * НЕ се произвежда“ — фишът остава извън декларацията и причината е обявена. {@link
 * #SEVERITY_ERROR} значи „записът съществува, но НАП няма да го приеме“. {@link #SEVERITY_WARNING}
 * значи „записът е валиден, но нещо в него не е установено“ — човекът, който подписва, трябва да го
 * види.
 */
public class BgD1FindingDto {

  /** Фишът е ИЗКЛЮЧЕН от декларацията. Причината е назована, защото мълчанието тук е скъпо. */
  public static final String SEVERITY_REFUSAL = "REFUSAL";

  /** Записът нарушава правило на наредбата — не се приема (чл. 11 ал. 1 и заб. към точките). */
  public static final String SEVERITY_ERROR = "ERROR";

  /** Записът е валиден, но нещо в него е ПРЕДПОЛОЖЕНО, не установено. */
  public static final String SEVERITY_WARNING = "WARNING";

  private String code;
  private String severity = SEVERITY_ERROR;
  private String message;

  /** Кой фиш — номерът му ({@code BgPaySlip.reference}), за да е намираем от човек. */
  private String paySlipRef;

  /** Кой вид осигурен — почти всяко правило на Приложение № 1 виси на него. */
  private String insuredTypeCode;

  /** Коя точка от Приложение № 1, ако находката е за конкретна точка. */
  private String point;

  public BgD1FindingDto() {}

  public BgD1FindingDto(String code, String severity, String message) {
    this.code = code;
    this.severity = severity;
    this.message = message;
  }

  /** Четимият ред за екрана и за отчета — причината, не код за грешка. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(severity).append(" · ").append(code);
    if (paySlipRef != null && !paySlipRef.isEmpty()) {
      sb.append(" · фиш ").append(paySlipRef);
    }
    if (insuredTypeCode != null && !insuredTypeCode.isEmpty()) {
      sb.append(" · вид осигурен ").append(insuredTypeCode);
    }
    if (point != null && !point.isEmpty()) {
      sb.append(" · т. ").append(point);
    }
    return sb.append(" — ").append(message).toString();
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getPaySlipRef() {
    return paySlipRef;
  }

  public void setPaySlipRef(String paySlipRef) {
    this.paySlipRef = paySlipRef;
  }

  public String getInsuredTypeCode() {
    return insuredTypeCode;
  }

  public void setInsuredTypeCode(String insuredTypeCode) {
    this.insuredTypeCode = insuredTypeCode;
  }

  public String getPoint() {
    return point;
  }

  public void setPoint(String point) {
    this.point = point;
  }

  public BgD1FindingDto on(String paySlipRef, String insuredTypeCode) {
    this.paySlipRef = paySlipRef;
    this.insuredTypeCode = insuredTypeCode;
    return this;
  }

  public BgD1FindingDto at(String point) {
    this.point = point;
    return this;
  }
}
