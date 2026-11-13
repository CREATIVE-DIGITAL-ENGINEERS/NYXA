package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
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
 * ФАЙЛОВАТА половина за ПЪРВАТА ВЪЛНА нови видове осигурен: вярно ли е ЗАПИСАНО. Без Postgres, без
 * App Install — същото разделение като {@code BgSeafarerSeedFileTest} и двойника му.
 *
 * <p><b>Какво пази.</b> Седем нови кода — 09, 16, 19, 20, 25, 26 и 99 — и една ПОПРАВКА по вече
 * заведен код (27, обезщетението по чл. 106 ал. 2 ЗДСл). Всеки нов код е РЕПЛИКА на вече заведен
 * шаблон, тоест твърдението не е „ставките са тези числа“, а нещо по-силно и по-евтино за
 * поддържане: „ставките са ДОСЛОВНО тези на кода-шаблон, минус изрично изброените фондове“. Сменѝ
 * се утре ставка при 04 заради ЗБДОО 2027 — тестът ще поиска същата смяна и при 16, 26, 99 и 25, а
 * няма да ги остави мълчаливо на стари числа.
 *
 * <p><b>⚠️ ЗАЩО НАБОРЪТ ФОНДОВЕ Е ЗАКОВАН ОТДЕЛНО.</b> Сравнението с шаблона хваща сгрешено ЧИСЛО,
 * но не хваща липсващ РЕД: подмножество на шаблона си остава дословно равно на шаблона по редовете,
 * които има. А липсващият фонд е точно тихият дефект — {@code BgInsuranceCalculationService}
 * филтрира по {@code laborCategory} с ТОЧНО РАВЕНСТВО и непокритата двойка просто не намира ред.
 * Затова наборът се изброява поименно, по категория труд.
 *
 * <p><b>⚠️ И ЗАЩО ПОПРАВКАТА ИМА СВОЕ ТВЪРДЕНИЕ.</b> Код 27 при основание {@code comp_zdsl_106_2} с
 * лична вноска НУЛА би чел обезщетението като „за сметка на платеца“. Регресия по брой редове не би
 * я хванала — шест реда с нули при лицето биха минали. Затова се пита самата ставка на ЛИЦЕТО.
 */
class BgInsuredTypeWave1SeedFileTest {

  private static final String FILE = "bg_insurance_contribution.csv";
  private static final String STANDARD = "STANDARD";

  /** Ключът, по който два реда са „един и същ ред“ за целите на сравнението с шаблона. */
  private static String key(List<String> h, String[] r) {
    return BgSeed.col(h, r, "laborCategory")
        + " · "
        + BgSeed.col(h, r, "fundCode")
        + " · "
        + BgSeed.col(h, r, "bgUpfRegime")
        + " · "
        + BgSeed.col(h, r, "bgSolScope")
        + " · "
        + BgSeed.col(h, r, "bgTeacherScope")
        + " · "
        + BgSeed.col(h, r, "validFrom");
  }

  /** Каквото репликата трябва да носи ДОСЛОВНО от шаблона: числата, видовете им и периодът. */
  private static String shape(List<String> h, String[] r) {
    return BgSeed.col(h, r, "employerRate")
        + " ("
        + BgSeed.col(h, r, "bgEmployerRateKind")
        + ") / "
        + BgSeed.col(h, r, "employeeRate")
        + " ("
        + BgSeed.col(h, r, "bgEmployeeRateKind")
        + ") до "
        + (BgSeed.col(h, r, "validTo").isEmpty() ? "—" : BgSeed.col(h, r, "validTo"));
  }

  private static List<String[]> rowsOf(String code, String compensation) {
    List<String> h = BgSeed.header(FILE);
    List<String[]> out = new ArrayList<>();
    for (String[] r : BgSeed.rows(FILE)) {
      if (code.equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          && (compensation == null
              || compensation.equals(BgSeed.col(h, r, "bgCompensationTypeCode")))) {
        out.add(r);
      }
    }
    return out;
  }

  /** Фондовете на един код по категория труд — „категория → {фондове}“, за четими съобщения. */
  private static Map<String, Set<String>> fundsByCategory(String code) {
    List<String> h = BgSeed.header(FILE);
    Map<String, Set<String>> out = new TreeMap<>();
    for (String[] r : rowsOf(code, STANDARD)) {
      out.computeIfAbsent(BgSeed.col(h, r, "laborCategory"), k -> new TreeSet<>())
          .add(BgSeed.col(h, r, "fundCode"));
    }
    return out;
  }

  /**
   * Сверява, че {@code code} е дословна реплика на {@code template} без фондовете в {@code
   * dropped}.
   *
   * <p>Три пътя към червено, и трите с различен смисъл: ред в повече (реплика, която е добавила
   * нещо), ред по-малко (мълчаливо изпаднал фонд) и ред със същия ключ, но други числа (разминала
   * се реплика — точно случаят „шаблонът се смени, репликата остана“).
   */
  private static void assertReplicates(String code, String template, String... dropped) {
    List<String> h = BgSeed.header(FILE);
    Set<String> drop = new LinkedHashSet<>(Arrays.asList(dropped));

    Map<String, String> want = new TreeMap<>();
    for (String[] r : rowsOf(template, STANDARD)) {
      if (!drop.contains(BgSeed.col(h, r, "fundCode"))) {
        want.put(key(h, r), shape(h, r));
      }
    }
    if (want.isEmpty()) {
      fail(
          "шаблонът — вид осигурен "
              + template
              + " — няма нито един STANDARD ред след изваждането на "
              + drop
              + ". Тест, който сравнява с празнота, минава от празнота, а не от вярност");
    }

    Map<String, String> got = new TreeMap<>();
    for (String[] r : rowsOf(code, STANDARD)) {
      got.put(key(h, r), shape(h, r));
    }

    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String> e : want.entrySet()) {
      String mine = got.get(e.getKey());
      if (mine == null) {
        problems.add(
            "ЛИПСВА " + e.getKey() + " — шаблонът " + template + " го носи: " + e.getValue());
      } else if (!mine.equals(e.getValue())) {
        problems.add(
            "РАЗМИНАТ "
                + e.getKey()
                + "\n      шаблон "
                + template
                + " : "
                + e.getValue()
                + "\n      код "
                + code
                + "      : "
                + mine);
      }
    }
    for (String k : got.keySet()) {
      if (!want.containsKey(k)) {
        problems.add(
            "В ПОВЕЧЕ "
                + k
                + " — шаблонът "
                + template
                + " такъв ред няма (или е сред изрично"
                + " извадените "
                + drop
                + ")");
      }
    }

    if (!problems.isEmpty()) {
      fail(
          "вид осигурен "
              + code
              + " вече НЕ е дословна реплика на "
              + template
              + (drop.isEmpty() ? "" : " без " + drop)
              + " ("
              + problems.size()
              + "):\n    "
              + String.join("\n    ", problems)
              + "\n\n  ⚠️ Ако шаблонът е сменен нарочно (ново ЗБДОО например), репликите се сменят"
              + " ЗАЕДНО с него — иначе кодовете тихо остават на стари ставки.");
    }
  }

  // ═══ РЕПЛИКИТЕ ════════════════════════════════════════════════════════════

  /**
   * ⚠️ 09 Е РЕПЛИКА НА 25, А НЕ НА 14 — И РАЗЛИКАТА Е ЦЯЛ ОСИГУРИТЕЛЕН РЕЖИМ.
   *
   * <p>Код 09, преписан от вид осигурен 14, би носил четири реда: фонд Пенсии, УПФ и ЗОВ. Такъв
   * избор е сгрешен, и то не в число, а в АЛИНЕЯ. Вид 14 е по КСО чл. 4 ал. 3 — тесният набор
   * „пенсия и здравно“ за работещите без трудово правоотношение ИЗВЪН изброените в ал. 1. Код 09 е
   * по чл. 4 ал. 1 т. 5, а шапката на ал. 1 слага лицето под ПЪЛНИЯ набор: ОЗМ,
   * инвалидност/старост/смърт, ТЗПБ и безработица. Изречение второ на т. 5 маха ЕДИНСТВЕНО
   * безработицата — „членовете на кооперации, работещи без трудови правоотношения в кооперацията,
   * не се осигуряват за безработица“.
   *
   * <p>Оттам 09 е правният близнак на вид осигурен 25, чието наименование буквално казва същото
   * („които не се осигуряват за безработица“). Затова тестът не заковава числа, а иска РАВЕНСТВО с
   * 25: смени ли се 25 утре по ЗБДОО 2027, 09 трябва да се смени заедно с него, а не мълчаливо да
   * остане на стари ставки.
   *
   * <p><b>⚠️ И РАЗПРЕДЕЛЕНИЕТО НЕ Е ПО АНАЛОГИЯ.</b> КСО чл. 6 ал. 3 назовава чл. 4 ал. 1 т. 5
   * ПОИМЕННО: „осигурителните вноски за работниците и служителите и за лицата по чл. 4, ал. 1, т.
   * 5, 7 и 8 и ал. 3, т. 5 и 6 се разпределят между осигурителите и осигурените“. Тоест
   * стандартният сплит тук е записан в закона по име — 60:40 по т. 7 за ОЗМ и пенсионните дялове по
   * т. 8 и 9 — а не изведен от приликата с 25.
   */
  @Test
  @DisplayName("ФАЙЛ · ⚠️ 09 е дословна реплика на 25, не на 14 — чл. 4 ал. 1 т. 5, не ал. 3")
  void code09ReplicatesCode25() {
    assertReplicates("09", "25");
    assertEquals(
        20,
        rowsOf("09", STANDARD).size(),
        "вид осигурен 09 вече няма двайсетте реда на 25 — сверѝ и брояча в BgRateKindSeedTest");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ РЕГРЕСИЯ: 09 носи ОЗМ, а фонд Безработица му ЛИПСВА — карв-аутът е един")
  void code09CarriesSicknessButNotUnemployment() {
    List<String> h = BgSeed.header(FILE);

    // 1 · ОЗМ — фондът, който липсва, ако 09 е преписан от 14. Иска се за ВСЯКА категория,
    // която кодът носи: BgInsuranceCalculationService филтрира laborCategory с точно равенство и
    // непокритата двойка не взима чужд ред.
    Map<String, String> ozm = new TreeMap<>();
    for (String[] r : rowsOf("09", STANDARD)) {
      if ("OZM".equals(BgSeed.col(h, r, "fundCode"))) {
        ozm.put(
            BgSeed.col(h, r, "laborCategory"),
            BgSeed.col(h, r, "employerRate") + " / " + BgSeed.col(h, r, "employeeRate"));
      }
    }
    Map<String, String> wantOzm = new TreeMap<>();
    for (String category : Arrays.asList("1", "2", "3")) {
      wantOzm.put(category, "2.1000 / 1.4000");
    }
    assertEquals(
        wantOzm,
        ozm,
        "⚠️ ОЗМ при вид осигурен 09 е изчезнал или се е сменил. Шапката на КСО чл. 4 ал. 1 слага"
            + " кооперативния член под ПЪЛНИЯ набор, а изречение второ на т. 5 маха само"
            + " безработицата. Липсващ ОЗМ е следствие от преписване на вид 14, който е по"
            + " чл. 4 ал. 3 — друга алинея и друг режим.");

    // 2 · и обратното: безработица НЯМА. Редът трябва да ЛИПСВА, а не да носи нула — нулата значи
    // дължима вноска с размер нула, а тук вноска изобщо не се дължи.
    for (String[] r : rowsOf("09", STANDARD)) {
      if ("UNEMPLOYMENT".equals(BgSeed.col(h, r, "fundCode"))) {
        fail(
            "вид осигурен 09 носи ред за фонд Безработица ("
                + BgSeed.col(h, r, "importId")
                + "). ⚠️ КСО чл. 4 ал. 1 т. 5 изр. 2 казва изрично, че членовете на кооперации,"
                + " работещи без трудови правоотношения в кооперацията, НЕ се осигуряват за"
                + " безработица. Изключването е по ВИДА осигурен, тоест редът липсва, а не носи"
                + " нула.");
      }
    }

    // 3 · ⚠️ ТЗПБ НЕ СЕ ТЪРСИ ТУК, И ТОВА НЕ Е ПРОПУСК В ТВЪРДЕНИЕТО. Ставката му зависи от
    // икономическата дейност и идва от КИД таблицата през BgContributionRequest.tzpbRate.
    // Собствен ред носят само 05 и 24 (фиксирани 0,70 на сто) и 92 — и такъв ред ПОБЕЖДАВА
    // ставката от заявката, вместо да я допълва. Ред за 09 щеше да ЗАМЕНИ дейностната ставка с
    // измислено число. Че 09 няма такъв ред, се пази от равенството с 25 отгоре.
    for (String[] r : rowsOf("09", STANDARD)) {
      if ("TZPB".equals(BgSeed.col(h, r, "fundCode"))) {
        fail(
            "вид осигурен 09 носи СВОЙ ред за ТЗПБ ("
                + BgSeed.col(h, r, "importId")
                + "). ⚠️ Такъв ред бие ставката по КИД в BgInsuranceCalculationService — това е"
                + " изключението на 05 и 24 (фиксирани 0,70) и на 92. За 09 ТЗПБ се дължи, но по"
                + " ДЕЙНОСТТА, тоест през заявката, а не от този файл.");
      }
    }
  }

  @Test
  @DisplayName(
      "ФАЙЛ · 16 е дословна реплика на 04 без Учителския фонд — сумирано изчисляване на РВ")
  void code16ReplicatesCode04WithoutTheTeacherFund() {
    assertReplicates("16", "04", "TEACHER_PF");
  }

  @Test
  @DisplayName("ФАЙЛ · 26 е дословна реплика на 04 без Учителския фонд — инвалиди по ЗХУ")
  void code26ReplicatesCode04WithoutTheTeacherFund() {
    assertReplicates("26", "04", "TEACHER_PF");
  }

  @Test
  @DisplayName("ФАЙЛ · 99 е дословна реплика на 04 без Учителския фонд — командировани по чл. 121а")
  void code99ReplicatesCode04WithoutTheTeacherFund() {
    assertReplicates("99", "04", "TEACHER_PF");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · 25 е реплика на 04 без Учителския фонд И без Безработица — програми за заетост")
  void code25ReplicatesCode04WithoutTheTeacherFundAndWithoutUnemployment() {
    assertReplicates("25", "04", "TEACHER_PF", "UNEMPLOYMENT");
  }

  @Test
  @DisplayName("ФАЙЛ · ПЕНСИОННИТЕ редове на 19 и 20 са реплики на един срез от 18 — чл. 9 ал. 3")
  void codes19And20ReplicateOneSliceOfCode18() {
    List<String> h = BgSeed.header(FILE);

    // Срезът, който се репликира: comp_kt_225_1 при код 18 — трите пенсионни реда.
    Map<String, String> slice = new TreeMap<>();
    for (String[] r : rowsOf("18", "comp_kt_225_1")) {
      slice.put(key(h, r), shape(h, r));
    }
    assertEquals(
        3,
        slice.size(),
        "срезът comp_kt_225_1 при вид осигурен 18 вече няма три реда — сверѝ и репликите 19 и 20");

    // ⚠️ САМО ПЕНСИОННИТЕ. Чл. 9 ал. 5 КСО изчерпва ДОО-то до фонд Пенсии и ДЗПО — и това важи за
    // ДВАТА кода. Здравното обаче НЕ е по КСО и се решава от ЗЗО поотделно: при 20 правоотношението
    // продължава и чл. 40 ал. 1 т. 1 иска ЗОВ ред, при 19 лицето е уволнено и здравното е по чл. 40
    // ал. 3 т. 6, за сметка на държавния бюджет и извън ведомостта. Затова сравнението със среза се
    // прави върху пенсионните редове, а ЗОВ се твърди отделно.
    for (String code : Arrays.asList("19", "20")) {
      Map<String, String> got = new TreeMap<>();
      for (String[] r : rowsOf(code, STANDARD)) {
        if (!"HEALTH".equals(BgSeed.col(h, r, "fundCode"))) {
          got.put(key(h, r), shape(h, r));
        }
      }
      assertEquals(
          slice,
          got,
          "пенсионните редове на вид осигурен "
              + code
              + " вече не са дословна реплика на среза comp_kt_225_1 при 18. По КСО чл. 9 ал. 5"
              + " дължимото по цялата ал. 3 е само за фонд Пенсии и за ДЗПО — без ОЗМ, без"
              + " безработица и без ТЗПБ.");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ 20 носи ЗОВ ред, а 19 — НЕ, и това е разликата между двете хипотези")
  void code20CarriesHealthAndCode19DoesNot() {
    List<String> h = BgSeed.header(FILE);

    Map<String, String> health20 = new TreeMap<>();
    for (String[] r : rowsOf("20", STANDARD)) {
      if ("HEALTH".equals(BgSeed.col(h, r, "fundCode"))) {
        health20.put(
            BgSeed.col(h, r, "laborCategory"),
            BgSeed.col(h, r, "employerRate") + " / " + BgSeed.col(h, r, "employeeRate"));
      }
    }
    Map<String, String> want = new TreeMap<>();
    want.put("3", "4.8000 / 3.2000");
    assertEquals(
        want,
        health20,
        "⚠️ ЗОВ редът на вид осигурен 20 е изчезнал или се е сменил. Чл. 9 ал. 5 КСО изчерпва само"
            + " ДОО-то — здравното не е по КСО. При ТРУДОУСТРОЕНИЯ правоотношението ПРОДЪЛЖАВА,"
            + " лицето остава по чл. 4 ал. 1 т. 1 КСО и ЗЗО чл. 40 ал. 1 т. 1 стъпва върху същия"
            + " доход, върху който се дължат вноските за ДОО, с разпределение 60:40.");

    for (String[] r : rowsOf("19", STANDARD)) {
      if ("HEALTH".equals(BgSeed.col(h, r, "fundCode"))) {
        fail(
            "вид осигурен 19 вече носи ЗОВ ред ("
                + BgSeed.col(h, r, "importId")
                + "). ⚠️ При чл. 9 ал. 3 т. 3 лицето е УВОЛНЕНО поради задържане от органите на"
                + " властта — здравното му върви по ЗЗО чл. 40 ал. 3 т. 6, за сметка на държавния"
                + " бюджет и ИЗВЪН ведомостта. Ред тук би начислил втора здравна вноска на"
                + " работодател, който вече няма трудово правоотношение с лицето. Ако това е"
                + " променено нарочно — смени и втория изход в BgHealthContributionInvariantTest,"
                + " защото 19 е единственият код, който минава през него.");
      }
    }
  }

  // ═══ НАБОРЪТ ФОНДОВЕ, ИЗБРОЕН ПОИМЕННО ════════════════════════════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ седемте нови кода носят точно обявения си набор фондове, по категория")
  void theSevenNewCodesCarryExactlyTheDeclaredFunds() {
    Map<String, Map<String, Set<String>>> expected = new LinkedHashMap<>();

    // 16, 26 и 99 — фондовете на 04 без Учителския: трета категория носи седем, а първа и втора
    // носят и ППФ (12 на сто при първа, 7 на сто при втора).
    Map<String, Set<String>> like04 = new TreeMap<>();
    like04.put("3", funds("GVRS", "HEALTH", "OZM", "PENSION", "UNEMPLOYMENT", "UPF"));
    like04.put("1", funds("GVRS", "HEALTH", "OZM", "PENSION", "PPF", "UNEMPLOYMENT", "UPF"));
    like04.put("2", funds("GVRS", "HEALTH", "OZM", "PENSION", "PPF", "UNEMPLOYMENT", "UPF"));
    for (String code : Arrays.asList("16", "26", "99")) {
      expected.put(code, like04);
    }

    // 25 — същото, но БЕЗ фонд Безработица: изключването е по ВИДА осигурен, тоест редът липсва.
    Map<String, Set<String>> like04NoUnemployment = new TreeMap<>();
    like04NoUnemployment.put("3", funds("GVRS", "HEALTH", "OZM", "PENSION", "UPF"));
    like04NoUnemployment.put("1", funds("GVRS", "HEALTH", "OZM", "PENSION", "PPF", "UPF"));
    like04NoUnemployment.put("2", funds("GVRS", "HEALTH", "OZM", "PENSION", "PPF", "UPF"));
    expected.put("25", like04NoUnemployment);
    // ⚠️ 09 носи ТОЧНО СЪЩИЯ набор като 25 и това не е съвпадение: и двата кода са под пълния
    // набор на КСО чл. 4 ал. 1, минус САМО безработицата. При 25 карв-аутът е в изр. 2 на т. 1,
    // при 09 — в изр. 2 на т. 5. Че двата набора СЪВПАДАТ ред по ред, се твърди отделно в
    // code09ReplicatesCode25.
    expected.put("09", like04NoUnemployment);

    // 19 и 20 — само пенсионните фондове по чл. 9 ал. 5, и изцяло за сметка на платеца.
    expected.put("19", cats(funds("PENSION", "UPF"), "3"));
    // ⚠️ 20 носи и ЗОВ — вж. code20CarriesHealthAndCode19DoesNot. Точно този набор е и причината
    // 20 да минава по ОБЩОТО правило на BgHealthContributionInvariantTest, а 19 — по втория изход.
    expected.put("20", cats(funds("HEALTH", "PENSION", "UPF"), "3"));

    for (Map.Entry<String, Map<String, Set<String>>> e : expected.entrySet()) {
      assertEquals(
          e.getValue(),
          fundsByCategory(e.getKey()),
          "наборът фондове на вид осигурен "
              + e.getKey()
              + " се е сменил.\n  ⚠️ BgInsuranceCalculationService филтрира laborCategory с ТОЧНО"
              + " РАВЕНСТВО — няма заместител ANY и няма падане назад към трета категория. Тоест"
              + " непокритата двойка (код × категория) не взима чужд ред: за нея фондът просто"
              + " НЯМА, тихо.");
    }
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ при 19 и 20 вноската е ИЗЦЯЛО на платеца — нулата при лицето е структурна")
  void codes19And20AreEntirelyPayerBorne() {
    List<String> h = BgSeed.header(FILE);
    List<String> problems = new ArrayList<>();

    for (String code : Arrays.asList("19", "20")) {
      for (String[] r : rowsOf(code, STANDARD)) {
        String id = BgSeed.col(h, r, "importId");
        // ⚠️ ЗОВ редът на 20 е ИЗВЪН това твърдение и това не е изключение „за да мине“: чл. 9
        // ал. 5 КСО урежда ДОО-то, а здравното е по ЗЗО чл. 40 ал. 1 т. 1 и там разпределението е
        // 60:40 като на всеки нает. Че редът СЪЩЕСТВУВА и е точно 4,80 / 3,20, се твърди в
        // code20CarriesHealthAndCode19DoesNot.
        if ("HEALTH".equals(BgSeed.col(h, r, "fundCode"))) {
          continue;
        }
        if (new BigDecimal(BgSeed.col(h, r, "employeeRate")).signum() != 0) {
          problems.add(
              id
                  + " ("
                  + code
                  + "): лицето носи "
                  + BgSeed.col(h, r, "employeeRate")
                  + ", а по чл. 9 ал. 3 т. 3 вноските са за сметка на ДЪРЖАВНИЯ БЮДЖЕТ, а по т. 4 —"
                  + " на ОСИГУРИТЕЛЯ");
        }
        if (!"STRUCTURAL_ZERO".equals(BgSeed.col(h, r, "bgEmployeeRateKind"))) {
          problems.add(
              id
                  + " ("
                  + code
                  + "): нулата при лицето е обявена за „"
                  + BgSeed.col(h, r, "bgEmployeeRateKind")
                  + "“, а тя е СТРУКТУРНА — дял на лицето не съществува, докато основанието важи,"
                  + " и този ред не може да се напълни с нов validFrom");
        }
        if (new BigDecimal(BgSeed.col(h, r, "employerRate")).signum() == 0) {
          problems.add(id + " (" + code + "): и платецът е на нула — тогава вноска изобщо няма");
        }
      }
    }

    if (!problems.isEmpty()) {
      fail(
          "посоката при 19 и 20 се е сменила ("
              + problems.size()
              + "):\n  "
              + String.join("\n  ", problems));
    }
  }

  // ═══ ПОПРАВКАТА · код 27 при обезщетение по чл. 106 ал. 2 ЗДСл ════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ РЕГРЕСИЯ: обезщетението по чл. 106 ал. 2 ЗДСл вече НЕ е с лична вноска 0")
  void theZdsl106Art2CompensationIsSplitAndNoLongerPayerOnly() {
    List<String> h = BgSeed.header(FILE);
    List<String[]> rows = rowsOf("27", "comp_zdsl_106_2");

    assertEquals(
        6,
        rows.size(),
        "обезщетението по чл. 106 ал. 2 ЗДСл вече няма шест реда — три за транша 80/20 от"
            + " 01.08.2026 г. и три за 60/40 от 01.01.2027 г., същите като при вид осигурен 05");

    Map<String, String> got = new TreeMap<>();
    for (String[] r : rows) {
      got.put(
          BgSeed.col(h, r, "validFrom")
              + " · "
              + BgSeed.col(h, r, "fundCode")
              + " · "
              + BgSeed.col(h, r, "bgUpfRegime"),
          BgSeed.col(h, r, "employerRate") + " / " + BgSeed.col(h, r, "employeeRate"));
    }

    Map<String, String> want = new TreeMap<>();
    want.put("2026-08-01 · PENSION · WITH_UPF", "11.8000 / 3.0000");
    want.put("2026-08-01 · PENSION · WITHOUT_UPF", "15.8000 / 4.0000");
    want.put("2026-08-01 · UPF · WITH_UPF", "4.0000 / 1.0000");
    want.put("2027-01-01 · PENSION · WITH_UPF", "8.2200 / 6.5800");
    want.put("2027-01-01 · PENSION · WITHOUT_UPF", "11.0200 / 8.7800");
    want.put("2027-01-01 · UPF · WITH_UPF", "2.8000 / 2.2000");

    assertEquals(
        want,
        got,
        "⚠️ ПОПРАВКАТА Е ВЪРНАТА. КСО чл. 9 ал. 3 т. 5 казва за това обезщетение само че вноските"
            + " се внасят върху получаваното обезщетение и СЕ РАЗПРЕДЕЛЯТ по чл. 6 ал. 3 т. 8 и 9."
            + " Клаузата „за сметка на осигурителя/държавния бюджет“ е в т. 1-4 — вид осигурен 18,"
            + " не 27. За държавния служител разпределението е по чл. 6 ал. 5а, тоест ТРАНШОВЕТЕ НА"
            + " ВИД ОСИГУРЕН 05, а не 14,80 / 0, 19,80 / 0 и 5,00 / 0 с отворен период.");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ другите две обезщетения на код 27 НЕ са пипани от поправката")
  void theOtherTwoCompensationsOfCode27AreUntouched() {
    List<String> h = BgSeed.header(FILE);
    Map<String, String> got = new TreeMap<>();
    for (String compensation : Arrays.asList("comp_kt_222_1", "comp_zvo_58_3")) {
      for (String[] r : rowsOf("27", compensation)) {
        got.put(
            compensation
                + " · "
                + BgSeed.col(h, r, "fundCode")
                + " · "
                + BgSeed.col(h, r, "bgUpfRegime"),
            BgSeed.col(h, r, "employerRate") + " / " + BgSeed.col(h, r, "employeeRate"));
      }
    }

    Map<String, String> want = new TreeMap<>();
    want.put("comp_kt_222_1 · PENSION · WITH_UPF", "8.2200 / 6.5800");
    want.put("comp_kt_222_1 · PENSION · WITHOUT_UPF", "11.0200 / 8.7800");
    want.put("comp_kt_222_1 · UPF · WITH_UPF", "2.8000 / 2.2000");
    want.put("comp_zvo_58_3 · PENSION · WITH_UPF", "8.2200 / 6.5800");
    want.put("comp_zvo_58_3 · PENSION · WITHOUT_UPF", "11.0200 / 8.7800");
    want.put("comp_zvo_58_3 · UPF · WITH_UPF", "2.8000 / 2.2000");

    assertEquals(
        want,
        got,
        "обезщетенията по чл. 222 ал. 1 КТ и по чл. 58 ал. 3 ЗВО са разпределени по чл. 6 ал. 3"
            + " т. 8 и 9 — и редовете по чл. 106 ал. 2 ЗДСл не бива да ги пипат");
  }

  // ═══ ИДЕНТИФИКАТОРИТЕ ═════════════════════════════════════════════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ разширяването на 09 ПРЕЗАПИСВА четирите заварени реда, не ги преномерира")
  void wideningCode09KeepsItsFourExistingImportIds() {
    List<String> h = BgSeed.header(FILE);
    Map<String, String> byKey = new TreeMap<>();
    for (String[] r : rowsOf("09", STANDARD)) {
      byKey.put(
          BgSeed.col(h, r, "laborCategory")
              + " · "
              + BgSeed.col(h, r, "fundCode")
              + " · "
              + BgSeed.col(h, r, "bgUpfRegime"),
          BgSeed.col(h, r, "importId"));
    }

    // Четирите реда, които 09 носеше и преди разширяването — трета категория, пенсионните два,
    // УПФ и ЗОВ. Те трябва да са СЪЩИТЕ идентификатори, тоест upsert на място.
    Map<String, String> want = new TreeMap<>();
    want.put("3 · PENSION · WITH_UPF", "IC-153-2026");
    want.put("3 · PENSION · WITHOUT_UPF", "IC-154-2026");
    want.put("3 · UPF · WITH_UPF", "IC-155-2026");
    want.put("3 · HEALTH · ANY", "IC-156-2026");

    for (Map.Entry<String, String> e : want.entrySet()) {
      assertEquals(
          e.getValue(),
          byKey.get(e.getKey()),
          "⚠️ ред на вид осигурен 09 е ПРЕНОМЕРИРАН: "
              + e.getKey()
              + ". App Install е upsert по"
              + " importId и НЕ ТРИЕ — нов номер тук оставя стария ред жив в базата, тоест два реда"
              + " за един фонд и DUPLICATE_FUND_ROWS при първото изчисление. Новите номера са само"
              + " за истински новите редове (ОЗМ, ГВРС и категории 1 и 2).");
    }

    // И обратното: нито един ред на 09 не бива да носи номер ИЗВЪН заварените четири и новия
    // диапазон — иначе е взет от чужд код.
    for (Map.Entry<String, String> e : byKey.entrySet()) {
      int n = Integer.parseInt(e.getValue().split("-")[1]);
      if (!want.containsValue(e.getValue()) && (n < 253 || n > 268)) {
        fail(
            "ред на вид осигурен 09 носи неочакван importId "
                + e.getValue()
                + " за "
                + e.getKey()
                + " — новите му редове са IC-253-2026 … IC-268-2026, а заварените са IC-153 … IC-156");
      }
    }
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ поправката на 27 ПРЕИЗПОЛЗВА заварените importId, за да не осиротеят редове")
  void theCorrectionReusesTheExistingImportIds() {
    List<String> h = BgSeed.header(FILE);
    Set<String> ids = new TreeSet<>();
    for (String[] r : rowsOf("27", "comp_zdsl_106_2")) {
      ids.add(BgSeed.col(h, r, "importId"));
    }

    assertEquals(
        new TreeSet<>(
            Arrays.asList(
                "IC-072-2026",
                "IC-073-2026",
                "IC-082-2026",
                "IC-150-2026",
                "IC-151-2026",
                "IC-152-2026")),
        ids,
        "⚠️ App Install е upsert по importId и НЕ ТРИЕ. Трите заварени реда — IC-072, IC-073 и"
            + " IC-082 — трябва да бъдат ОБНОВЕНИ, а не изоставени: нови идентификатори за всичките"
            + " шест реда биха оставили старите три плоски реда живи в базата, тоест девет реда за"
            + " едно основание и DUPLICATE_FUND_ROWS при първото изчисление. Точно това стана с"
            + " IC-115/116/117.");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ всеки importId е уникален и нито един нов ред не е под заварен номер")
  void everyImportIdIsUniqueAndTheNewOnesContinueTheSequence() {
    List<String> h = BgSeed.header(FILE);
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicates = new ArrayList<>();
    int max = 0;

    for (String[] r : BgSeed.rows(FILE)) {
      String id = BgSeed.col(h, r, "importId");
      if (!seen.add(id)) {
        duplicates.add(id);
      }
      max = Math.max(max, Integer.parseInt(id.split("-")[1]));
    }

    assertEquals(
        new ArrayList<String>(),
        duplicates,
        "повторен importId в "
            + FILE
            + " — вносителят е upsert по него, тоест вторият ред"
            + " ПРЕЗАПИСВА първия и един от двата фонда изчезва мълчаливо");
    // ⚠️ ПРАГ, А НЕ ТОЧНО ЧИСЛО — и това не е отслабване.
    //
    // Точното равенство (assertEquals(268, max)) пада В МИГА, в който следваща вълна наистина
    // продължи оттам — тоест би наказвало точно поведението, което се изисква.
    //
    // Инвариантът, който ИМА смисъл тук и е в самото заглавие на теста, е че номерацията НЕ
    // ЗАПОЧВА ОТНАЧАЛО: следващият свободен номер е винаги над тавана на първата вълна. Точният
    // таван е свойство на НАЙ-НОВАТА вълна и се заковава там — виж
    // BgInsuredTypeWave2SeedFileTest#theImportIdSequenceContinuesFromWave1.
    assertTrue(
        max >= 268,
        "най-големият importId е "
            + max
            + ", тоест под тавана на първата вълна (268). Номерацията е започнала отначало и нови"
            + " редове ще ПРЕЗАПИШАТ заварени при App Install, който е upsert по importId");
  }

  /** Помощник: набор фондове, за да са четими картите горе. */
  private static Set<String> funds(String... names) {
    return new TreeSet<>(Arrays.asList(names));
  }

  /** Помощник: един и същ набор фондове за изброените категории труд. */
  private static Map<String, Set<String>> cats(Set<String> funds, String... categories) {
    Map<String, Set<String>> out = new TreeMap<>();
    for (String category : categories) {
      out.put(category, funds);
    }
    return out;
  }
}
