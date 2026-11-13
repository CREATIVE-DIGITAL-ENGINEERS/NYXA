package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПОДПИСЪТ НА КОНТРАКТА ВХОД → ENGINE.
 *
 * <p>§ 13.1 от спецификацията иска WS-3 и WS-5 да се договорят за входната структура и „да не я
 * менят едностранно“ — но не казва как се спазва това. Ето как: списъкът с полета е закован тук.
 * Добавено, махнато или преименувано поле прави ТОЗИ тест червен, тоест промяната се вижда в ревюто
 * вместо да мине тихо през нечий commit.
 *
 * <p><b>Какво НЕ пази.</b> Не пази стойности и не проверява поведение — контрактът няма поведение.
 * Пази имена, ПЪЛНИ типове, реда, това, че полетата остават финални, и това, че всяко поле обявява
 * единицата и източника си: DTO, което може да се промени след създаване, не е договор, а споделено
 * състояние.
 *
 * <p><b>Как се променя контрактът.</b> Сменяш списъка тук, в същия commit, в който сменяш класа.
 * Тогава ревюто вижда две неща наведнъж: какво е добавено и че някой е бил наясно, че добавя.
 *
 * <p><b>⚠️ ЗАЩО ТИПЪТ Е ПЪЛЕН, А НЕ ПРОСТО ИМЕ.</b> Сверка по {@code getType().getSimpleName()}
 * пази само низа „List“. Смяна на {@code List<SickLeaveEpisode>} на {@code List<String>} би минала
 * ЗЕЛЕНО — подписът би бил сляп точно за типа на съдържанието, заради което болничните са епизоди.
 * Сляп би бил и за два различни типа с еднакво просто име от различни пакети. Затова се сверява
 * {@code getGenericType().getTypeName()}: с пакет и с генеричен параметър. Списъкът е по-дълъг за
 * четене и това е приемливата цена — подпис, който не се чете буквално, не е подпис.
 *
 * <p><b>⚠️ ЗАЩО РЕДЪТ СЕ СВЕРЯВА СЪС СОРСА, А НЕ С REFLECTION.</b> {@link
 * Class#getDeclaredFields()} по спецификация НЕ гарантира ред; сравнение срещу него работи на
 * HotSpot само по случайност на реализацията. Твърдение, което държи по случайност, е по-лошо от
 * липсващо: то учи читателя, че редът е защитен, и мълчи в деня, в който не е.
 *
 * <p>Редът обаче наистина значи нещо тук — списъкът е групиран по смисъл (кой и по кой договор,
 * периодът, отработено, отсъствия, суми, които идват готови), а групите са отбелязани с коментари в
 * самия клас. Затова твърдението не отпада, а се пренася върху нещо ГАРАНТИРАНО: реда на
 * декларациите в сорсовия файл. Тестът и без това чете файла, за да проверява единиците, тоест това
 * не струва нищо допълнително. Reflection пази имената, пълните типове и финалността — но без да
 * претендира за ред.
 */
class BgPayrollInputContractTest {

  /**
   * Сорсът на контракта. Пътят е спрямо директорията на модула — същата уговорка като в {@code
   * BgInertSeamTest} и {@code BgDefaultedGetterTest}, които също четат сорса.
   *
   * <p>⚠️ Единицата и източникът живеят САМО в javadoc-а и не се виждат през reflection. Затова
   * тестът чете файла. Ако файлът не се намери, тестът ПАДА, а не се пропуска: пропуснатата
   * проверка изглежда точно като минала.
   */
  private static final Path SOURCE =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/payroll/BgPayrollInput.java");

  /**
   * ⚠️ ТОЧНИЯТ СПИСЪК. Ред: както в класа, групиран по смисъл. Форматът е „ПЪЛЕН тип име“, тоест
   * {@code getGenericType().getTypeName()} — с пакет и с генеричен параметър, виж javadoc-а на
   * класа. Вложените типове се изписват с {@code $}, както ги дава reflection.
   *
   * <p>Единицата и източникът на всяко поле живеят в javadoc-а НА КЛАСА, не тук — за да няма два
   * списъка, които се разминават. Че ги ИМА, се проверява от {@link #everyFieldDeclaresItsUnit()} и
   * {@link #everyFieldDeclaresItsSourceOrIsAKnownException()}.
   */
  private static final List<String> CONTRACT =
      Arrays.asList(
          // кой и по кой договор
          "java.lang.Long employmentContractId",
          // ⚠️ Обосновката е, че самоосигуряващото се лице НЯМА трудов договор (КСО чл. 6 ал.
          // 8: вноските са за сметка на осигурените лица), тоест employmentContractId остава
          // ПРАЗЕН за него и без второ поле входът не може да каже от какво е сглобен. Стои до
          // носача си, а не в края.
          "java.lang.Long selfInsuredRegistrationId",
          "java.lang.Long employeeId",
          "java.lang.String insuredTypeCode",
          "java.lang.String laborCategory",
          "java.time.LocalDate birthDate",
          "boolean bornAfter1959",
          "java.lang.String pensionChoice",
          "boolean pensioner",
          "java.lang.String solScope",
          "java.lang.String teacherScope",
          "java.lang.String nkpdCode",
          "java.lang.String nkidCode",
          "java.math.BigDecimal baseSalary",
          "java.math.BigDecimal contractHoursPerDay",
          // ⚠️ Без него режимът по чл. 138 ал. 1 КТ не е изразим: непълното работно време може да
          // е с намален брой ДНИ, а само часовете на ден не различават този случай. Стои в своята
          // смислова група, до contractHoursPerDay, а не в края на списъка.
          "java.math.BigDecimal contractDaysPerWeek",
          "java.lang.String workTimeMode",
          // ⚠️ Часовият енджин (WS-3). Стоят в своята смислова група, до работното време, а не
          // в края на списъка: научната степен по НСОРЗ чл. 11 е свързана с ИЗПЪЛНЯВАНАТА
          // РАБОТА, а ненормираният работен ден по КТ чл. 139а мени заплащането на извънредния
          // труд (чл. 263).
          "java.lang.String academicDegree",
          "boolean unregulatedWorkDay",
          "java.lang.Integer recognisedServiceYears",
          "java.lang.Integer recognisedServiceMonths",
          "java.lang.Integer recognisedServiceDays",
          "java.time.LocalDate recognisedServiceStartDate",
          "java.lang.Integer insuranceServiceMonths",
          // периодът
          "java.time.LocalDate periodFrom",
          "java.time.LocalDate periodTo",
          "java.time.LocalDate parameterReferenceDate",
          "java.lang.Integer workingDaysInPeriod",
          // отработено
          "java.lang.Integer workedDays",
          "java.math.BigDecimal workedHours",
          // ⚠️ НЕ Е workedDays: онова са дните, по които се пропорционира
          // заплатата, а това е колко дни ПОКРИВА отчетът за време. Разликата между двете носи
          // часовете на платения отпуск — третата съставка на т. 16.7 от Декларация обр. 1.
          "java.lang.Integer timesheetDays",
          "java.math.BigDecimal nightHours",
          "java.math.BigDecimal overtimeWorkdayHours",
          "java.math.BigDecimal overtimeRestDayHours",
          "java.math.BigDecimal overtimeHolidayHours",
          "java.math.BigDecimal overtimeSirvHours",
          "java.math.BigDecimal onCallHours",
          "java.math.BigDecimal holidayWorkHours",
          // отсъствия
          // ⚠️ BigDecimal, а НЕ Integer, и това е решение. Дробният ден е задължителният образ на
          // КТ чл. 166 (кърмене, 1 час дневно) и КТ чл. 157 ал. 1 т. 6 (1 час дневно при
          // предизвестие) в дни: час при осемчасов ден Е 0,125 дни. С Integer дробният сбор е
          // ОТКАЗ — а отказът обезсилва ЦЯЛАТА тройка, тоест ЕДНА такава заявка би махнала
          // здравната вноска при неплатен отпуск от целия фиш.
          "java.math.BigDecimal paidLeaveDays",
          // ⚠️ ПОДМНОЖЕСТВО на paidLeaveDays, а не съседна кофа — виж javadoc-а на полето.
          // Влиза в групата на ОТСЪСТВИЯТА, до онова, чието подмножество е, а не в края.
          "java.math.BigDecimal art177LeaveDays",
          "java.math.BigDecimal unpaidLeaveDays",
          // ⚠️ ПОДМНОЖЕСТВО на
          // unpaidLeaveDays — онази негова част, която Декларация обр. 1 вади от т. 16.1 (т. 16.5
          // по КТ чл. 160 ал. 3 и т. 16.3 по чл. 167а). Стои до носача си, а не в края на списъка,
          // по същия довод като art177LeaveDays. Второ число, защото двата читателя искат различни
          // обхвати: здравната вноска по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ се дължи за ВСЕКИ ден
          // неплатен отпуск, а заплатата се сваля само за онези дни, които Д1 обявява извън т.16.1.
          "java.math.BigDecimal unpaidLeaveDaysInD1",
          // ⚠️ Разцепването на КТ чл. 157. ВТОРО подмножество на unpaidLeaveDays, и то по ДРУГ
          // признак: `unpaidLeaveDaysInD1` отговаря на „дължи ли работодателят заплащане за деня“
          // (карвът), а това поле — на „НЕПЛАТЕН ОТПУСК ли е денят“, дословната хипотеза на ЗЗО
          // чл. 40 ал. 1 т. 1 б. „б“. За петте вида от категория 7 двата въпроса имат един
          // отговор: всички носят платец. paid_157_special (ал. 1 т. 4, 5 и 7) е видът, при който
          // се разминават — работодателят не дължи
          // размера (ал. 3 т. 3 го праща в специалните закони), но възнаграждение СЕ ИЗПЛАЩА от
          // друг платец и денят се зачита за стаж. Стои до носача си, не в края, по същия довод
          // като art177LeaveDays.
          "java.math.BigDecimal unpaidLeaveDaysUnderZzo",
          // ⚠️ СЪСЕДНО поле, не подмножество на кофа. Дните извън
          // осигуряването (КСО чл. 10 ал. 2) се разпръсват по трите кофи, а непредставеният
          // болничен не е в НИТО ЕДНА — категория 2 няма кофа на фиша. Двете числа за карва се
          // сгъват едва в BgEarningsService, защото тук правят различни твърдения пред Д1.
          "java.math.BigDecimal daysOutsideInsurance",
          // ⚠️ КАРТА, а не число: Декларация обр. 1 НЕ смята дните по кофи наново с „огледална“
          // заявка над същите заявки за отпуск. Огледалото се държи само докато никой не пипне
          // едната половина — махне ли сглобителят отказ (часове, граница на периода, дробен
          // ден), а Д1 го запази, заявка 28.12–08.01 сваля 4 дни от декемврийската заплата и
          // НУЛА от декемврийската т. 16.5. Обходът е ЕДИН и изходът му е тук.
          "java.util.Map<java.lang.String, java.math.BigDecimal> leaveDaysByD1Code",
          "java.util.List<com.axelor.apps.bglocalization.service.payroll."
              + "BgPayrollInput$SickLeaveEpisode> sickLeaveEpisodes",
          "java.math.BigDecimal otherAbsenceDays",
          // ⚠️ НЕ Е число и точно затова е тук, в края на групата на отсъствията: причината важи
          // за ЦЯЛАТА група — отказът на leaveDaysOf обезсилва и четирите кофи наведнъж. Изхвърлена
          // в build(), тя би направила отказа неразличим от „няма нито една заявка за отпуск“.
          "java.lang.String leaveDaysRefusal",
          // суми, които идват готови
          "java.util.List<com.axelor.apps.bglocalization.service.payroll."
              + "BgPayrollInput$ManualLine> manualLines",
          "java.util.List<java.lang.Long> compensationDocumentIds",
          "java.math.BigDecimal otherEmployerInsurableIncome",
          "java.math.BigDecimal insurableIncomeOverride",
          "java.lang.String insurableIncomeOverrideReason",
          // ⚠️ Обосновката е, че лицето без брутно възнаграждение няма как да получи база от
          // перата: чл. 6 ал. 8 КСО я определя като ИЗБОР между минимума и максимума по ЗБДОО.
          // Стои в тази група, защото е точно „сума, която идва готова“, а НЕ до
          // insurableIncomeOverride по смисъл — онова е обявена липса за админ надписване, което
          // заобикаля изчислението, а това е самото изчисление. Съседството е случайно и е
          // записано като такова.
          "java.math.BigDecimal selfInsuredSelectedIncome",
          // ⚠️ ГРАЖДАНСКИ ДОГОВОР — СВОЯ ГРУПА, а не край на предишната, и това е решението.
          // Двете полета не са „сума, която идва готова“: първото е СТАВКА (процент по ЗДДФЛ
          // чл. 29 ал. 1), второто е ПРИЗНАК за правно положение (КСО чл. 4 ал. 3 т. 6). Сложени в
          // групата на сумите, те щяха да четат като още едно надписване на базата, а те не
          // надписват нищо — те са входовете, от които базата се ИЗВЕЖДА.
          "java.math.BigDecimal expenseRate",
          "boolean insuredOnOtherGround");

  /** Един болничен епизод — отделен подпис, по същата причина. */
  private static final List<String> SICK_EPISODE =
      Arrays.asList(
          "java.time.LocalDate fromDate",
          "java.time.LocalDate toDate",
          "boolean continuation",
          "java.lang.String documentNumber",
          "java.lang.String absenceTypeCode",
          // Влезе с Ф0 на НОИ слоя (Приложение № 9): разбивът по т. 9 се записва обратно върху
          // ТОЧНИЯ запис на листа, а documentNumber не е ключ — домейнът го оставя без unique.
          "java.lang.Long sickLeaveId");

  /** Полетата на едно ръчно перо — отделен подпис, по същата причина. */
  private static final List<String> MANUAL_LINE =
      Arrays.asList(
          "java.lang.String lineTypeCode",
          "java.math.BigDecimal quantity",
          "java.math.BigDecimal rate",
          "java.math.BigDecimal amount",
          "java.lang.String note");

  /**
   * ⚠️ ИЗКЛЮЧЕНИЕ, КОЕТО СТЪПВА ВЪРХУ ЧУЖД ИЗТОЧНИК — И КАЗВА ВЪРХУ ЧИЙ.
   *
   * <p>⚠️ ПРИЧИНАТА НА ВСЯКО ОТ ТЕЗИ ИЗКЛЮЧЕНИЯ Е ЗАВИСИМОСТ, НЕ ОБЯСНЕНИЕ. „Стъпва върху източника
   * на друго поле“ значи, че изключението е основателно САМО докато другото поле още обявява
   * източник. Ако носещото поле изгуби своя „Източник:“, шестте изключения тихо остават без
   * основание — точно формата „предпоставка в проза, която нищо не сверява“.
   *
   * <p>Затова носещото поле е ПОЛЕ НА ИЗКЛЮЧЕНИЕТО, а не изречение в причината: така {@link
   * #everyExemptionCarrierStillDeclaresItsSource()} може да го провери, а преименуване на носещото
   * поле проваля търсенето вместо да остави разминати имена в проза.
   */
  private static final class Exemption {

    /** Полето, чийто обявен източник важи и за освободеното. Формат: {@code ПростоИме#поле}. */
    private final String carrier;

    private final String reason;

    private Exemption(String carrier, String reason) {
      this.carrier = carrier;
      this.reason = reason;
    }
  }

  /**
   * ⚠️ ИЗКЛЮЧЕНИЯТА ОТ ПРАВИЛОТО „ВСЯКО ПОЛЕ ОБЯВЯВА ИЗТОЧНИКА СИ“ — С ПРИЧИНА, НЕ МЪЛЧАЛИВО.
   *
   * <p>Образецът е {@code BgInertSeamTest}: явен списък плюс проверка в ОБРАТНАТА посока, тоест щом
   * поле започне да обявява източника си, тестът пак става червен и изключението трябва да се
   * махне. Причината се пише като стойност, по образеца на {@code BgDefaultedGetterTest.EXEMPT} —
   * изключение без причина е криене на грешка.
   *
   * <p>Формат на ключа: {@code ПростоИмеНаКласа#поле}. Списъкът е ПЪЛЕН — шест полета, стъпили
   * върху два носача.
   *
   * <p>⚠️ Обратната посока хваща и трето нещо: изключение за поле, което вече не съществува.
   * Остарялото изключение е точно толкова тихо, колкото и липсващата проверка.
   */
  private static final Map<String, Exemption> SOURCE_EXEMPT = new LinkedHashMap<>();

  static {
    SOURCE_EXEMPT.put(
        "BgPayrollInput#employeeId",
        new Exemption(
            "BgPayrollInput#employmentContractId",
            "javadoc-ът казва само „⚠️ Само за проследимост, както и горното“, тоест стъпва върху"
                + " източника на employmentContractId вместо да обяви свой"));
    // Петте полета на ManualLine: източникът е обявен ЕДИН ЕТАЖ ПО-ГОРЕ, на полето manualLines
    // („Източник: ТРЗ-специалистът, тоест WS-5“), и важи за целия клас. Изнесен там, той е верен и
    // за петте — повтарянето му на всяко поле щеше да е четвъртото място, което трябва да се пази в
    // синхрон.
    for (String field : Arrays.asList("lineTypeCode", "quantity", "rate", "amount", "note")) {
      SOURCE_EXEMPT.put(
          "ManualLine#" + field,
          new Exemption(
              "BgPayrollInput#manualLines", "източникът е на полето manualLines, за целия клас"));
    }
  }

  private static final List<Class<?>> SIGNED_TYPES =
      Arrays.asList(
          BgPayrollInput.class,
          BgPayrollInput.SickLeaveEpisode.class,
          BgPayrollInput.ManualLine.class);

  /** {@code public final LocalDate periodFrom;} — само публичните финални полета на контракта. */
  private static final Pattern FIELD =
      Pattern.compile("^\\s*public final ([\\w.]+(?:<[^>]+>)?)\\s+(\\w+);\\s*$");

  private static final Pattern CLASS_DECL =
      Pattern.compile(
          "^\\s*(?:(?:public|protected|private|static|final|abstract)\\s+)*class\\s+(\\w+)\\b");

  /** „Единица: ЧАСОВЕ.“ — иска се и НЕЩО след двоеточието. „Единица: няма“ също е обявяване. */
  private static final Pattern UNIT_DECLARED = Pattern.compile("Единица:\\s*\\S");

  private static final Pattern SOURCE_DECLARED = Pattern.compile("Източник:\\s*\\S");

  // ═══ КАКВО ВИЖДА REFLECTION ═══════════════════════════════════════════════

  private static List<Field> declaredFieldsOf(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
        .collect(Collectors.toList());
  }

  /**
   * Подписът през reflection: ПЪЛЕН тип плюс име.
   *
   * <p>⚠️ Върнатият ред НЕ е гаранция — {@code getDeclaredFields()} не обещава ред. Затова всеки
   * сравняващ го подрежда. Редът се пази отделно, срещу сорса.
   */
  private static List<String> signatureOf(Class<?> type) {
    return declaredFieldsOf(type).stream()
        .map(f -> f.getGenericType().getTypeName() + " " + f.getName())
        .collect(Collectors.toList());
  }

  /** „java.lang.Long employeeId“ → „employeeId“. */
  private static List<String> namesOf(List<String> signature) {
    return signature.stream()
        .map(s -> s.substring(s.lastIndexOf(' ') + 1))
        .collect(Collectors.toList());
  }

  // ═══ КАКВО ПИШЕ В СОРСА ═══════════════════════════════════════════════════

  /** Едно поле, както е ЗАПИСАНО в сорса, заедно с това, което javadoc-ът над него обявява. */
  private static final class Declared {

    private final String owner;
    private final String name;
    private final boolean hasUnit;
    private final boolean hasSource;
    private final int line;

    private Declared(String owner, String name, boolean hasUnit, boolean hasSource, int line) {
      this.owner = owner;
      this.name = name;
      this.hasUnit = hasUnit;
      this.hasSource = hasSource;
      this.line = line;
    }

    private String key() {
      return owner + "#" + name;
    }

    private String at() {
      return key() + " (" + SOURCE.getFileName() + ":" + line + ")";
    }
  }

  /**
   * Чете сорса и връща полетата В РЕДА НА ДЕКЛАРИРАНЕ, с това, което javadoc-ът над всяко обявява.
   *
   * <p>Правило за връзката javadoc → поле: блокът се приписва на ПЪРВАТА декларация под себе си.
   * Празни редове и {@code //} коментари между двете не я разкъсват; всеки друг ред (клас,
   * конструктор, метод, затваряща скоба) изхвърля блока — тоест javadoc-ът на класа не се брои за
   * javadoc на поле.
   */
  private static List<Declared> parseSource() throws IOException {
    assertTrue(
        Files.isRegularFile(SOURCE),
        "не намирам сорса на контракта: "
            + SOURCE.toAbsolutePath()
            + "\n  ⚠️ Тестът ПАДА, вместо да се пропусне: единицата и източникът се виждат само в"
            + " javadoc-а, а пропуснатата проверка изглежда точно като минала. Пътят е спрямо"
            + " директорията на модула — проверете от къде се пуска тестът.");

    List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
    List<Declared> out = new ArrayList<>();
    String owner = null;
    StringBuilder doc = null;
    boolean insideDoc = false;

    for (int i = 0; i < lines.size(); i++) {
      String trimmed = lines.get(i).trim();

      if (insideDoc) {
        doc.append(' ').append(trimmed.replaceFirst("^\\*+", ""));
        insideDoc = !trimmed.contains("*/");
        continue;
      }
      if (trimmed.startsWith("/**")) {
        doc = new StringBuilder(trimmed.replaceFirst("^/\\*+", ""));
        insideDoc = !trimmed.contains("*/");
        continue;
      }
      if (trimmed.isEmpty() || trimmed.startsWith("//")) {
        continue; // не разкъсва връзката javadoc → декларация
      }

      Matcher field = FIELD.matcher(lines.get(i));
      if (field.matches()) {
        String text = doc == null ? "" : doc.toString();
        out.add(
            new Declared(
                owner,
                field.group(2),
                UNIT_DECLARED.matcher(text).find(),
                SOURCE_DECLARED.matcher(text).find(),
                i + 1));
        doc = null;
        continue;
      }

      Matcher clazz = CLASS_DECL.matcher(lines.get(i));
      if (clazz.find()) {
        owner = clazz.group(1);
      }
      doc = null;
    }
    return out;
  }

  private static List<Declared> fieldsOwnedBy(List<Declared> declared, Class<?> type) {
    return declared.stream()
        .filter(d -> type.getSimpleName().equals(d.owner))
        .collect(Collectors.toList());
  }

  /**
   * ⚠️ КОТВАТА: парсерът вижда СЪЩИТЕ полета като reflection.
   *
   * <p>Без нея преформатиран сорс или счупен regex биха дали нула прочетени полета, а тестовете за
   * единицата и източника щяха да минат зелени, без да са проверили каквото и да е. Същата
   * предпазна мярка като котвата в {@code BgDefaultedGetterTest}.
   */
  private static void assertParserSeesEveryField(List<Declared> declared) {
    for (Class<?> type : SIGNED_TYPES) {
      Set<String> fromSource =
          fieldsOwnedBy(declared, type).stream()
              .map(d -> d.name)
              .collect(Collectors.toCollection(TreeSet::new));
      Set<String> fromReflection =
          declaredFieldsOf(type).stream()
              .map(Field::getName)
              .collect(Collectors.toCollection(TreeSet::new));
      assertEquals(
          fromReflection,
          fromSource,
          "ПАРСЕРЪТ НА СОРСА НЕ ВИЖДА СЪЩИТЕ ПОЛЕТА КАТО REFLECTION за "
              + type.getSimpleName()
              + ".\n  ⚠️ Поправете парсера, не заобикаляйте: инак проверките за единица и източник"
              + " минават зелени, без да са прочели нищо.");
    }
  }

  // ═══ ПОДПИСЪТ ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Списъкът с полета на контракта е точно този — промяна тук значи промяна в ревюто")
  void theContractHasExactlyTheseFields() {
    List<String> actual = signatureOf(BgPayrollInput.class);

    List<String> added = new ArrayList<>(actual);
    added.removeAll(CONTRACT);
    List<String> removed = new ArrayList<>(CONTRACT);
    removed.removeAll(actual);

    if (!added.isEmpty() || !removed.isEmpty()) {
      fail(
          "КОНТРАКТЪТ ВХОД → ENGINE е променен едностранно.\n"
              + "  добавени : "
              + added
              + "\n  махнати  : "
              + removed
              + "\n"
              + "  ⚠️ § 13.1 иска WS-3 и WS-5 да се договорят за тази структура. Ако промяната е"
              + " уговорена, обновете и списъка в този тест — в същия commit.\n"
              + "  ⚠️ Типът се сверява ПЪЛЕН, с пакет и генеричен параметър: смяна само на"
              + " съдържанието на списък (List<SickLeaveEpisode> → List<String>) също се вижда"
              + " тук.");
    }
    // ⚠️ ПОДРЕДЕНО, не както дойде: редът от getDeclaredFields() не е гаранция и се пази отделно,
    // от theDeclarationOrderIsPartOfTheSignature, срещу сорса.
    assertEquals(
        new TreeSet<>(CONTRACT),
        new TreeSet<>(actual),
        "имената и пълните типове са част от подписа (за реда виж отделния тест)");
    assertEquals(56, actual.size(), "петдесет и шест полета");
  }

  @Test
  @DisplayName("Ръчното перо носи точно тези пет полета")
  void theManualLineHasExactlyTheseFields() {
    assertEquals(
        new TreeSet<>(MANUAL_LINE),
        new TreeSet<>(signatureOf(BgPayrollInput.ManualLine.class)),
        "за реда виж theDeclarationOrderIsPartOfTheSignature");
  }

  @Test
  @DisplayName("Болничният епизод носи точно тези пет полета")
  void theSickEpisodeHasExactlyTheseFields() {
    assertEquals(
        new TreeSet<>(SICK_EPISODE),
        new TreeSet<>(signatureOf(BgPayrollInput.SickLeaveEpisode.class)),
        "за реда виж theDeclarationOrderIsPartOfTheSignature");
  }

  @Test
  @DisplayName("⚠️ Редът на полетата е част от подписа — сверява се със СОРСА, не с reflection")
  void theDeclarationOrderIsPartOfTheSignature() throws IOException {
    List<Declared> declared = parseSource();
    assertParserSeesEveryField(declared);

    for (Class<?> type : SIGNED_TYPES) {
      List<String> expected =
          namesOf(
              type == BgPayrollInput.class
                  ? CONTRACT
                  : type == BgPayrollInput.SickLeaveEpisode.class ? SICK_EPISODE : MANUAL_LINE);
      List<String> actual =
          fieldsOwnedBy(declared, type).stream().map(d -> d.name).collect(Collectors.toList());
      assertEquals(
          expected,
          actual,
          "РЕДЪТ НА ДЕКЛАРИРАНЕ СЕ Е ПРОМЕНИЛ в "
              + type.getSimpleName()
              + ".\n  ⚠️ Редът е част от подписа: списъкът е групиран по смисъл (кой и по кой"
              + " договор, периодът, отработено, отсъствия, суми, които идват готови) и групите са"
              + " отбелязани с коментари в класа. Ново поле влиза в СВОЯТА група, не в края.\n"
              + "  ⚠️ Сверява се срещу реда в сорсовия файл, защото getDeclaredFields() не"
              + " гарантира ред — виж javadoc-а на класа.");
    }
  }

  // ═══ ЕДИНИЦАТА И ИЗТОЧНИКЪТ ═══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Всяко поле обявява ЕДИНИЦАТА си — правилото, с което класът обосновава себе си")
  void everyFieldDeclaresItsUnit() throws IOException {
    List<Declared> declared = parseSource();
    assertParserSeesEveryField(declared);

    List<String> silent =
        declared.stream().filter(d -> !d.hasUnit).map(Declared::at).collect(Collectors.toList());

    if (!silent.isEmpty()) {
      fail(
          "ПОЛЕ БЕЗ ОБЯВЕНА ЕДИНИЦА:\n  "
              + String.join("\n  ", silent)
              + "\n\n  ⚠️ Класът обявява точно това за своя причина да съществува: „ЕДИНИЦАТА Е"
              + " ЧАСТ ОТ ПОЛЕТО… поле без обявена единица не влиза в този контракт.“ Необявената"
              + " единица е скъпа грешка: при чл. 220 срок в календарни дни и делител в работни"
              + " дават 143 на сто от заплата — правдоподобно и грешно.\n"
              + "  Поправка: напишете „Единица: …“ в javadoc-а НА ПОЛЕТО. Ако полето наистина няма"
              + " единица (идентификатор, код, булева), пише се изрично „Единица: няма“ —"
              + " мълчанието не се различава от недоглеждане.");
    }
  }

  @Test
  @DisplayName("⚠️ Всяко поле обявява ИЗТОЧНИКА си, освен шестте явни изключения")
  void everyFieldDeclaresItsSourceOrIsAKnownException() throws IOException {
    List<Declared> declared = parseSource();
    assertParserSeesEveryField(declared);

    Set<String> present =
        declared.stream().map(Declared::key).collect(Collectors.toCollection(TreeSet::new));

    List<String> silent = new ArrayList<>();
    Set<String> nowDeclared = new TreeSet<>();
    for (Declared d : declared) {
      boolean exempt = SOURCE_EXEMPT.containsKey(d.key());
      if (!d.hasSource && !exempt) {
        silent.add(d.at());
      }
      if (d.hasSource && exempt) {
        nowDeclared.add(d.key());
      }
    }
    Set<String> stale = new TreeSet<>(SOURCE_EXEMPT.keySet());
    stale.removeAll(present);

    if (!silent.isEmpty() || !nowDeclared.isEmpty() || !stale.isEmpty()) {
      fail(
          "СПИСЪКЪТ С ИЗКЛЮЧЕНИЯ ОТ „ВСЯКО ПОЛЕ ОБЯВЯВА ИЗТОЧНИКА СИ“ СЕ Е ПРОМЕНИЛ.\n"
              + "  без източник и извън списъка : "
              + silent
              + "\n  вече обявяват източник      : "
              + nowDeclared
              + "\n  изключения за полета, които вече не съществуват: "
              + stale
              + "\n  ⚠️ Ако полето е ново: напишете „Източник: …“ в javadoc-а му. Ако наистина"
              + " стъпва върху чужд източник, впишете го в SOURCE_EXEMPT С ПРИЧИНА — мълчаливото"
              + " пропускане не се различава от недоглеждане.\n"
              + "  ⚠️ Ако поле вече обявява източника си, махнете го от SOURCE_EXEMPT: остарялото"
              + " изключение е точно толкова тихо, колкото и липсващата проверка.");
    }
  }

  /**
   * ⚠️ ПРЕДПОСТАВКАТА НА ИЗКЛЮЧЕНИЕТО СЕ СВЕРЯВА, НЕ СЕ ВЯРВА.
   *
   * <p>Шестте изключения не са „това поле няма източник и това е приемливо“, а „източникът му е
   * обявен НА ДРУГО ПОЛЕ“. Това е зависимост: щом носещото поле изгуби своя „Източник:“, всички
   * стъпили върху него изключения остават без основание — и мълчат, защото проверката за източник
   * гледа само освободеното поле, а него го подминава по списък.
   *
   * <p>Проверява три неща за всеки носач: че още съществува (преименуван носач проваля търсенето,
   * вместо да остави разминати имена в проза), че още обявява източник, и че САМИЯТ ТОЙ не е
   * освободен — иначе веригата стъпва върху нищо и цялата група виси във въздуха.
   */
  @Test
  @DisplayName("⚠️ Носещите полета на изключенията още обявяват източник — предпоставката е жива")
  void everyExemptionCarrierStillDeclaresItsSource() throws IOException {
    List<Declared> declared = parseSource();
    assertParserSeesEveryField(declared);

    Map<String, Declared> byKey = new LinkedHashMap<>();
    for (Declared d : declared) {
      byKey.put(d.key(), d);
    }

    List<String> broken = new ArrayList<>();
    for (Map.Entry<String, Exemption> e : SOURCE_EXEMPT.entrySet()) {
      String carrier = e.getValue().carrier;
      Declared d = byKey.get(carrier);
      if (d == null) {
        broken.add(
            e.getKey() + " → носачът " + carrier + " НЕ СЪЩЕСТВУВА (преименуван или махнат)");
      } else if (!d.hasSource) {
        broken.add(
            e.getKey() + " → носачът " + carrier + " ВЕЧЕ НЕ обявява „Източник:“ (" + d.at() + ")");
      } else if (SOURCE_EXEMPT.containsKey(carrier)) {
        broken.add(e.getKey() + " → носачът " + carrier + " САМИЯТ е освободен: веригата е празна");
      }
    }

    if (!broken.isEmpty()) {
      fail(
          "ИЗКЛЮЧЕНИЕ БЕЗ ЖИВА ПРЕДПОСТАВКА:\n  "
              + String.join("\n  ", broken)
              + "\n\n  ⚠️ Причината на всяко от тези изключения е ЗАВИСИМОСТ: полето е освободено"
              + " само защото източникът му е обявен на носещото поле. Падне ли носачът, шестте"
              + " изключения тихо остават без основание.\n"
              + "  Поправка: върнете „Източник:“ на носача, или обявете източник на самото"
              + " освободено поле и го махнете от SOURCE_EXEMPT. Ако носачът е преименуван,"
              + " обновете полето carrier — не само прозата.");
    }
  }

  @Test
  @DisplayName("⚠️ Изключенията за източника носят причина, не само име")
  void everySourceExceptionCarriesItsReason() {
    List<String> empty =
        SOURCE_EXEMPT.entrySet().stream()
            .filter(e -> e.getValue().reason == null || e.getValue().reason.trim().length() < 20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    assertEquals(
        new ArrayList<String>(),
        empty,
        "изключение без причина е криене на грешка — образецът е BgDefaultedGetterTest.EXEMPT");
  }

  // ═══ НЕИЗМЕНИМОСТ И ЛИПСА НА ПОВЕДЕНИЕ ════════════════════════════════════

  @Test
  @DisplayName("Контрактът е неизменим: всички полета са final, класът също")
  void theContractIsImmutable() {
    assertTrue(Modifier.isFinal(BgPayrollInput.class.getModifiers()), "класът не е final");
    for (Class<?> type : SIGNED_TYPES) {
      for (Field f : declaredFieldsOf(type)) {
        assertTrue(
            Modifier.isFinal(f.getModifiers()),
            "полето " + type.getSimpleName() + "." + f.getName() + " не е final");
      }
    }
  }

  @Test
  @DisplayName("Контрактът НЯМА поведение — само сглобяване и четене")
  void theContractCarriesNoLogic() {
    List<String> methods =
        Arrays.stream(BgPayrollInput.class.getDeclaredMethods())
            .filter(m -> !m.isSynthetic())
            .map(java.lang.reflect.Method::getName)
            .collect(Collectors.toList());
    assertEquals(
        Arrays.asList("builder"),
        methods,
        "в контракта се е появил метод — изчисление в DTO е WS-3, не тук: " + methods);
  }

  // ═══ ГРАНИЦИТЕ, КОИТО КОНТРАКТЪТ ТРЯБВА ДА ИЗРАЗЯВА ═══════════════════════

  @Test
  @DisplayName("⚠️ Болничните са ЕПИЗОДИ: продължението се различава от нов болничен")
  void sickLeaveArrivesAsEpisodesNotAsSplitDays() {
    // Два входа с ЕДНАКЪВ брой дни и различен смисъл: веднъж два отделни болнични, веднъж един с
    // продължение. От гол брой дни двата случая са неразличими — оттам полето continuation.
    BgPayrollInput twoNew =
        BgPayrollInput.builder()
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 4), false, "A-1", "sick_er"))
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 18), false, "A-2", "sick_er"))
            .build();
    BgPayrollInput oneContinued =
        BgPayrollInput.builder()
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 4), false, "B-1", "sick_er"))
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 7), true, "B-2", "sick_er"))
            .build();

    assertEquals(2, twoNew.sickLeaveEpisodes.size());
    assertEquals(2, oneContinued.sickLeaveEpisodes.size());
    assertTrue(oneContinued.sickLeaveEpisodes.get(1).continuation, "второто е продължение");
    assertTrue(!twoNew.sickLeaveEpisodes.get(1).continuation, "второто е нов болничен");

    // ⚠️ Контрактът НЕ казва как се разделят дните между осигурител и НОИ — това е на WS-3. Тук се
    // проверява само, че входът носи достатъчно, за да се приложи каквото и да е правило.
    assertEquals(LocalDate.of(2026, 3, 5), oneContinued.sickLeaveEpisodes.get(1).fromDate);
  }

  @Test
  @DisplayName("⚠️ Видът на епизода различава майчинството от общото заболяване")
  void theEpisodeCarriesItsKind() {
    // Двата дни за сметка на осигурителя не важат при бременност и раждане. Без вида двата случая
    // са
    // еднакви на входа, а разходът пада върху работодателя без основание.
    BgPayrollInput input =
        BgPayrollInput.builder()
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 6), false, "C-1", "sick_er"))
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 8, 1),
                    false,
                    "C-2",
                    "maternity_birth"))
            .build();
    assertEquals("sick_er", input.sickLeaveEpisodes.get(0).absenceTypeCode);
    assertEquals("maternity_birth", input.sickLeaveEpisodes.get(1).absenceTypeCode);
    // ⚠️ Кодовете са от BgAbsenceType, не своя номенклатура — виж javadoc-а на полето.
  }

  @Test
  @DisplayName("⚠️ Датата на раждане е котвата, булевата е удобство")
  void theBirthDateIsTheAnchorForEveryAgeQuestion() {
    BgPayrollInput input =
        BgPayrollInput.builder()
            .birthDate(LocalDate.of(2009, 5, 20))
            .bornAfter1959(true)
            .periodFrom(LocalDate.of(2026, 3, 1))
            .build();

    assertNotNull(input.birthDate, "котвата");
    assertTrue(input.bornAfter1959, "изведеното удобство");
    // Втори въпрос, на който само датата може да отговори: навършени ли са 18 към периода.
    assertTrue(
        java.time.temporal.ChronoUnit.YEARS.between(input.birthDate, input.periodFrom) < 18,
        "ненавършило 18 — изключението при шестмесечния осигурителен стаж. ⚠️ Правилото е"
            + " НЕСВЕРЕНО и НЕ е кодирано: контрактът само носи датата.");
  }

  @Test
  @DisplayName("⚠️ Двата стажа са РАЗЛИЧНИ и не се сливат")
  void theTwoKindsOfServiceAreSeparate() {
    // Човек с дълъг трудов стаж, но малко осигурителен: ТСПО го има, правото на обезщетение — не.
    BgPayrollInput input =
        BgPayrollInput.builder()
            .recognisedServiceYears(12)
            .recognisedServiceMonths(4)
            .recognisedServiceDays(0)
            .recognisedServiceStartDate(LocalDate.of(2013, 11, 1))
            .insuranceServiceMonths(3)
            .build();

    assertEquals(12, input.recognisedServiceYears, "трудов стаж за ТСПО");
    assertEquals(3, input.insuranceServiceMonths, "осигурителен стаж — под шестте месеца");
    assertNotNull(input.recognisedServiceStartDate, "котвата е източникът на истината");
  }

  @Test
  @DisplayName("⚠️ Котвата дава годишнината, сборът — не")
  void theServiceAnchorAnswersWhenTheYearIsCompleted() {
    // 01.11.2013 + 13 години → 01.11.2026. Ако правилото се окаже „от месеца след навършването“,
    // ядрото може да го приложи. От сбора „13 години към 30.11.2026“ не може.
    LocalDate anchor = LocalDate.of(2013, 11, 1);
    BgPayrollInput input =
        BgPayrollInput.builder()
            .recognisedServiceStartDate(anchor)
            .recognisedServiceYears(13)
            .periodFrom(LocalDate.of(2026, 11, 1))
            .periodTo(LocalDate.of(2026, 11, 30))
            .build();

    assertEquals(
        13,
        java.time.temporal.ChronoUnit.YEARS.between(anchor, input.periodTo),
        "навършените години се извеждат от котвата");
    assertEquals(
        LocalDate.of(2026, 11, 1),
        anchor.plusYears(13),
        "и точният ден на навършването — вътре в периода");
  }

  @Test
  @DisplayName("⚠️ Таванът е на ЛИЦЕТО: доходът от други правоотношения е част от входа")
  void theCeilingNeedsIncomeFromOtherEmployers() {
    BgPayrollInput input =
        BgPayrollInput.builder()
            .baseSalary(new BigDecimal("1500.00"))
            .otherEmployerInsurableIncome(new BigDecimal("1200.00"))
            .build();
    assertEquals(new BigDecimal("1200.00"), input.otherEmployerInsurableIncome);
    // 1500 + 1200 минава тавана от 2300 за второто полугодие — но ограничаването е на WS-3.
    assertTrue(
        input
                .baseSalary
                .add(input.otherEmployerInsurableIncome)
                .compareTo(new BigDecimal("2300.00"))
            > 0,
        "точно случаят, който без това поле щеше да се осигури двойно");
  }

  @Test
  @DisplayName("Надписаният осигурителен доход върви с причина")
  void anOverriddenInsurableIncomeCarriesItsReason() {
    BgPayrollInput input =
        BgPayrollInput.builder()
            .insurableIncomeOverride(new BigDecimal("2300.00"))
            .insurableIncomeOverrideReason("Таванът е изчерпан по договора при другия работодател.")
            .build();
    assertNotNull(input.insurableIncomeOverrideReason);
    assertTrue(input.insurableIncomeOverrideReason.length() > 10);
  }

  @Test
  @DisplayName("⚠️ ГРАНИЦАТА: напълно РЪЧЕН фиш се описва от контракта")
  void aFullyManualPaySlipCanBeExpressed() {
    // Нито час, нито ден — само пера, въведени от човек. Ако това не стига, свързаността е
    // обърната.
    BgPayrollInput input =
        BgPayrollInput.builder()
            .employmentContractId(1L)
            .periodFrom(LocalDate.of(2026, 3, 1))
            .periodTo(LocalDate.of(2026, 3, 31))
            .parameterReferenceDate(LocalDate.of(2026, 3, 1))
            .manualLine(
                new BgPayrollInput.ManualLine(
                    "pl_osnovna", null, null, new BigDecimal("1500.00"), "по договор"))
            .manualLine(
                new BgPayrollInput.ManualLine(
                    "pl_zapor", null, null, new BigDecimal("120.00"), "изпълнителен лист"))
            .build();

    assertEquals(2, input.manualLines.size());
    assertEquals("pl_osnovna", input.manualLines.get(0).lineTypeCode);
    assertNotNull(input.compensationDocumentIds, "празен списък, не null");
    assertTrue(input.compensationDocumentIds.isEmpty());
    assertEquals(null, input.workedDays, "ръчният фиш не иска отработени дни");
    assertTrue(input.sickLeaveEpisodes.isEmpty(), "нито болнични");

    // и списъците не се пипат отвън
    try {
      input.manualLines.add(null);
      fail("списъкът с ръчни пера трябва да е неизменим");
    } catch (UnsupportedOperationException expected) {
      // точно това
    }
  }

  @Test
  @DisplayName("⚠️ Датата за версията на параметрите е ОТДЕЛНА от началото на периода")
  void theParameterDateIsSeparateFromThePeriod() {
    // Коригиращ фиш за юли, издаден през септември: параметрите са ЮЛСКИ, не днешните. 2026 г. има
    // две осигурителни половини (чл. 9 ЗБДОО) и ако двете дати се слеят, корекцията тихо ще смята
    // по
    // августовските стойности.
    BgPayrollInput july =
        BgPayrollInput.builder()
            .periodFrom(LocalDate.of(2026, 7, 1))
            .periodTo(LocalDate.of(2026, 7, 31))
            .parameterReferenceDate(LocalDate.of(2026, 7, 1))
            .build();
    assertEquals(LocalDate.of(2026, 7, 1), july.parameterReferenceDate);
    assertTrue(july.parameterReferenceDate.isBefore(LocalDate.of(2026, 8, 1)), "юлски параметри");
  }
}
