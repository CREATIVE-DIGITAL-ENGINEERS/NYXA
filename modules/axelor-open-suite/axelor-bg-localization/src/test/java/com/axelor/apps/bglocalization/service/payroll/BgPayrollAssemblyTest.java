package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * ⚠️ ВСЯКО ПОЛЕ НА КОНТРАКТА Е ИЛИ ПОПЪЛНЕНО ОТ ИЗТОЧНИК, ИЛИ ОБЯВЕНО ЗА ЛИПСВАЩО. Трето няма.
 *
 * <p><b>Кое е същинското.</b> Пропуснат {@code .nightHours(...)} и обявен {@code
 * .nightHours(absent("nightHours"))} дават ЕДНА И СЪЩА стойност — {@code null} — и РАЗЛИЧНО
 * твърдение. Първото е недоглеждане, второто е решение с причина. Подразбиращата се стойност на
 * строителя е точно това, което не може да каже „не знам“, затова разликата не се вижда нито в
 * билда, нито в ревюто — освен ако някой не я направи видима. Този тест я прави видима.
 *
 * <p><b>Защо се чете СОРСЪТ, а не се вика сглобителят.</b> Изпълнението показва СТОЙНОСТИ, а
 * въпросът е за ТВЪРДЕНИЯ. Сглобител, който подминава поле мълчаливо, и сглобител, който го обявява
 * за липсващо, връщат идентичен обект — тоест никакво извикване не може да ги различи. Разликата
 * съществува само в кода и се чете само от кода.
 *
 * <p><b>Двете посоки, и трите състояния.</b> {@code BgPayrollInputBuilder.requireDeclared} пази
 * едната посока още при изпълнение: обявяване на липса за поле извън списъка ПАДА веднага. Тук се
 * пазят другите: запис в списъка без свой ред в кода (остаряло изключение), запис за поле, което
 * вече се пълни от източник, и поле на контракта, подминато без нито едно от двете. Образецът е
 * {@code BgInertSeamTest} — явен списък плюс проверка в обратната посока.
 *
 * <p>Тече БЕЗ база. Базовата половина — че JPQL-ът, който сглобителят пуска, отговаря на
 * действителната схема — е {@code BgPayrollAssemblySchemaTest}.
 */
class BgPayrollAssemblyTest {

  private static final Path BUILDER =
      Paths.get(
          "src/main/java/com/axelor/apps/bglocalization/service/payroll/BgPayrollInputBuilder.java");

  private static final Path WORK_TIME =
      Paths.get(
          "src/main/java/com/axelor/apps/bglocalization/service/payroll/BgPayrollWorkTime.java");

  private static final Path FACTS =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/BgWorkTimeFacts.java");

  /**
   * Полетата, чийто setter в контракта НЕ носи името на полето: списъците приемат „по един“.
   *
   * <p>Изнесено като данни, а не заровено в проза: преименуван setter проваля търсенето, вместо да
   * остави разминати имена в коментар.
   */
  private static final Map<String, String> ADDER = new LinkedHashMap<>();

  static {
    ADDER.put("sickLeaveEpisodes", "sickLeaveEpisode");
    ADDER.put("manualLines", "manualLine");
    ADDER.put("compensationDocumentIds", "compensationDocumentId");
  }

  /**
   * {@code absent("nightHours")} и {@code declareAbsent("sickLeaveEpisodes", "manualLines")}.
   *
   * <p>⚠️ {@code [aA]}, не само {@code a}: търсене на {@code absent\(} НЕ намира {@code
   * declareAbsent(} — там буквата е ГЛАВНА. Обявените липси, които минават през него, биха излезли
   * „премълчани“ и тестът би станал червен за собствения си regex. Записано, защото същият капан
   * чака всеки следващ вход, чието име свършва на {@code Absent(}.
   *
   * <p>{@code DECLARED_ABSENT.put(} НЕ се хваща и това е нарочно: там всички букви са главни, тоест
   * самият списък не се брои за употреба на списъка.
   */
  private static final Pattern ABSENT_CALL = Pattern.compile("[aA]bsent\\(([^)]*)\\)");

  /**
   * Само формата с главна „A“ — за КОТВАТА.
   *
   * <p>⚠️ Отделен regex, а не име на поле. Канарчето трябва да е нещо СТРУКТУРНО, а не поле, което
   * може законно да получи източник и да изчезне от списъка. Заковано поле като канарче би се
   * обадило при нарочното му счупване ПРЕДИ същинското твърдение и червеното би казало „regex-ът е
   * счупен“ вместо „полето минава мълчаливо“ — тоест тестът би бил прав по случайност и подвеждащ
   * по съобщение.
   */
  private static final Pattern DECLARE_ABSENT_CALL = Pattern.compile("declareAbsent\\(([^)]*)\\)");

  private static final Pattern NOT_SUPPLIED_CALL = Pattern.compile("notSupplied\\(([^)]*)\\)");

  /**
   * {@code refuse("unknownRegime")}.
   *
   * <p>⚠️ {@code refuse\(}, не {@code refused\(}: вторият е частният конструктор на решението и не
   * е обявяване. Разликата е една буква и точно затова е записана.
   */
  private static final Pattern REFUSE_CALL = Pattern.compile("refuse\\(([^)]*)\\)");

  /** {@code assume("contractDaysPerWeek")}. */
  private static final Pattern ASSUME_CALL = Pattern.compile("assume\\(([^)]*)\\)");

  private static final Pattern QUOTED = Pattern.compile("\"(\\w+)\"");

  // ═══ ЧЕТЕНЕТО ═════════════════════════════════════════════════════════════

  /**
   * Сорсът, без коментарите и без javadoc-а.
   *
   * <p>⚠️ Коментарите се махат нарочно: този файл ОБЯСНЯВА обявените липси в проза и всяко име на
   * поле се среща и в текста. Без изчистването изречението „nightHours няма източник“ би минало за
   * попълване на полето и тестът щеше да е зелен за всяко поле, за което просто пише нещо.
   *
   * <p>Липсващият файл ПАДА, не се пропуска.
   */
  private static List<String> codeLinesOf(Path source) throws IOException {
    assertTrue(
        Files.isRegularFile(source),
        "не намирам сорса: "
            + source.toAbsolutePath()
            + "\n  ⚠️ Тестът ПАДА, вместо да се пропусне: твърдението живее в кода, а пропуснатата"
            + " проверка изглежда точно като минала. Пътят е спрямо директорията на модула.");
    List<String> out = new ArrayList<>();
    boolean insideBlock = false;
    for (String raw : Files.readAllLines(source, StandardCharsets.UTF_8)) {
      String line = raw.trim();
      if (insideBlock) {
        insideBlock = !line.contains("*/");
        continue;
      }
      if (line.startsWith("/*")) {
        insideBlock = !line.contains("*/");
        continue;
      }
      if (line.startsWith("//") || line.startsWith("*")) {
        continue;
      }
      out.add(raw);
    }
    return out;
  }

  private static String codeOf(Path source) throws IOException {
    return String.join("\n", codeLinesOf(source));
  }

  /** Имената, обявени за липсващи в кода — от {@code absent(...)} и {@code declareAbsent(...)}. */
  private static Set<String> declaredAbsentInCode(String code, Pattern call) {
    Set<String> names = new TreeSet<>();
    Matcher m = call.matcher(code);
    while (m.find()) {
      Matcher q = QUOTED.matcher(m.group(1));
      while (q.find()) {
        names.add(q.group(1));
      }
    }
    return names;
  }

  /** Полетата на контракта, през reflection — 42 на върхово ниво. */
  private static List<String> contractFields() {
    return Arrays.stream(BgPayrollInput.class.getDeclaredFields())
        .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
        .map(Field::getName)
        .collect(Collectors.toList());
  }

  private static String setterOf(String field) {
    return ADDER.containsKey(field) ? ADDER.get(field) : field;
  }

  // ═══ КОТВАТА ══════════════════════════════════════════════════════════════

  /**
   * ⚠️ КОТВАТА: сорсът наистина е прочетен и парсерът наистина вижда и двата вида твърдение.
   *
   * <p>Без нея счупен regex или преформатиран файл биха дали нула намерени обявявания и нула
   * намерени попълвания — а тогава всяко твърдение отдолу минава зелено, без да е прочело нищо.
   * Образецът е {@code assertParserSeesEveryField} в {@code BgPayrollInputContractTest}.
   */
  private static void assertTheParserSeesBothKinds(String code, Set<String> absent) {
    assertTrue(
        code.contains("BgPayrollInput.builder()"),
        "котвата: не намирам дори влизането в строителя — парсерът чете нещо друго, не сглобителя");
    assertTrue(
        code.contains(".periodFrom("),
        "котвата: не намирам попълнено поле — regex-ът за попълване е счупен. periodFrom е избран,"
            + " защото източникът му е САМИЯТ период: той не може законно да стане обявена липса");
    // ⚠️ КОТВАТА Е НИСКА НАРОЧНО. Тя пази СРЕЩУ СЧУПЕН REGEX (парсер, който не вижда нищо), а не
    // срещу смаляващ се списък: всяка обявена липса, получила източник, излиза от него. Висока
    // котва би искала липсите да НЕ намаляват — тоест би пазила точно обратното на това, за което
    // съществува. Тройката лови празен и почти празен резултат; когато останат под 3, се сваля.
    assertTrue(
        absent.size() >= 3,
        "котвата: очаквах поне няколко обявени липси, намерих " + absent.size());
    // ⚠️ И ВТОРАТА ФОРМА, но без да се назовава поле: щом кодът вика declareAbsent(...), от него
    // трябва да излезе поне едно име. Regex, който търси само „absent(“, подминава главната „A“ и
    // тук мълчи.
    if (code.contains("declareAbsent(")) {
      assertFalse(
          declaredAbsentInCode(code, DECLARE_ABSENT_CALL).isEmpty(),
          "котвата: кодът вика declareAbsent(...), а парсерът не извлича нито едно име от него —"
              + " тоест обявените през него липси излизат „премълчани“ и следващото твърдение е"
              + " червено за собствения си regex, не за кода");
    }
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Всяко от 56-те полета е ИЛИ попълнено от източник, ИЛИ обявено за липсващо")
  void everyContractFieldIsEitherSourcedOrDeclaredAbsent() throws IOException {
    String code = codeOf(BUILDER);
    Set<String> absent = declaredAbsentInCode(code, ABSENT_CALL);
    assertTheParserSeesBothKinds(code, absent);

    List<String> fields = contractFields();
    // 45 → 47: двата входа на ГРАЖДАНСКИЯ ДОГОВОР — разходната ставка по ЗДДФЛ чл. 29 ал. 1
    // (EmploymentContract.bgIncomeType.expenseRate) и признакът по КСО чл. 4 ал. 3 т. 6
    // (EmploymentContract.bgInsuredOnOtherGround). И двата имат ИЗТОЧНИК, тоест нито един не влиза
    // в DECLARED_ABSENT — полетата на договора съществуват.
    // 47 → 50: трите входа на ЧАСОВИЯ ЕНДЖИН — научната степен по НСОРЗ чл. 11, ненормираният
    // работен ден по КТ чл. 139а и броят дни с отчет за време (т. 16.7 на Д1). И трите имат
    // ИЗТОЧНИК (трудовият договор и отчетът), тоест нито един не влиза в DECLARED_ABSENT.
    // 50 → 51: дните платен отпуск, заплащани по КТ чл. 177 — ПОДМНОЖЕСТВО на paidLeaveDays, не
    // съседна кофа. Източникът му е речникът на отсъствията (BgAbsenceType.bgPaidUnderArt177) и
    // излиза от същото ЕДНО четене на заявките като другите три кофи, тоест НЕ е обявена липса.
    // 51 → 53: ПОДМНОЖЕСТВОТО на неплатения отпуск, което Д1 вади от т. 16.1
    // (BgAbsenceType.d1Code), и НАЗОВАНАТА ПРИЧИНА кофите да са празни. И двете излизат от СЪЩОТО
    // едно четене на заявките като другите четири числа, тоест нито едно не е обявена липса.
    // ⚠️ Причината е поле на контракта, а не изключение: тя е ЕДИНСТВЕНОТО, което различава
    // „отказано преброяване“ от „няма нито една заявка“ — а двете искат противоположно поведение.
    // ⚠️ 54 → 55: ДНИТЕ ПО КОФА НА Т. 16 (leaveDaysByD1Code). Източникът е СЪЩИЯТ обход, който
    // смята и парите — заявките за отпуск, — тоест полето НЕ влиза в DECLARED_ABSENT. Декларация
    // обр. 1 ги чете оттук, а не ги смята наново: отделна сметка се разминава с фиша — заявка през
    // границата на периода сваля дни от заплатата и нула от т. 16.5.
    // 53 → 54: ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО (КСО чл. 10 ал. 2 — BgAbsenceType.countsAsInsuranceService
    // при празна кофа). Излиза от СЪЩОТО едно четене на заявките като другите пет числа, тоест не е
    // обявена липса. ⚠️ Съседно поле, а не подмножество: непредставеният болничен е категория 2 и
    // не е в НИТО ЕДНА от трите кофи.
    // ⚠️ 55 → 56: ПОДМНОЖЕСТВОТО НА НЕПЛАТЕНИЯ ОТПУСК ПО ЗЗО ЧЛ. 40 АЛ. 1 Т. 1 Б. „Б“
    // (unpaidLeaveDaysUnderZzo). Източникът е СЪЩИЯТ един обход на заявките — речникът на
    // отсъствията през BgAbsenceType.healthContributionRule, — тоест полето НЕ влиза в
    // DECLARED_ABSENT. Нужно е заради разцепването на КТ чл. 157: `paid_157_special` (ал. 1 т. 4,
    // 5 и 7) е в неплатената кофа, но НЕ е неплатен отпуск, и прочетена върху цялата кофа, вноската
    // би се дължала и за него.
    assertEquals(
        56,
        fields.size(),
        "контрактът вече няма 55 полета — сверете и сглобителя: ново поле трябва да получи източник"
            + " ИЛИ обявена липса, инак минава мълчаливо");

    List<String> silent = new ArrayList<>();
    List<String> both = new ArrayList<>();
    for (String field : fields) {
      String setter = setterOf(field);
      boolean declaredAbsent = absent.contains(field);
      // Попълнено значи: има извикване на setter-а, чийто аргумент НЕ е обявената липса.
      boolean sourced =
          code.contains("." + setter + "(") && !code.contains("." + setter + "(absent(");
      if (!declaredAbsent && !sourced) {
        silent.add(field + " (търсено: „." + setter + "(“ или absent(\"" + field + "\"))");
      }
      if (declaredAbsent && sourced) {
        both.add(field);
      }
    }

    if (!silent.isEmpty() || !both.isEmpty()) {
      fail(
          "ПОЛЕ НА КОНТРАКТА, КОЕТО СГЛОБИТЕЛЯТ НЕ Е ОБЯВИЛ.\n"
              + "  минава мълчаливо (нито източник, нито обявена липса): "
              + silent
              + "\n  твърди и двете наведнъж: "
              + both
              + "\n\n  ⚠️ Пропуснат setter и обявена липса дават ЕДНА И СЪЩА стойност (null) и"
              + " РАЗЛИЧНО твърдение. Подразбиращата се стойност е точно това, което не може да каже"
              + " „не знам“.\n"
              + "  Поправка: или попълнете полето от източник, или го впишете в"
              + " BgPayrollInputBuilder.DECLARED_ABSENT С ПРИЧИНА и го обявете с absent(...) на"
              + " мястото на стойността. Ако полето няма setter, приемащ липсата (списък), обявете го"
              + " с declareAbsent(...).");
    }
  }

  @Test
  @DisplayName("⚠️ Списъкът с обявени липси съвпада с кода — в ДВЕТЕ посоки")
  void theDeclaredAbsenceListMatchesTheCode() throws IOException {
    String code = codeOf(BUILDER);
    Set<String> inCode = declaredAbsentInCode(code, ABSENT_CALL);
    assertTheParserSeesBothKinds(code, inCode);

    Set<String> listed = new TreeSet<>(BgPayrollInputBuilder.DECLARED_ABSENT.keySet());

    Set<String> onlyInCode = new TreeSet<>(inCode);
    onlyInCode.removeAll(listed);
    Set<String> onlyListed = new TreeSet<>(listed);
    onlyListed.removeAll(inCode);

    if (!onlyInCode.isEmpty() || !onlyListed.isEmpty()) {
      fail(
          "СПИСЪКЪТ С ОБЯВЕНИ ЛИПСИ СЕ Е РАЗМИНАЛ С КОДА.\n"
              + "  обявени в кода, но извън DECLARED_ABSENT : "
              + onlyInCode
              + "\n  в DECLARED_ABSENT, но неупотребени      : "
              + onlyListed
              + "\n\n  ⚠️ Първото пада още при изпълнение (requireDeclared) — ако го виждате тук,"
              + " значи редът не се изпълнява, тоест е мъртъв код.\n"
              + "  ⚠️ Второто е ОСТАРЯЛО ИЗКЛЮЧЕНИЕ: полето вече се пълни от източник, а списъкът"
              + " още твърди, че източник няма. Остарялото изключение е точно толкова тихо, колкото"
              + " и липсващата проверка — махнете записа.");
    }
  }

  @Test
  @DisplayName("⚠️ Всяка обявена липса сочи ДЕЙСТВИТЕЛНО поле на контракта")
  void everyDeclaredAbsenceNamesARealContractField() {
    Set<String> fields = new TreeSet<>(contractFields());
    Set<String> unknown = new TreeSet<>(BgPayrollInputBuilder.DECLARED_ABSENT.keySet());
    unknown.removeAll(fields);
    assertEquals(
        new TreeSet<String>(),
        unknown,
        "обявена липса за поле, което контрактът НЕ носи — преименувано или махнато. Обявяване за"
            + " несъществуващо поле е твърдение за нищо: то не може да падне и учи читателя, че"
            + " нещо е проверено");
  }

  @Test
  @DisplayName("⚠️ Всяка обявена липса носи ПРИЧИНА, не само име")
  void everyDeclaredAbsenceCarriesItsReason() {
    List<String> thin =
        BgPayrollInputBuilder.DECLARED_ABSENT.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().trim().length() < 20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    assertEquals(
        new ArrayList<String>(),
        thin,
        "обявена липса без причина е криене на грешка — образецът е BgDefaultedGetterTest.EXEMPT");
  }

  @Test
  @DisplayName("⚠️ Обявяване на липса за поле извън списъка ПАДА, а не минава тихо")
  void declaringAnUnlistedFieldAbsentThrows() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> BgPayrollInputBuilder.absent("nyamaTakovaPole"));
    assertTrue(e.getMessage().contains("nyamaTakovaPole"), e.getMessage());
    assertTrue(e.getMessage().contains("DECLARED_ABSENT"), e.getMessage());

    // ⚠️ ПЪРВОТО ИМЕ ТРЯБВА ДА Е ВСЕ ОЩЕ ОБЯВЕНО, за да се провери ВТОРОТО: declareAbsent пада на
    // първия непознат аргумент, тоест известно име отпред е това, което кара проверката да стигне
    // до непознатото отзад. „manualLines“ е още обявена липса; когато и то получи източник, този
    // ред пада — вярното му поведение — и тук се слага следващото. Това е напомнянето, че
    // списъкът се смалява.
    IllegalStateException list =
        assertThrows(
            IllegalStateException.class,
            () -> BgPayrollInputBuilder.declareAbsent("manualLines", "nyamaTakavSpisak"));
    assertTrue(list.getMessage().contains("nyamaTakavSpisak"), list.getMessage());
  }

  // ═══ ВТОРИЯТ СПИСЪК: КАКВО НЕ СЕ ПОДАВА НА НАБОРА ФАКТИ ═══════════════════

  @Test
  @DisplayName("⚠️ Неподаденото към BgWorkTimeFacts е обявено — в ДВЕТЕ посоки, с причина")
  void theUnsuppliedWorkTimeArgumentsAreDeclared() throws IOException {
    String code = codeOf(WORK_TIME);
    Set<String> inCode = declaredAbsentInCode(code, NOT_SUPPLIED_CALL);
    assertTrue(
        code.contains("BgWorkTimeFacts.byHours("),
        "котвата: не намирам извикването на byHours — парсерът чете нещо друго");
    assertTrue(
        inCode.contains("art40Par5Hours"),
        "котвата: не намирам нито едно обявяване — regex-ът е счупен, намерено: " + inCode);

    Set<String> listed = new TreeSet<>(BgPayrollWorkTime.NOT_SUPPLIED.keySet());
    assertEquals(
        listed, inCode, "NOT_SUPPLIED и кодът се разминават — виж двете посоки в другия тест");

    List<String> thin =
        BgPayrollWorkTime.NOT_SUPPLIED.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().trim().length() < 20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    assertEquals(new ArrayList<String>(), thin, "обявена липса без причина е криене на грешка");
  }

  @Test
  @DisplayName("⚠️ Ключовете на NOT_SUPPLIED са ДЕЙСТВИТЕЛНИ носители на BgWorkTimeFacts")
  void everyUnsuppliedArgumentIsARealWorkTimeField() throws IOException {
    String facts = codeOf(FACTS);
    List<String> unknown = new ArrayList<>();
    for (String argument : BgPayrollWorkTime.NOT_SUPPLIED.keySet()) {
      // Носачът е ПОЛЕ на BgWorkTimeFacts, не изречение: преименуването му проваля търсенето,
      // вместо да остави разминати имена в проза.
      if (!facts.contains("private final BigDecimal " + argument + ";")) {
        unknown.add(argument);
      }
    }
    assertEquals(
        new ArrayList<String>(),
        unknown,
        "NOT_SUPPLIED сочи име, което BgWorkTimeFacts вече не носи — преименувано или махнато."
            + " Тогава причината виси във въздуха: обявяваме, че не подаваме нещо, което не"
            + " съществува");
  }

  @Test
  @DisplayName("⚠️ Обявяване на неподаден аргумент извън списъка ПАДА")
  void declaringAnUnlistedArgumentThrows() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> BgPayrollWorkTime.notSupplied("art40Par5Weeks"));
    assertTrue(e.getMessage().contains("NOT_SUPPLIED"), e.getMessage());
  }

  // ═══ ТРЕТИЯТ СПИСЪК: ОТКАЗИТЕ ════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Отказите съвпадат с кода в ДВЕТЕ посоки и всеки носи причина")
  void everyRefusalIsDeclaredWithItsReason() throws IOException {
    String code = codeOf(WORK_TIME);
    Set<String> inCode = declaredAbsentInCode(code, REFUSE_CALL);
    assertTrue(
        code.contains("BgPayrollWorkTime"),
        "котвата: парсерът чете нещо друго, не решаващия класа");
    assertFalse(
        inCode.isEmpty(), "котвата: не намирам нито едно обявяване на отказ — regex-ът е счупен");

    Set<String> listed = new TreeSet<>(BgPayrollWorkTime.REFUSALS.keySet());
    assertEquals(
        listed,
        inCode,
        "СПИСЪКЪТ С ОТКАЗИ СЕ Е РАЗМИНАЛ С КОДА.\n"
            + "  ⚠️ Отказ в кода без запис в REFUSALS пада още при извикването. Запис без ред в кода"
            + " е ОСТАРЯЛ ОТКАЗ: причина, която никога няма да се покаже, е точно толкова тиха,"
            + " колкото и липсващата проверка.");

    List<String> thin =
        BgPayrollWorkTime.REFUSALS.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().trim().length() < 20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    assertEquals(
        new ArrayList<String>(),
        thin,
        "отказ без причина е по-лош от липсващ отказ: той спира изчислението и не казва защо");
  }

  @Test
  @DisplayName("⚠️ Отказ извън списъка ПАДА, а не минава с празен текст")
  void refusingWithAnUnlistedCodeThrows() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> BgPayrollWorkTime.refuse("nyamaTakuvOtkaz"));
    assertTrue(e.getMessage().contains("REFUSALS"), e.getMessage());
    assertTrue(e.getMessage().contains("nyamaTakuvOtkaz"), e.getMessage());
  }

  // ═══ ЧЕТВЪРТИЯТ СПИСЪК: ОБЯВЕНИТЕ ДОПУСКАНИЯ ═════════════════════════════

  @Test
  @DisplayName("⚠️ Допусканията съвпадат с кода в ДВЕТЕ посоки, носят причина и сочат реално поле")
  void everyAssumptionIsDeclaredWithItsReason() throws IOException {
    String code = codeOf(WORK_TIME);
    Set<String> inCode = declaredAbsentInCode(code, ASSUME_CALL);
    assertFalse(
        inCode.isEmpty(),
        "котвата: не намирам нито едно обявено допускане — regex-ът е счупен или допусканията са"
            + " изчезнали от кода, а списъкът още ги твърди");

    Set<String> listed = new TreeSet<>(BgPayrollWorkTime.ASSUMPTIONS.keySet());
    assertEquals(
        listed,
        inCode,
        "СПИСЪКЪТ С ДОПУСКАНИЯ СЕ Е РАЗМИНАЛ С КОДА.\n"
            + "  ⚠️ Допускане в кода без запис в ASSUMPTIONS пада още при извикването. Запис без ред"
            + " в кода значи, че допускането вече не се прилага — тогава го махнете, инак списъкът"
            + " твърди прочит, който никой не прави.");

    // Ключът е ПОЛЕ на контракта: преименувано поле проваля търсенето, вместо да остави разминати
    // имена в проза.
    Set<String> unknown = new TreeSet<>(listed);
    unknown.removeAll(new TreeSet<>(contractFields()));
    assertEquals(
        new TreeSet<String>(),
        unknown,
        "допускане за поле, което контрактът НЕ носи — преименувано или махнато");

    List<String> thin =
        BgPayrollWorkTime.ASSUMPTIONS.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().trim().length() < 20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    assertEquals(
        new ArrayList<String>(),
        thin,
        "допускане без причина е мълчаливо подразбиране с по-дълго име");
  }

  @Test
  @DisplayName("⚠️ Допускане извън списъка ПАДА, а не се прилага тихо")
  void assumingSomethingUnlistedThrows() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> BgPayrollWorkTime.assume("nyamaTakovoDopuskane"));
    assertTrue(e.getMessage().contains("ASSUMPTIONS"), e.getMessage());
    assertTrue(e.getMessage().contains("nyamaTakovoDopuskane"), e.getMessage());
  }

  /**
   * ⚠️ Отказът трябва да КАЗВА какво липсва, не само че липсва.
   *
   * <p>„Не мога да определя прага“ е отказ без адрес: човекът пред екрана не знае какво да попълни.
   * Затова всяка причина е задължена да назове поле, норма или документ — проверява се, че носи
   * поне едно име от модела или член от нормата.
   */
  @Test
  @DisplayName("⚠️ Всеки отказ назовава КОЕ липсва — поле от модела или член от нормата")
  void everyRefusalNamesWhatIsMissing() {
    List<String> vague = new ArrayList<>();
    for (Map.Entry<String, String> refusal : BgPayrollWorkTime.REFUSALS.entrySet()) {
      String reason = refusal.getValue();
      // Адресът е поле от модела, член от нормата, или самият вход, който липсва. Списъкът е
      // нарочно тесен: „не мога да определя прага“ минава всяка широка проверка и не помага на
      // никого пред екрана.
      boolean namesSomething =
          reason.contains("bgHoursPerDay")
              || reason.contains("BgPayrollInput")
              || reason.contains("ал. 4")
              || reason.contains("ал. 3")
              || reason.contains("отчет");
      if (!namesSomething) {
        vague.add(refusal.getKey());
      }
    }
    assertEquals(
        new ArrayList<String>(),
        vague,
        "отказ без адрес не е поправим: назовете полето по договора, нормата или документа, който"
            + " липсва");
  }
}
