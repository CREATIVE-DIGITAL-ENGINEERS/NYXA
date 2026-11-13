package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgAbsenceType;

/**
 * КОИ ВИДОВЕ ОТСЪСТВИЕ СЕ ДОПУСКАТ НА БОЛНИЧЕН ЛИСТ (A4). Чиста функция над реда на речника.
 *
 * <p><b>Какво беше.</b> {@code BgSickLeave.bgAbsenceType} приемаше ВСЕКИ ред на речника — платен
 * годишен отпуск, неплатен над 30 дни, самоотлъчка. Резолверът на болничния чете само платеца и
 * процента на вида: лист с „Платен годишен отпуск“ (платец осигурителят, 100 на сто) даваше перо
 * 195 „Болничен от осигурител“ за два дни по 100 на сто, а останалите дни отиваха в т. 16.2 като
 * дни ВН и в базата на здравната вноска по ЗЗО чл. 40 ал. 1 т. 5. Тоест пари и декларация без
 * документ, който да ги оправдава.
 *
 * <p><b>Правилото — два признака на речника, без списък в Java.</b>
 *
 * <ul>
 *   <li>{@code requiresSickNote} — видът се удостоверява с болничен лист (болест, злополука,
 *       гледане на болен, професионална болест, бременност и раждане). Останалите се въвеждат като
 *       ЗАЯВКА ЗА ОТПУСК.
 *   <li>{@code countsAsInsuranceService} — и се зачита за осигурителен стаж. Изключва „Непредставен
 *       болничен“: той ИСКА лист, но е отсъствието ДОКАТО листът липсва, тоест не е вид на самия
 *       лист. Същият признак ги различава и в {@code BgPayrollInputBuilder#leaveDaysOf} — двете
 *       места четат едно и също.
 * </ul>
 *
 * <p>Изборът на формата ({@code bg-sick-leave-views.xml}) носи {@link #PICKER_DOMAIN}: това
 * правило, И без видовете „без право на обезщетение“ ({@link
 * BgSickLeaveResolver#CODES_CLAIMING_NO_BENEFIT}) — те не се предлагат за нови листове, но
 * правилото ги допуска при запис, защото заварените листове ги носят. {@code
 * BgSickLeaveTypePickerTrapTest} пази двете места да не се разминат и сверява правилото с всеки ред
 * на сийда.
 */
public final class BgSickLeaveTypeRules {

  /**
   * Домейнът на избора на вид върху болничния лист — дословно като в изгледа: правилото на този
   * клас И без видовете, които твърдят „без право“.
   */
  public static final String PICKER_DOMAIN =
      "self.requiresSickNote = true AND self.countsAsInsuranceService = true"
          + " AND self.code NOT IN ('sick_no_benefit_er','sick_no_benefit_doo')";

  private BgSickLeaveTypeRules() {}

  /** Отказът за реда на речника, или {@code null}, ако видът се допуска на болничен лист. */
  public static String refusal(BgAbsenceType type) {
    if (type == null) {
      return null;
    }
    return refusal(
        type.getCode(),
        type.getName(),
        type.getRequiresSickNote(),
        type.getCountsAsInsuranceService());
  }

  /**
   * Отказът по стойностите на реда, или {@code null}.
   *
   * <p>⚠️ {@code null} за признак се чете като „не“: генерираният getter така или иначе връща
   * {@code FALSE} за празна колона, а лист, чийто вид не е обявен за вид с лист, не е болничен.
   */
  public static String refusal(
      String code, String name, Boolean requiresSickNote, Boolean countsAsInsuranceService) {
    String label = "„" + (name == null ? code : name) + "“ (" + code + ")";
    if (!Boolean.TRUE.equals(requiresSickNote)) {
      return "видът "
          + label
          + " не се удостоверява с болничен лист — въвежда се като ЗАЯВКА ЗА ОТПУСК, не като"
          + " болничен. На болничен лист той щеше да се смята като временна неработоспособност:"
          + " перо по чл. 40 ал. 5 КСО и дни в т. 16.2 на Д1 без документ зад тях. Сменете вида на"
          + " листа с вида от самия болничен лист.";
    }
    if (!Boolean.TRUE.equals(countsAsInsuranceService)) {
      return "видът "
          + label
          + " е отсъствието, ДОКАТО болничният лист липсва, а не вид на самия лист. Когато листът"
          + " пристигне, въведете го с вида от листа (болест, злополука, гледане на болен) и"
          + " отразете го с „Отрази пристигнал болничен“.";
    }
    return null;
  }
}
