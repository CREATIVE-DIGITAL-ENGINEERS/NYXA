package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * УКАЗАНИЯ Т. 16.1 – 16.А → {@code BgAbsenceType.d1Code}: всеки вид отсъствие сочи СВОЯТА кофа.
 *
 * <p>{@code BgD1DaySource} чете точно колоната {@code d1Code}, тоест грешна стойност в нея не е
 * неточност в документацията, а дни на грешна точка от декларация.
 *
 * <p><b>⚠️ ЧЕТЕ СЕ СЪС СВОЕ ЦЕПЕНЕ, А НЕ ПРЕЗ {@code BgSeed.rows}.</b> {@code bg_absence_type.csv}
 * е с разделител ЗАПЕТАЯ (виж {@code bg-localization-config.xml}: {@code separator=","}), докато
 * {@code BgSeed} цепи по {@code ;}. Прекаран през него, целият ред би станал ЕДНА колона и всяка
 * проверка тук щеше да мине зелена върху нищо. ⚠️ Проверката „en/ и fr/ са байт по байт еднакви“
 * обаче Е нужна и затова е ПРЕПИСАНА тук изрично — тя е ограничение на модула, не на цепенето.
 *
 * <p><b>Източникът е Приложение № 1 към чл. 2 ал. 1, указания т. 16.1 – 16.А</b>, преписани по
 * подточки. Препис е правилен точно там, където сравнението на два независими източника Е целта.
 */
class BgD1DayBucketSeedTest {

  private static final String FILE = "bg_absence_type.csv";

  /**
   * „код на отсъствие → кофа“, изведено от указанията:
   *
   * <ul>
   *   <li><b>16.1</b> „отработените дни в осигуряване, дните в ПЛАТЕН отпуск, дните, за които е
   *       упражнявана дейност … и всички дни с осигурителни вноски на основание чл. 9 ал. 3 КСО“;
   *   <li><b>16.2</b> „ВНР поради общо заболяване, гледане на болен член от семейството, бременност
   *       и раждане, трудова злополука, професионална болест, санаторно-курортно лечение,
   *       карантина, осиновяване на дете до 5-годишна възраст … С ПРАВО НА ОБЕЗЩЕТЕНИЕ. Не се
   *       попълват дните, посочени в т. 16.А“;
   *   <li><b>16.3</b> „съгласно чл. 164, 164а, 164в, 167, 167а от Кодекса на труда … и дните БЕЗ
   *       право на обезщетение по КСО“;
   *   <li><b>16.4</b> „работните дни в НЕПЛАТЕН отпуск за временна неработоспособност, и други дни
   *       в осигуряване без осигурителни вноски. Не се попълват дните, посочени в т. 16.5“;
   *   <li><b>16.5</b> „дните в неплатен отпуск, който се зачита за осигурителен стаж на основание
   *       чл. 9 ал. 2 т. 3 от КСО“;
   *   <li><b>16.А</b> „работните дни, за които работодателят изплаща възнаграждение на основание
   *       чл. 40 ал. 5 от КСО“.
   * </ul>
   *
   * <p><b>⚠️ КАТЕГОРИЯ „БОЛНИЧНИ“ СЕ РАЗЦЕПВА НА ТРИ, и това е цялата причина колоната да
   * съществува.</b> {@code sick_er} → 16.А (чл. 40 ал. 5), {@code sick_doo} → 16.2 (обезщетение от
   * ДОО), {@code sick_no_benefit_doo} → 16.4 (без право на обезщетение). Един switch по {@code
   * category} би слял трите — при това правдоподобно, защото сборът им е верният общ брой дни.
   *
   * <p><b>⚠️ И ЕДНО ОСНОВАНИЕ, КОЕТО ОТИВА В ДВЕ КОФИ.</b> Чл. 164б КТ (осиновяване до 5 г.) е в
   * 16.2 при право на обезщетение по чл. 53в КСО ({@code child_care_2_8}) и в 16.3 без такова право
   * ({@code adoption_u5_no_benefit}). Тоест разграничителят е ПРАВНОТО СЛЕДСТВИЕ, не нормата — и
   * затова двата реда са РАЗЛИЧНИ редове, а не един ред с флаг.
   */
  private static final Map<String, String> UKAZANIA = new LinkedHashMap<>();

  static {
    // т. 16.1 — платените отпуски
    UKAZANIA.put("paid_leave_coef", "16.1");
    UKAZANIA.put("paid_annual", "16.1");
    UKAZANIA.put("official_creative_161", "16.1");
    UKAZANIA.put("student_paid", "16.1");
    // ⚠️ ТРИТЕ РЕДА НА ЧЛ. 157 СА В ДВЕ РАЗЛИЧНИ КОФИ, И ТОВА Е ЦЕЛИЯТ СМИСЪЛ НА РАЗЦЕПВАНЕТО.
    // Ал. 3 дава три режима на заплащане; т. 1 (по КТД) и т. 2 („в размера по чл. 177“) се заплащат
    // от РАБОТОДАТЕЛЯ, тоест дните им са дни с осигурителни вноски → т. 16.1. Т. 3 („в останалите
    // случаи според предвиденото в специалните закони“) НЕ се заплаща от него — ГПК чл. 168 дава
    // възнаграждението на свидетеля „от внесения депозит“ — тоест осигурителен доход по този
    // договор няма и денят не може да е в 16.1. Указание т. 16.4 го поема дословно: „други дни в
    // осигуряване без осигурителни вноски“.
    UKAZANIA.put("paid_157", "16.1");
    UKAZANIA.put("paid_157_art177", "16.1");
    UKAZANIA.put("paid_157_special", "16.4");
    UKAZANIA.put("union_159", "16.1");
    // ⚠️ ЧЛ. 166 (КЪРМЕНЕ) Е В 16.1, МАКАР ДА СЕ МЕРИ В ЧАСОВЕ. Указание т. 16.1 иска „дните в
    // ПЛАТЕН отпуск“, а часовете по чл. 166 са ЧАСТ от работния ден — денят си остава ден в
    // осигуряване и се брои цял. Празна кофа тук би била двойно грешна: и декларацията щеше да
    // загуби деня, и заплатата щеше да се КАРВИРА (виж BgD1DayBuckets.outsideInsurance), тоест
    // работодателят щеше да удържи за право, което чл. 166 ал. 4 му вменява да ПЛАТИ.
    UKAZANIA.put("nursing_166", "16.1");
    // т. 16.2 — ВНР и обезщетенията от ДОО
    UKAZANIA.put("sick_doo", "16.2");
    UKAZANIA.put("work_accident_doo", "16.2");
    UKAZANIA.put("home_accident_doo", "16.2");
    UKAZANIA.put("care_sick_child_u18", "16.2");
    UKAZANIA.put("care_sick_adult_o18", "16.2");
    UKAZANIA.put("maternity_birth", "16.2");
    // ⚠️ Указание т. 16.2 изброява „ПРОФЕСИОНАЛНА БОЛЕСТ“ ДОСЛОВНО, редом с трудовата злополука —
    // тоест кофата ѝ не е извод по аналогия, а е изписана в самия текст, преписан по-горе.
    UKAZANIA.put("occupational_disease_doo", "16.2");
    UKAZANIA.put("paternity", "16.2");
    UKAZANIA.put("child_care_2_8", "16.2");
    // т. 16.3 — отглеждане на дете
    UKAZANIA.put("child_care_u2", "16.3");
    UKAZANIA.put("unpaid_child_u8_167a", "16.3");
    UKAZANIA.put("child_placement_164a", "16.3");
    UKAZANIA.put("child_care_father_164v", "16.3");
    UKAZANIA.put("child_care_father_167", "16.3");
    UKAZANIA.put("adoption_u5_no_benefit", "16.3");
    // т. 16.4 — без вноски, зачетени за стаж
    UKAZANIA.put("sick_no_benefit_er", "16.4");
    UKAZANIA.put("sick_no_benefit_doo", "16.4");
    // т. 16.5 — неплатен, зачетен за стаж
    UKAZANIA.put("unpaid_own_le30", "16.5");
    UKAZANIA.put("unpaid_prod_le30", "16.5");
    // т. 16.А — чл. 40 ал. 5 КСО
    UKAZANIA.put("sick_er", BgD1DayBuckets.B_16_A);
    UKAZANIA.put("work_accident_er", BgD1DayBuckets.B_16_A);
    UKAZANIA.put("home_accident_er", BgD1DayBuckets.B_16_A);
    // Чл. 40 ал. 5 КСО не гледа РИСКА: първите дни са на осигурителя и при професионалната болест.
    UKAZANIA.put("occupational_disease_er", BgD1DayBuckets.B_16_A);
  }

  /** Четирите нови реда, добавени в този етап — по искане, сверени срещу дословния текст. */
  private static final Map<String, String> NEW_ROWS = new LinkedHashMap<>();

  static {
    NEW_ROWS.put("child_placement_164a", "чл.164а КТ / чл.26 ЗЗДет");
    NEW_ROWS.put("child_care_father_164v", "чл.164в КТ");
    NEW_ROWS.put("child_care_father_167", "чл.167 КТ");
    NEW_ROWS.put("adoption_u5_no_benefit", "чл.164б КТ / чл.53в КСО");
  }

  // ═══ ЧЕТЕНЕТО ═════════════════════════════════════════════════════════════

  private static Path path(String lang) {
    return Paths.get("src/main/resources/apps/init-data/" + lang + "/" + FILE);
  }

  /** Редовете като „колона → стойност“, цепени по ЗАПЕТАЯ. */
  private static List<Map<String, String>> rows() {
    Path en = path("en");
    Path fr = path("fr");
    try {
      // ⚠️ ОГРАНИЧЕНИЕ НА МОДУЛА, преписано изрично: всяка промяна влиза И в двете, байт по байт.
      if (!Arrays.equals(Files.readAllBytes(en), Files.readAllBytes(fr))) {
        fail(
            "en/ и fr/ се разминават за "
                + FILE
                + " — BgSeed.rows() го пази за своите файлове, но този се чете със свое цепене и"
                + " затова проверката е преписана тук");
      }
      List<String> lines = Files.readAllLines(en, StandardCharsets.UTF_8);
      List<String> header = Arrays.asList(lines.get(0).split(",", -1));
      List<Map<String, String>> out = new ArrayList<>();
      for (int i = 1; i < lines.size(); i++) {
        if (lines.get(i).isEmpty()) {
          continue;
        }
        String[] cells = lines.get(i).split(",", -1);
        assertEquals(
            header.size(),
            cells.length,
            "ред "
                + (i + 1)
                + " има "
                + cells.length
                + " колони при "
                + header.size()
                + " в заглавието — най-вероятно стойност носи ЗАПЕТАЯ, а разделителят е точно тя");
        Map<String, String> row = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
          row.put(header.get(c), cells[c]);
        }
        out.add(row);
      }
      return out;
    } catch (IOException e) {
      fail("не се чете " + FILE + ": " + e.getMessage());
      return null;
    }
  }

  // ═══ СВИДЕТЕЛИТЕ ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("указания т. 16.1–16.А · всеки вид отсъствие сочи кофата, която нормата му дава")
  void everyAbsenceTypePointsAtItsOwnBucket() {
    Map<String, String> seed = new TreeMap<>();
    for (Map<String, String> row : rows()) {
      seed.put(row.get("code"), row.get("d1Code"));
    }

    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, String> e : UKAZANIA.entrySet()) {
      String actual = seed.get(e.getKey());
      if (actual == null) {
        wrong.add(e.getKey() + ": липсва ред в речника");
      } else if (!e.getValue().equals(actual)) {
        wrong.add(e.getKey() + ": кофа „" + actual + "“, а указанието дава „" + e.getValue() + "“");
      }
    }
    if (!wrong.isEmpty()) {
      fail("кофи, които не се връзват с указанията:\n  " + String.join("\n  ", wrong));
    }
  }

  @Test
  @DisplayName("⚠️ НИТО ЕДИН ред от речника не остава непроверен — указание ИЛИ обявена липса")
  void everyRowIsEitherInTheUkazaniaOrDeclaredUnbucketed() {
    // ⚠️ ТОЗИ СВИДЕТЕЛ ЗАТВАРЯ ДУПКА. Свидетелят по-горе обхожда UKAZANIA и пита речника —
    // тоест НОВ ред, който никой не е вписал в картата, минава зелен и необявен, със своя кофа и
    // без никой да е питал дали тя е вярна. Обходът трябва да е и в ДВЕТЕ посоки.
    TreeSet<String> unchecked = new TreeSet<>();
    for (Map<String, String> row : rows()) {
      String code = row.get("code");
      if (!UKAZANIA.containsKey(code) && !BgD1DayBuckets.UNBUCKETED.contains(code)) {
        unchecked.add(code + " → кофа „" + row.get("d1Code") + "“");
      }
    }
    assertEquals(
        new TreeSet<String>(),
        unchecked,
        "вид отсъствие, чиято кофа не е сверена срещу указанията и не е обявен за безкофен."
            + " Впиши го в UKAZANIA с точката, която указанието му дава, или в"
            + " BgD1DayBuckets.UNBUCKETED с довода защо не е в нито една точка. Непроверени: "
            + unchecked);
  }

  @Test
  @DisplayName("⚠️ КТ чл. 157 · ТРИ РЕЖИМА, ТРИ РЕДА — и трите с режима, който ал. 3 им дава")
  void theArticle157SplitsIntoItsThreeStatutoryRegimes() {
    Map<String, Map<String, String>> byCode = new TreeMap<>();
    for (Map<String, String> row : rows()) {
      byCode.put(row.get("code"), row);
    }

    // ═══ ЗАВАРЕНИЯТ РЕД · НИТО ЕДИН ПОВЕДЕНЧЕСКИ ПРИЗНАК НЕ МЪРДА ═══
    // ⚠️ ТОВА Е ПОЛОВИНАТА ОТ МИГРАЦИЯТА. paid_157 запазва КОДА и всичките си флагове,
    // тоест всяка заварена заявка за отпуск продължава да сочи ред с днешното третиране и нито един
    // заварен фиш не мърда — без да разчитаме на замразяване. Мени се само етикетът: „Платен отпуск
    // чл.157“ не позволява на оператора да ИЗБЕРЕ режим, а точно изборът решава сумата.
    Map<String, String> ktd = byCode.get("paid_157");
    assertTrue(
        ktd != null, "речникът няма ред paid_157 — кодът НЕ се преименува, той е миграцията");
    assertEquals("1", ktd.get("category"), "институтът е отпуск по КТ");
    assertEquals("true", ktd.get("isPaid"), "ал. 3 казва „се изплаща възнаграждение“");
    assertEquals("employer", ktd.get("payer"), "по т. 1 плаща работодателят, по уговореното");
    assertEquals("100", ktd.get("compensationPct"));
    assertEquals(
        "false",
        ktd.get("bgPaidUnderArt177"),
        "⚠️ ал. 3 т. 1 праща размера в КТД или споразумение и НЕ препраща към чл. 177 — за разлика"
            + " от т. 2, която го прави дословно. Вдигнат по подразбиране, флагът щеше да наложи"
            + " среднодневното от предходния месец там, където законът пита договора. Инсталация с"
            + " КТД „в размера по чл. 177“ го отмята сама — признакът е ДАННИ.");
    assertEquals("true", ktd.get("countsAsInsuranceService"));
    assertEquals("16.1", ktd.get("d1Code"));
    assertEquals("standard", ktd.get("healthContributionRule"));
    assertTrue(
        ktd.get("legalBasis").contains("ал.3 т.1"),
        "основанието вече сочи ТОЧКАТА, не просто „чл.157 КТ“: " + ktd.get("legalBasis"));

    // ═══ РЕЖИМ 2 · „В РАЗМЕРА ПО ЧЛ. 177“ ═══
    // ЦИТАТ, ал. 3 т. 2: „по т. 5а и 6 — от работодателя В РАЗМЕРА ПО ЧЛ. 177“. И ал. 2 дословно:
    // „се изплаща възнаграждение от работодателя в размера по чл. 177“ — тоест прегледите при
    // бременност и ин-витро са в СЪЩИЯ режим, макар да не са точка от ал. 1.
    Map<String, String> art177 = byCode.get("paid_157_art177");
    assertTrue(art177 != null, "речникът няма ред paid_157_art177 — ал. 3 т. 2 е право без вход");
    assertEquals("1", art177.get("category"));
    assertEquals("true", art177.get("isPaid"));
    assertEquals("employer", art177.get("payer"), "„ОТ РАБОТОДАТЕЛЯ в размера по чл. 177“");
    assertEquals(
        "true",
        art177.get("bgPaidUnderArt177"),
        "⚠️ ЕДИНСТВЕНИЯТ ФЛАГ, КОЙТО РАЗЦЕПВАНЕТО ВДИГА. Оттук нататък дните се карвират от перата"
            + " с постоянен характер и се връщат по среднодневното от ПРЕДХОДНИЯ месец (перо 165,"
            + " #205). Сведен до заплатата на текущия месец, отпускът по т. 5а/6 се плащаше по"
            + " ставка без норма зад себе си.");
    assertEquals(
        "true",
        art177.get("countsAsInsuranceService"),
        "възнаграждение има → вноски има → стаж има");
    assertEquals("16.1", art177.get("d1Code"), "дните в платен отпуск са в т. 16.1");
    assertTrue(art177.get("legalBasis").contains("ал.3 т.2"), art177.get("legalBasis"));
    assertTrue(
        !BgD1DayBuckets.outsideInsurance(art177.get("d1Code"), Boolean.TRUE)
            && !BgD1DayBuckets.insuranceFlagsConflict(art177.get("d1Code"), Boolean.TRUE),
        "кофа 16.1 + „зачита се“ е съгласуваната двойка — нито карв, нито изваждане от т. 16.1");

    // ═══ РЕЖИМ 3 · ПО СПЕЦИАЛНИТЕ ЗАКОНИ — РАБОТОДАТЕЛЯТ НЕ ДЪЛЖИ РАЗМЕРА ═══
    // ЦИТАТ, ал. 3 т. 3: „в останалите случаи — според предвиденото в СПЕЦИАЛНИТЕ ЗАКОНИ“. Остават
    // т. 4 (призован в съд като страна, свидетел или вещо лице), т. 5 (представителен държавен
    // орган
    // или съдебен заседател) и т. 7 (доброволни формирования при бедствия). За т. 4 специалният
    // закон е сверен ДОСЛОВНО: ГПК чл. 168 — „Свидетелят има право на възнаграждение и на разноски
    // за явяването в съда … Възнаграждението и разноските се изплащат от ВНЕСЕНИЯ ДЕПОЗИТ“, а
    // размерът е на съда (чл. 75). ЗСВ и ЗЗБ не са сверени в тази среда и за тях стои само
    // делегацията на ал. 3 т. 3 — тя обаче е достатъчна за ЕДИНСТВЕНИЯ признак, който фишът чете:
    // работодателят не е платецът.
    Map<String, String> special = byCode.get("paid_157_special");
    assertTrue(special != null, "речникът няма ред paid_157_special — ал. 3 т. 3 е право без вход");
    assertEquals(
        "1",
        special.get("category"),
        "⚠️ И ТОВА НЕ Е „НЕПЛАТЕН“ (категория 7). Ал. 3 казва, че възнаграждение СЕ ИЗПЛАЩА — само"
            + " че от друг платец. Заведен като неплатен отпуск, той щеше да попадне в хипотезата на"
            + " ЗЗО чл. 40 ал. 1 т. 1 б. „б“ и да удържи здравна вноска от лицето за дни, в които то"
            + " подлежи на осигуряване по т. 1.");
    assertEquals(
        "false",
        special.get("isPaid"),
        "⚠️ „Платено“ в речника значи „платено ОТ НАС“. Оставено true, фишът плащаше пълната дневна"
            + " ставка за дни, за които КТ не дава на работодателя нито размер, нито основание.");
    assertEquals("none", special.get("payer"), "платецът е съдът/органът, не работодателят");
    assertEquals("0", special.get("compensationPct"));
    assertEquals(
        "false",
        special.get("bgPaidUnderArt177"),
        "не се заплаща от работодателя изобщо, тоест не и в размера по чл. 177");
    assertEquals(
        "true",
        special.get("countsAsInsuranceService"),
        "⚠️ ТОВА Е ЕДИНСТВЕНИЯТ ОТВОРЕН ПРОЧИТ В ЦЕЛИЯ РЕД и той е обявен, не скрит. КСО чл. 9 ал. 2"
            + " изброява периодите, зачетени за стаж БЕЗ вноски, и чл. 157 НЕ е в списъка; ЗСВ и ЗЗБ"
            + " не са сверени в тази среда. Избрано е „зачита се“, защото указание т. 16.4 поема"
            + " случая дословно („други дни в осигуряване без осигурителни вноски“) и защото трудовото"
            + " правоотношение не е прекъснато. Флипът към прочита „прекъсване по КСО чл. 10 ал. 2“ е"
            + " описан ред по ред в BgD1DayBuckets.UNBUCKETED и НЕ мени нито едно число в пари.");
    assertEquals("16.4", special.get("d1Code"));
    assertEquals(
        "standard",
        special.get("healthContributionRule"),
        "⚠️ НЕ unpaid_employee. Правилото е признакът, по който BgUnpaidLeaveHealthResolver#payerFor"
            + " решава дали хипотезата на б. „б“ изобщо се открива — а тя иска НЕПЛАТЕН ОТПУСК.");
    assertEquals(
        "true",
        special.get("countsAsLaborService"),
        "КТ чл. 352 ал. 1 т. 2 дословно: „ползваните ПЛАТЕНИ отпуски НЕЗАВИСИМО ОТ ТЯХНОТО ОСНОВАНИЕ"
            + " И НАЧИНА НА ЗАПЛАЩАНЕТО ИМ“ — тоест трудовият стаж не пита кой е платецът");
    assertTrue(special.get("legalBasis").contains("ал.3 т.3"), special.get("legalBasis"));

    // ═══ И ТРИТЕ СА РАЗЛИЧНИ РЕДОВЕ, А НЕ ЕДИН РЕД С ФЛАГ ═══
    Set<String> regimes =
        new LinkedHashSet<>(
            Arrays.asList(
                ktd.get("bgPaidUnderArt177") + "/" + ktd.get("isPaid") + "/" + ktd.get("d1Code"),
                art177.get("bgPaidUnderArt177")
                    + "/"
                    + art177.get("isPaid")
                    + "/"
                    + art177.get("d1Code"),
                special.get("bgPaidUnderArt177")
                    + "/"
                    + special.get("isPaid")
                    + "/"
                    + special.get("d1Code")));
    assertEquals(
        3,
        regimes.size(),
        "три РАЗЛИЧНИ тройки (по чл. 177 / платено от нас / кофа) от ЕДИН член — точно както при"
            + " чл. 164б, където правното следствие дава два реда, а не един ред с флаг. Намерено: "
            + regimes);
  }

  @Test
  @DisplayName("категория „Болнични“ се РАЗЦЕПВА на три кофи — затова колоната не е извод")
  void theSickCategorySplitsIntoThreeBuckets() {
    Map<String, String> byCode = new TreeMap<>();
    Map<String, String> categoryByCode = new TreeMap<>();
    for (Map<String, String> row : rows()) {
      byCode.put(row.get("code"), row.get("d1Code"));
      categoryByCode.put(row.get("code"), row.get("category"));
    }

    // И трите носят категория 2 („Болнични“) — тоест категорията НЕ различава кофите им.
    for (String code : Arrays.asList("sick_er", "sick_doo", "sick_no_benefit_doo")) {
      assertEquals("2", categoryByCode.get(code), code + " трябва да е категория „Болнични“");
    }
    Set<String> buckets =
        new LinkedHashSet<>(
            Arrays.asList(
                byCode.get("sick_er"), byCode.get("sick_doo"), byCode.get("sick_no_benefit_doo")));
    assertEquals(
        3,
        buckets.size(),
        "три РАЗЛИЧНИ кофи от една категория: "
            + buckets
            + ". Switch по category би ги слял в едно число — и то е правдоподобно, защото сборът им"
            + " е верният общ брой дни отсъствие.");
  }

  @Test
  @DisplayName("чл. 164б · едно основание, ДВЕ кофи — според правото на обезщетение по чл. 53в КСО")
  void oneLegalBasisSplitsByBenefitEntitlement() {
    Map<String, Map<String, String>> byCode = new TreeMap<>();
    for (Map<String, String> row : rows()) {
      byCode.put(row.get("code"), row);
    }
    Map<String, String> withBenefit = byCode.get("child_care_2_8");
    Map<String, String> withoutBenefit = byCode.get("adoption_u5_no_benefit");

    assertEquals("16.2", withBenefit.get("d1Code"), "с право на обезщетение по чл. 53в → т. 16.2");
    assertEquals(
        "16.3",
        withoutBenefit.get("d1Code"),
        "указание т. 16.3: „В тази точка се попълват и дните БЕЗ право на обезщетение по КСО“");
    assertTrue(
        withBenefit.get("legalBasis").contains("164б")
            && withoutBenefit.get("legalBasis").contains("164б"),
        "ЕДНО И СЪЩО основание на двата реда — ако разграничителят беше нормата, тук щеше да е"
            + " невъзможно да се различат: "
            + withBenefit.get("legalBasis")
            + " / "
            + withoutBenefit.get("legalBasis"));
  }

  @Test
  @DisplayName("четирите нови реда · всеки с основанието, сверено срещу дословния текст на КТ/КСО")
  void theFourNewRowsCarryTheirVerifiedLegalBasis() {
    Map<String, String> basisByCode = new TreeMap<>();
    for (Map<String, String> row : rows()) {
      basisByCode.put(row.get("code"), row.get("legalBasis"));
    }
    for (Map.Entry<String, String> e : NEW_ROWS.entrySet()) {
      assertEquals(
          e.getValue(),
          basisByCode.get(e.getKey()),
          "новият ред " + e.getKey() + " трябва да носи основанието, сверено срещу качения текст");
    }
    // ⚠️ ЧЛ. 26 Е ОТ ЗАКОНА ЗА ЗАКРИЛА НА ДЕТЕТО, НЕ ОТ ЗЗД. Чл. 164а ал. 1 КТ дословно: „имат
    // лицата, при които е настанено дете по реда на чл. 26 от ЗАКОНА ЗА ЗАКРИЛА НА ДЕТЕТО“.
    // Заданието на етапа го цитира като „чл. 26 ЗЗД“ — Законът за задълженията и договорите, който
    // няма нищо общо. Съкращенията се различават с една буква и грешката е невидима в преглед.
    assertTrue(
        basisByCode.get("child_placement_164a").contains("ЗЗДет"),
        "чл. 164а сочи чл. 26 от Закона за ЗАКРИЛА НА ДЕТЕТО (ЗЗДет), не ЗЗД: "
            + basisByCode.get("child_placement_164a"));
  }

  @Test
  @DisplayName("чл. 166 КТ · кърменето е ПЛАТЕНО ОТ РАБОТОДАТЕЛЯ и НЕ е по чл. 177")
  void theNursingLeaveCarriesTheRegimeArticle166Gives() {
    Map<String, String> row = null;
    for (Map<String, String> r : rows()) {
      if ("nursing_166".equals(r.get("code"))) {
        row = r;
      }
    }
    assertTrue(row != null, "речникът няма ред nursing_166 — чл. 166 КТ е право без вход");

    // ЦИТАТ, чл. 166 ал. 1: „има право на ПЛАТЕН отпуск за кърмене … по 1 час два пъти дневно или
    // с нейно съгласие по 2 часа наведнъж“. ЦИТАТ, ал. 4: „Отпускът по предходните алинеи СЕ
    // ЗАПЛАЩА ОТ РАБОТОДАТЕЛЯ.“ Тоест платец е работодателят, не ДОО — обезщетение по КСО няма.
    assertEquals("true", row.get("isPaid"), "ал. 1 казва „платен отпуск“");
    assertEquals("employer", row.get("payer"), "ал. 4 казва „се заплаща от работодателя“");
    assertEquals("1", row.get("category"), "категория „Отпуск“ — институтът е отпуск по КТ");

    // ⚠️ И НЕ Е ПО ЧЛ. 177, ВЪПРЕКИ ЧЕ Е ПЛАТЕН. Ал. 4 казва само „се заплаща“; препратка към
    // чл. 177 НЯМА — за разлика от чл. 159 ал. 2, който я прави ДОСЛОВНО („заплаща се съгласно
    // чл. 177“). НСОРЗ чл. 17 ал. 1 от своя страна урежда базата за „платен ГОДИШЕН отпуск по
    // чл. 177“, а кърменето не е годишен отпуск. Вдигнат, флагът щеше да карвира часовете от
    // заплатата и да ги върне по среднодневното на ПРЕДХОДНИЯ месец — друго число, без норма зад
    // себе си.
    assertEquals(
        "false",
        row.get("bgPaidUnderArt177"),
        "чл. 166 ал. 4 не препраща към чл. 177 — заплаща се като отработено време");

    // ⚠️ И ЗАЧИТА СЕ ЗА ОСИГУРИТЕЛЕН СТАЖ — КСО чл. 9 ал. 1 т. 1: времето, за което са дължими
    // вноски върху начисленото възнаграждение. Възнаграждение има (ал. 4), тоест вноски има.
    assertEquals(
        "true",
        row.get("countsAsInsuranceService"),
        "възнаграждение от работодателя значи дължими вноски, значи осигурителен стаж");
    assertEquals("16.1", row.get("d1Code"), "дните в платен отпуск са в т. 16.1");

    // ⚠️ И ДВЕТЕ ПОЛОВИНИ НА ПРЕДИКАТА СОЧАТ НАВЪТРЕ — тоест нито карв, нито изваждане от т. 16.1.
    assertTrue(
        !BgD1DayBuckets.outsideInsurance(row.get("d1Code"), Boolean.TRUE),
        "платеният отпуск за кърмене НЕ е извън осигуряването");
    assertTrue(
        !BgD1DayBuckets.insuranceFlagsConflict(row.get("d1Code"), Boolean.TRUE),
        "кофа 16.1 + „зачита се“ е съгласуваната двойка");
  }

  @Test
  @DisplayName("⚠️ чл. 165 КТ е ОТМЕНЕН (ДВ бр. 54/2015) и НЯМА ред в речника")
  void therepealedArticle165HasNoRow() {
    for (Map<String, String> row : rows()) {
      assertTrue(
          !row.get("legalBasis").contains("165"),
          "чл. 165 КТ е отменен с ДВ бр. 54 от 2015 г., в сила от 17.07.2015 г. Ред, който го сочи,"
              + " би дал право на отпуск, което не съществува — а старите справочници още го"
              + " изброяват. Ред: "
              + row.get("code")
              + " → "
              + row.get("legalBasis"));
    }
  }

  @Test
  @DisplayName("етикетът на child_care_2_8 · вече казва каквото основанието му означава")
  void theCorrectedLabelMatchesItsLegalBasis() {
    Map<String, String> row = null;
    for (Map<String, String> r : rows()) {
      if ("child_care_2_8".equals(r.get("code"))) {
        row = r;
      }
    }
    assertTrue(
        row != null,
        "редът child_care_2_8 трябва да съществува — кодът се ползва от"
            + " клиентското съпоставяне в apps/init-data/en/leave_reason.xml и НЕ се преименува");
    assertTrue(
        !row.get("name").contains("6 месеца"),
        "⚠️ Етикетът носеше „6 месеца от 2-8 г“ при основание чл. 164б/53а. Двете не се връзват:"
            + " чл. 164б ал. 1 КТ е ОСИНОВЯВАНЕ на дете до 5-годишна възраст за 365 дни, а „6 месеца"
            + " за дете до 8 г.“ е чл. 167а — НЕПЛАТЕНИЯТ отпуск, който вече има свой ред"
            + " (unpaid_child_u8_167a). Сегашен етикет: "
            + row.get("name"));
    assertTrue(
        row.get("legalBasis").contains("53в"),
        "плащащата норма е чл. 53в КСО (чл. 53а дава ПРАВОТО, чл. 53в — размера и срока до 365 дни):"
            + " "
            + row.get("legalBasis"));
  }

  @Test
  @DisplayName("нито един ред не носи кофа извън шестте — и петте без кофа са ИЗБРОЕНИ")
  void everyBucketIsOneOfTheSixAndTheEmptyOnesAreDeclared() {
    Set<String> withoutBucket = new TreeSet<>();
    List<String> unknown = new ArrayList<>();
    for (Map<String, String> row : rows()) {
      String bucket = row.get("d1Code");
      if (bucket == null || bucket.isEmpty()) {
        withoutBucket.add(row.get("code"));
      } else if (!BgD1DayBuckets.BUCKETS.contains(bucket)) {
        unknown.add(row.get("code") + " → „" + bucket + "“");
      }
    }
    if (!unknown.isEmpty()) {
      fail(
          "кофи извън шестте на т. 16 (16.1–16.5, 16.А):\n  "
              + String.join("\n  ", unknown)
              + "\n⚠️ Провери и БУКВАТА на 16.А — тя е КИРИЛСКА „А“ (U+0410).");
    }
    assertEquals(
        new TreeSet<>(BgD1DayBuckets.UNBUCKETED),
        withoutBucket,
        "празната кофа е СТОЙНОСТ („не се зачита за осигурителен стаж, тоест никоя точка не го"
            + " иска“), но само за изброените в BgD1DayBuckets.UNBUCKETED. Празна клетка без списък"
            + " е неразличима от забравена клетка.");
  }

  @Test
  @DisplayName("⚠️ НИТО ЕДНА ПРАЗНА КЛЕТКА в countsAsInsuranceService — null НЕ Е „не се зачита“")
  void theInsuranceServiceFlagIsNeverLeftBlank() throws IOException {
    TreeSet<String> blank = new TreeSet<>();
    for (Map<String, String> row : rows()) {
      String credited = row.get("countsAsInsuranceService");
      String value = credited == null ? "" : credited.trim();
      if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
        blank.add(row.get("code") + " → „" + value + "“");
      }
    }
    assertTrue(
        blank.isEmpty(),
        "⚠️ ПРАЗНАТА КЛЕТКА Е ТИХ ПРОХОД, А НЕ ПОДРАЗБИРАНЕ. Адаптерът я внася като null, а"
            + " BgD1DayBuckets.outsideInsurance пита Boolean.FALSE.equals(...) — при null отговорът"
            + " е false, тоест видът се плаща ЦЯЛ и Д1 го брои в т. 16.1, докато т. 14/15 мълчи."
            + " Точно тази празнота е разликата между „измерено, зачита се“ и „никой не е попълнил“."
            + " Клетки, които не са нито true, нито false: "
            + blank);
  }

  @Test
  @DisplayName(
      "⚠️ ДВАТА ПРИЗНАКА СЪВПАДАТ: празна кофа ⟷ countsAsInsuranceService = false (КСО чл. 10 ал. 2)")
  void theEmptyBucketAgreesWithTheInsuranceServiceFlag() throws IOException {
    TreeSet<String> withoutBucket = new TreeSet<>();
    TreeSet<String> notCredited = new TreeSet<>();
    TreeSet<String> conflicting = new TreeSet<>();
    TreeSet<String> outside = new TreeSet<>();

    for (Map<String, String> row : rows()) {
      String code = row.get("code");
      String bucket = row.get("d1Code");
      String credited = row.get("countsAsInsuranceService");
      if (bucket == null || bucket.trim().isEmpty()) {
        withoutBucket.add(code);
      }
      if ("false".equalsIgnoreCase(credited == null ? "" : credited.trim())) {
        notCredited.add(code);
      }
      // ⚠️ ПИТА СЕ САМИЯТ ПРЕДИКАТ, а не преписан негов образ. Речникът и кодът, който го чете,
      // трябва да казват едно и също — сверени през две отделни сметки, те са свободни да се
      // разминат точно в деня, в който някоя от двете се редактира.
      Boolean flag =
          "true".equalsIgnoreCase(credited == null ? "" : credited.trim())
              ? Boolean.TRUE
              : "false".equalsIgnoreCase(credited == null ? "" : credited.trim())
                  ? Boolean.FALSE
                  : null;
      if (BgD1DayBuckets.insuranceFlagsConflict(bucket, flag)) {
        conflicting.add(code);
      }
      if (BgD1DayBuckets.outsideInsurance(bucket, flag)) {
        outside.add(code);
      }
    }

    assertTrue(
        conflicting.isEmpty(),
        "⚠️ РЕЧНИКЪТ СИ ПРОТИВОРЕЧИ И ДВЕТЕ СТРАНИ ЩЯХА ДА ГО ПРОЧЕТАТ РАЗЛИЧНО. Кофа + „не се"
            + " зачита“ вади деня от т. 16.1 ДВА пъти; празна кофа + „зачита се“ (или непопълнено)"
            + " го оставя платен срещу прекъснато осигуряване. Видове: "
            + conflicting);
    assertEquals(
        new TreeSet<>(BgD1DayBuckets.UNBUCKETED),
        outside,
        "⚠️ КАРВИРАЩОТО МНОЖЕСТВО = Д1-ИЗВАЖДАЩОТО МНОЖЕСТВО, и това е инвариантът на целия клон."
            + " BgD1DayBuckets.outsideInsurance е ЕДИНСТВЕНИЯТ предикат и за заплатата"
            + " (BgPayrollInputBuilder#leaveDaysOf), и за т. 16.1 (BgD1DaySource#read). Този ред"
            + " сверява какво връща той ВЪРХУ РЕЧНИКА срещу обявения списък — разминаване значи или"
            + " шести вид без кофа, или вид, който тихо е излязъл от карва.");

    assertEquals(
        withoutBucket,
        notCredited,
        "⚠️ БЛОК Ф ГИ ПОСТАВИ ЕДИН СРЕЩУ ДРУГ, И ТЕ ОПИСВАТ РАЗЛИЧНИ НЕЩА. Празният d1Code значи"
            + " „няма кофа в т. 16“; countsAsInsuranceService = false значи „периодът НЕ се зачита за"
            + " осигурителен стаж“, тоест ПРЕКЪСВА осигуряването по КСО чл. 10 ал. 2 и поражда двойка"
            + " т. 14/15. Днес двата признака съвпадат за петте вида и това е ВЯРНО — но е СЪВПАДЕНИЕ"
            + " на данните, не тъждество: чл. 9 ал. 2 изброява периоди, които СЕ ЗАЧИТАТ за стаж без"
            + " вноски, тоест бъдещ вид отсъствие може да има кофа И да не се зачита, или обратно.\n"
            + "  ⚠️ ЗАЩО ТОГАВА ТЕСТЪТ ПАЗИ РАВЕНСТВОТО: разминаване значи, че една от двете колони е"
            + " редактирана без другата, а следствията им са на РАЗЛИЧНИ точки от декларацията (т. 16"
            + " срещу т. 14/15) и никой не сверява двете наведнъж. Ако разминаването е НАРОЧНО, този"
            + " тест е мястото, където се вписва — заедно с реда, който го налага.\n"
            + "  Без кофа: "
            + withoutBucket
            + "\n  Не се зачита за стаж: "
            + notCredited);
  }
}
