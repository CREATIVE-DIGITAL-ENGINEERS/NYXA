package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * КАКВО ЛИПСВА, ЗА ДА Е ВАЛИДНО УДОСТОВЕРЕНИЕТО — чиста функция, без база и без странични ефекти.
 *
 * <p><b>⚠️ ТОВА Е МЯСТОТО, КЪДЕТО ЕМИСИЯТА СПИРА, И ТО СЪЩЕСТВУВА ЗАРАДИ ЕДИН КОНКРЕТЕН КАПАН.</b>
 * Схемата {@code Pril9.xsd} НЕ може да опази пълнотата на удостоверението: всеки елемент в нея е
 * задължителен СТРУКТУРНО, а празнотата се изразява със стойност — празен низ, или кода 9 „не е
 * избрано“ при {@code Code019}. Тоест файл, в който т. 1, т. 2, т. 3 и т. 26 са попълнени с 9, е
 * НАПЪЛНО ВАЛИДЕН по XSD и напълно безсмислен пред НОИ. Валидатор, който проверява само схемата, би
 * дал зелено на точно този файл.
 *
 * <p><b>ЗАТОВА ЗАДЪЛЖИТЕЛНОСТТА ИДВА ОТ ДРУГ ПЪРВОИЗТОЧНИК</b> — таблицата „Структура, формат,
 * използвани номенклатури и изисквания към контрола“ ({@link BgApp9Xsd#CONTROL_SPEC_URL}, редакция
 * 03.2024 г.), колони 6 („задължителност при попълването“) и 7 („задължителност за прием на файла в
 * НОИ“). Всяко правило по-долу носи в съобщението си дословния контрол от нея, за да може да се
 * сверява ред по ред, без да се чете код.
 *
 * <p><b>⚠️ И ЗАТОВА „ВСИЧКИ ТАГОВЕ ВИНАГИ ПРИСЪСТВАТ“ НЕ ПРОТИВОРЕЧИ НА „НУЛА ТИХИ НУЛИ“.</b> Двете
 * говорят за различни неща. Изискванията за прием, т. 2, задължават структурата да е пълна —
 * празният елемент е ДЕКЛАРИРАНОТО „няма избор“ на образеца и се пише. Задължително ОБСТОЯТЕЛСТВО
 * без отговор е друго: то не се пише като 9, а спира файла и се назовава тук. Разликата е между
 * „образецът има поле, което в този случай е празно“ и „никой не е отговорил на въпрос, на който
 * НОИ иска отговор“.
 *
 * <p><b>⚠️ ЛИПСИТЕ НЕ СА ОТКАЗИТЕ НА Ф1.</b> Резолверът може да е отказал т. 8 и файлът пак да
 * тръгне (т. 8 е допустимо празна); може да НЕ е отказал нищо и файлът пак да спре (ТП на НОИ няма
 * носител, тоест няма и присъда за него). Затова двата списъка се броят поотделно и се показват
 * поотделно — слети, операторът щеше да чете двайсет реда, за да намери трите, които го спират.
 */
public final class BgApp9MandatoryPoints {

  /**
   * Кодовете за вид осигурен, при които т. 25 НЕ се попълва (контрол към {@code
   * NumberInEmployerRegistry}).
   *
   * <p>⚠️ Чете го и {@link BgApp9Obligation}: лице без входящ номер при осигурител е лице, което
   * подава САМО — тоест извън напомнянето на осигурителя. Един списък, два читателя.
   */
  static final Set<String> WITHOUT_EMPLOYER_REGISTRY =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("12", "13", "96")));

  /** Наборът кодове, който схемата допуска в {@code TypeOfInsured}. Дословно от изброяването. */
  private static final Set<String> ALLOWED_INSURED_TYPES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "0", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13",
                  "16", "17", "21", "24", "25", "26", "29", "71", "72", "82", "83", "92", "93",
                  "94", "95", "96", "97", "99")));

  /** Къде се попълва „Флаг на източник“ — отказът го назовава. */
  public static final String SOURCE_WHERE =
      "Настройки › Българска локализация › „ЕИК на разработчика на софтуера“ (същата като за поле"
          + " 53 на Д1)";

  private BgApp9MandatoryPoints() {}

  /**
   * Липсите в ЗАГЛАВНАТА част — те спират ЦЕЛИЯ файл, независимо колко документа носи.
   *
   * <p>⚠️ Проверяват се отделно от документите, защото и адресатът е друг: заглавната част се
   * попълва ВЕДНЪЖ на износа, а не на всеки болничен лист.
   */
  public static List<BgApp9Gap> ofHeader(BgApp9EmitterContext context) {
    List<BgApp9Gap> gaps = new ArrayList<>();
    if (context == null) {
      gaps.add(
          BgApp9Gap.header(
              "заглавна част", "BPril9", "няма данни за износа — записът не е попълнен"));
      return gaps;
    }

    if (context.nssiRoCode == null
        || context.nssiRoCode.intValue() < 1
        || context.nssiRoCode.intValue() > 28) {
      gaps.add(
          BgApp9Gap.header(
              "ТП на НОИ",
              "NSSI_RO",
              "контролът иска „задължително попълнена стойност от 1 до 28“, а стойността е "
                  + (context.nssiRoCode == null ? "празна" : context.nssiRoCode)
                  + ". Няма носител в модела — попълва се на самия износ."));
    }
    if (blank(context.bulstatKasa)) {
      gaps.add(
          BgApp9Gap.header(
              "ЕИК на подателя",
              "BULSTATKasa",
              "контролът иска „винаги да е попълнен“ — идва от Партньора на дружеството"
                  + " (регистрационен номер)."));
    } else if (!isBulstatShape(context.bulstatKasa)) {
      gaps.add(
          BgApp9Gap.header(
              "ЕИК на подателя",
              "BULSTATKasa",
              "стойността „"
                  + context.bulstatKasa
                  + "“ не е 9, 11 или 13 цифри, както описанието изисква."));
    }
    if (blank(context.bulName)) {
      gaps.add(
          BgApp9Gap.header(
              "наименование на подателя", "BulName", "контролът иска „винаги да е попълнено“."));
    }
    if (context.bulAddressProblem != null) {
      gaps.add(
          BgApp9Gap.header(
              "контактни данни на осигурителя",
              "BulAddressFull",
              "контролът е дословен: „Задължителни са поне ОБЛАСТ и НАСЕЛЕНО МЯСТО“ — "
                  + context.bulAddressProblem));
    } else if (blank(context.bulRegion) || blank(context.bulCity)) {
      gaps.add(
          BgApp9Gap.header(
              "контактни данни на осигурителя",
              "BulAddressFull",
              "контролът е дословен: „Задължителни са поне ОБЛАСТ и НАСЕЛЕНО МЯСТО“ —"
                  + " попълва се: "
                  + BgApp9Place.EMPLOYER_WHERE
                  + "."));
    }
    if (blank(context.representativeFamilyName)) {
      gaps.add(
          BgApp9Gap.header(
              "длъжностно лице",
              "Representative/FamilyName",
              "контролът иска „поне фамилия“ — идва от Декларатора на дружеството."));
    }
    // ⚠️ И КРАТКАТА ДЛЪЖНОСТ СЕ ОТКАЗВА ТУК, ПОИМЕННО — не чак от XSD-то. Отказ от схемата казва
    // „cvc-minLength-valid“ и не сочи къде се поправя; този сочи полето.
    if (blank(context.representativePosition)
        || context.representativePosition.trim().length() < 2) {
      gaps.add(
          BgApp9Gap.header(
              "длъжностно лице · длъжност",
              "Representative/Position",
              "контролът иска „да е попълнена задължително“ (2–100 знака) — попълва се"
                  + " на Декларатора на дружеството (поле „Длъжност“, напр. „Управител“)."
                  + " „Качество“ (законен представител/пълномощник) не е длъжност."));
    }
    if (context.certificateDate == null) {
      gaps.add(
          BgApp9Gap.header(
              "дата на удостоверението",
              "DateCertificate",
              "контролът иска „да е попълнена задължително“."));
    }
    if (context.exportDate == null) {
      gaps.add(
          BgApp9Gap.header(
              "дата на износа",
              "DateExport",
              "контролът иска „задължително попълнена пълна и коректна дата“."));
    }
    // Приложение № 1 (03/2024), Pril9_Boln, Source: „код по БУЛСТАТ на разработчик на софтуера
    // – 9, 11 или 13 цифри“, задължителен за прием. НЕ е ЕИК-ът на осигурителя.
    if (blank(context.source)) {
      gaps.add(
          BgApp9Gap.header(
              "флаг на източник",
              "Source",
              "задължителен за прием: ЕИК на РАЗРАБОТЧИКА на софтуера (9, 11 или 13 цифри), не на"
                  + " осигурителя. Попълва се: "
                  + SOURCE_WHERE
                  + "."));
    } else if (!isBulstatShape(context.source)) {
      gaps.add(
          BgApp9Gap.header(
              "флаг на източник",
              "Source",
              "стойността „"
                  + context.source
                  + "“ не е 9, 11 или 13 цифри, както Приложение № 1 изисква. Поправя се: "
                  + SOURCE_WHERE
                  + "."));
    }
    return gaps;
  }

  /**
   * Липсите в ЕДНО удостоверение — всяка закачена към него по id ({@link BgApp9Gap#about}).
   *
   * @param declaration записът от Ф1, попълнен
   * @param months редовете на т. 9 след агрегацията на {@link BgApp9Point9}
   */
  public static List<BgApp9Gap> ofDocument(
      BgApp9Declaration declaration, List<BgApp9Point9.Month> months) {
    List<BgApp9Gap> out = new ArrayList<>();
    for (BgApp9Gap gap : documentGaps(declaration, months)) {
      out.add(gap.about(declaration));
    }
    return out;
  }

  private static List<BgApp9Gap> documentGaps(
      BgApp9Declaration declaration, List<BgApp9Point9.Month> months) {

    List<BgApp9Gap> gaps = new ArrayList<>();
    if (declaration == null) {
      return gaps;
    }
    String doc = label(declaration);
    BgSickLeave sick = declaration.getSickLeave();

    if (sick == null) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "болничен лист",
              "PatientsChartNumber",
              "удостоверението не сочи болничен лист — цялата заглавна част на документа идва"
                  + " от него."));
      return gaps;
    }

    // ═══ Идентификация на листа ═══
    if (BgApp9ChartNumber.compose(sick.getBgSeries(), sick.getBgYear(), sick.getBgDocumentNumber())
        == null) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "№ на болничен лист",
              "PatientsChartNumber",
              BgApp9ChartNumber.describeMissing(
                  sick.getBgSeries(), sick.getBgYear(), sick.getBgDocumentNumber())));
    }
    if (sick.getBgIssueDate() == null) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "дата на издаване",
              "DateIssued",
              "контролът иска „попълнена пълна и коректна дата“ — тя е ДАТАТА НА ИЗДАВАНЕ от"
                  + " лечебното заведение, не началото на отпуска."));
    }
    if (sick.getFromDate() == null) {
      gaps.add(BgApp9Gap.of(doc, "в отпуск от", "SickLeaveStartDate", "листът няма начална дата."));
    }
    if (sick.getToDate() == null) {
      gaps.add(BgApp9Gap.of(doc, "в отпуск до", "SickLeaveEndDate", "листът няма крайна дата."));
    }

    // ═══ Лицето ═══
    Employee employee = employeeOf(sick);
    Partner person = employee == null ? null : employee.getContactPartner();
    if (person == null || blank(person.getName())) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "имена на лицето",
              "Insured/FamilyName",
              "контролът иска „да е попълнена поне фамилия“ — идва от Партньора на служителя."));
    }
    String personalId = employee == null ? null : trim(employee.getBgPersonalIdNumber());
    if (personalId == null) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "ЕГН/ЛН/ЛНЧ на лицето",
              "Insured/EGN",
              "задължителен за прием на файла — попълва се на служителя."));
    } else if (personalId.length() != 10 || !isDigits(personalId)) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "ЕГН/ЛН/ЛНЧ на лицето",
              "Insured/EGN",
              "схемата иска ТОЧНО десет цифри, а стойността е „" + personalId + "“."));
    }

    // ═══ Адресът на лицето ═══
    // ⚠️ ТУК СХЕМАТА Е ПО-СТРОГА ОТ ОЧАКВАНОТО, И ТОВА НЕ Е НАША ПОЛИТИКА. EGNRegion и EGNCity са
    // с minLength = 1 в самия XSD, тоест празни правят файла СТРУКТУРНО невалиден — не „непълен“.
    // Контролът казва същото с думи: „Задължителни са поне ОБЛАСТ и НАСЕЛЕНО МЯСТО“.
    // Областта и населеното място се четат от ГРАДА (ЕКАТТЕ) — виж BgApp9Place.
    Address address = person == null ? null : person.getMainAddress();
    BgApp9Place personPlace = BgApp9Place.of(address, "лицето", BgApp9Place.PERSON_WHERE);
    if (!personPlace.isUsable()) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "контактни данни на лицето",
              "Insured/InsuredAddress",
              "схемата иска ОБЛАСТ и НАСЕЛЕНО МЯСТО с поне един знак (minLength=1) — "
                  + personPlace.refusal
                  + " Празни, те правят файла структурно невалиден, а не просто непълен."));
    }

    // ═══ Кодът на операцията ═══
    if (blank(declaration.getBgSubmissionTypeSelect())) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "вид подаване",
              "CodeOperation",
              "контролът е „винаги да е избрана“ операция — представяне, корекция или"
                  + " заличаване."));
    }

    // ═══ Т. 1 ═══
    if (blank(declaration.getBgP1Insured())) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 1 · осигурен за риска",
              "RiskInsured",
              "контролът е „задължително да е направен избор“. Празно тук значи, че резолверът"
                  + " е отказал — причината е в „Присъди на резолвера“."));
    } else if (BgApp9ObstoyatelstvaResolver.YES.equals(declaration.getBgP1Insured())
        && blank(declaration.getBgP1TermTypeSelect())
        && blank(declaration.getBgP1SolRegTypeSelect())) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 1.1 / т. 1.2 · срочност",
              "EmploymentType",
              "контролът е дословен: „винаги да е направен избор ИЛИ в т. 1.1, ИЛИ в 1.2, ако е"
                  + " попълнено, че е осигурен“."));
    }

    // ═══ Т. 2 и т. 3 — точно ЕДНА от двете носи отговор ═══
    boolean p2 = isAnswered(declaration.getBgP2SixMonthStaySelect());
    boolean p3 = isAnswered(declaration.getBgP3TwelveMonthStaySelect());
    if (!p2 && !p3) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 2 / т. 3 · осигурителен стаж",
              "SixMonthsInsurance",
              "контролът иска стойност, различна от „9“, ИЛИ в т. 2, ИЛИ в т. 3 — според"
                  + " осигурителния риск. И двете са без отговор."));
    } else if (p2 && p3) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 2 / т. 3 · осигурителен стаж",
              "SixMonthsInsurance",
              "контролът допуска отговор САМО в едната от двете точки, а тук и двете носят"
                  + " стойност. Съобщението на НОИ е „Направен е избор за липса на осигурителен"
                  + " стаж и в двете т. 2 и т. 3“."));
    }

    // ═══ Т. 4 ═══
    String insuredType = trim(declaration.getBgP4InsuredTypeCode());
    if (insuredType == null) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 4 · код за вид осигурен",
              "TypeOfInsured",
              "контролът е „винаги да е попълнен“ — идва от трудовия договор."));
    } else if (!ALLOWED_INSURED_TYPES.contains(insuredType)) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 4 · код за вид осигурен",
              "TypeOfInsured",
              "код „"
                  + insuredType
                  + "“ НЕ Е в изброяването на схемата. Номенклатурата на образеца е по-тясна от"
                  + " bg.insured.type.select — виж домейна на удостоверението."));
    }

    // ═══ Т. 5 и т. 6 ═══
    // ⚠️ БЕЗ ПРОВЕРКА ЗА null. Генерираният getter за Integer връща 0 при празна колона, тоест
    // непопълнената точка пристига като нула — а нулата е точно това, което контролът отхвърля
    // („да е по-голям от 0“). Проверка за null тук би била мъртъв код И би пропуснала празното.
    Integer count = declaration.getBgP5RelationshipCount();
    Integer seq = declaration.getCertificateSeqNo();
    if (count.intValue() < 1) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 5 · брой правоотношения",
              "EGNBulCountTOI",
              "контролът е „винаги да е попълнен и да е по-голям от 0“."));
    }
    if (seq.intValue() < 1) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 6 · пореден номер на удостоверението",
              "DocNumber",
              "контролът е „винаги да е попълнен и да е по-голям от 0“."));
    } else if (count.intValue() >= 1 && seq.intValue() > count.intValue()) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 6 · пореден номер на удостоверението",
              "DocNumber",
              "контролът иска номерът да е <= броя правоотношения (т. 5 = "
                  + count
                  + "), а тук е "
                  + seq
                  + "."));
    }

    // ═══ Т. 9 ═══
    if (months == null || months.isEmpty()) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 9 · работни дни по месеци",
              "SickLeaveMonths",
              "няма НИТО ЕДИН ред за периода. Редовете се пишат при ПРЕСМЯТАНЕ НА ФИША за"
                  + " месеците на листа (Ф0) — без пресметнат фиш удостоверението няма т. 9."));
    } else {
      for (BgApp9Point9.Month month : months) {
        if (month.legalWorkHours != null
            && (month.legalWorkHours.intValue() < 1
                || month.legalWorkHours.intValue() > BgApp9Point9.MAX_LEGAL_HOURS)) {
          gaps.add(
              BgApp9Gap.of(
                  doc,
                  "т. 9 · дневно работно време (" + month.month + "." + month.year + ")",
                  "PersonalLegalWorkHours",
                  "контролът иска стойност МЕЖДУ 1 И 8 часа (освен при вид осигурен 99), а по"
                      + " договора нормата е "
                      + month.dailyHours
                      + " ч. Схемата не може да носи стойност извън 1–8."));
        }
      }
    }

    // ═══ Т. 25 ═══
    if (insuredType == null || !WITHOUT_EMPLOYER_REGISTRY.contains(insuredType)) {
      if (blank(sick.getBgEmployerReceivedNo())) {
        gaps.add(
            BgApp9Gap.of(
                doc,
                "т. 25 · входящ № при осигурителя",
                "NumberInEmployerRegistry",
                "контролът е „задължително да е попълнен, С ИЗКЛЮЧЕНИЕ на удостоверенията с"
                    + " вид осигурен 12, 13 или 96“ — попълва се на болничния лист."));
      }
      if (sick.getBgEmployerReceivedDate() == null) {
        gaps.add(
            BgApp9Gap.of(
                doc,
                "т. 25 · дата на представяне при осигурителя",
                "DateInEmployerRegistry",
                "контролът е „задължително попълнена пълна и коректна дата“ за същите случаи."));
      }
    }

    // ═══ Т. 26 ═══
    String firstSubmission = declaration.getBgP26FirstSubmissionSelect();
    if (blank(firstSubmission)) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 26 · платежна сметка",
              "BankAccountDeclared",
              "контролът е „задължително се отбелязва“ дали лицето ДЕКЛАРИРА сметка, или вече"
                  + " има декларирана в НОИ."));
    } else if (BgApp9ObstoyatelstvaResolver.YES.equals(firstSubmission)
        && blank(declaration.getBenefitIban())) {
      gaps.add(
          BgApp9Gap.of(
              doc,
              "т. 26 · IBAN",
              "IBAN",
              "при ПЪРВО представяне сметката се декларира задължително, а IBAN-ът е празен —"
                  + " попълва се на служителя (поле „IBAN за обезщетения от ДОО“)."));
    }

    return gaps;
  }

  /** Има ли отговор в поле по {@code bg.app9.stay.select} — „не се отнася“ НЕ е отговор. */
  private static boolean isAnswered(String value) {
    return !blank(value) && !BgApp9ObstoyatelstvaResolver.STAY_NOT_APPLICABLE.equals(value);
  }

  /** Етикетът на документа в съобщенията: номер на лист + фамилия. */
  public static String label(BgApp9Declaration declaration) {
    BgSickLeave sick = declaration == null ? null : declaration.getSickLeave();
    String number = sick == null ? null : trim(sick.getBgDocumentNumber());
    Employee employee = employeeOf(sick);
    Partner person = employee == null ? null : employee.getContactPartner();
    String name = person == null ? null : trim(person.getName());
    StringBuilder sb = new StringBuilder();
    sb.append(number == null ? "лист без номер" : "лист " + number);
    if (name != null) {
      sb.append(" · ").append(name);
    }
    return sb.toString();
  }

  private static Employee employeeOf(BgSickLeave sick) {
    EmploymentContract contract = sick == null ? null : sick.getEmploymentContract();
    return contract == null ? null : contract.getEmployee();
  }

  /** 9, 11 или 13 цифри — описанието на {@code BULSTATKasa} и на {@code Source}. */
  static boolean isBulstatShape(String value) {
    String t = trim(value);
    if (t == null || !isDigits(t)) {
      return false;
    }
    return t.length() == 9 || t.length() == 11 || t.length() == 13;
  }

  static boolean isDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  static boolean blank(String value) {
    return trim(value) == null;
  }

  static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
