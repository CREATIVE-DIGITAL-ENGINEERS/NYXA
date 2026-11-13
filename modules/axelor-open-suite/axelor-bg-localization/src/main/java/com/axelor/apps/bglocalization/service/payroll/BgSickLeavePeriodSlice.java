package com.axelor.apps.bglocalization.service.payroll;

import java.time.LocalDate;

/**
 * ЕДИН РЕД ОТ Т. 9 НА ПРИЛОЖЕНИЕ № 9, ОЩЕ БЕЗ БАЗА — дните на ЕДИН лист, в ЕДИН месец, за ЕДИН
 * платец.
 *
 * <p><b>Защо втори клас до {@code BgSickLeavePeriodLine}, а не самото entity.</b> Същият довод,
 * който дели {@link BgSickLeaveResolver} от {@code BgSickLeaveService}: разбивът се смята в чиста
 * функция и се проверява без Postgres, без Guice и без Hibernate. Резолвер, който строи entity-та,
 * спира да е проверим без база — а точно този разбив е онова, което трябва да се докаже, че НЕ
 * мести пари.
 *
 * <p><b>⚠️ ТОВА НЕ Е ВТОРА СМЕТКА, А ВТОРИ ИЗГЛЕД.</b> Срезовете се строят в СЪЩИЯ обход и от
 * същите примитиви, от които {@link BgSickLeaveResolver#split} смята {@code employerDays} и {@code
 * dooDays}; двата броя НЕ се изчисляват наново от срезовете. Сверката между двата изгледа е {@link
 * BgSickLeaveDays#linesReconcile()} — тя пита дали сборът на срезовете плюс непокритите дни дава
 * агрегатите, и е закована от свидетел.
 *
 * <p><b>⚠️ ЧАСОВЕТЕ ГИ НЯМА ТУК, И ТОВА Е НАРОЧНО.</b> Дневното работно време е факт на
 * ПРАВООТНОШЕНИЕТО, не на разделянето на дните — влиза при записа ({@code
 * BgSickLeaveService#applyPeriodLines}), където се знае и режимът на отчитане. Внесено тук, чистата
 * функция щеше да иска вход, от който отговорът ѝ не зависи.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgSickLeavePeriodSlice {

  /** Стойността на {@link #payer} за първите дни по чл. 40 ал. 5 КСО — за сметка на осигурителя. */
  public static final String PAYER_EMPLOYER = "EMPLOYER";

  /**
   * Стойността на {@link #payer} за останалите дни — обезщетението ги плаща НОИ, не осигурителят.
   */
  public static final String PAYER_NOI = "NOI";

  /**
   * Идентификаторът на {@code BgSickLeave}, на който този ред принадлежи. Единица: няма.
   *
   * <p>⚠️ {@code null} значи, че епизодът е дошъл БЕЗ документ зад себе си — така пристигат
   * епизодите в тестовете и така би пристигнал епизод от бъдещ вход, който не е болничен лист.
   * Такъв срез се смята, показва се в обяснението, но НЕ се записва: ред без лист няма къде да
   * виси, а Приложение № 9 се подава за конкретен лист (чл. 8 ал. 1 НПОПДОО).
   */
  public final Long sickLeaveId;

  /**
   * Номерът на листа. Единица: няма. Само за обяснението — записът се намира по {@link
   * #sickLeaveId}.
   */
  public final String documentNumber;

  /** Кодът от {@code BgAbsenceType.code}. Единица: код. За проследимост в обяснението. */
  public final String absenceTypeCode;

  /**
   * ПЪРВИЯТ КАЛЕНДАРЕН ден на листа в този месец. Единица: ДАТА (календарна).
   *
   * <p>⚠️ КАЛЕНДАРЕН, а не първият РАБОТЕН. Указанието към т. 9 е дословно: „в колони «От ден» и
   * «До ден включително» се попълват първият и последният КАЛЕНДАРЕН ден на периода по болничния
   * лист за съответния месец“. Оттам болничен, започнал в събота, носи съботата.
   */
  public final LocalDate fromDay;

  /** ПОСЛЕДНИЯТ КАЛЕНДАРЕН ден на листа в този месец. Единица: ДАТА (календарна). */
  public final LocalDate toDay;

  /**
   * Работните дни в този месец за този платец. Единица: РАБОТНИ ДНИ.
   *
   * <p>Винаги > 0: срез с нула дни не се създава — той не е ред от т. 9, а липса на ред.
   */
  public final int workDays;

  /** {@link #PAYER_EMPLOYER} или {@link #PAYER_NOI}. Единица: код. Никога {@code null}. */
  public final String payer;

  public BgSickLeavePeriodSlice(
      Long sickLeaveId,
      String documentNumber,
      String absenceTypeCode,
      LocalDate fromDay,
      LocalDate toDay,
      int workDays,
      String payer) {
    this.sickLeaveId = sickLeaveId;
    this.documentNumber = documentNumber;
    this.absenceTypeCode = absenceTypeCode;
    this.fromDay = fromDay;
    this.toDay = toDay;
    this.workDays = workDays;
    this.payer = payer;
  }

  /** Годината на реда — извежда се от котвата {@link #fromDay}, не се носи отделно. */
  public int year() {
    return fromDay.getYear();
  }

  /** Месецът на реда (1–12) — извежда се от котвата {@link #fromDay}. */
  public int month() {
    return fromDay.getMonthValue();
  }

  /** За сметка на осигурителя ли са дните на този ред. */
  public boolean isEmployerPaid() {
    return PAYER_EMPLOYER.equals(payer);
  }

  /** Може ли този срез да се запише — ред без лист няма къде да виси. */
  public boolean isPersistable() {
    return sickLeaveId != null;
  }

  @Override
  public String toString() {
    return "BgSickLeavePeriodSlice["
        + documentNumber
        + " "
        + fromDay
        + " – "
        + toDay
        + ", "
        + workDays
        + " дни, "
        + payer
        + "]";
  }
}
