package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА половина за морските лица: вярно ли е ЗАПИСАНО. Без Postgres, без App Install.
 *
 * <p>Двойникът ѝ {@code BgSeafarerContributionManifestTest} пита другото — ДОСТАВЕНО ли е — и се
 * пропуска с обявена причина, когато не е. Двата въпроса са различни и затова са различни тестове:
 * слети в един, отговорът „не е пускан App Install“ маскира отговора „ставката е сгрешена“.
 */
class BgSeafarerSeedFileTest {

  private static final String MANIFEST = "/apps/init-data/manifest/seafarers-2026.yml";
  private static final String CONTRIBUTIONS = "bg_insurance_contribution.csv";
  private static final String INSURED_TYPES = "bg_insured_type.csv";
  private static final String CODE = "92";

  /** Сборовете по подразбиране, БЕЗ ТЗПБ, по категория труд. */
  private static final Map<String, String> DEFAULT_TOTAL = new LinkedHashMap<>();

  static {
    DEFAULT_TOTAL.put("1", "38.3000");
    DEFAULT_TOTAL.put("2", "33.3000");
    DEFAULT_TOTAL.put("3", "23.3000");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> manifest() throws Exception {
    try (InputStream in = BgSeafarerSeedFileTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null, "манифестът " + MANIFEST + " не е в classpath-а");
      return new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
    }
  }

  private static List<String> head() {
    return BgSeed.header(CONTRIBUTIONS);
  }

  private static List<String[]> seafarerRows() {
    List<String[]> out = new ArrayList<>();
    List<String> h = head();
    for (String[] r : BgSeed.rows(CONTRIBUTIONS)) {
      if (CODE.equals(BgSeed.col(h, r, "bgInsuredTypeCode"))) {
        out.add(r);
      }
    }
    return out;
  }

  private static BigDecimal dec(Object v) {
    return new BigDecimal(String.valueOf(v)).setScale(4);
  }

  // ─── А1 · двайсет и трите реда срещу манифеста ───────────────────────────────────────────────

  @Test
  @DisplayName("ФАЙЛ · двайсет и трите реда за морско лице отговарят на манифеста")
  @SuppressWarnings("unchecked")
  void theSeedFileCarriesTheTwentyThreeRows() throws Exception {
    Map<String, Object> expected = (Map<String, Object>) manifest().get("редове");
    List<Map<String, Object>> want = (List<Map<String, Object>>) expected.get("ставки");
    List<String> h = head();

    Map<String, String[]> found = new TreeMap<>();
    for (String[] r : seafarerRows()) {
      found.put(BgSeed.col(h, r, "importId"), r);
    }

    if (found.size() != want.size()) {
      fail(
          "редове за вид осигурен "
              + CODE
              + " в seed-а: "
              + found.size()
              + ", а манифестът очаква "
              + want.size()
              + " — намерени: "
              + found.keySet());
    }

    List<String> problems = new ArrayList<>();
    for (Map<String, Object> w : want) {
      String id = String.valueOf(w.get("importId"));
      String[] r = found.get(id);
      if (r == null) {
        problems.add(id + ": липсва в seed-а");
        continue;
      }
      check(problems, id, "категория", w.get("кат"), BgSeed.col(h, r, "laborCategory"));
      check(problems, id, "фонд", w.get("фонд"), BgSeed.col(h, r, "fundCode"));
      check(problems, id, "режим", w.get("режим"), BgSeed.col(h, r, "bgUpfRegime"));
      check(problems, id, "обхват", w.get("обхват"), BgSeed.col(h, r, "bgSolScope"));

      BigDecimal rate = new BigDecimal(BgSeed.col(h, r, "employeeRate"));
      if (dec(w.get("лице")).compareTo(rate) != 0) {
        problems.add(id + ": ставка за лицето " + rate + ", а манифестът казва " + w.get("лице"));
      }
      // ⚠️ ЧЛ. 4А АЛ. 1 — „ИЗЦЯЛО ЗА СВОЯ СМЕТКА“. Нула изключения, затова се проверява всеки ред.
      if (new BigDecimal(BgSeed.col(h, r, "employerRate")).signum() != 0) {
        problems.add(
            id
                + ": ставка за ОСИГУРИТЕЛЯ "
                + BgSeed.col(h, r, "employerRate")
                + " — чл. 4а ал. 1 КСО иска вноските да са изцяло за сметка на лицето");
      }
    }

    if (!problems.isEmpty()) {
      fail("seed-ът се разминава с манифеста:\n  " + String.join("\n  ", problems));
    }
  }

  private static void check(List<String> into, String id, String what, Object want, String got) {
    if (!String.valueOf(want).equals(got)) {
      into.add(id + ": " + what + " е " + got + ", а манифестът казва " + want);
    }
  }

  // ─── А2 · подразбиращият се набор ────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ подразбиращото се по чл. 4а ал. 2 е „БЕЗ безработица“ — 23,3 / 38,3 / 33,3")
  void theDefaultSetExcludesTheElectiveUnemploymentRow() {
    List<String> h = head();

    // ⚠️ ПРИЗНАКЪТ ЗА ПОДРАЗБИРАЩ СЕ РЕД Е bgSolScope = ANY. Филтърът в
    // BgInsuranceCalculationService взима „bgSolScope = 'ANY' OR bgSolScope = :scope“, тоест ред с
    // друга стойност НЕ влиза, докато заявката не я поиска изрично. Никой не я иска днес — и точно
    // това прави подразбирането вярно.
    List<String> misplaced = new ArrayList<>();
    for (String[] r : seafarerRows()) {
      String fund = BgSeed.col(h, r, "fundCode");
      String scope = BgSeed.col(h, r, "bgSolScope");
      String id = BgSeed.col(h, r, "importId");
      boolean elective = "UNEMPLOYMENT".equals(fund);

      if (elective && "ANY".equals(scope)) {
        misplaced.add(
            id
                + ": фонд Безработица с обхват ANY — влиза в подразбирането и начислява 1 на сто на"
                + " ВСЯКО морско лице. Чл. 4а ал. 2 прави осигуряването за безработица избор, а"
                + " неупражненият избор не поражда задължение.");
      }
      if (!elective && !"ANY".equals(scope)) {
        misplaced.add(
            id
                + ": фонд "
                + fund
                + " с обхват "
                + scope
                + " — излиза от подразбирането, а не е"
                + " избираем по чл. 4а. Задължителните фондове се дължат без избор.");
      }
    }
    if (!misplaced.isEmpty()) {
      fail("подразбиращият се набор е сгрешен:\n  " + String.join("\n  ", misplaced));
    }

    // И сборът, който излиза от подразбиращия се набор — числото, което лицето наистина ще плати
    // ПО КСО. ТЗПБ е извън сбора: справката на НАП дава ДОО и ДЗПО, а 19,8 + 3,5 = 23,3 точно, без
    // остатък за него.
    // ⚠️ И ЗОВ Е ИЗВЪН СБОРА, ПО ДРУГА ПРИЧИНА — НЕ ЗАЩОТО ЛИЦЕТО НЕ ГО ПЛАЩА. Здравната вноска е
    // по ЗЗО чл. 40 ал. 1 т. 2а, тоест ДРУГ ЗАКОН и друга справка: тези 8 на сто НЕ са ДОО и НЕ са
    // ДЗПО, а трите числа тук са сверени срещу справката за ДОО и ДЗПО. Влезеше ли HEALTH в сбора,
    // 23,3 щеше да стане 31,3 и тестът щеше да поиска НОВИ константи, сверени срещу НЕСЪЩЕСТВУВАЩА
    // справка. Изключването е ГРАНИЦА НА ТВЪРДЕНИЕТО, а не премахване: че лицето плаща и ЗОВ изцяло
    // за своя сметка се проверява в А1 (employerRate = 0 на ВСЕКИ ред, нула изключения).
    for (Map.Entry<String, String> e : DEFAULT_TOTAL.entrySet()) {
      for (String cohort : Arrays.asList("WITH_UPF", "WITHOUT_UPF")) {
        BigDecimal sum = BigDecimal.ZERO.setScale(4);
        for (String[] r : seafarerRows()) {
          String regime = BgSeed.col(h, r, "bgUpfRegime");
          String fund = BgSeed.col(h, r, "fundCode");
          if (!BgSeed.col(h, r, "laborCategory").equals(e.getKey())
              || !"ANY".equals(BgSeed.col(h, r, "bgSolScope"))
              || "TZPB".equals(fund)
              || "HEALTH".equals(fund)
              || !("ANY".equals(regime) || cohort.equals(regime))) {
            continue;
          }
          sum = sum.add(new BigDecimal(BgSeed.col(h, r, "employeeRate")));
        }
        if (sum.compareTo(new BigDecimal(e.getValue())) != 0) {
          fail(
              "подразбиращият се сбор за категория "
                  + e.getKey()
                  + ", кохорта "
                  + cohort
                  + " е "
                  + sum
                  + ", а трябва да е "
                  + e.getValue()
                  + " (ДОО и ДЗПО; без ТЗПБ, без безработица, без ЗОВ)");
        }
      }
    }
  }

  // ─── А3 · ТЗПБ като ИНВАРИАНТ, а не факт за днес ─────────────────────────────────────────────

  /**
   * ⚠️ ТОЧНИЯТ СПИСЪК на видовете осигурени, чиито вноски са ИЗЦЯЛО за сметка на лицето и които
   * въпреки това нямат собствен ТЗПБ ред. Формат: {@code код — причина}.
   *
   * <p>Кодове 12 и 13: самоосигуряващите се НЕ се осигуряват за трудова злополука и професионална
   * болест изобщо — чл. 4 ал. 3 КСО изброява инвалидност поради общо заболяване, старост и смърт, а
   * ОЗМ е по избор (ал. 4). Тоест липсата на ред там е вярна, а не пропусната.
   *
   * <p>⚠️ КОД 13 Е ВТОРИЯТ ВИД ОСИГУРЕН БЕЗ НИТО ЕДИН ДЯЛ ЗА ОСИГУРИТЕЛЯ — тоест точно случаят, за
   * който резервният път по КИД е двойно грешен: чужда ставка (0,4–1,2 вместо 1,1) на грешен
   * платец. Нов такъв код пада тук още при добавянето, преди да е стигнал до нечий фиш.
   */
  private static final Set<String> NO_TZPB_ON_PURPOSE =
      new HashSet<>(
          Arrays.asList(
              "12", // самоосигуряващи се — ТЗПБ не се дължи (чл. 4 ал. 3 КСО)
              "13", // регистрирани земеделски стопани — същото основание, чл. 4 ал. 3 т. 4
              "41", // диворастящи гъби и плодове — чл. 4а1 ал. 1 изброява само инвалидност,
              // старост и смърт; ТЗПБ не е сред тях
              "86", // международни органи и организации — НАП разд. 14 дава само Пенсии и УПФ
              "87", // изпратени в чужбина от посредник — разд. 13, същият тесен набор
              "91", // съпрузи на командировани дипломати — разд. 13, същият тесен набор
              "96")); // съпрузи по чл. 4 ал. 9 — разд. 15 дава Пенсии и ОЗМ, ТЗПБ го няма

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: вид осигурен изцяло за сметка на лицето НЕ виси на ТЗПБ по КИД")
  void everyFullyPersonBorneInsuredTypeCarriesItsOwnAccidentFundRow() {
    List<String> h = head();

    // ЗАЩО ТОЧНО ТОЗИ ИНВАРИАНТ, А НЕ „всеки вид осигурен има ТЗПБ ред“. Последното е НЕВЯРНО и по
    // устройство: за обикновения работник ТЗПБ е за сметка на осигурителя по ставка според
    // икономическата дейност (чл. 6 ал. 6 КСО), тя идва от bg_tzpb_rate през заявката и затова
    // редове НЯМА при десет от тринайсетте кода. Тест, който го изисква, би бил червен от първия
    // ден.
    //
    // ОПАСНОСТТА Е ДРУГА И Е ТЯСНА. BgContributionResolver добавя ставката от заявката на
    // Payer.EMPLOYER, когато наборът редове няма собствен ТЗПБ ред. За вид осигурен, чиито вноски
    // са ИЗЦЯЛО за сметка на лицето, това е двойно грешно: и ставката е чужда (по КИД, 0,4–1,2
    // вместо 1,1), и платецът е грешен (осигурител вместо лице) — при това мълчаливо, защото и
    // двете изглеждат правдоподобно.
    //
    // Оттам инвариантът: щом ДАННИТЕ казват „никъде няма дял за осигурителя“, ТЗПБ не бива да се
    // оставя на онзи резервен път. Следващият, който добави такъв вид осигурен без ТЗПБ ред, ще го
    // научи ТУК, а не от грешна ставка на грешния платец в нечий фиш.
    Map<String, List<String[]>> byType = new LinkedHashMap<>();
    for (String[] r : BgSeed.rows(CONTRIBUTIONS)) {
      byType.computeIfAbsent(BgSeed.col(h, r, "bgInsuredTypeCode"), k -> new ArrayList<>()).add(r);
    }

    List<String> exposed = new ArrayList<>();
    Set<String> unusedAllowances = new LinkedHashSet<>(NO_TZPB_ON_PURPOSE);

    for (Map.Entry<String, List<String[]>> e : byType.entrySet()) {
      String type = e.getKey();
      boolean anyEmployerShare = false;
      boolean hasTzpb = false;
      for (String[] r : e.getValue()) {
        if (new BigDecimal(BgSeed.col(h, r, "employerRate")).signum() > 0) {
          anyEmployerShare = true;
        }
        if ("TZPB".equals(BgSeed.col(h, r, "fundCode"))) {
          hasTzpb = true;
        }
      }
      if (anyEmployerShare) {
        continue; // има осигурител — резервният път по КИД е законният (чл. 6 ал. 6 КСО)
      }
      unusedAllowances.remove(type);
      if (!hasTzpb && !NO_TZPB_ON_PURPOSE.contains(type)) {
        exposed.add(
            "вид осигурен "
                + type
                + ": всичките "
                + e.getValue().size()
                + " реда са изцяло за сметка на лицето, но ТЗПБ ред НЯМА — резолверът ще вземе"
                + " ставката по КИД и ще я запише на РАБОТОДАТЕЛЯ");
      }
      if (hasTzpb && NO_TZPB_ON_PURPOSE.contains(type)) {
        exposed.add(
            "вид осигурен "
                + type
                + " вече НОСИ ТЗПБ ред, но още стои в списъка с изключения —"
                + " махни го оттам, инак списъкът лъже");
      }
    }

    if (!exposed.isEmpty()) {
      fail(
          "видове осигурени, изложени на ТЗПБ по КИД на грешния платец:\n  "
              + String.join("\n  ", exposed)
              + "\n  Или добави собствен ТЗПБ ред, или впиши кода в NO_TZPB_ON_PURPOSE С ПРИЧИНА.");
    }

    // Обратната посока: изключение, което вече не важи, трябва да се маха, а не да залежава.
    if (!unusedAllowances.isEmpty()) {
      fail(
          "кодове в NO_TZPB_ON_PURPOSE, които вече не са изцяло за сметка на лицето (или ги няма"
              + " в данните): "
              + unusedAllowances
              + " — списъкът трябва да казва истината");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ТЗПБ за морско лице е плоско 1,1 на сто и за трите категории")
  void theAccidentFundRateIsFlatAcrossAllThreeCategories() {
    List<String> h = head();
    Map<String, String> byCategory = new TreeMap<>();
    for (String[] r : seafarerRows()) {
      if ("TZPB".equals(BgSeed.col(h, r, "fundCode"))) {
        byCategory.put(BgSeed.col(h, r, "laborCategory"), BgSeed.col(h, r, "employeeRate"));
      }
    }
    if (byCategory.size() != 3) {
      fail("ТЗПБ редове за " + byCategory.size() + " категории вместо 3: " + byCategory);
    }
    for (Map.Entry<String, String> e : byCategory.entrySet()) {
      if (new BigDecimal(e.getValue()).compareTo(new BigDecimal("1.1000")) != 0) {
        fail(
            "ТЗПБ за категория "
                + e.getKey()
                + " е "
                + e.getValue()
                + ", а чл. 4а ал. 3 т. 5 КСО дава 1,1 на сто плоско за трите категории — не по КИД");
      }
    }
  }

  // ─── А4 · долният праг на вида осигурен ──────────────────────────────────────────────────────

  @Test
  @DisplayName("ФАЙЛ · морското лице има долен праг MIN_INCOME_SOL и не прилага МОД по НКПД")
  void theSeafarerInsuredTypeCarriesTheSelfInsuredFloor() {
    List<String> h = BgSeed.header(INSURED_TYPES);
    String[] row = null;
    for (String[] r : BgSeed.rows(INSURED_TYPES)) {
      if (CODE.equals(BgSeed.col(h, r, "code"))) {
        row = r;
      }
    }
    if (row == null) {
      fail("вид осигурен " + CODE + " липсва в " + INSURED_TYPES);
    }

    // ⚠️ ДВЕТЕ ЗАЕДНО, НЕ ПООТДЕЛНО. Чл. 4а ал. 1: доходът се ИЗБИРА между минималния и максималния
    // за САМООСИГУРЯВАЩИТЕ СЕ. Оттам и двете: МОД по НКПД не се прилага, но праг ИМА. Стоеше NONE,
    // тоест никакъв праг — морско лице можеше да се осигури под 550,66 евро без никой да се обади.
    String applies = BgSeed.col(h, row, "bgAppliesMod");
    String floor = BgSeed.col(h, row, "bgFloorType");
    if (!"false".equals(applies) || !"MIN_INCOME_SOL".equals(floor)) {
      fail(
          "вид осигурен "
              + CODE
              + " (bgAppliesMod | bgFloorType).\n  очаква се : false |"
              + " MIN_INCOME_SOL\n  намерено  : "
              + applies
              + " | "
              + floor);
    }
  }

  // ─── А5 · аритметиката на двайсетте комбинации ───────────────────────────────────────────────

  @Test
  @DisplayName("Двайсетте комбинации: изведеното от закона съвпада със справката на НАП")
  @SuppressWarnings("unchecked")
  void theTwentyCombinationsAgreeWithTheNapReference() throws Exception {
    List<Map<String, Object>> rows = (List<Map<String, Object>>) manifest().get("комбинации");
    if (rows == null || rows.size() != 20) {
      fail("очакват се точно двайсет комбинации, а са " + (rows == null ? 0 : rows.size()));
    }

    List<String> mismatched = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      // Сборът се смята ТУК, от компонентите по чл. 4а ал. 3 и чл. 157 ал. 1 — не се чете наготово.
      BigDecimal sum =
          dec(r.get("пенсии"))
              .add(dec(r.get("озм")))
              .add(dec(r.get("безр")))
              .add(dec(r.get("упф")))
              .add(dec(r.get("ппф")));
      BigDecimal derived = dec(r.get("изведено"));
      BigDecimal nap = dec(r.get("справка_нап"));

      // ⚠️ И ДВЕТЕ ПОСОКИ — съвпадение само с едното значи напасване към свидетеля под ръка.
      if (sum.compareTo(derived) != 0 || sum.compareTo(nap) != 0) {
        mismatched.add(
            String.format(
                "ред %s (кат. %s, %s, безработица: %s, избор: %s): смятам %s · записано изведено %s"
                    + " · справка НАП %s · разлика спрямо справката %s",
                r.get("No"),
                r.get("кат"),
                r.get("роден"),
                r.get("безработица"),
                r.get("избор"),
                sum.toPlainString(),
                derived.toPlainString(),
                nap.toPlainString(),
                sum.subtract(nap).toPlainString()));
      }
    }

    if (!mismatched.isEmpty()) {
      fail(
          "комбинации, които НЕ се връзват ("
              + mismatched.size()
              + " от 20). ⚠️ НЕ напасвай данните"
              + " към справката и НЕ напасвай справката към сметката:\n  "
              + String.join("\n  ", mismatched));
    }
  }

  @Test
  @DisplayName(
      "⚠️ Достижимостта е ЗАПИСАНА за всяка от двайсетте — чл. 4в вече е достижим, чл. 4а ал. 2 не е")
  @SuppressWarnings("unchecked")
  void theReachableCombinationsAreExactlyTheDefaultOnes() throws Exception {
    List<Map<String, Object>> rows = (List<Map<String, Object>>) manifest().get("комбинации");

    List<String> wrong = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      String reach = String.valueOf(r.get("достижимост"));
      boolean unemployment = "да".equals(String.valueOf(r.get("безработица")));
      boolean art4c = "чл_4в".equals(String.valueOf(r.get("избор")));

      // ⚠️ ЧЛ. 4В ВЕЧЕ Е ДОСТИЖИМ. Указание т. 25 и Д6 т. 13 декларират избора по чл. 4в в
      // ТОЧКАТА НА ППФ, тоест редът за ППФ дава верните числа и без ос за самия избор.
      // Немоделиран остава само ПОЛУЧАТЕЛЯТ (bgRecipient) — една колона, не цял фиш.
      String expected =
          art4c ? "достижим_чл_4в" : unemployment ? "неподдържан_чл_4а_ал_2" : "по_подразбиране";
      if (!expected.equals(reach)) {
        wrong.add("ред " + r.get("No") + ": достижимост " + reach + ", а следва да е " + expected);
      }
      // Достижимите трябва да дават точно подразбиращия се сбор за категорията си.
      if ("по_подразбиране".equals(expected)) {
        String want = DEFAULT_TOTAL.get(String.valueOf(r.get("кат")));
        if (dec(r.get("изведено")).compareTo(new BigDecimal(want)) != 0) {
          wrong.add(
              "ред "
                  + r.get("No")
                  + ": достижим, но дава "
                  + r.get("изведено")
                  + " вместо подразбиращото се "
                  + want);
        }
      }
    }
    if (!wrong.isEmpty()) {
      fail("достижимостта е записана грешно:\n  " + String.join("\n  ", wrong));
    }
  }
}
