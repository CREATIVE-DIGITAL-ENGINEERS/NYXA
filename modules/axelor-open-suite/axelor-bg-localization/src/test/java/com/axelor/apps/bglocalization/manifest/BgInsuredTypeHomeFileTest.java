package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.BgInsuredTypeCategoryRule;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПАЗАЧЪТ НА СЪХРАНЕНИЯ ВИД ОСИГУРЕН — файловата половина: „вярно ли е ЗАПИСАНО“.
 *
 * <p><b>Защо изобщо съществува.</b> {@code EmploymentContract.bgInsuredTypeCode} е СЪХРАНЕНО поле,
 * а не изведено. Съхраненото поле може да се разминава с всичко останало по записа и никой да не
 * разбере: сгрешеният вид осигурен избира ДРУГ набор ставки, тоест грешна вноска, която изглежда
 * точно като вярна. Пътят В („съхрани и сверявай“) е избран пред пътя „изведи“ по ИЗМЕРЕНА причина,
 * не по вкус — виж {@link #theSignalsThatDoNotFixACodeAreNotUsedAsRules}: нито един сигнал в модела
 * не фиксира кода еднозначно. Оттам и сделката: съхраняваме, но сверяваме ТАМ, където сигнал
 * наистина фиксира код.
 *
 * <p><b>⚠️ ПРАВИЛАТА ИДВАТ ОТ ИЗМЕРВАНЕ, НЕ ОТ СПОМЕН.</b> Всяко правило долу е изведено от {@code
 * bg_insurance_contribution.csv} и {@code bg_insured_type.csv} и се ПРЕИЗВЕЖДА при всяко пускане на
 * теста. Тоест правилото не е преписано число, което остарява мълчаливо: смени ли се seed-ът, сменя
 * се и правилото, а разминаването с домейна и със селекцията става червено.
 *
 * <p><b>Двете половини.</b> Тук е файловата: чете {@code apps/init-data/}, домейните и {@code
 * views/Selects.xml} от диска и тече ВИНАГИ, без Postgres. Базовата половина — съхранените договори
 * в базата сверени срещу същите правила — е {@code BgInsuredTypeHomeDbTest} и се пропуска с обявена
 * причина, когато база няма. Слети в един тест, отговорът „няма база“ би маскирал отговора „кодът е
 * сгрешен“.
 */
class BgInsuredTypeHomeFileTest {

  private static final String TYPES = "bg_insured_type.csv";
  private static final String CONTRIB = "bg_insurance_contribution.csv";

  private static final Path SELECTS = Paths.get("src/main/resources/views/Selects.xml");
  private static final Path CONTRACT_DOMAIN =
      Paths.get("src/main/resources/domains/EmploymentContract.xml");

  private static final String SELECTION = "bg.insured.type.select";

  /** Разделът в манифеста, към който всяко червено за самоосигуряващ код препраща читателя. */
  static final String SOL_MANIFEST = "apps/init-data/manifest/sol-2026.yml";

  // ═══ ЯВНИЯТ СПИСЪК ════════════════════════════════════════════════════════

  /**
   * ⚠️ ВИДОВЕТЕ ОСИГУРЕН БЕЗ РАБОТОДАТЕЛ — кодовете, които НЕ бива да стоят на трудов договор.
   *
   * <p>Появи ли се някой от тях на {@code EmploymentContract.bgInsuredTypeCode}, това НЕ е валиден
   * договор: това е самоосигуряващо се лице, вкарано през единствената врата, която системата днес
   * има. Самоосигуряващият се няма работодател — има РЕГИСТРАЦИЯ, декларация за вида осигуряване и
   * собствен сглобител, какъвто модулът още няма. Записът е в {@value #SOL_MANIFEST} и всяко
   * червено оттук сочи натам, за да намери следващият читател ЗАЩО кодът е изключен, а не само ЧЕ
   * е.
   *
   * <p><b>⚠️ ПРОИЗХОДЪТ НА ВСЕКИ ЗАПИС Е РАЗЛИЧЕН И Е ЗАПИСАН.</b> Два от четирите кода се доказват
   * от ДАННИТЕ, а два — не, и разликата не се крие:
   *
   * <ul>
   *   <li><b>12 и 22</b> носят {@code bgFloorType = MIN_INCOME_SOL} в seed-а, тоест самите данни
   *       казват „долният праг е минималният осигурителен доход ЗА САМООСИГУРЯВАЩИТЕ СЕ“. Това е
   *       машинно четимо и се проверява от {@link #theListCoversEverySelfInsuredFloorInTheSeed}.
   *   <li><b>13 и 41</b> НЕ се различават по нито един флаг от 15, 86, 87, 88, 89, 91 и 96 —
   *       измерено, седемте им флага съвпадат буква по буква. Тоест seed-ът НЕ ги маркира и
   *       твърдението за тях идва от НАИМЕНОВАНИЕТО им и от нормата (чл. 4 ал. 3 т. 4 КСО за
   *       регистрираните земеделски стопани, чл. 4а1 КСО за събирачите на диворастящи гъби и
   *       плодове), а не от измерване. Записано като такова, за да не се чете като доказано.
   * </ul>
   */
  static final Map<String, String> KNOWN_SELF_INSURED = new LinkedHashMap<>();

  static {
    KNOWN_SELF_INSURED.put(
        "12",
        "самоосигуряващи се, без обхванатите от 13 и 22 — ДОКАЗАНО ОТ ДАННИТЕ:"
            + " bgFloorType = MIN_INCOME_SOL и всичките седем реда със ставки носят нула за"
            + " осигурителя");
    KNOWN_SELF_INSURED.put(
        "13",
        "регистрирани земеделски стопани и тютюнопроизводители (чл. 4 ал. 3 т. 4 КСО) — ⚠️"
            + " ИЗМЕРЕНО, а не изведено от наименованието: bgFloorType ="
            + " MIN_INCOME_SOL и седем реда със ставки, всичките с нула за осигурителя. ЗБДОО 2026"
            + " НЕ им дава отделен по-нисък минимум — измерено, думата „земеделск“ не се среща в"
            + " закона нито веднъж");
    KNOWN_SELF_INSURED.put(
        "22",
        "самоосигуряващи се за инвалидност, старост и смърт с еднократна декларация — ДОКАЗАНО ОТ"
            + " ДАННИТЕ: bgFloorType = MIN_INCOME_SOL, а редове със ставки няма нито един");
    KNOWN_SELF_INSURED.put(
        "41",
        "лица по чл. 4а1 КСО — събиране на диворастящи гъби и плодове. ⚠️"
            + " ИЗМЕРЕНО: bgFloorType = HALF_MIN_WAGE и четири реда със ставки, всичките с нула за"
            + " осигурителя. Чл. 4а1 ал. 1 дословно: „осигуряват се ИЗЦЯЛО ЗА СВОЯ СМЕТКА … върху"
            + " месечен осигурителен доход в размер на половината от минималната работна заплата“");
    KNOWN_SELF_INSURED.put(
        "96",
        "съпрузи на лицата по чл. 4 ал. 3 т. 1 и 4 КСО (чл. 4 ал. 9) — ⚠️"
            + " ИЗМЕРЕНО: bgFloorType = MIN_INCOME_SOL и четири реда със ставки, всичките с нула за"
            + " осигурителя. И нормата казва същото дословно: „могат да се осигуряват ПО СВОЕ"
            + " ЖЕЛАНИЕ И ЗА СВОЯ СМЕТКА“. ⚠️ Че данните му подава ДРУГО лице (Н-13 чл. 4 ал. 1"
            + " т. 2 — самоосигуряващият се) НЕ прави осигурителя негов: подател и осигурител са"
            + " различни въпроси");
  }

  /**
   * ⚠️ ЗАЕЛИТЕ ПРАГА НА СОЛ, БЕЗ ДА СА САМООСИГУРЯВАЩИ СЕ — с причина за всеки.
   *
   * <p>Обратната посока на {@link #KNOWN_SELF_INSURED} иска отговор на въпроса „кой друг код носи
   * машинния признак и защо не е в списъка“. Без този втори списък проверката има само два изхода —
   * „в списъка“ и „червено“ — и код 92 щеше да е вечно червен по повод, който е РЕШЕН и записан.
   */
  static final Map<String, String> SOL_FLOOR_BORROWED = new LinkedHashMap<>();

  static {
    SOL_FLOOR_BORROWED.put(
        "92",
        "морски лица (чл. 4а КСО) — ИМАТ работодател и трудов договор. Чл. 4а ал. 1 ги праща да се"
            + " осигуряват върху ИЗБРАН доход между минималния и максималния ЗА САМООСИГУРЯВАЩИТЕ"
            + " СЕ, оттам и bgFloorType = MIN_INCOME_SOL — но прагът е ЗАЕТ, не признак. Чл. 4а"
            + " ал. 7 казва изрично, че вноските ГИ УДЪРЖА И ВНАСЯ РАБОТОДАТЕЛЯТ. Записано дословно"
            + " и в manifest/seafarers-2026.yml: „морското лице не е самоосигуряващо се“");
    // ⚠️ ТРИТЕ КОДА, КОИТО ЗАЕМАТ ПРАГА, ЗАЩОТО ДАННИТЕ ИМ ПОДАВА ТРЕТО ЛИЦЕ.
    // Наредба Н-13 чл. 4 ал. 1 възлага декларацията на изпращащото ведомство, на компетентния
    // български държавен орган или на посредника — тоест „осигуряващ“ СЪЩЕСТВУВА и договорният път
    // остава верният им дом. Прагът им обаче Е минималният за самоосигуряващите се, защото самите
    // норми ги пращат точно на него.
    SOL_FLOOR_BORROWED.put(
        "86",
        "лица, работещи в международни органи и организации (НАП разд. 14). ⚠️ Базата им е ИЗБРАНА"
            + " между минималния за самоосигуряващите се и максималния — както при код 12, — но"
            + " декларацията подава компетентният български държавен орган, не лицето");
    SOL_FLOOR_BORROWED.put(
        "87",
        "лица, изпратени на работа в чужбина от български посредник (чл. 4 ал. 5 КСО, НАП"
            + " разд. 13) — нормата ги праща ВЪРХУ минималния осигурителен доход за"
            + " самоосигуряващите се лица, тоест прагът е ЗАЕТ. Декларацията подава ПОСРЕДНИКЪТ");
    SOL_FLOOR_BORROWED.put(
        "91",
        "съпрузи на дългосрочно командировани в дипломатическа служба (чл. 4 ал. 7 КСО) — същият"
            + " заем на прага и същата секция на таблицата (разд. 13) като код 87. Декларацията"
            + " подава изпращащото ведомство");
  }

  // ═══ ЧЕТЕНЕТО НА ДАННИТЕ ══════════════════════════════════════════════════

  /**
   * ДАТАТА, ЗА КОЯТО ТОЗИ ПАЗАЧ ПИТА — второто полугодие на 2026 г.
   *
   * <p>⚠️ ЗАКОВАНА, А НЕ „ДНЕС“. Тестът твърди нещо за ДНЕШНИЯ режим на всеки код; вързан за
   * системния часовник, той щеше да сменя отговора си с изтичането на един ред в seed-а и да падне
   * в ден, в който никой нищо не е пипал. 01.08.2026 е и датата, на която влиза приложение № 1А по
   * ЗБДОО чл. 9 т. 2 — тоест изборът не е произволен.
   */
  private static final String ON = "2026-08-01";

  /**
   * Един ред от {@code bg_insured_type.csv}, по име на колона — редът, В СИЛА на {@link #ON}.
   *
   * <p><b>⚠️ ТЕМПОРАЛЕН ПРОЧИТ, А НЕ „ПОСЛЕДНИЯТ СПЕЧЕЛВА“.</b> {@code out.put(code, cols)} върху
   * всеки ред работи само ако всеки код има точно по един ред. Код 13 има ДВА (2024–2025 без праг и
   * 2026 с {@code MIN_INCOME_SOL}) и такъв прочит би задържал онзи, който случайно е последен във
   * файла — тоест твърдение за режим, избрано от подредбата на CSV-то, а не от датата.
   *
   * <p>⚠️ Ред без {@code validFrom} НЕ се пропуска мълчаливо: празната начална дата се чете като
   * „от винаги“, точно както я чете и {@code BgInsurableIncomeService#insuredTypeFor} със своя
   * {@code self.validFrom <= :d}. Второ правило тук би се разминало с производственото.
   */
  private static Map<String, Map<String, String>> insuredTypes() {
    List<String> header = BgSeed.quotedHeader(TYPES);
    Map<String, Map<String, String>> out = new TreeMap<>();
    for (String[] row : BgSeed.quotedRows(TYPES)) {
      Map<String, String> cols = new LinkedHashMap<>();
      for (String name : header) {
        cols.put(name, BgSeed.col(header, row, name));
      }
      if (!inForceOn(cols)) {
        continue;
      }
      String code = cols.get("code");
      if (out.containsKey(code)) {
        fail(
            "ДВА реда на вид осигурен "
                + code
                + " са в сила на "
                + ON
                + " — темпоралният прочит на речника ще върне единия ПРОИЗВОЛНО."
                + " BgInsurableIncomeService#insuredTypeFor ползва fetchOne() и ще получи същия"
                + " произвол, тоест прагът и флаговете на кода стават въпрос на подредба."
                + " Поправка: затворете предишния ред с validTo.");
      }
      out.put(code, cols);
    }
    return out;
  }

  /** Дали редът покрива {@link #ON} — същият полуотворен интервал като в производството. */
  private static boolean inForceOn(Map<String, String> cols) {
    String from = trimmed(cols.get("validFrom"));
    String to = trimmed(cols.get("validTo"));
    if (from != null && from.compareTo(ON) > 0) {
      return false;
    }
    return to == null || to.compareTo(ON) >= 0;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }

  /** Редовете със ставки, групирани по вид осигурен. */
  private static Map<String, List<Map<String, String>>> contributionsByCode() {
    List<String> header = BgSeed.quotedHeader(CONTRIB);
    Map<String, List<Map<String, String>>> out = new TreeMap<>();
    for (String[] row : BgSeed.quotedRows(CONTRIB)) {
      Map<String, String> cols = new LinkedHashMap<>();
      for (String name : header) {
        cols.put(name, BgSeed.col(header, row, name));
      }
      out.computeIfAbsent(cols.get("bgInsuredTypeCode"), k -> new ArrayList<>()).add(cols);
    }
    return out;
  }

  private static boolean isTrue(String v) {
    return "true".equalsIgnoreCase(v == null ? null : v.trim());
  }

  private static boolean nonZero(String rate) {
    try {
      return new java.math.BigDecimal(rate.trim()).signum() != 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  // ═══ ПРАВИЛАТА, ИЗВЕДЕНИ ОТ ДАННИТЕ ═══════════════════════════════════════

  /**
   * КАТЕГОРИЯ ТРУД: код → категориите, които редовете със ставки допускат за него.
   *
   * <p>⚠️ ЕДНОПОСОЧНО ПРАВИЛО, И ТОВА Е ИЗМЕРЕНО. Обратната посока НЕ е функция: категория 1 води
   * към {02, 04, 92}, категория 2 — към {03, 04, 92}, а категория 3 — към единайсет кода. Тоест от
   * категорията НЕ следва код и правило „категория ⇒ код“ тук няма. Следва обаче другото: щом кодът
   * е 02, редовете му съществуват САМО за първа категория, и запис с код 02 и категория 3 е набор
   * ставки, който няма да се намери.
   *
   * <p>Кодове без нито един ред със ставки НЕ влизат в правилото: липсата на редове е мълчание на
   * данните, не разрешение и не забрана.
   */
  public static Map<String, Set<String>> allowedLaborCategories() {
    Map<String, Set<String>> out = new TreeMap<>();
    for (Map.Entry<String, List<Map<String, String>>> e : contributionsByCode().entrySet()) {
      Set<String> cats = new TreeSet<>();
      for (Map<String, String> row : e.getValue()) {
        cats.add(row.get("laborCategory"));
      }
      out.put(e.getKey(), cats);
    }
    return out;
  }

  /**
   * УЧИТЕЛСТВО: кодовете, чийто учителски фонд е БЕЗУСЛОВЕН — тоест кодът ИСКА учителска длъжност.
   *
   * <p>Измерено: {@code TEACHER_PF} се среща при два кода. При 04 редът носи {@code bgTeacherScope
   * = TEACHER}, тоест фондът важи САМО при учителска длъжност и кодът допуска и двете. При 08 редът
   * носи {@code ANY}, тоест фондът се дължи БЕЗУСЛОВНО — а учителски фонд без учителска длъжност е
   * противоречие.
   */
  static Set<String> codesRequiringTeacherPosition() {
    Set<String> out = new TreeSet<>();
    for (Map.Entry<String, List<Map<String, String>>> e : contributionsByCode().entrySet()) {
      boolean unconditionalTeacherFund = false;
      for (Map<String, String> row : e.getValue()) {
        if ("TEACHER_PF".equals(row.get("fundCode")) && "ANY".equals(row.get("bgTeacherScope"))) {
          unconditionalTeacherFund = true;
        }
      }
      if (unconditionalTeacherFund) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  /**
   * УЧИТЕЛСТВО, ОБРАТНАТА ПОСОКА: кодовете, които ИЗКЛЮЧВАТ учителска длъжност.
   *
   * <p>⚠️ Само кодове с ПОЛОЖИТЕЛНО ИЗБРОЕНИ фондове. Код с нула реда мълчи и не забранява нищо —
   * инак трийсетте кода без редове щяха да забранят учителството навсякъде, тоест правилото щеше да
   * мери липсата на данни вместо самите данни.
   */
  static Set<String> codesForbiddingTeacherPosition() {
    Set<String> out = new TreeSet<>();
    for (Map.Entry<String, List<Map<String, String>>> e : contributionsByCode().entrySet()) {
      boolean anyTeacherFund = false;
      for (Map<String, String> row : e.getValue()) {
        if ("TEACHER_PF".equals(row.get("fundCode"))) {
          anyTeacherFund = true;
        }
      }
      if (!anyTeacherFund) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  /** ЧЛ. 4а АЛ. 2: изборът съществува само за кодовете с ред, носещ обхват ART4A_AL2. */
  static Set<String> codesAllowingArt4aAl2Choice() {
    Set<String> out = new TreeSet<>();
    for (Map.Entry<String, List<Map<String, String>>> e : contributionsByCode().entrySet()) {
      for (Map<String, String> row : e.getValue()) {
        if ("ART4A_AL2".equals(row.get("bgSolScope"))) {
          out.add(e.getKey());
        }
      }
    }
    return out;
  }

  // ═══ ЕДИН ЗАПИС, СВЕРЕН СРЕЩУ ПРАВИЛАТА ═══════════════════════════════════

  /**
   * Съгласуваността на ЕДИН запис: кодът срещу изводимите сигнали.
   *
   * <p>Изнесено като метод, а не преписано в двата теста: базовата половина ({@code
   * BgInsuredTypeHomeDbTest}) сверява СЪЩИТЕ правила срещу редовете в базата и две копия биха се
   * разминали при първата поправка.
   *
   * @param label как се казва записът в текста на грешката (номер на ред, id на договор)
   * @return празен списък при съгласуван запис, инак по едно изречение на разминаване
   */
  static List<String> disagreements(
      String label, String code, String laborCategory, boolean teacher, boolean seafarerChoice) {

    List<String> out = new ArrayList<>();
    if (code == null || code.trim().isEmpty()) {
      return out;
    }
    String c = code.trim();

    // ── 1. Самоосигуряващ код на трудов договор ──────────────────────────────
    if (KNOWN_SELF_INSURED.containsKey(c)) {
      out.add(
          label
              + ": вид осигурен "
              + c
              + " е САМООСИГУРЯВАЩ СЕ БЕЗ РАБОТОДАТЕЛ и няма какво да търси на трудов договор ("
              + KNOWN_SELF_INSURED.get(c)
              + "). Това не е валиден договор — това е ЛИПСВАЩИЯТ СОЛ ПЪТ: регистрация на"
              + " самоосигуряващо се лице → BgContributionRequest, преизползвайки"
              + " BgContributionResolver. Записът е в "
              + SOL_MANIFEST
              + " — прочетете го, преди да махнете кода оттам.");
    }

    // ── 2. Категория труд ───────────────────────────────────────────────────
    // ⚠️ ПРАВИЛОТО НЕ ЖИВЕЕ ТУК ВЕЧЕ. То е BgInsuredTypeCategoryRule — главен код — и същият метод
    // отказва ЗАПИСА на договора. Дотук правилото се проверяваше само оттук, тоест изплуваше след
    // като договорът вече е записан; преместено, то се задава на входа, а тестът остава свидетелят
    // му. Наборът категории се подава ОТ ТУК (от seed-а на диска), а при запис — от таблицата:
    // един и същ набор дава едно и също изречение, и точно това мери
    // BgInsuredTypeCategoryDriftTest.
    String categoryDisagreement =
        BgInsuredTypeCategoryRule.disagreement(c, laborCategory, allowedLaborCategories().get(c));
    if (categoryDisagreement != null) {
      out.add(label + ": " + categoryDisagreement);
    }

    // ── 3. Учителска длъжност ───────────────────────────────────────────────
    if (codesRequiringTeacherPosition().contains(c) && !teacher) {
      out.add(
          label
              + ": вид осигурен "
              + c
              + " носи БЕЗУСЛОВЕН ред за фонд „Учители“ (bgTeacherScope = ANY), а"
              + " EmploymentContract.bgIsTeacherPosition е свален. Учителски фонд без учителска"
              + " длъжност е противоречие: или длъжността е учителска, или кодът не е "
              + c
              + ".");
    }
    if (codesForbiddingTeacherPosition().contains(c) && teacher) {
      out.add(
          label
              + ": EmploymentContract.bgIsTeacherPosition е вдигнат, а вид осигурен "
              + c
              + " изброява фондовете си и сред тях TEACHER_PF НЕ Е. Тоест учителският фонд НЯМА да"
              + " се начисли и това няма да се види никъде. Учител на втори или допълнителен"
              + " трудов договор се подава под код 04, а лице с учителски осигурителен стаж — под"
              + " 08.");
    }

    // ── 4. Изборът по чл. 4а ал. 2 ──────────────────────────────────────────
    if (seafarerChoice && !codesAllowingArt4aAl2Choice().contains(c)) {
      out.add(
          label
              + ": EmploymentContract.bgSeafarerUnemploymentChoice е вдигнат при вид осигурен "
              + c
              + ", а ред с обхват ART4A_AL2 има само "
              + codesAllowingArt4aAl2Choice()
              + ". Изборът по чл. 4а ал. 2 КСО е на МОРСКОТО ЛИЦЕ и на никого другиго — вдигнат"
              + " другаде той не начислява нищо и мълчи.");
    }

    return out;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Селекцията и seed-ът носят ЕДНИ И СЪЩИ 43 кода — в ДВЕТЕ посоки, и по име")
  void theSelectionMatchesTheSeedInBothDirections() throws IOException {
    Map<String, Map<String, String>> types = insuredTypes();
    Map<String, String> options = selectionOptions();

    assertEquals(
        43,
        types.size(),
        "seed-ът bg_insured_type.csv вече няма 43 кода — сверете и селекцията "
            + SELECTION
            + ": тя е ВТОРО място със същия списък и мълчаливо се разминава");
    assertTrue(
        options.size() >= 10,
        "котвата: намерих само "
            + options.size()
            + " опции в "
            + SELECTION
            + " — парсерът чете нещо друго, не селекцията, и всяко твърдение отдолу би минало"
            + " зелено, без да е прочело нищо");

    Set<String> onlyInSeed = new TreeSet<>(types.keySet());
    onlyInSeed.removeAll(options.keySet());
    Set<String> onlyInSelection = new TreeSet<>(options.keySet());
    onlyInSelection.removeAll(types.keySet());

    List<String> renamed = new ArrayList<>();
    for (Map.Entry<String, String> option : options.entrySet()) {
      Map<String, String> seed = types.get(option.getKey());
      if (seed == null) {
        continue;
      }
      String expected = option.getKey() + " — " + seed.get("name");
      if (!expected.equals(option.getValue())) {
        renamed.add(
            "\n    код "
                + option.getKey()
                + "\n      seed     : "
                + expected
                + "\n      селекция : "
                + option.getValue());
      }
    }

    if (!onlyInSeed.isEmpty() || !onlyInSelection.isEmpty() || !renamed.isEmpty()) {
      fail(
          "СЕЛЕКЦИЯТА "
              + SELECTION
              + " СЕ Е РАЗМИНАЛА СЪС SEED-А bg_insured_type.csv.\n"
              + "  в seed-а, но не в селекцията : "
              + onlyInSeed
              + "\n  в селекцията, но не в seed-а : "
              + onlyInSelection
              + "\n  разминато наименование       : "
              + renamed
              + "\n\n  ⚠️ Двете живеят в РАЗЛИЧНИ канала за доставка: селекцията е view XML и"
              + " пристига с вдигане на версията на модула, а seed-ът е начални данни и пристига"
              + " само през uninstall → App Install. Тоест разминаването е невидимо в работеща"
              + " база: формата предлага код, за който ред НЯМА, или пази код, който формата не"
              + " умее да покаже (тогава гридът показва гола стойност).");
    }
  }

  @Test
  @DisplayName("⚠️ KNOWN_SELF_INSURED покрива ВСЕКИ код с праг за СОЛ — инак списъкът е остарял")
  void theListCoversEverySelfInsuredFloorInTheSeed() {
    Set<String> withSolFloor = new TreeSet<>();
    for (Map.Entry<String, Map<String, String>> e : insuredTypes().entrySet()) {
      if ("MIN_INCOME_SOL".equals(e.getValue().get("bgFloorType"))) {
        withSolFloor.add(e.getKey());
      }
    }
    assertTrue(
        !withSolFloor.isEmpty(),
        "котвата: нито един код със bgFloorType = MIN_INCOME_SOL — колоната е преименувана или"
            + " парсерът чете грешния файл, а тогава твърдението отдолу минава зелено за нищо");

    Set<String> unaccounted = new TreeSet<>(withSolFloor);
    unaccounted.removeAll(KNOWN_SELF_INSURED.keySet());
    unaccounted.removeAll(SOL_FLOOR_BORROWED.keySet());

    assertEquals(
        new TreeSet<String>(),
        unaccounted,
        "КОД С ПРАГ ЗА САМООСИГУРЯВАЩИ СЕ, КОЙТО НЕ Е В НИТО ЕДИН ОТ ДВАТА СПИСЪКА: "
            + unaccounted
            + "\n  ⚠️ Това значи, че seed-ът знае за самоосигуряване, за което пазачът не знае —"
            + " тоест списъкът KNOWN_SELF_INSURED е ОСТАРЯЛ и кодът ще мине по трудов договор без"
            + " никой да се обади.\n"
            + "  Поправка: или го впишете в KNOWN_SELF_INSURED с причина (и допълнете "
            + SOL_MANIFEST
            + "), или — ако кодът ЗАЕМА прага, без да е самоосигуряващ, както код 92 — в"
            + " SOL_FLOOR_BORROWED с довода защо има работодател.");
  }

  @Test
  @DisplayName("⚠️ Всеки код в KNOWN_SELF_INSURED наистина НЯМА работодател — обратната посока")
  void everyListedCodeReallyHasNoEmployer() {
    Map<String, Map<String, String>> types = insuredTypes();
    Map<String, List<Map<String, String>>> contributions = contributionsByCode();
    List<String> wrong = new ArrayList<>();

    for (String code : KNOWN_SELF_INSURED.keySet()) {
      Map<String, String> seed = types.get(code);
      if (seed == null) {
        wrong.add(
            "\n  код "
                + code
                + ": НЯМА го в bg_insured_type.csv — номенклатурата го е премахнала"
                + " (както ДВ бр. 15/2024 премахна 07, 21, 23 и 93) или кодът е сбъркан");
        continue;
      }

      List<String> employerRows = new ArrayList<>();
      for (Map<String, String> row : contributions.getOrDefault(code, Collections.emptyList())) {
        if (nonZero(row.get("employerRate"))) {
          employerRows.add(row.get("importId") + " (" + row.get("fundCode") + ")");
        }
      }
      if (!employerRows.isEmpty()) {
        wrong.add(
            "\n  код "
                + code
                + ": ВЕЧЕ ИМА работодателска вноска на редове "
                + employerRows
                + " — тоест лицето има осигурител и кодът НЕ Е самоосигуряващ. Махнете го от"
                + " списъка.");
      }
      if (isTrue(seed.get("bgAppliesMod"))) {
        wrong.add(
            "\n  код "
                + code
                + ": носи bgAppliesMod = true, а МОД е прагът на НАЕТИЯ (Приложение"
                + " № 1 по НКПД и икономическа дейност). Самоосигуряващият се стъпва на"
                + " MIN_INCOME_SOL, не на МОД.");
      }
      if (!isTrue(seed.get("bgNoNetPay"))) {
        wrong.add(
            "\n  код "
                + code
                + ": носи bgNoNetPay = false, тоест Д1 т. 34 иска НЕТНО"
                + " ВЪЗНАГРАЖДЕНИЕ — а възнаграждение има само там, където има работодател, който"
                + " го плаща.");
      }
    }

    if (!wrong.isEmpty()) {
      fail(
          "ЗАПИС В KNOWN_SELF_INSURED, ОПРОВЕРГАН ОТ ДАННИТЕ:"
              + String.join("", wrong)
              + "\n\n  ⚠️ Списъкът изключва кодове от трудовия договор. Сгрешен запис или пропуска"
              + " самоосигуряващ се през вратата на договора, или спира валиден договор — и двете"
              + " тихо. Сверете с "
              + SOL_MANIFEST
              + ".");
    }
  }

  @Test
  @DisplayName("⚠️ Сигналите, които НЕ фиксират код еднозначно, НЕ са станали правила")
  void theSignalsThatDoNotFixACodeAreNotUsedAsRules() {
    // Категорията труд: обратната посока НЕ е функция и точно затова „категория ⇒ код“ правило
    // няма.
    Map<String, Set<String>> byCategory = new TreeMap<>();
    for (Map.Entry<String, Set<String>> e : allowedLaborCategories().entrySet()) {
      for (String cat : e.getValue()) {
        byCategory.computeIfAbsent(cat, k -> new TreeSet<>()).add(e.getKey());
      }
    }
    for (Map.Entry<String, Set<String>> e : byCategory.entrySet()) {
      assertTrue(
          e.getValue().size() > 1,
          "категория труд "
              + e.getKey()
              + " води към ЕДИНСТВЕН код "
              + e.getValue()
              + " — измерването се е сменило. Ако вече фиксира код еднозначно, това е НОВО правило"
              + " за пазача и трябва да влезе в disagreements(...), вместо да остане"
              + " неизползвано.");
    }

    // Учителството: вдигнат флаг допуска ПОВЕЧЕ от един код, тоест „учител ⇒ код“ правило няма.
    Set<String> teacherCapable = new TreeSet<>();
    for (Map.Entry<String, List<Map<String, String>>> e : contributionsByCode().entrySet()) {
      for (Map<String, String> row : e.getValue()) {
        if ("TEACHER_PF".equals(row.get("fundCode"))) {
          teacherCapable.add(e.getKey());
        }
      }
    }
    assertTrue(
        teacherCapable.size() > 1,
        "учителският фонд вече се среща при ЕДИН код "
            + teacherCapable
            + " — тогава вдигнатата учителска длъжност ФИКСИРА кода и това е ново правило за"
            + " пазача, а не наблюдение за доклада");

    // Видът договор по КТ: НИТО ЕДНА колона в данните не го свързва с вид осигурен.
    List<String> contribHeader = BgSeed.quotedHeader(CONTRIB);
    List<String> typeHeader = BgSeed.quotedHeader(TYPES);
    List<String> allColumns = new ArrayList<>(contribHeader);
    allColumns.addAll(typeHeader);
    for (String column : allColumns) {
      assertTrue(
          !column.toLowerCase(java.util.Locale.ROOT).contains("contracttype"),
          "seed-ът вече носи колона „"
              + column
              + "“ — щом видът договор по КТ е влязъл в данните, връзката „вид договор ⇒ вид"
              + " осигурен“ вече е измерима и трябва да стане правило на пазача, вместо да остане"
              + " извън него");
    }
  }

  @Test
  @DisplayName("⚠️ Домейнът наистина носи двете нови полета със селекцията, която пазачът чете")
  void theContractDomainCarriesBothFields() throws IOException {
    String domain = read(CONTRACT_DOMAIN);
    assertTrue(
        domain.contains("name=\"bgInsuredTypeCode\""),
        "EmploymentContract.xml няма поле bgInsuredTypeCode — домът на вида осигурен е изчезнал, а"
            + " пазачът пази празно място");
    assertTrue(
        domain.contains("selection=\"" + SELECTION + "\""),
        "bgInsuredTypeCode вече не сочи селекцията "
            + SELECTION
            + " — тоест формата ще пази стойност, която този тест сверява срещу друг списък");
    assertTrue(
        domain.contains("name=\"bgSeafarerUnemploymentChoice\""),
        "EmploymentContract.xml няма поле bgSeafarerUnemploymentChoice — изборът по чл. 4а ал. 2"
            + " КСО остава без дом и solScope се връща към обявена липса");
    assertTrue(
        domain.contains("<boolean name=\"bgSeafarerUnemploymentChoice\"")
            && domain.contains("default=\"false\""),
        "изборът по чл. 4а ал. 2 вече не е булев с подразбиране false — „неупражнената опция е"
            + " неупражнена“ е ПОСОКАТА на подразбирането, не украса: обратната начислява 1 на сто"
            + " на всяко морско лице");
  }

  @Test
  @DisplayName("⚠️ Разделът за СОЛ пътя съществува и червеното сочи именно него")
  void theSolManifestSectionExists() throws IOException {
    Path manifest = Paths.get("src/main/resources/" + SOL_MANIFEST);
    assertTrue(
        Files.isRegularFile(manifest),
        "липсва "
            + manifest.toAbsolutePath()
            + " — пазачът сочи читателя към раздел, който не съществува. Съобщение, което сочи"
            + " никъде, е по-лошо от съобщение без препратка: то изглежда като обяснение.");
    String text = read(manifest);
    for (String code : KNOWN_SELF_INSURED.keySet()) {
      assertTrue(
          text.contains("\"" + code + "\"") || text.contains("· " + code + " ·"),
          "манифестът "
              + SOL_MANIFEST
              + " не назовава код "
              + code
              + ", а пазачът го изключва от трудовия договор и праща читателя точно натам. Празен"
              + " раздел е приемлив; раздел, който не знае за кода, не е.");
    }
  }

  // ═══ ПАЗАЧЪТ, ПРОВЕРЕН ЧРЕЗ НАРОЧНО СЧУПВАНЕ ══════════════════════════════
  //
  // ⚠️ Всеки от случаите отдолу СЕ КОМПИЛИРА и СЕ ВРЪЩА: счупването е СТОЙНОСТ, подадена на
  // disagreements(...), а не закоментиран ред в главния код. Пазач, който никой не е виждал червен,
  // е пазач, за когото не се знае дали пази.

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · учител с код, който сигналът не позволява")
  void aTeacherOnACodeWithoutTheTeacherFundIsRed() {
    List<String> found = disagreements("ред 1", "01", "3", true, false);
    assertEquals(1, found.size(), "очаквах точно едно разминаване, получих: " + found);
    assertTrue(
        found.get(0).contains("TEACHER_PF")
            && found.get(0).contains("bgIsTeacherPosition")
            && found.get(0).contains("04")
            && found.get(0).contains("08"),
        "червеното трябва да назове И сигнала, И къде е верният код: " + found.get(0));

    // И обратната посока: код 08 иска учителска длъжност, свалената е противоречие.
    List<String> reverse = disagreements("ред 2", "08", "3", false, false);
    assertEquals(1, reverse.size(), "очаквах точно едно разминаване, получих: " + reverse);
    assertTrue(reverse.get(0).contains("БЕЗУСЛОВЕН"), "червеното не казва защо: " + reverse.get(0));

    // ⚠️ КОНТРОЛАТА: същите сигнали, но съгласувани — зелено. Без нея тестът щеше да мине и с
    // пазач, който връща разминаване за ВСЕКИ вход.
    assertEquals(
        Collections.emptyList(),
        disagreements("ред 3", "08", "3", true, false),
        "учител с код 08 и категория 3 е СЪГЛАСУВАН запис — пазачът не бива да го спира");
    assertEquals(
        Collections.emptyList(),
        disagreements("ред 4", "04", "1", true, false),
        "учител на допълнителен договор (04) при първа категория е съгласуван — код 04 носи и"
            + " условния учителски ред, и трите категории");
  }

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · самоосигуряващ код на трудов договор — с текст, сочещ СОЛ пътя")
  void aSelfInsuredCodeOnAContractIsRed() {
    for (String code : KNOWN_SELF_INSURED.keySet()) {
      List<String> found = disagreements("договор #77", code, null, false, false);
      assertTrue(
          !found.isEmpty(), "код " + code + " е в KNOWN_SELF_INSURED, а пазачът мълчи за него");
      String first = found.get(0);
      assertTrue(first.contains("САМООСИГУРЯВАЩ"), first);
      assertTrue(
          first.contains("СОЛ ПЪТ") && first.contains(SOL_MANIFEST),
          "червеното трябва да сочи КЪДЕ е записано защо кодът е изключен, инак следващият читател"
              + " научава само ЧЕ е: "
              + first);
      assertTrue(
          first.contains("BgContributionResolver"),
          "червеното трябва да каже какво липсва, за да стане валидно: " + first);
    }

    // ⚠️ КОНТРОЛАТА: код 92 носи СЪЩИЯ праг MIN_INCOME_SOL и НЕ бива да се спира — той има
    // работодател (чл. 4а ал. 7 КСО). Без нея правилото „праг за СОЛ ⇒ забранен“ би минало зелено.
    assertEquals(
        Collections.emptyList(),
        disagreements("договор #78", "92", "1", false, true),
        "морското лице (92) има работодател — прагът за СОЛ му е ЗАЕТ, не признак");
  }

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · категория труд, за която кодът няма нито един ред със ставки")
  void aLaborCategoryWithoutRowsForTheCodeIsRed() {
    List<String> found = disagreements("ред 9", "02", "3", false, false);
    assertEquals(1, found.size(), "очаквах точно едно разминаване, получих: " + found);
    assertTrue(found.get(0).contains("ПРАЗЕН"), found.get(0));

    assertEquals(
        Collections.emptyList(),
        disagreements("ред 10", "02", "1", false, false),
        "код 02 е първа категория труд по самото си наименование — съгласуван запис");
  }

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · изборът по чл. 4а ал. 2, вдигнат при код, различен от морско лице")
  void theArt4aAl2ChoiceOutsideTheSeafarerCodeIsRed() {
    List<String> found = disagreements("ред 11", "01", "3", false, true);
    assertEquals(1, found.size(), "очаквах точно едно разминаване, получих: " + found);
    assertTrue(found.get(0).contains("ART4A_AL2") && found.get(0).contains("92"), found.get(0));

    assertEquals(
        Collections.emptyList(),
        disagreements("ред 12", "92", "3", false, true),
        "морско лице с упражнен избор е съгласуван запис — точно за него съществува стойността");
  }

  @Test
  @DisplayName("Празен вид осигурен НЕ е разминаване — той е ОТКАЗ, и той е на сглобителя")
  void anEmptyCodeIsNotADisagreement() {
    assertEquals(Collections.emptyList(), disagreements("ред 13", null, "3", true, true));
    assertEquals(Collections.emptyList(), disagreements("ред 14", "   ", "3", true, true));
  }

  // ═══ ЧЕТЕНЕТО НА СЕЛЕКЦИЯТА ═══════════════════════════════════════════════

  private static final Pattern OPTION =
      Pattern.compile("<option\\s+value=\"([^\"]*)\"\\s*>(.*?)</option>", Pattern.DOTALL);

  /**
   * Опциите на {@value #SELECTION} — стойност → заглавие.
   *
   * <p>Чете се самият файл, а не meta: селекцията стига до {@code meta_select} само след boot с
   * вдигната версия на модула, тоест базата отговаря на друг въпрос. Тук въпросът е „вярно ли е
   * записано“.
   */
  private static Map<String, String> selectionOptions() throws IOException {
    String xml = read(SELECTS);
    int start = xml.indexOf("<selection name=\"" + SELECTION + "\">");
    assertTrue(start >= 0, "не намирам селекцията " + SELECTION + " в " + SELECTS.toAbsolutePath());
    int end = xml.indexOf("</selection>", start);
    assertTrue(end > start, "селекцията " + SELECTION + " не се затваря");

    Map<String, String> out = new LinkedHashMap<>();
    Set<String> duplicates = new LinkedHashSet<>();
    Matcher m = OPTION.matcher(xml.substring(start, end));
    while (m.find()) {
      String value = m.group(1);
      String title = unescape(m.group(2).trim());
      if (out.put(value, title) != null) {
        duplicates.add(value);
      }
    }
    assertEquals(
        new LinkedHashSet<String>(),
        duplicates,
        "повторена стойност в " + SELECTION + " — вторият ред печели тихо и първият изчезва");
    return out;
  }

  private static String unescape(String s) {
    return s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&amp;", "&");
  }

  private static String read(Path path) throws IOException {
    assertTrue(Files.isRegularFile(path), "не намирам " + path.toAbsolutePath());
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }
}
