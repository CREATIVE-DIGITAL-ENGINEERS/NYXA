package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 11 — АНКЕТНИЯТ ЛИСТ ПО ЧЛ. 46 АЛ. 1 КСО, И КОЕ НЕ Е ТОЙ.
 *
 * <p><b>⚠️ КАКВО УДОСТОВЕРЯВА {@code AnketaCode} — И КАКВО НЕ.</b> Дословно от НПОПДОО, Приложение
 * № 9 към чл. 8 ал. 1 и чл. 11 ал. 1, т. 11: <i>„С анкетен лист № … от … 20.. г. е установено …
 * (умишлено увреждане на здравето с цел получаване на отпуск или обезщетение/нарушаване на режима,
 * определен от здравните органи/употреба на алкохол …/хулигански и други противообществени
 * прояви…/временна неработоспособност поради неспазване на правилата за безопасна работа…)“</i> — и
 * указанието добавя <i>„попълва се за случаите по чл. 46, ал. 1 от Кодекса за социално
 * осигуряване“</i>. Коментарът в {@code Pril9.xsd} над елемента го казва със същите думи:
 * <i>„Анкетен лист - Вид на нарушението - код“</i>.
 *
 * <p>Тоест т. 11 е за <b>НАРУШЕНИЕ</b>, което отнема или намалява обезщетението, а НЕ за трудова
 * злополука или професионална болест. Анкетната карта за трудова злополука е друг документ по друг
 * ред (чл. 57 КСО) и Приложение № 9 НЯМА поле за нея — затова свидетелят по-долу заковава, че ТЗ/ПБ
 * не пълни {@code AnketaCode}. Обратното допускане би сложило код на нарушение върху лице, което не
 * е извършило нарушение.
 *
 * <p><b>⚠️ И ЗАЩО КОДЪТ ВИНАГИ Е ПРАЗЕН.</b> Образецът изброява петте случая БЕЗ да ги номерира, а
 * номенклатурата „Вид на нарушението“ на НОИ не е набавена ({@code Pril9.xsd} дава на елемента гол
 * {@code xsd:string} без изброяване). Празният код с флаг е ОБЯВЕНА липса; приписан номер би
 * твърдял съответствие, което нормата не установява.
 */
class BgApp9SurveyPointTest {

  private static final BgApp9Validator VALIDATOR = new BgApp9Validator();

  private static String xmlOf(BgApp9Declaration d) {
    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);
    assertFalse(emission.isRefused(), "емисията отказа: " + emission.gapReport());
    return emission.getXml();
  }

  private static boolean hasFlag(List<BgApp9Finding> findings, String fragment) {
    for (BgApp9Finding f : findings) {
      if (!f.isError() && (f.message.contains(fragment) || f.subject.contains(fragment))) {
        return true;
      }
    }
    return false;
  }

  /** Вид отсъствие по мярка — кодът и категорията са осите, по които се чете рискът. */
  private static BgAbsenceType absenceType(String code, String name, int category) {
    BgAbsenceType type = new BgAbsenceType();
    type.setCode(code);
    type.setName(name);
    type.setCategory(Integer.valueOf(category));
    return type;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // AnketaCode
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Типичен болничен (общо заболяване): AnketaCode е ПРАЗЕН и т. 11 не е отбелязана")
  void generalIllnessLeavesTheSurveyPointClosed() {
    BgSickLeave sick = BgApp9Fixture.sickLeave();
    BgApp9Fixture.standardLines(sick);
    sick.setBgAbsenceType(absenceType("sick_doo", "Болнични ДОО (общо заболяване)", 2));

    String xml = xmlOf(BgApp9Fixture.declarationFor(sick));

    // ⚠️ Елементът СЪЩЕСТВУВА и е ПРАЗЕН — това не е едно и също с „липсва“: схемата го иска
    // винаги, а празнотата е стойността, с която се казва „точката не се попълва“.
    assertTrue(xml.contains("<AnketaCode></AnketaCode>"), "AnketaCode не е празен елемент: " + xml);
    assertTrue(xml.contains("<Anketa>0</Anketa>"), "т. 11 е отбелязана без анкетен лист: " + xml);

    // И нито един флаг — празната т. 11 при общо заболяване е ВЯРНА, не пропусната.
    List<BgApp9Finding> findings = VALIDATOR.controls(BgApp9Fixture.declarationFor(sick));
    assertFalse(
        hasFlag(findings, "т. 11"),
        "празната т. 11 не бива да се флагва: " + BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("⚠️ Трудова злополука и професионална болест НЕ пълнят AnketaCode — друг документ е")
  void workAccidentDoesNotFillTheSurveyCode() {
    // Наборът е BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT — същият, по който се чете
    // рискът ТЗПБ за т. 2. Ако някой ден AnketaCode се навърже за ТЗ/ПБ, този свидетел пада.
    for (String code : BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT) {
      BgSickLeave sick = BgApp9Fixture.sickLeave();
      BgApp9Fixture.standardLines(sick);
      sick.setBgAbsenceType(absenceType(code, "ТЗ/ПБ", 3));

      String xml = xmlOf(BgApp9Fixture.declarationFor(sick));

      assertTrue(
          xml.contains("<AnketaCode></AnketaCode>"),
          "видът „" + code + "“ напълни AnketaCode — т. 11 е за НАРУШЕНИЕ по чл. 46 ал. 1 КСО");
      assertTrue(
          xml.contains("<Anketa>0</Anketa>"),
          "видът „" + code + "“ отметна т. 11 без анкетен лист");
    }
  }

  @Test
  @DisplayName("Установено нарушение: т. 11 се отмята, а кодът остава празен и ФЛАГНАТ")
  void aRecordedFindingMarksThePointAndFlagsTheMissingCode() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11SurveyNo("АЛ-7");
    d.setBgP11SurveyDate(LocalDate.of(2026, 6, 8));
    d.setBgP11FindingSelect("REGIME_BREACH");

    String xml = xmlOf(d);

    assertTrue(xml.contains("<Anketa>1</Anketa>"), "т. 11 не е отбелязана: " + xml);
    assertTrue(xml.contains("<OrderNumberAnketa>АЛ-7</OrderNumberAnketa>"), xml);
    assertTrue(xml.contains("<AnketaCode></AnketaCode>"), "измислен е код на нарушението: " + xml);

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasFlag(findings, "AnketaCode"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Отбелязана т. 11 с ПРАЗЕН AnketaCode пак минава Pril9.xsd")
  void aMarkedSurveyPointWithAnEmptyCodeStillValidates() {
    // ⚠️ ТОВА Е ПРОВЕРКАТА, ЧЕ ПРАЗНИЯТ КОД НЕ Е СТРУКТУРНО НЕГОДЕН. Кръговратът на Ф2 валидира
    // декларация, в която т. 11 НЕ е попълнена — тоест не доказва нищо за отбелязаната точка.
    // Ако НОИ някой ден стегне AnketaCode с изброяване, този свидетел ще падне ПЪРВИ и ще каже, че
    // празното вече не минава, вместо файлът да бъде отхвърлен на портала.
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11SurveyNo("АЛ-7");
    d.setBgP11SurveyDate(LocalDate.of(2026, 6, 8));
    d.setBgP11FindingSelect("SAFETY_BREACH");

    List<BgApp9Finding> schema = VALIDATOR.validateAgainstSchema(xmlOf(d));
    assertTrue(schema.isEmpty(), "схемата намери: " + BgApp9Validator.report(schema));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Установеното без номер — точката не бива да изпада МЪЛЧАЛИВО
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Установено БЕЗ номер и дата пак отмята т. 11 (иначе фактът изпада мълчаливо)")
  void aFindingWithoutANumberStillMarksThePoint() {
    // Единственият носител на нарушението по чл. 46 ал. 1 КСО е bgP11FindingSelect: AnketaCode е
    // празен по номенклатура, а номерът и датата описват ДОКУМЕНТА, не установеното. Ако флагът
    // се чете само от номера и датата, записано „установено“ дава Anketa = 0 — тоест
    // удостоверението твърди пред НОИ, че анкетен лист НЯМА.
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11FindingSelect("SELF_HARM");

    assertTrue(xmlOf(d).contains("<Anketa>1</Anketa>"), "установеното изпадна: " + xmlOf(d));
  }

  @Test
  @DisplayName("Отворена, но непълна т. 11 се флагва — до НОИ стига гола отметка")
  void anIncompleteSurveyPointIsFlagged() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11FindingSelect("ALCOHOL_DRUGS");

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertTrue(hasFlag(findings, "непълен анкетен лист"), BgApp9Validator.report(findings));
  }

  @Test
  @DisplayName("Пълна т. 11 не се флагва за непълнота")
  void aCompleteSurveyPointIsNotFlaggedAsIncomplete() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP11SurveyNo("АЛ-7");
    d.setBgP11SurveyDate(LocalDate.of(2026, 6, 8));
    d.setBgP11FindingSelect("HOOLIGANISM");

    List<BgApp9Finding> findings = VALIDATOR.controls(d);
    assertFalse(hasFlag(findings, "непълен анкетен лист"), BgApp9Validator.report(findings));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // sicknessReasonCode — НЯМА КЪДЕ ДА ОТИДЕ В ПРИЛОЖЕНИЕ № 9
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ BgAbsenceType.sicknessReasonCode НЕ мени нито един байт от Приложение № 9")
  void theSicknessReasonCodeHasNoDestinationInTheCertificate() {
    // Приложение № 9 идентифицира болничния лист с НОМЕР и ДАТА НА ИЗДАВАНЕ (PatientsChartNumber,
    // DateIssued) и НЯМА точка „причина за неработоспособност“ — рискът стига до НОИ през самия
    // болничен лист, подаван от лечебното заведение по своя ред, а не през удостоверението на
    // осигурителя. В целия Pril9.xsd няма елемент за причината: най-близкото, RiskInsured, е
    // „осигурен за съответния риск – да/не“ (Code019), а не вид на риска.
    //
    // Затова кодът „причина“ е АДМИНИСТРАТИВЕН описател на речника и този свидетел заковава, че
    // емисията не го чете. Ако някой ден НОИ поиска причината, свидетелят ще падне и ще посочи
    // мястото, вместо кодът да протече тихо в чужд елемент.
    BgSickLeave withoutCode = BgApp9Fixture.sickLeave();
    BgApp9Fixture.standardLines(withoutCode);
    withoutCode.setBgAbsenceType(absenceType("sick_doo", "Болнични ДОО", 2));

    BgSickLeave withCode = BgApp9Fixture.sickLeave();
    BgApp9Fixture.standardLines(withCode);
    BgAbsenceType typed = absenceType("sick_doo", "Болнични ДОО", 2);
    typed.setSicknessReasonCode("26");
    withCode.setBgAbsenceType(typed);

    assertTrue(
        xmlOf(BgApp9Fixture.declarationFor(withoutCode))
            .equals(xmlOf(BgApp9Fixture.declarationFor(withCode))),
        "sicknessReasonCode промени емисията — Приложение № 9 няма поле за причината");
  }
}
