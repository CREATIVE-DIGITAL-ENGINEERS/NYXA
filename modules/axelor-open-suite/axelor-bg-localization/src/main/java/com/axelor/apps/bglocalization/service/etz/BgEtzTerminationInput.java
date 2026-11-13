package com.axelor.apps.bglocalization.service.etz;

import java.time.LocalDate;

/**
 * РЕКВИЗИТИТЕ НА ПРЕКРАТЯВАНЕТО, КОИТО <b>ДОГОВОРЪТ НЕ ПАЗИ</b> — подавани изрично, не измисляни.
 *
 * <p><b>Защо изричен вход, а не поле на договора или сметка наум.</b> Приложение № 1 иска при тип
 * на документа 3 и вид правоотношение 01 още две стойности: т. 25 (използван платен годишен отпуск
 * за годината на прекратяване) и т. 30 (признат трудов стаж, ггммдд). Нито едната не е на договора:
 *
 * <ul>
 *   <li><b>т. 25</b> е ИЗПОЛЗВАНИЯТ отпуск за конкретната година — извежда се от отпуските за
 *       периода, а {@code bgAnnualLeaveDays} на договора е ПОЛАГАЕМИЯТ. Двете се бъркат лесно и
 *       разликата е точно това, което се обезщетява по чл. 224 КТ.
 *   <li><b>т. 30</b> е стажът, натрупан ПО ТОЗИ ДОГОВОР. {@code bgPriorExperience*} описва стажа
 *       ПРЕДИ него — пренесено, то би обявило чужд стаж като придобит при този работодател.
 * </ul>
 *
 * <p><b>⚠️ И ЗАТОВА НЯМА ПОДРАЗБИРАНЕ.</b> Липсващ вход не става „0 дни“ и не се смята от датите:
 * нула използван отпуск е ИЗЯВЛЕНИЕ, а стаж, сметнат като разлика между двете дати, мълчаливо
 * приема, че няма непризнато време — а т. 31 съществува точно защото го има. Липсата се обявява
 * като пречка с назована причина.
 *
 * <p><b>⚠️ СТАЖЪТ Е 6 ПОЗИЦИИ „ггммдд“, А НЕ БРОЙ ДНИ.</b> Наредбата (т. 30–32): „първите две за
 * години, вторите две за месеци, третите две за дни“. Тоест 2 години, 3 месеца и 15 дни е {@code
 * "020315"}, а не 830. Затова типът е низ — число би загубило водещите нули.
 */
public final class BgEtzTerminationInput {

  private Integer usedVacationDays;
  private String recognisedExperience;
  private String notRecognisedExperience;
  private String civilServicePeriod;
  private LocalDate compensation222PaidOn;
  private LocalDate compensation106PaidOn;
  private Boolean garnishment;

  /** т. 25 — дни използван платен годишен отпуск за годината на прекратяване. */
  public Integer getUsedVacationDays() {
    return usedVacationDays;
  }

  public BgEtzTerminationInput setUsedVacationDays(Integer value) {
    this.usedVacationDays = value;
    return this;
  }

  /** т. 30 — признат трудов стаж, 6 позиции „ггммдд“. */
  public String getRecognisedExperience() {
    return recognisedExperience;
  }

  public BgEtzTerminationInput setRecognisedExperience(String value) {
    this.recognisedExperience = value;
    return this;
  }

  /**
   * т. 31 — НЕпризнат трудов стаж, „ггммдд“.
   *
   * <p>⚠️ Незадължителна по смисъл, не само по схема: приложението я иска „само когато ИМА
   * продължителност на време, което не се признава“. Празна значи „няма такова време“.
   */
  public String getNotRecognisedExperience() {
    return notRecognisedExperience;
  }

  public BgEtzTerminationInput setNotRecognisedExperience(String value) {
    this.notRecognisedExperience = value;
    return this;
  }

  /** т. 32 — служебен стаж, „ггммдд“; иска се при вид правоотношение 02 или 03. */
  public String getCivilServicePeriod() {
    return civilServicePeriod;
  }

  public BgEtzTerminationInput setCivilServicePeriod(String value) {
    this.civilServicePeriod = value;
    return this;
  }

  /** т. 28 — ДАТАТА на изплащане на обезщетението по чл. 222 ал. 2 КТ, не сумата. */
  public LocalDate getCompensation222PaidOn() {
    return compensation222PaidOn;
  }

  public BgEtzTerminationInput setCompensation222PaidOn(LocalDate value) {
    this.compensation222PaidOn = value;
    return this;
  }

  /** т. 29 — ДАТАТА по чл. 222 ал. 3 КТ / чл. 106 ал. 3 ЗДСл. */
  public LocalDate getCompensation106PaidOn() {
    return compensation106PaidOn;
  }

  public BgEtzTerminationInput setCompensation106PaidOn(LocalDate value) {
    this.compensation106PaidOn = value;
    return this;
  }

  /** т. 33 — запорни съобщения по чл. 512 ал. 5 ГПК: 0 няма, 1 има. */
  public Boolean getGarnishment() {
    return garnishment;
  }

  public BgEtzTerminationInput setGarnishment(Boolean value) {
    this.garnishment = value;
    return this;
  }
}
