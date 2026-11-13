package com.axelor.apps.bglocalization.service.payroll;

import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ПРАВОТО НА ПАРИЧНО ОБЕЗЩЕТЕНИЕ КЪМ НАЧАЛОТО НА НЕРАБОТОСПОСОБНОСТТА. Чиста функция: без база, без
 * Guice. Входът се събира от {@code BgSickBenefitVerdictService}.
 *
 * <p><b>Какво беше.</b> Стажът беше число без дата ({@code Employee.bgInsuranceServiceMonths}) и
 * {@code BgSickLeaveResolver} го четеше при ВСЯКО пресмятане на фиша. Лист, започнал при 5 месеца,
 * получаваше „с право“ за дните си в следващия месец, щом ТРЗ обнови числото; закъснял лист за
 * минал месец се мереше по днешния стаж. А числото никой не увеличава — с времето то остарява и в
 * двете посоки.
 *
 * <p><b>Правилото.</b> КСО чл. 40 ал. 1: право на парично обезщетение при най-малко 6 месеца
 * осигурителен стаж като осигурен за общо заболяване и майчинство; изр. 2 — изискването не се
 * отнася за ненавършилите 18 години. Ал. 2 — при трудова злополука и професионална болест стаж не
 * се изисква. Чл. 48а — при бременност и раждане 12 месеца, без изключение за възрастта. Всичко се
 * мери КЪМ ДЕНЯ НА НАСТЪПВАНЕ НА РИСКА (така е формулирана и т. 2 на Приложение № 9).
 *
 * <p><b>Стажът към дата D — извеждане, не догадка.</b> Декларацията казва „M месеца към дата A“.
 * Котвата е началото {@code S = A − M месеца}; стажът към D е броят пълни месеци от S до D. Това
 * важи САМО ако осигуряването е непрекъснато между A и D — затова всеки календарен месец след A и
 * преди месеца на D трябва да има ПОТВЪРДЕН фиш при осигурителя, без дни извън осигуряване (КСО чл.
 * 9 ал. 1 — осигурителен стаж е времето, през което лицето е осигурено; чл. 9 ал. 2 т. 3 —
 * неплатеният отпуск над 30 дни не се зачита). Месецът на D не се проверява: той е текущият, фишът
 * му е този, който се пресмята.
 *
 * <p><b>⚠️ Отказите — всеки е „не знам“, а не „не“:</b> няма декларация; няма дата на декларацията;
 * D е преди нея (назад стажът не се изважда — промяната може да е от друго правоотношение); месец
 * без потвърден фиш или с дни извън осигуряване (стажът не е непрекъснат); липсва датата на
 * възникване; под прага при неизвестна дата на раждане.
 *
 * <p>⚠️ ДА СЕ ПОТВЪРДИ: Наредбата за пенсиите и осигурителния стаж брои стажа в години, месеци и
 * ДНИ; „пълни месеци от S до D“ е месечното приближение. Месец с ЧАСТИЧНО осигуряване (дни извън
 * осигуряване) не се дели на дни — отказва се, и операторът декларира стажа наново към по-късна
 * дата.
 */
public final class BgSickBenefitVerdictRules {

  /** Има право — стойност на {@code bg.sick.benefit.verdict.select}. */
  public static final String HAS_RIGHT = "HAS_RIGHT";

  /** Няма право — стойност на {@code bg.sick.benefit.verdict.select}. */
  public static final String NO_RIGHT = "NO_RIGHT";

  /** Прагът при бременност и раждане — КСО чл. 48а. Единица: МЕСЕЦИ. */
  public static final int MATERNITY_SERVICE_MONTHS = 12;

  /** Категорията „Майчинство-Бащинство“ по {@code bg.absence.category.select} — прагът е 12. */
  public static final int MATERNITY_CATEGORY = BgSickLeaveResolver.RISK_MATERNITY_CATEGORY;

  private BgSickBenefitVerdictRules() {}

  /** Входът — събран от базата от услугата. */
  public static final class Input {
    public String absenceTypeCode;
    public Integer category;
    public LocalDate incapacityStart;

    /**
     * Датата е НАЧАЛОТО на листа, защото „Дата на възникване“ е празна — казва се в основанието.
     */
    public boolean startFromSheet;

    public LocalDate birthDate;
    public boolean serviceDeclared;
    public Integer declaredMonths;
    public LocalDate declaredAsOf;

    /** Месеците от {@link #monthsToVerify}, които НЕ са удостоверени — с причината на всеки. */
    public List<String> unverifiedMonths = Collections.emptyList();
  }

  /** Присъдата — или отказ с причина. */
  public static final class Verdict {
    public final String verdict;
    public final LocalDate asOf;
    public final Integer serviceMonths;
    public final String why;
    public final String refusal;

    private Verdict(
        String verdict, LocalDate asOf, Integer serviceMonths, String why, String refusal) {
      this.verdict = verdict;
      this.asOf = asOf;
      this.serviceMonths = serviceMonths;
      this.why = why;
      this.refusal = refusal;
    }

    public static Verdict of(String verdict, LocalDate asOf, Integer months, String why) {
      return new Verdict(verdict, asOf, months, why, null);
    }

    public static Verdict refused(String refusal) {
      return new Verdict(null, null, null, null, refusal);
    }

    public boolean isRefused() {
      return refusal != null;
    }

    public boolean hasRight() {
      return HAS_RIGHT.equals(verdict);
    }
  }

  /**
   * МЕСЕЦИТЕ, КОИТО ТРЯБВА ДА СА УДОСТОВЕРЕНИ — след датата на декларацията и преди месеца на D.
   *
   * <p>Празно, ако D е в месеца на декларацията или в следващия, а декларацията е към края на
   * месеца. {@code null} при липсващи дати.
   */
  public static List<YearMonth> monthsToVerify(LocalDate declaredAsOf, LocalDate incapacityStart) {
    if (declaredAsOf == null || incapacityStart == null) {
      return null;
    }
    LocalDate from = declaredAsOf.plusDays(1);
    LocalDate to = incapacityStart.withDayOfMonth(1).minusDays(1);
    List<YearMonth> out = new ArrayList<>();
    if (to.isBefore(from)) {
      return out;
    }
    for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
      out.add(m);
    }
    return out;
  }

  /** Стажът в пълни месеци към D: от котвата {@code A − M месеца} до D. */
  public static int serviceMonthsAt(int declaredMonths, LocalDate declaredAsOf, LocalDate day) {
    LocalDate anchor = declaredAsOf.minusMonths(declaredMonths);
    return (int) Period.between(anchor, day).toTotalMonths();
  }

  /** Присъдата за правото — или отказ. Виж javadoc-а на класа. */
  public static Verdict decide(Input in) {
    LocalDate day = in.incapacityStart;
    if (day == null) {
      return Verdict.refused(
          "датата на възникване на неработоспособността е празна на листа („Дата на"
              + " възникване“). Правото на обезщетение се определя КЪМ НЕЯ (КСО чл. 40 ал. 1) —"
              + " без нея няма към кога да се мери стажът и възрастта.");
    }
    boolean maternity = in.category != null && in.category == MATERNITY_CATEGORY;
    int threshold =
        maternity ? MATERNITY_SERVICE_MONTHS : BgSickLeaveResolver.MIN_INSURANCE_SERVICE_MONTHS;
    String basis = maternity ? "чл. 48а КСО" : "чл. 40 ал. 1 КСО";

    if (!maternity) {
      if (BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT.contains(in.absenceTypeCode)) {
        return Verdict.of(
            HAS_RIGHT,
            day,
            null,
            "трудова злополука или професионална болест — стаж не се изисква (чл. 40 ал. 2 КСО)");
      }
      if (in.birthDate != null
          && ChronoUnit.YEARS.between(in.birthDate, day)
              < BgSickLeaveResolver.AGE_WITHOUT_SERVICE_REQUIREMENT) {
        return Verdict.of(
            HAS_RIGHT,
            day,
            null,
            "към "
                + day
                + " лицето не е навършило "
                + BgSickLeaveResolver.AGE_WITHOUT_SERVICE_REQUIREMENT
                + " години — стаж не се изисква (чл. 40 ал. 1 изр. 2 КСО)");
      }
    }

    if (!in.serviceDeclared || in.declaredMonths == null) {
      return Verdict.refused(
          "осигурителният стаж не е деклариран при служителя — без него не се знае правото по "
              + basis
              + ". Отметнете „Осигурителният стаж е деклариран“, въведете месеците и датата, към"
              + " която са верни.");
    }
    if (in.declaredAsOf == null) {
      return Verdict.refused(
          "стажът е деклариран като "
              + in.declaredMonths
              + " месеца, но не и КЪМ КОЯ ДАТА („Стажът е към дата“). Правото се определя към"
              + " началото на неработоспособността ("
              + day
              + "); число без дата е стаж „към неизвестно кога“ и не може да се мери към нея.");
    }
    if (day.isBefore(in.declaredAsOf)) {
      return Verdict.refused(
          "неработоспособността започва на "
              + day
              + ", ПРЕДИ датата, към която е деклариран стажът ("
              + in.declaredAsOf
              + "). Назад стажът не се извежда. Декларирайте стажа към дата не по-късна от началото"
              + " на листа, или определете правото с причина („Определи правото наново“).");
    }
    if (in.unverifiedMonths != null && !in.unverifiedMonths.isEmpty()) {
      return Verdict.refused(
          "стажът към "
              + day
              + " не може да се изведе: между датата на декларацията ("
              + in.declaredAsOf
              + ") и началото на неработоспособността има месеци без удостоверено осигуряване при"
              + " осигурителя — "
              + String.join("; ", in.unverifiedMonths)
              + ". Липсващ месец не е „осигурен“. Потвърдете фишовете или декларирайте стажа към"
              + " по-късна дата.");
    }
    int months = serviceMonthsAt(in.declaredMonths, in.declaredAsOf, day);
    boolean met = months >= threshold;
    if (!met && !maternity && in.birthDate == null) {
      return Verdict.refused(
          "стажът към "
              + day
              + " е "
              + months
              + " месеца, под "
              + threshold
              + ", а датата на раждане липсва — не се знае дали важи изключението за ненавършили"
              + " 18 години (чл. 40 ал. 1 изр. 2 КСО). Попълнете датата на раждане.");
    }
    String why =
        "стаж към "
            + day
            + ": "
            + months
            + " месеца ("
            + in.declaredMonths
            + " към "
            + in.declaredAsOf
            + " + потвърдените месеци след това) "
            + (met ? "≥ " : "< ")
            + threshold
            + " по "
            + basis
            + (in.startFromSheet
                ? " (датата е началото на листа — „Дата на възникване“ е празна)"
                : "");
    return Verdict.of(met ? HAS_RIGHT : NO_RIGHT, day, months, why);
  }
}
