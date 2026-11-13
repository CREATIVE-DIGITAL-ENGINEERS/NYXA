package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ЕДИН ЕПИЗОД ВРЕМЕННА НЕРАБОТОСПОСОБНОСТ, ВЕЧЕ СЪПОСТАВЕН С РЕЧНИКА.
 *
 * <p><b>Защо втори клас до {@code BgPayrollInput.SickLeaveEpisode}, а не същият.</b> Контрактът
 * носи КОД на вида отсъствие — низ, стабилен между среди. Кой е платецът на първите дни и колко на
 * сто е обезщетението е в {@code BgAbsenceType}, тоест В БАЗАТА. Ако чистият резолвер приемаше
 * кода, той щеше да трябва да чете речника, и половината му щеше да спре да е чиста.
 *
 * <p>Затова разделянето е тук: {@code BgSickLeaveService} чете речника ВЕДНЪЖ и подава епизодите с
 * вече прикачени атрибути; {@link BgSickLeaveResolver} смята по стойности и се тества без база.
 *
 * <p><b>⚠️ ПЛАТЕЦЪТ И ПРОЦЕНТЪТ СА ДАННИ, НЕ КОД.</b> И двете идват от {@code BgAbsenceType} —
 * {@code payer} и {@code compensationPct} — а не от списък в Java. Оттам „осигурителят плаща 70 на
 * сто“ се сменя в CSV-то, а не в резолвера, и е ЕДНО и също число за фиша, за справките и за
 * речника, който ТРЗ вижда на екрана.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgSickLeaveEpisode {

  /**
   * Стойността на {@code BgAbsenceType.payer}, при която първите дни са за сметка на осигурителя.
   */
  public static final String PAYER_EMPLOYER = "employer";

  /** Първи ден на неработоспособността. Единица: ДАТА (календарна). Не {@code null}. */
  public final LocalDate fromDate;

  /** Последен ден. Единица: ДАТА (календарна). Не {@code null}. */
  public final LocalDate toDate;

  /**
   * Продължение на предходен епизод ли е.
   *
   * <p>⚠️ Полето, което прави разликата между „нов болничен“ и „продължение“: при второто броенето
   * на дните за сметка на осигурителя НЕ започва отначало. Виж {@link BgSickLeaveResolver#split}.
   */
  public final boolean continuation;

  /**
   * Кодът от {@code BgAbsenceType.code} — напр. „sick_er“. Единица: код. За проследимост и правила.
   */
  public final String absenceTypeCode;

  /**
   * {@code BgAbsenceType.payer} — „employer“, „doo“ или „none“. Единица: код.
   *
   * <p>⚠️ {@code null} значи, че видът НЕ Е НАМЕРЕН в речника, а НЕ че платец няма. Двете водят до
   * различен изход — отказ срещу нула дни — и затова полето е низ, а не булево.
   */
  public final String payer;

  /**
   * {@code BgAbsenceType.compensationPct} — процентът на обезщетението. Единица: ПРОЦЕНТ.
   *
   * <p>{@code null} значи „не е попълнен в речника“, а нула — „вид без обезщетение“. Двете НЕ се
   * сливат: първото е отказ, второто е законен нула.
   */
  public final BigDecimal compensationPct;

  /** Номер на болничния лист. Единица: няма. Само за проследимост в обяснението на сумата. */
  public final String documentNumber;

  /**
   * Идентификаторът на {@code BgSickLeave}, от който идва епизодът. Единица: няма.
   *
   * <p><b>⚠️ ЗАЩО ИД, СЛЕД КАТО НОМЕРЪТ ВЕЧЕ Е ТУК.</b> Номерът НЕ Е КЛЮЧ: домейнът изрично го
   * оставя без {@code unique}, защото един лист поражда по един епизод за ВСЕКИ договор на лицето и
   * законно се среща няколко пъти. Разбивът по т. 9 обаче трябва да се ЗАПИШЕ обратно върху точния
   * запис — по номер това е търсене с няколко възможни отговора.
   *
   * <p>⚠️ {@code null} е законно и значи „епизодът няма документ зад себе си“: така пристигат
   * епизодите в тестовете и така би пристигнал епизод от бъдещ вход, който не е болничен лист.
   * Тогава дните се смятат както винаги, но ред по т. 9 не се записва — виж {@link
   * BgSickLeavePeriodSlice#isPersistable()}.
   */
  public final Long sickLeaveId;

  /**
   * {@code BgAbsenceType.category} — РИСКЪТ по {@code bg.absence.category.select}: 2 „Болнични“, 3
   * „Злополука“, 4 „Майчинство-Бащинство“. Единица: код.
   *
   * <p><b>⚠️ ПРАВОТО СЕ ИЗЧИСЛЯВА ПО НЕГО, НЕ ПО ПЛАТЕЦА.</b> Прагът от шест месеца по чл. 40 ал. 1
   * КСО е за ВРЕМЕННАТА НЕРАБОТОСПОСОБНОСТ — болест, гледане на болен, битова злополука, карантина
   * — независимо дали първите дни плаща осигурителят или ДОО. Майчинството има СВОЙ праг (чл. 48а,
   * дванайсет месеца) и не е предмет на тази присъда. Виж {@link
   * BgSickLeaveResolver#RISK_MATERNITY_CATEGORY}.
   *
   * <p>⚠️ {@code null} значи „не е подадена“ — така идват епизодите в заварените тестове — и
   * резолверът я чете ПО-СТРОГО: прагът важи. Обратното четене би освободило от стаж всеки епизод с
   * непопълнена категория, тоест тихо.
   */
  public final Integer category;

  /**
   * Епизод БЕЗ документ зад себе си — дните се смятат, ред по т. 9 не се записва.
   *
   * <p>Запазен нарочно: подписът е на цялата заварена сметка и смяната му щеше да пипне всеки
   * свидетел на болничния, за да добави {@code null} на едно място.
   */
  public BgSickLeaveEpisode(
      LocalDate fromDate,
      LocalDate toDate,
      boolean continuation,
      String absenceTypeCode,
      String payer,
      BigDecimal compensationPct,
      String documentNumber) {
    this(
        fromDate,
        toDate,
        continuation,
        absenceTypeCode,
        payer,
        compensationPct,
        documentNumber,
        null);
  }

  public BgSickLeaveEpisode(
      LocalDate fromDate,
      LocalDate toDate,
      boolean continuation,
      String absenceTypeCode,
      String payer,
      BigDecimal compensationPct,
      String documentNumber,
      Long sickLeaveId) {
    this(
        fromDate,
        toDate,
        continuation,
        absenceTypeCode,
        payer,
        compensationPct,
        documentNumber,
        sickLeaveId,
        null);
  }

  public BgSickLeaveEpisode(
      LocalDate fromDate,
      LocalDate toDate,
      boolean continuation,
      String absenceTypeCode,
      String payer,
      BigDecimal compensationPct,
      String documentNumber,
      Long sickLeaveId,
      Integer category) {
    this.fromDate = fromDate;
    this.toDate = toDate;
    this.continuation = continuation;
    this.absenceTypeCode = absenceTypeCode;
    this.payer = payer;
    this.compensationPct = compensationPct;
    this.documentNumber = documentNumber;
    this.sickLeaveId = sickLeaveId;
    this.category = category;
  }

  /** Плаща ли ОСИГУРИТЕЛЯТ първите дни при този вид отсъствие. Непознат платец → не. */
  public boolean employerPaysOpeningDays() {
    return PAYER_EMPLOYER.equals(payer);
  }

  @Override
  public String toString() {
    return "BgSickLeaveEpisode["
        + absenceTypeCode
        + " "
        + fromDate
        + "–"
        + toDate
        + (continuation ? " (продължение)" : "")
        + ", платец "
        + payer
        + ", "
        + compensationPct
        + "%]";
  }
}
