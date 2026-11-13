package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ЕМИТЕРЪТ НА ПРИЛОЖЕНИЕ № 9 — записите на Ф1 и редовете на Ф0 стават файл по {@code Pril9.xsd}.
 *
 * <p><b>ЧИСТА ФУНКЦИЯ.</b> Няма база, няма Guice, няма мрежа, няма часовник — датата на износа и
 * часът на записа влизат през {@link BgApp9EmitterContext}. Оттам целият файл е проверим със
 * свидетел, който строи два POJO-а и сравнява байтове, точно както {@code BgD1SubmissionPlanTest}
 * прави за Д1.
 *
 * <p><b>⚠️ ЕМИТЕРЪТ НЕ СМЯТА НИЩО.</b> Т. 1–8 и 10–26 се вземат от записа на Ф1 КАКТО СА — той вече
 * е минал през резолвера и всяка стойност там носи свидетел. Т. 9 се СБИРА от редовете на Ф0 (виж
 * {@link BgApp9Point9}) и нищо повече: нито един ред тук не докосва пари, перо или фиш. Второ
 * извеждане тук би било второ мнение по въпрос, на който Ф1 вече е отговорил — и двете щяха да се
 * разминат при първата поправка на договора.
 *
 * <p><b>⚠️ И НЕ ПОПЪЛВА ЛИПСВАЩО ЗАДЪЛЖИТЕЛНО ОБСТОЯТЕЛСТВО С НУЛА ИЛИ С 9.</b> Преди първия байт
 * се вика {@link BgApp9MandatoryPoints}; при непразен списък се връща {@link
 * BgApp9Emission#refused}. Кодът 9 „не е избрано“ се пише САМО там, където образецът го предвижда
 * като ДЕКЛАРАЦИЯ (неотносима точка, непопълнен раздел II) — никога като заместител на отговор,
 * който никой не е дал.
 *
 * <p><b>⚠️ РАЗДЕЛ II СЕ ИЗПИСВА ПРАЗЕН, И ТОВА НЕ Е „ТИХА НУЛА“.</b> Изискванията за прием, т. 2,
 * са дословни: „Структурата ВИНАГИ съдържа всички тагове/секции … Когато не е избран цял раздел I.
 * или II., структурата му съдържа всички тагове/секции с непопълнени стойности или попълнени
 * стойностите, означаващи, че не е направен избор“. Тоест празният раздел II е предписаната форма
 * на „няма промени в обстоятелствата“, а {@code FlagCertificate = 1} го казва изрично. Ф2 не строи
 * раздел II — той е друго подаване с друг повод.
 */
public final class BgApp9Emitter {

  /** {@code FlagCertificate} = 1 — попълнен САМО раздел I. Единственото, което Ф2 произвежда. */
  public static final String CERTIFICATE_SECTION_ONE = "1";

  /** Кодовете на {@code CodeOperation} по {@code bg.app9.submission.type.select}. */
  private static final String OPERATION_SUBMIT = "0";

  private static final String OPERATION_CORRECT = "1";
  private static final String OPERATION_DELETE = "2";

  /** „Не е избрано“ по {@code Code019} — декларираната липса на избор. */
  private static final String NOT_CHOSEN = "9";

  /** „Не е отбелязано“ по {@code Code01}. */
  private static final String NOT_MARKED = "0";

  /** „Отбелязано“ по {@code Code01}. */
  private static final String MARKED = "1";

  private static final DateTimeFormatter SAVED_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final DateTimeFormatter SAVED_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private BgApp9Emitter() {}

  /**
   * ЕДНО удостоверение във свой файл — това, което бутонът на формата произвежда.
   *
   * <p>Файлът е същият като при пакета, само с един {@code NumberPril9}: схемата не различава двата
   * случая, а операторът често подава лист по лист.
   */
  public static BgApp9Emission emitOne(
      BgApp9EmitterContext context, BgApp9Declaration declaration, int fileSequence) {
    List<BgApp9Declaration> one = new ArrayList<>(1);
    one.add(declaration);
    return emit(context, one, fileSequence);
  }

  /**
   * ЦЕЛИЯТ ФАЙЛ — заглавна част, всички удостоверения, и опашката с начина на подаване.
   *
   * @param context заглавната част; {@code null} дава отказ, а не файл без осигурител
   * @param declarations удостоверенията в реда, в който влизат във файла
   * @param fileSequence числото {@code n} в името {@code Pril9_ddmmyyyy_n.xml}
   */
  public static BgApp9Emission emit(
      BgApp9EmitterContext context, List<BgApp9Declaration> declarations, int fileSequence) {

    // ═══ ПЪРВО ЛИПСИТЕ, ПОСЛЕ БАЙТОВЕТЕ ═══
    // ⚠️ Събират се ВСИЧКИТЕ, а не се спира на първата. Операторът поправя веднъж, а не отваря
    // формата двайсет пъти, за да научи следващото празно поле.
    List<BgApp9Gap> gaps = new ArrayList<>(BgApp9MandatoryPoints.ofHeader(context));

    if (declarations == null || declarations.isEmpty()) {
      gaps.add(
          BgApp9Gap.header(
              "документи във файла",
              "DocList",
              "файлът не носи нито едно удостоверение — схемата иска NumberPril9 поне веднъж."
                  + " Проверете прозореца на износа: болничните се търсят по МЕСЕЦ ЗА ЕКСПОРТ,"
                  + " когато е попълнен, и по началото на отпуска, когато не е."));
      return BgApp9Emission.refused(gaps);
    }

    List<List<BgApp9Point9.Month>> monthsPerDoc = new ArrayList<>(declarations.size());
    for (BgApp9Declaration declaration : declarations) {
      List<BgApp9Point9.Month> months = monthsOf(declaration);
      monthsPerDoc.add(months);
      gaps.addAll(BgApp9MandatoryPoints.ofDocument(declaration, months));
    }

    if (!gaps.isEmpty()) {
      return BgApp9Emission.refused(gaps);
    }

    BgApp9XmlWriter w = new BgApp9XmlWriter();
    w.open("BPril9");
    w.leaf("FlagDelegated", nz(context.flagDelegated, NOT_MARKED));
    w.leaf("TypeOfDocument", BgApp9EmitterContext.TYPE_OF_DOCUMENT_SICK_LEAVE);

    w.open("NSSI_RO_All");
    w.leaf("NSSI_RO", context.nssiRoCode);
    w.leaf("NSSI_RO_Name", clip(context.nssiRoName, 15));
    w.close("NSSI_RO_All");

    w.open("Insurer");
    w.leaf("FlagKasa", nz(context.flagKasa, NOT_MARKED));
    w.leaf("BULSTATKasa", context.bulstatKasa);
    w.leaf("BulName", clip(context.bulName, 200));
    w.leaf("BULSTAT", nz(context.bulstatMember, ""));
    w.open("BulAddressFull");
    w.leaf("BulRegion", clip(context.bulRegion, 30));
    w.leaf("BulSubRegion", clip(context.bulSubRegion, 30));
    w.leaf("BulCity", clip(context.bulCity, 50));
    w.leaf("BulPostalCode", clip(context.bulPostalCode, 4));
    w.leaf("BulAddress", clip(context.bulAddress, 100));
    w.leaf("BulPhone", clip(context.bulPhone, 50));
    w.leaf("BulCellPhoneNumber", clip(context.bulCellPhone, 50));
    w.leaf("BulEMail", clip(context.bulEmail, 50));
    w.close("BulAddressFull");
    w.close("Insurer");

    w.open("DocList");
    for (int i = 0; i < declarations.size(); i++) {
      document(w, context, declarations.get(i), monthsPerDoc.get(i), i + 1);
    }
    w.close("DocList");

    w.leaf("Exported", BgApp9EmitterContext.EXPORTED_BY_FILE);
    w.fullDate("DateExport", context.exportDate);
    w.leaf("Source", context.source);
    w.close("BPril9");

    return BgApp9Emission.emitted(w.toXml(), declarations.size(), fileName(context, fileSequence));
  }

  /**
   * Името на файла — дословно от описанието: „{@code Pril9_ddmmyyyy_n}, където Pril9 — твърд текст;
   * ddmmyyyy — дата на създаване на файла; n — число, поредност на файла за датата“.
   */
  public static String fileName(BgApp9EmitterContext context, int sequence) {
    LocalDate date = context == null || context.exportDate == null ? null : context.exportDate;
    String stamp = date == null ? "00000000" : date.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
    return "Pril9_" + stamp + "_" + Math.max(1, sequence) + ".xml";
  }

  /** Редовете на т. 9 за едно удостоверение, вече сбрани по месеци. */
  static List<BgApp9Point9.Month> monthsOf(BgApp9Declaration declaration) {
    BgSickLeave sick = declaration == null ? null : declaration.getSickLeave();
    List<BgSickLeavePeriodLine> lines = sick == null ? null : sick.getBgPeriodLineList();
    return BgApp9Point9.aggregate(
        lines,
        declaration == null ? null : declaration.getBgP7TerminatedFrom(),
        declaration == null ? null : declaration.getBgP4InsuredTypeCode());
  }

  /** ЕДИН {@code NumberPril9} — цялата секция на едно удостоверение. */
  private static void document(
      BgApp9XmlWriter w,
      BgApp9EmitterContext context,
      BgApp9Declaration d,
      List<BgApp9Point9.Month> months,
      int number) {

    BgSickLeave sick = d.getSickLeave();
    Employee employee = employeeOf(sick);
    Partner person = employee == null ? null : employee.getContactPartner();
    Address address = person == null ? null : person.getMainAddress();

    w.open("NumberPril9");
    w.leaf("Number", Integer.valueOf(number));
    w.leaf("CodeOperation", operationOf(d.getBgSubmissionTypeSelect()));
    w.leaf(
        "PatientsChartNumber",
        BgApp9ChartNumber.compose(
            sick.getBgSeries(), sick.getBgYear(), sick.getBgDocumentNumber()));
    w.fullDate("DateIssued", sick.getBgIssueDate());
    w.fullDate("SickLeaveStartDate", sick.getFromDate());
    w.fullDate("SickLeaveEndDate", sick.getToDate());

    w.open("Insured");
    w.leaf("FirstName", clip(person == null ? null : person.getFirstName(), 21));
    w.leaf("SurName", clip(person == null ? null : person.getBgMiddleName(), 21));
    w.leaf("FamilyName", clip(person == null ? null : person.getName(), 42));
    w.leaf("EGN", employee == null ? null : trim(employee.getBgPersonalIdNumber()));
    // ⚠️ 0 за ЕГН, 2 за ЛН/ЛНЧ/СлН — схемата допуска САМО тези две за осигуреното лице (детето и
    // родителите имат по-широк набор с 3 и 9). Празният тип се чете като ЕГН, защото българският
    // модул го въвежда като ЕГН по подразбиране; несъвпадението пада при модулната проверка на НОИ.
    w.leaf("FlagEGN", personalIdFlag(employee));
    w.open("InsuredAddress");
    // Областта, общината и населеното място — от ГРАДА (ЕКАТТЕ), единственият четец.
    BgApp9Place place = BgApp9Place.of(address, "лицето", BgApp9Place.PERSON_WHERE);
    w.leaf("EGNRegion", clip(place.region, 30));
    w.leaf("EGNSubRegion", clip(place.municipality, 30));
    w.leaf("EGNCity", clip(place.settlement, 50));
    w.leaf("EGNPostalCode", clip(zipOf(address), 4));
    w.leaf("EGNAddress", clip(streetOf(address), 100));
    w.leaf("EGNPhone", clip(person == null ? null : person.getFixedPhone(), 50));
    w.leaf("EGNCellPhoneNumber", clip(person == null ? null : person.getMobilePhone(), 50));
    w.leaf("EGNEMail", clip(emailOf(person), 50));
    w.close("InsuredAddress");
    w.close("Insured");

    w.leaf("FlagCertificate", CERTIFICATE_SECTION_ONE);

    certificates(w, d, months);
    emptyChanges(w);

    w.open("Representative");
    w.leaf("FirstName", clip(context.representativeFirstName, 21));
    w.leaf("SurName", clip(context.representativeSurName, 21));
    w.leaf("FamilyName", clip(context.representativeFamilyName, 42));
    w.leaf("Position", clip(context.representativePosition, 100));
    w.leaf("City", clip(context.representativeCity, 50));
    w.close("Representative");

    w.fullDate("DateCertificate", context.certificateDate);
    LocalDateTime saved = context.savedAt == null ? LocalDateTime.now() : context.savedAt;
    w.leaf("DateSaved", saved.format(SAVED_DATE));
    w.leaf("TimeSaved", saved.format(SAVED_TIME));

    w.close("NumberPril9");
  }

  /** РАЗДЕЛ I — двайсет и шестте точки, в реда на схемата. */
  private static void certificates(
      BgApp9XmlWriter w, BgApp9Declaration d, List<BgApp9Point9.Month> months) {

    w.open("Certificates");

    // т. 1 и т. 1.1 / 1.2
    w.leaf("RiskInsured", yesNo(d.getBgP1Insured()));
    w.leaf("EmploymentType", termType(d.getBgP1TermTypeSelect()));
    w.optionalDate("EploymentDateExpire", d.getBgP1TermEndDate());
    w.leaf("SelfEmploymentType", termType(d.getBgP1SolRegTypeSelect()));
    w.optionalDate("SelfEmploymentDateExpire", d.getBgP1SolRegEndDate());

    // ⚠️ Т. 2 И Т. 3 СА ОТМЕТКИ НА ОБРАЗЕЦА, А ЗАПИСЪТ ПАЗИ ПРАВОТО — обръщането става ТУК.
    // Ф1 го обяви дословно: „Образецът гласи «лицето НЯМА 6 месеца стаж» и се отмята САМО когато
    // стажът липсва — хартиената отметка е ОТРИЦАНИЕТО на изведеното. Пази се MET/NOT_MET/
    // NOT_APPLICABLE; превръщането в отметка е на образеца (Ф2).“ Тоест 0 значи „НЯМА стажа“.
    w.leaf("SixMonthsInsurance", stayFlag(d.getBgP2SixMonthStaySelect()));
    w.optionalDate("DateOfAchievingInsuranceRigths", d.getBgP2StayAcquiredDate());
    w.leaf("InsuranceRigthsOZM", stayFlag(d.getBgP3TwelveMonthStaySelect()));
    w.optionalDate("DateOfAchievingInsuranceRigthsOZM", d.getBgP3StayAcquiredDate());

    w.leaf("TypeOfInsured", trim(d.getBgP4InsuredTypeCode()));
    w.leaf("EGNBulCountTOI", d.getBgP5RelationshipCount());
    w.leaf("DocNumber", d.getCertificateSeqNo());
    w.optionalDate("InsuranceTerminationDate", d.getBgP7TerminatedFrom());
    w.optionalDate("InsuranceStopDate", d.getBgP8InterruptedFrom());

    // ═══ Т. 9 ═══
    w.open("SickLeaveMonths");
    for (BgApp9Point9.Month month : months) {
      w.open("BSickLeave");
      w.leaf("SickNumber", Integer.valueOf(month.sickNumber));
      w.leaf("Month", Integer.valueOf(month.month));
      w.leaf("Year", Integer.valueOf(month.year));
      w.leaf("FromDay", Integer.valueOf(month.fromDay));
      w.leaf("ToDay", Integer.valueOf(month.toDay));
      w.leaf("Days", Integer.valueOf(month.days));
      // ⚠️ ЧАСОВЕТЕ ВИНАГИ ПРАЗНИ. Виж BgApp9Point9: наличните часове са за режима, в който
      // образецът ги ЗАБРАНЯВА, а за СИРВ — режима, в който ги иска — модулът няма график.
      // Контролът изрично допуска дни, когато часове не са попълнени; обратното би било
      // правдоподобно грешно число.
      w.leaf("Hours", "");
      w.leaf("PersonalLegalWorkHours", month.legalWorkHours);
      w.close("BSickLeave");
    }
    w.close("SickLeaveMonths");

    // ═══ Т. 10 — трудоустрояване ═══
    boolean rehab = d.getBgP10RehabFrom() != null || d.getBgP10RehabTo() != null;
    w.leaf("FlagJobTransfer", rehab ? MARKED : NOT_MARKED);
    w.open("JobTransfer");
    w.optionalDate("DateOfJobTransfer", d.getBgP10RehabFrom());
    w.optionalDate("DateOfJobTransferEnd", d.getBgP10RehabTo());
    w.leaf("OrderNumberOfJobTransfer", clip(d.getBgP10OrderNo(), 15));
    w.optionalDate("DateOfJobTransfer2", d.getBgP10OrderDate());
    // ⚠️ signum(), а НЕ проверка за null: генерираният getter за BigDecimal връща ZERO при празна
    // колона, тоест `== null` е мъртъв код. И нулата тук е верният признак за „няма сума“ — типът
    // money на схемата е minExclusive=0 и не приема 0.00.
    w.leaf("Salary_Begin", d.getBgP10AvgPayAtStart().signum() == 0 ? NOT_MARKED : MARKED);
    w.leaf("SalaryMeasureForJobTransfered", payBasis(d.getBgP10BasisAtStartSelect()));
    w.money("SalaryForJobTransfered", d.getBgP10AvgPayAtStart());
    w.leaf("Salary_End", d.getBgP10AvgPayAtEnd().signum() == 0 ? NOT_MARKED : MARKED);
    w.leaf("SalaryMeasureForJobTransferedEnd", payBasis(d.getBgP10BasisAtEndSelect()));
    w.money("SalaryForJobTransferedEnd", d.getBgP10AvgPayAtEnd());
    w.close("JobTransfer");

    // ═══ Т. 11 — анкетен лист ═══
    // ⚠️ AnketaSickLeave/Months СА ЗАДЪЛЖИТЕЛНИ ПО СХЕМАТА (minOccurs по подразбиране = 1) дори
    // когато точката не е отбелязана. Затова се изписва ЕДИН празен комплект — това е „структурата
    // винаги съдържа всички секции с непопълнени стойности“, а не измислен период.
    // ⚠️ И УСТАНОВЕНОТО ОТМЕТВА ТОЧКАТА, не само номерът и датата. Образецът е ЕДНО изречение —
    // „С анкетен лист № … от … е установено …“ — тоест трите полета са един факт. Докато флагът се
    // четеше само от номера и датата, записано bgP11FindingSelect без номер даваше Anketa = 0 и
    // точката изпадаше МЪЛЧАЛИВО: единственият носител на нарушението по чл. 46 ал. 1 КСО не се
    // емитира никъде (AnketaCode е празен по номенклатура), затова флагът е и единствената следа.
    w.leaf(
        "Anketa",
        blank(d.getBgP11SurveyNo())
                && d.getBgP11SurveyDate() == null
                && blank(d.getBgP11FindingSelect())
            ? NOT_MARKED
            : MARKED);
    w.open("AnketaSickLeave");
    w.open("Months");
    w.leaf("SickNumber", "");
    w.leaf("Month", "");
    w.leaf("Year", "");
    w.leaf("FromDay", "");
    w.leaf("ToDay", "");
    w.leaf("Days", "");
    w.leaf("Hours", "");
    w.close("Months");
    w.close("AnketaSickLeave");
    w.leaf("OrderNumberAnketa", clip(d.getBgP11SurveyNo(), 15));
    w.optionalDate("DateAnketa", d.getBgP11SurveyDate());
    // ⚠️ Кодът на нарушението е ПО НОМЕНКЛАТУРА НА НОИ (AnketaCode), а
    // bg.app9.survey.finding.select
    // носи ОПИСАТЕЛНИ кодове нарочно — Ф1 отказа да им припише номера, защото образецът изброява
    // случаите по чл. 46 ал. 1 КСО БЕЗ да ги номерира. Съответствието не е набавено, затова полето
    // остава празно, а валидаторът флагва точката вместо да гадае число.
    w.leaf("AnketaCode", "");

    // ═══ Т. 12 — платен годишен отпуск ═══
    w.leaf("LeaveWithPay", yesNoMark(d.getBgP12ConfirmedSelect()));
    w.open("LeaveWithPaySickLeave");
    w.open("Months");
    w.leaf("SickNumber", "");
    w.leaf("Month", "");
    w.leaf("Year", "");
    w.leaf("FromDay", "");
    w.leaf("ToDay", "");
    w.leaf("Days", "");
    w.close("Months");
    w.close("LeaveWithPaySickLeave");
    w.leaf("OrderLeaveWithPay", clip(d.getBgP12LeaveOrderNo(), 15));
    w.optionalDate("DateOrderLeaveWithPay", d.getBgP12LeaveOrderDate());

    // ═══ Т. 13–23 — данни за деца ═══
    childData(w, d);

    // ═══ Т. 24 ═══
    boolean other = !blank(d.getBgP24OtherCircumstances());
    w.leaf("FlagOtherCircumstances", other ? MARKED : NOT_MARKED);
    w.leaf("OtherCircumstances", clip(d.getBgP24OtherCircumstances(), 250));
    w.optionalDate("DateOtherCircumstances", null);

    // ═══ Т. 25 — от ЛИСТА, не от удостоверението (Ф1 нарочно не ги копира) ═══
    BgSickLeave sick = d.getSickLeave();
    w.leaf("NumberInEmployerRegistry", clip(sick.getBgEmployerReceivedNo(), 15));
    w.optionalDate("DateInEmployerRegistry", sick.getBgEmployerReceivedDate());

    // ═══ Т. 26 ═══
    // „0 — декларира; 1 — има декларирана в НОИ сметка; 9 — не е избрано“. Първото представяне
    // ДЕКЛАРИРА сметката; всяко следващо се позовава на вече декларираната.
    boolean first = BgApp9ObstoyatelstvaResolver.YES.equals(d.getBgP26FirstSubmissionSelect());
    w.leaf("BankAccountDeclared", first ? "0" : "1");
    // ⚠️ Само българска банка. Чуждата сметка иска BIC, име, код на държава и ПРИКАЧЕН ДОКУМЕНТ —
    // нито едно от четирите няма носител в модела, а измислени, те щяха да са декларация пред НОИ.
    w.leaf("FlagBankAccount", first ? "0" : NOT_CHOSEN);
    w.leaf("IBAN", clip(d.getBenefitIban(), 22));
    emptyBankData(w, "otherBankData");

    w.close("Certificates");
  }

  /** Т. 13–23 — секцията за деца; празна, когато листът не е за майчинство след 42-рия ден. */
  private static void childData(BgApp9XmlWriter w, BgApp9Declaration d) {
    boolean hasChild = d.getBgP13Child() != null;
    w.leaf("ChildDataFlag", hasChild ? MARKED : NOT_MARKED);
    w.open("ChildData");

    w.open("BChildData");
    // ⚠️ Вътрешната последователност е с minOccurs="0" — единственото място в цялата схема, където
    // секция ЗАКОННО отсъства. Затова при липса на дете тук не се пише празен BChildDataClass:
    // празен запис за дете щеше да декларира дете без имена и без ЕГН.
    if (hasChild) {
      w.open("BChildDataClass");
      String[] childName = childNames(d);
      w.leaf("ChildFirstName", clip(childName[0], 21));
      w.leaf("ChildSurName", clip(childName[1], 21));
      w.leaf("ChildFamilyName", clip(childName[2], 42));
      w.optionalDate("ChildBirthDate", childBirthDate(d));
      w.leaf("ChildEGN", childId(d));
      w.leaf("ChildFlagEGN", blank(childId(d)) ? "3" : "0");
      w.open("ChildBirthDoc");
      w.leaf("ChildBirthDocNumber", clip(d.getBgP13BirthCertNo(), 15));
      w.optionalDate("ChildBirthDocDate", d.getBgP13BirthCertDate());
      parent(w, "MotherData", d.getBgP13MotherName(), d.getBgP13MotherId());
      parent(w, "FatherData", d.getBgP13FatherName(), d.getBgP13FatherId());
      w.close("ChildBirthDoc");
      w.close("BChildDataClass");
    }
    w.close("BChildData");

    // ⚠️ Integer getter → 0 при празна колона, не null. Затова признакът за близнаци е „> 1“, а
    // не „попълнено“: нулата значи „не е въведен брой“ и е неразличима от липса по построение.
    Integer twins = d.getBgP14TwinCount();
    boolean isTwins = twins.intValue() > 1;
    w.leaf("FlagTwins", hasChild ? (isTwins ? MARKED : NOT_MARKED) : NOT_CHOSEN);
    w.leaf("CountTwins", isTwins ? twins : null);
    w.leaf("ChildFlagAlive", yesNoMark(d.getBgP15ChildAlive()));
    w.leaf("ChildFlagAdopted", yesNoMark(d.getBgP16NotForAdoption()));
    w.leaf("ChildFlagKindergarden", yesNoMark(d.getBgP17NotInStateCare()));
    w.leaf("ChildFlagMotherhood", yesNoMark(d.getBgP18NotUnderMaternityProgramme()));
    w.leaf("ChildFlagStepFamily", yesNoMark(d.getBgP19NotPlacedArt26()));
    w.leaf("FlagMissingParentsRights", yesNoMark(d.getBgP20NotDeprivedOfRights()));
    w.leaf("FlagRestrictedParentsRights", yesNoMark(d.getBgP21RightsNotRestricted()));
    w.leaf("FlagStepParent", yesNoMark(d.getBgP22GuardianshipNotTerminated()));
    w.leaf("ChildFlagStepFamilyStopped", yesNoMark(d.getBgP23Art26PlacementNotTerminated()));

    w.close("ChildData");
  }

  private static void parent(BgApp9XmlWriter w, String tag, String names, String id) {
    w.open(tag);
    String[] parts = splitNames(names);
    w.leaf("FirstName", clip(parts[0], 21));
    w.leaf("SurName", clip(parts[1], 21));
    w.leaf("FamilyName", clip(parts[2], 42));
    w.leaf("EGN", trim(id));
    w.leaf("FlagEGN", blank(id) ? "" : "0");
    w.close(tag);
  }

  /**
   * РАЗДЕЛ II, ИЗПИСАН ПРАЗЕН.
   *
   * <p>⚠️ НЕ СЕ ПРОПУСКА. Схемата държи {@code CertificateChanges} задължителен, а изискванията за
   * прием, т. 2, го казват направо: непопълненият раздел присъства с всичките си тагове. Проверката
   * за смисъл е обратната и я прави НОИ: „Ако не е избран Раздел II (FlagCertificate=1), НЕ трябва
   * да са избрани обстоятелства от него“ — тоест точно нулите по-долу.
   */
  private static void emptyChanges(BgApp9XmlWriter w) {
    w.open("CertificateChanges");
    w.leaf("InsuredIsDead", NOT_MARKED);
    w.optionalDate("InsuredDateDead", null);
    w.optionalDate("A2InsuranceTerminationDate", null);
    w.leaf("A2LeaveWithPay", NOT_MARKED);
    w.open("A2LeaveWithPaySickLeave");
    w.open("Months");
    w.leaf("SickNumber", "");
    w.leaf("Month", "");
    w.leaf("Year", "");
    w.leaf("FromDay", "");
    w.leaf("ToDay", "");
    w.leaf("Days", "");
    w.close("Months");
    w.close("A2LeaveWithPaySickLeave");
    w.leaf("A2OrderLeaveWithPay", "");
    w.optionalDate("A2DateOrderLeaveWithPay", null);
    w.leaf("ChildFlagAlive2", NOT_MARKED);
    w.optionalDate("ChildDeathDate", null);
    w.optionalDate("TerminationDateFromDeclaration", null);
    w.leaf("A2ChildFlagAdopted", NOT_MARKED);
    w.leaf("A2ChildFlagKindergarden", NOT_MARKED);
    w.leaf("A2ChildFlagMotherhood", NOT_MARKED);
    w.leaf("A2ChildFlagStepFamily", NOT_MARKED);
    w.leaf("A2FlagMissingParentsRights", NOT_MARKED);
    w.leaf("A2FlagRestrictedParentsRights", NOT_MARKED);
    w.leaf("A2FlagStepParent", NOT_MARKED);
    w.leaf("A2ChildFlagStepFamilyStopped", NOT_MARKED);
    w.leaf("FamilyMemberIsDead", NOT_MARKED);
    w.optionalDate("FamilyMemberDateDead", null);
    w.leaf("A2FlagOtherCircumstances", NOT_MARKED);
    w.leaf("A2OtherCircumstances", "");
    w.optionalDate("A2DateOtherCircumstances", null);
    w.leaf("A2NumberInEmployerRegistry", "");
    w.optionalDate("A2DateInEmployerRegistry", null);
    w.leaf("BankAccountChanged", NOT_MARKED);
    w.leaf("FlagBankAccountChanged", NOT_CHOSEN);
    w.leaf("IBAN_Changed", "");
    emptyBankData(w, "otherBankData_Changed");
    w.close("CertificateChanges");
  }

  /**
   * Секцията за чужда банка, празна.
   *
   * <p>⚠️ {@code BankCountryCode} Е ЧИСЛО 0–99 БЕЗ ПРАЗЕН ВАРИАНТ — единственият елемент в цялата
   * схема, който принуждава стойност там, където смисъл няма. Пише се 0. Това е РАЗМИНАВАНЕ на
   * схемата със себе си (всяко друго поле на секцията приема празно) и е записано като отворен
   * въпрос в доклада, а не оправено с догадка.
   */
  private static void emptyBankData(BgApp9XmlWriter w, String tag) {
    w.open(tag);
    w.leaf("IBAN", "");
    w.leaf("BIC", "");
    w.leaf("BankName", "");
    w.leaf("BankCountryCode", "0");
    w.leaf("DocName", "");
    w.leaf("DocData", "");
    w.close(tag);
  }

  // ═══════════════════ превод на стойности ═══════════════════

  /** „YES“/„NO“ → 1/0; празно → 9 „не е избрано“. */
  private static String yesNo(String value) {
    if (BgApp9ObstoyatelstvaResolver.YES.equals(value)) {
      return MARKED;
    }
    if (BgApp9ObstoyatelstvaResolver.NO.equals(value)) {
      return NOT_MARKED;
    }
    return NOT_CHOSEN;
  }

  /** „YES“ → отбелязано (1); всичко друго → неотбелязано (0), по {@code Code01}. */
  private static String yesNoMark(String value) {
    return BgApp9ObstoyatelstvaResolver.YES.equals(value) ? MARKED : NOT_MARKED;
  }

  /**
   * Т. 2 / т. 3 — ОТМЕТКАТА НА ОБРАЗЕЦА, обърната спрямо съхраненото право.
   *
   * <p>„0 — избрано (лицето НЯМА стажа); 1 — не е избрано (приема се, че ГО ИМА); 9 — точката не е
   * относима към риска“.
   */
  private static String stayFlag(String value) {
    if (BgApp9ObstoyatelstvaResolver.STAY_NOT_MET.equals(value)) {
      return "0";
    }
    if (BgApp9ObstoyatelstvaResolver.STAY_MET.equals(value)) {
      return "1";
    }
    return NOT_CHOSEN;
  }

  /** Т. 1.1 / 1.2 — „0 безсрочно; 1 срочно; 9 не е избрано“. */
  private static String termType(String value) {
    if (BgApp9ObstoyatelstvaResolver.TERM_INDEFINITE.equals(value)) {
      return "0";
    }
    if (BgApp9ObstoyatelstvaResolver.TERM_FIXED.equals(value)) {
      return "1";
    }
    return NOT_CHOSEN;
  }

  /** Т. 10.1 / 10.2 — „1 среднодневно; 2 средночасово; 9 не е избрано“. */
  private static String payBasis(String value) {
    if ("DAILY".equals(value)) {
      return "1";
    }
    if ("HOURLY".equals(value)) {
      return "2";
    }
    return NOT_CHOSEN;
  }

  /** Видът подаване → {@code CodeOperation}. */
  private static String operationOf(String value) {
    if ("CORRECT".equals(value)) {
      return OPERATION_CORRECT;
    }
    if ("DELETE".equals(value)) {
      return OPERATION_DELETE;
    }
    return OPERATION_SUBMIT;
  }

  /** „0 — ЕГН; 2 — ЛН/ЛНЧ/СлН“. */
  private static String personalIdFlag(Employee employee) {
    String type = employee == null ? null : trim(employee.getBgPersonalIdType());
    return type == null || "EGN".equalsIgnoreCase(type) ? "0" : "2";
  }

  // ═══════════════════ четене на свързаните записи ═══════════════════

  private static Employee employeeOf(BgSickLeave sick) {
    EmploymentContract contract = sick == null ? null : sick.getEmploymentContract();
    return contract == null ? null : contract.getEmployee();
  }

  static String zipOf(Address address) {
    if (address == null) {
      return null;
    }
    if (!blank(address.getZip())) {
      return address.getZip();
    }
    return address.getCity() == null ? null : address.getCity().getZip();
  }

  static String streetOf(Address address) {
    if (address == null) {
      return null;
    }
    if (!blank(address.getAddressL4())) {
      return address.getAddressL4();
    }
    StringBuilder sb = new StringBuilder();
    if (!blank(address.getStreetName())) {
      sb.append(address.getStreetName());
    }
    if (!blank(address.getBuildingNumber())) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(address.getBuildingNumber());
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  static String emailOf(Partner person) {
    if (person == null || person.getEmailAddress() == null) {
      return null;
    }
    return person.getEmailAddress().getAddress();
  }

  /**
   * Имената на детето — ⚠️ {@code BgEmployeeChild} ги пази като ЕДИН низ ({@code fullName}).
   *
   * <p>Схемата иска три полета, затова низът се РАЗДЕЛЯ тук, по същия помощник като родителите.
   * Разделянето е на образеца, не на записа: обратното — три колони върху детето — би поискало
   * миграция на попълнена таблица заради поле, което днес се въвежда наведнъж.
   */
  private static String[] childNames(BgApp9Declaration d) {
    return splitNames(d.getBgP13Child() == null ? null : d.getBgP13Child().getFullName());
  }

  private static LocalDate childBirthDate(BgApp9Declaration d) {
    return d.getBgP13Child() == null ? null : d.getBgP13Child().getBirthDate();
  }

  private static String childId(BgApp9Declaration d) {
    return d.getBgP13Child() == null ? null : trim(d.getBgP13Child().getBgEgn());
  }

  /** „Име Презиме Фамилия“ в един низ → трите полета на схемата. */
  static String[] splitNames(String names) {
    String[] out = new String[] {"", "", ""};
    String t = trim(names);
    if (t == null) {
      return out;
    }
    String[] parts = t.split("\\s+");
    if (parts.length == 1) {
      out[2] = parts[0];
    } else if (parts.length == 2) {
      out[0] = parts[0];
      out[2] = parts[1];
    } else {
      out[0] = parts[0];
      out[1] = parts[1];
      StringBuilder rest = new StringBuilder(parts[2]);
      for (int i = 3; i < parts.length; i++) {
        rest.append(' ').append(parts[i]);
      }
      out[2] = rest.toString();
    }
    return out;
  }

  // ═══════════════════ дребни помощници ═══════════════════

  /**
   * Реже до дължината на схемата.
   *
   * <p>⚠️ РЕЖЕ, не отказва — и разликата е важна. Дължините тук са на ИМЕНА и АДРЕСИ; отрязано име
   * остава разпознаваемо, докато отказан файл заради дълъг адрес спира обезщетение. Отрязването се
   * флагва от валидатора, за да се види.
   */
  static String clip(String value, int max) {
    String t = trim(value);
    if (t == null) {
      return "";
    }
    return t.length() <= max ? t : t.substring(0, max);
  }

  private static String nz(String value, String fallback) {
    String t = trim(value);
    return t == null ? fallback : t;
  }

  private static boolean blank(String value) {
    return trim(value) == null;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
