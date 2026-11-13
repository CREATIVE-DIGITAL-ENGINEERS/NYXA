package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СОЦИАЛНИТЕ РАЗХОДИ — ФАЙЛОВАТА ПОЛОВИНА: „вярно ли е ЗАПИСАНО“.
 *
 * <p>Тече без Postgres и без App Install. Базовата половина ({@code BgSocialExpenseDbTest}) пита
 * другото — доставено ли е — и се пропуска с обявена причина, когато таблиците още ги няма.
 *
 * <p><b>⚠️ ЗАЩО ЕВРО СТОЙНОСТИТЕ СЕ СВЕРЯВАТ ТУК, А НЕ В {@code BgEuroConversionTest}.</b> Онзи
 * тест чете {@code bg_payroll_parameter.csv} и списъкът му е за параметри. Правилото обаче е едно и
 * също и е приложено дословно: чл. 12 от Закона за въвеждане на еврото — дели се на пълния курс
 * 1,95583 без закръгляне и без съкращаване на курса; чл. 13 ал. 1 — резултатът се закръгля до
 * втория знак по третия, тоест „половина нагоре“.
 *
 * <p><b>⚠️ И ГРАНИЦАТА НА ТАЗИ ПРОВЕРКА Е СЪЩАТА КАТО ТАМ.</b> Левовият оригинал (60, 200, 2400) и
 * очакваният резултат идват от ЕДНО убеждение — че тези числа са в актовете. Тестът затваря кръга
 * между тях, но НЕ го закотвя за нищо извън себе си: ако чл. 214 ал. 2 някой ден каже друго число,
 * тестът остава зелен и сверява една грешка срещу самата себе си. Актът е котвата и е цитиран
 * ДОСЛОВНО в {@code bgCompanyLegalBasis} на всеки ред. Зеленото тук значи „seed-ът е съгласуван с
 * левовото число в този файл“, а НЕ „левовото число е вярно“.
 *
 * <p><b>⚠️ ЧЕТИРИТЕ АСИМЕТРИИ МЕЖДУ ЗДДФЛ И НЕВД СЕ ПАЗЯТ ПОИМЕННО, а не по брой.</b> Броят минава
 * и при разместени флагове: 25 реда с 6 вдигнати осигурителни флага минава и когато вдигнатите са
 * грешните шест. Затова всяко разминаване е закотвено за КОДА на перото и за нормата, която го
 * налага.
 */
class BgSocialExpenseSeedFileTest {

  private static final String CAPS = "bg_social_expense_cap.csv";
  private static final String SOCIAL = "bg_pay_slip_line_type_social.csv";
  private static final String BASE = "bg_pay_slip_line_type.csv";
  private static final String MANIFEST = "/apps/init-data/manifest/payslip-line-type.yml";

  /** Курсът, чл. 12 ал. 1 — пълната шестцифрена стойност, без закръгляне и без съкращаване. */
  private static final BigDecimal RATE = new BigDecimal("1.95583");

  private static Map<String, Map<String, String>> byKey(String file, String keyColumn) {
    List<String> header = BgSeed.quotedHeader(file);
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      Map<String, String> cells = new LinkedHashMap<>();
      for (String c : header) {
        cells.put(c, BgSeed.col(header, row, c));
      }
      out.put(BgSeed.col(header, row, keyColumn), cells);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> manifestSocialExpenses() throws Exception {
    try (InputStream in = BgSocialExpenseSeedFileTest.class.getResourceAsStream(MANIFEST)) {
      assertNotNull(in, "манифестът " + MANIFEST + " не е в classpath-а");
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      Map<String, Object> expected = (Map<String, Object>) m.get("очаквано");
      return (Map<String, Object>) expected.get("социални_разходи");
    }
  }

  /**
   * ⚠️ МАНИФЕСТЪТ И ФАЙЛОВЕТЕ СЕ СВЕРЯВАТ ПОМЕЖДУ СИ, БЕЗ БАЗА.
   *
   * <p>{@code BgPaySlipLineTypeManifestTest} пита базата и се ПРОПУСКА без Postgres — тоест числата
   * в манифеста могат да се разминат със seed-а и на всяка машина без база билдът остава зелен.
   * Точно това разминаване е тихото: манифестът е записът за това какво ТРЯБВА да пристигне, а
   * seed-ът е онова, което пристига.
   */
  @Test
  @DisplayName("ФАЙЛ · ⚠️ манифестът и двата seed файла казват ЕДНО И СЪЩО за 2xx")
  void theManifestAgreesWithTheSeedFiles() throws Exception {
    Map<String, Object> манифест = manifestSocialExpenses();

    int подКапачка = BgSeed.quotedRows(SOCIAL).size();
    List<String> header = BgSeed.quotedHeader(BASE);
    int булеви = 0;
    for (String[] row : BgSeed.quotedRows(BASE)) {
      if (Integer.parseInt(BgSeed.col(header, row, "number").trim()) / 100 == 2) {
        булеви++;
      }
    }

    assertEquals(манифест.get("под_капачка"), подКапачка, "редове в " + SOCIAL);
    assertEquals(манифест.get("булеви"), булеви, "редове от 2xx в " + BASE);
    assertEquals(
        манифест.get("общо"),
        подКапачка + булеви,
        "⚠️ „общо“ трябва да е сборът на двете, инак манифестът твърди трето число");
    assertEquals(
        манифест.get("с_попълнена_капачка"),
        подКапачка,
        "всеки ред в " + SOCIAL + " носи FK — липсващ FK значи перо, което резолверът НЕ вижда");

    @SuppressWarnings("unchecked")
    Map<String, Object> позиции = (Map<String, Object>) манифест.get("позиции_по_капачка");
    Map<String, Integer> преброени = new TreeMap<>();
    List<String> h = BgSeed.quotedHeader(SOCIAL);
    for (String[] row : BgSeed.quotedRows(SOCIAL)) {
      if (!"true".equals(BgSeed.col(h, row, "bgIsCapExcess"))) {
        преброени.merge(BgSeed.col(h, row, "bgSocialExpenseCap"), 1, Integer::sum);
      }
    }
    assertEquals(new TreeMap<>(позиции).toString(), преброени.toString(), "позиции по капачка");

    @SuppressWarnings("unchecked")
    List<String> кодове = (List<String>) манифест.get("кодове_с_вдигнат_bgIsCompanyExpenseTaxed");
    List<String> вдигнати = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(BASE)) {
      if ("true".equals(BgSeed.col(header, row, "bgIsCompanyExpenseTaxed"))) {
        вдигнати.add(BgSeed.col(header, row, "code"));
      }
    }
    assertEquals(
        кодове, вдигнати, "манифестът изброява ПОИМЕННО кои редове влизат цели в основата");
    assertEquals(манифест.get("с_вдигнат_bgIsCompanyExpenseTaxed"), вдигнати.size());
  }

  private static void empty(String какво, List<String> случаи) {
    if (!случаи.isEmpty()) {
      fail(какво + " — " + случаи.size() + " случая:\n  " + String.join("\n  ", случаи));
    }
  }

  // ═══ КАПАЧКИТЕ ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName("ФАЙЛ · трите капачки са ТОЧНО тези три, с код, период и правно основание")
  void theThreeCapsAreExactlyThose() {
    Map<String, Map<String, String>> caps = byKey(CAPS, "code");
    assertEquals(
        new LinkedHashSet<>(Arrays.asList("DDO_HEALTH_LIFE", "FOOD_VOUCHERS", "ONE_OFF_AID")),
        caps.keySet(),
        "⚠️ Четвърта капачка НЕ се добавя тихо: тя значи нов праг, тоест нова норма, и иска редовете"
            + " на перата, които сочат към нея");

    assertEquals("MONTHLY", caps.get("DDO_HEALTH_LIFE").get("bgPeriodicity"));
    assertEquals("MONTHLY", caps.get("FOOD_VOUCHERS").get("bgPeriodicity"));
    assertEquals(
        "ANNUAL",
        caps.get("ONE_OFF_AID").get("bgPeriodicity"),
        "⚠️ ЗДДФЛ чл. 24 ал. 2 т. 11 дава „ОБЩО до 2400 лв.“ БЕЗ месечен делител, за разлика от"
            + " чл. 213 ал. 2 и чл. 214 ал. 2 ЗКПО, които изрично казват „месечно“. Заредена като"
            + " MONTHLY, капачката щеше да се подновява всеки месец");

    List<String> без = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> e : caps.entrySet()) {
      if (e.getValue().get("bgPersonalLegalBasis").trim().isEmpty()) {
        без.add(e.getKey() + " — липсва основание на личната ос");
      }
      if (e.getValue().get("bgCompanyLegalBasis").trim().isEmpty()) {
        без.add(e.getKey() + " — липсва основание на фирмената ос");
      }
      if (e.getValue().get("validFrom").trim().isEmpty()) {
        без.add(e.getKey() + " — липсва validFrom, а капачката е ТЕМПОРАЛНА");
      }
    }
    empty("капачка без правно основание или без период", без);
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ евро стойностите са ПРЕОБРАЗУВАНИ по чл. 12 и чл. 13 ал. 1, не преписани")
  void theEuroAmountsFollowTheConversionRule() {
    Map<String, Map<String, String>> caps = byKey(CAPS, "code");
    Map<String, String> леваПоКод = new LinkedHashMap<>();
    леваПоКод.put("DDO_HEALTH_LIFE", "60"); // ЗКПО чл. 208 · ЗДДФЛ чл. 24 ал. 2 т. 12
    леваПоКод.put("FOOD_VOUCHERS", "200"); // ЗКПО чл. 209а ал. 1 · чл. 214 ал. 2
    леваПоКод.put("ONE_OFF_AID", "2400"); // ЗДДФЛ чл. 24 ал. 2 т. 11

    List<String> разминати = new ArrayList<>();
    for (Map.Entry<String, String> e : леваПоКод.entrySet()) {
      BigDecimal очаквано = new BigDecimal(e.getValue()).divide(RATE, 2, RoundingMode.HALF_UP);
      BigDecimal заредено = new BigDecimal(caps.get(e.getKey()).get("bgPersonalCapAmount"));
      if (очаквано.compareTo(заредено) != 0) {
        разминати.add(
            e.getKey()
                + " · "
                + e.getValue()
                + " лв. ÷ 1,95583 = "
                + очаквано
                + ", а заредено е "
                + заредено);
      }
    }
    empty(
        "лична капачка, която НЕ съответства на левовия си оригинал (друг курс или друго закръгляне"
            + " дават друга стотинка)",
        разминати);

    // Фирмената ос — там, където съществува, е СЪЩОТО число, и това се проверява ИЗРИЧНО: две
    // различни норми със съвпадащи стойности са точно случаят, в който едната се редактира сама.
    assertEquals(
        "30.68",
        caps.get("DDO_HEALTH_LIFE").get("bgCompanyCapAmount"),
        "ЗКПО чл. 208 дава същите 60 лв. като ЗДДФЛ чл. 24 ал. 2 т. 12 — но от друга норма");
    assertEquals(
        "102.26",
        caps.get("FOOD_VOUCHERS").get("bgCompanyCapAmount"),
        "ЗКПО чл. 214 ал. 2 дава същите 200 лв. като препратката в ЗДДФЛ чл. 24 ал. 2 т. 1 б. „е“");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ еднократната помощ НЯМА фирмена ос — признак, не празна сума")
  void theOneOffAidDeclaresNoCompanyAxis() {
    Map<String, String> aid = byKey(CAPS, "code").get("ONE_OFF_AID");
    assertEquals(
        "false",
        aid.get("bgCompanyTaxApplies"),
        "⚠️ Помощта е в ПАРИ, тоест по § 1 т. 34 ДР ЗКПО не е социален разход в натура и не е обект"
            + " по чл. 204 ал. 1 т. 2 — важи чл. 205. Вдигнат, флагът щеше да ѝ начисли данък върху"
            + " разходите");
    assertEquals(
        "",
        aid.get("bgCompanyCapAmount"),
        "⚠️ И празната сума НЕ е онова, което решава: в базата тя е 0,00, тоест неразличима от"
            + " капачка нула, а капачка нула значи ОБРАТНОТО — целият размер в основата");
    assertEquals(
        "false",
        aid.get("bgWholeAmountOnConditionFail"),
        "чл. 24 ал. 2 т. 11 не поставя условие, тоест няма условие, което да падне");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ условията са ТОЧНО там, където нормите ги слагат — и никъде другаде")
  void theConditionsSitExactlyWhereTheNormsPutThem() {
    Map<String, Map<String, String>> caps = byKey(CAPS, "code");

    // ЧЛ. 208 изисква САМО липса на публични задължения.
    Map<String, String> ddo = caps.get("DDO_HEALTH_LIFE");
    assertEquals("true", ddo.get("bgRequiresNoPublicDebts"), "ЗКПО чл. 208 го изисква изрично");
    assertEquals(
        "false",
        ddo.get("bgRequiresSalaryNotBelowAverage"),
        "⚠️ Чл. 208 НЕ говори за заплата — това е условие само на чл. 209а ал. 1 т. 1. Вдигнато тук,"
            + " едно лице с намалена заплата щеше да загуби и облекчението по чл. 208");
    assertEquals(
        "false",
        ddo.get("bgRequiresLicensedOperator"),
        "⚠️ Чл. 208 НЕ говори за оператор — операторът съществува само за ваучерите (чл. 209а ал. 1"
            + " т. 3). Вдигнато тук, отметка за оператора щеше да обложи и доброволното осигуряване");
    assertEquals("true", ddo.get("bgWholeAmountOnConditionFail"), "чл. 213 ал. 3");

    // ЧЛ. 209а АЛ. 1 изисква ТРИТЕ едновременно.
    Map<String, String> vouchers = caps.get("FOOD_VOUCHERS");
    assertEquals("true", vouchers.get("bgRequiresNoPublicDebts"), "чл. 209а ал. 1 т. 2");
    assertEquals("true", vouchers.get("bgRequiresSalaryNotBelowAverage"), "чл. 209а ал. 1 т. 1");
    assertEquals("true", vouchers.get("bgRequiresLicensedOperator"), "чл. 209а ал. 1 т. 3");
    assertEquals("true", vouchers.get("bgWholeAmountOnConditionFail"), "чл. 214 ал. 3");

    // ЧЛ. 24 АЛ. 2 Т. 11 ЗДДФЛ не поставя нито едно.
    Map<String, String> aid = caps.get("ONE_OFF_AID");
    assertEquals("false", aid.get("bgRequiresNoPublicDebts"));
    assertEquals("false", aid.get("bgRequiresSalaryNotBelowAverage"));
    assertEquals("false", aid.get("bgRequiresLicensedOperator"));
  }

  // ═══ ПЕРАТА ПОД КАПАЧКА ══════════════════════════════════════════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ всяка позиция под капачка е ДВОЙКА — „в рамките“ и „превишение“")
  void everyCappedPositionIsAPair() {
    Map<String, Map<String, String>> rows = byKey(SOCIAL, "code");
    assertEquals(14, rows.size(), "седем позиции × две пера");

    Map<String, Integer> вРамките = new TreeMap<>();
    Map<String, Integer> превишения = new TreeMap<>();
    List<String> лоши = new ArrayList<>();

    for (Map.Entry<String, Map<String, String>> e : rows.entrySet()) {
      Map<String, String> r = e.getValue();
      String cap = r.get("bgSocialExpenseCap");
      if (cap.trim().isEmpty()) {
        лоши.add(e.getKey() + " — ред в този файл БЕЗ капачка, тоест няма какво да го дели");
        continue;
      }
      if (!"true".equals(r.get("bgIsCapExcess"))) {
        вРамките.merge(cap, 1, Integer::sum);
        if (!"MANUAL_AMOUNT".equals(r.get("bgCalculationBase"))) {
          лоши.add(
              e.getKey()
                  + " — редът „в рамките“ трябва да е MANUAL_AMOUNT (операторът въвежда цялото"
                  + " начислено), а е "
                  + r.get("bgCalculationBase"));
        }
      } else {
        превишения.merge(cap, 1, Integer::sum);
        if (!"DERIVED".equals(r.get("bgCalculationBase"))) {
          лоши.add(
              e.getKey()
                  + " — редът „превишение“ трябва да е DERIVED (смята се от резолвера), а е "
                  + r.get("bgCalculationBase"));
        }
      }
      if (!"SOCIAL_EXPENSES".equals(r.get("bgGroup"))) {
        лоши.add(e.getKey() + " — групата не е SOCIAL_EXPENSES");
      }
      if (!"CREDIT".equals(r.get("bgSign"))) {
        лоши.add(e.getKey() + " — знакът не е CREDIT");
      }
      if (!"false".equals(r.get("bgIsPermanentCharacter"))) {
        лоши.add(
            e.getKey()
                + " — вдигнат постоянен характер: социалният разход НЕ е допълнително възнаграждение"
                + " по НСОРЗ чл. 11 или чл. 12 и не бива да влиза в базата за отпуск по КТ чл. 177"
                + " ал. 2");
      }
      int number = Integer.parseInt(r.get("number").trim());
      if (number / 100 != 2) {
        лоши.add(e.getKey() + " · номер " + number + " → група " + (number / 100) + ", а не 2");
      }
    }
    empty("ред под капачка с грешно устройство", лоши);

    assertEquals(вРамките, превишения, "⚠️ по ЕДНО „превишение“ за всяко „в рамките“, и обратното");
    assertEquals(
        3, вРамките.get("DDO_HEALTH_LIFE").intValue(), "доброволно пенсионно, здравно и „Живот“");
    assertEquals(1, вРамките.get("FOOD_VOUCHERS").intValue());
    assertEquals(3, вРамките.get("ONE_OFF_AID").intValue(), "раждане, брак, смърт");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ АСИМЕТРИЯ 1: „Живот“ Е осигурителен доход, а другите две — НЕ")
  void lifeInsuranceIsInsuranceIncomeUnlikeTheOtherTwo() {
    Map<String, Map<String, String>> rows = byKey(SOCIAL, "code");
    for (String code : Arrays.asList("pl_soc_zhivot_v_ramkite", "pl_soc_zhivot_previshenie")) {
      assertEquals(
          "true",
          rows.get(code).get("bgIsInsuranceIncome"),
          "⚠️ "
              + code
              + ": НЕВД чл. 2 ал. 2 изброява ИЗЧЕРПАТЕЛНО и в списъка стоят само доброволното"
              + " ПЕНСИОННО, доброволното ЗДРАВНО и доброволното за БЕЗРАБОТИЦА и професионална"
              + " квалификация — застраховката „Живот“ Я НЯМА. Тя остава в общото правило на чл. 2"
              + " ал. 1 (вноски върху социални разходи в пари ИЛИ В НАТУРА), и то БЕЗ капачка."
              + " Свален, флагът маха вноски, които се дължат");
    }
    for (String code :
        Arrays.asList(
            "pl_soc_ddo_v_ramkite",
            "pl_soc_ddo_previshenie",
            "pl_soc_dzo_v_ramkite",
            "pl_soc_dzo_previshenie")) {
      assertEquals(
          "false",
          rows.get(code).get("bgIsInsuranceIncome"),
          "⚠️ "
              + code
              + ": изключено ИЗЦЯЛО от НЕВД чл. 2 ал. 2, без сума — тоест и превишението не"
              + " е осигурителен доход. Капачката от 30,68 EUR е праг само на данъка");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ АСИМЕТРИЯ 2: помощта при БРАК е осиг. доход, при раждане и смърт — НЕ")
  void marriageAidIsInsuranceIncomeUnlikeBirthAndDeath() {
    Map<String, Map<String, String>> rows = byKey(SOCIAL, "code");
    for (String code :
        Arrays.asList("pl_soc_pomosht_brak_v_ramkite", "pl_soc_pomosht_brak_previshenie")) {
      assertEquals(
          "true",
          rows.get(code).get("bgIsInsuranceIncome"),
          "⚠️ "
              + code
              + ": ЗДДФЛ чл. 24 ал. 2 т. 11 обединява раждане, БРАК и смърт в едно необлагаемо"
              + " число, но НЕВД чл. 2 ал. 2 изключва помощите „за лекарства, при продължително"
              + " боледуване, за раждане, при смърт на член от семейството или при други СЛУЧАЙНО"
              + " НАСТЪПИЛИ събития“ — сключването на граждански брак ГО НЯМА и не е случайно"
              + " настъпило събитие. Изравнено с раждането, това маха вноски, които се дължат");
    }
    for (String code :
        Arrays.asList(
            "pl_soc_pomosht_razhdane_v_ramkite",
            "pl_soc_pomosht_razhdane_previshenie",
            "pl_soc_pomosht_smart_v_ramkite",
            "pl_soc_pomosht_smart_previshenie")) {
      assertEquals(
          "false",
          rows.get(code).get("bgIsInsuranceIncome"),
          code + " — НЕВД чл. 2 ал. 2, изрично");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ваучерите: „в рамките“ е чисто, „превишение“ е облагаемо И осигурително")
  void theVoucherPairCarriesBothAxes() {
    Map<String, Map<String, String>> rows = byKey(SOCIAL, "code");
    Map<String, String> within = rows.get("pl_soc_vaucheri_v_ramkite");
    assertEquals("false", within.get("bgIsTaxable"), "ЗДДФЛ чл. 24 ал. 2 т. 1 б. „е“");
    assertEquals("false", within.get("bgIsInsuranceIncome"), "НЕВД чл. 2 ал. 3 т. 1");
    assertTrue(
        within.get("name").contains("електронен носител"),
        "⚠️ Носителят е в НАИМЕНОВАНИЕТО нарочно: чл. 209 ЗКПО (хартиени ваучери) е ОТМЕНЕН от"
            + " 01.01.2025 г., а НЕВД чл. 2 ал. 3 т. 2 освобождава хартиените само до 30.06.2024 г."
            + " Перо без носител в името кани да се въведе хартиен ваучер като необлагаем");

    Map<String, String> above = rows.get("pl_soc_vaucheri_previshenie");
    assertEquals(
        "true",
        above.get("bgIsTaxable"),
        "⚠️ ЧАКА РЕШЕНИЕ — т. 1 б. „е“ срещу т. 9 на ЗДДФЛ чл. 24 ал. 2. Изключение, записано в"
            + " #whatEntersTheCompanyBaseIsNotTaxedAgainAtThePerson, не решение");
    assertEquals(
        "true",
        above.get("bgIsInsuranceIncome"),
        "НЕВД чл. 2 ал. 3 т. 1 освобождава „ПРИ УСЛОВИЯТА на чл. 209а ал. 1“, тоест и до размера по"
            + " него — превишението остава в чл. 2 ал. 1");
  }

  // ═══ БУЛЕВИТЕ РЕДОВЕ ОТ БАЗОВИЯ ФАЙЛ ═════════════════════════════════════

  @Test
  @DisplayName("ФАЙЛ · единайсетте булеви 2xx реда са БЕЗ капачка и с очакваните флагове")
  void theElevenFlagOnlyRowsAreLoadedAsDeclared() {
    Map<String, Map<String, String>> base = byKey(BASE, "code");
    Set<String> двеxx = new LinkedHashSet<>();
    for (Map.Entry<String, Map<String, String>> e : base.entrySet()) {
      if (Integer.parseInt(e.getValue().get("number").trim()) / 100 == 2) {
        двеxx.add(e.getKey());
      }
    }
    assertEquals(
        11,
        двеxx.size(),
        "⚠️ Базовият файл носи САМО редовете БЕЗ капачка — тези с капачка живеят в "
            + SOCIAL
            + ", защото носят FK колона. Разделението е точно по наличието на препратка, както при"
            + " 5xx и 9xx");

    // Необлагаеми И извън осигурителния доход по изрична норма.
    Map<String, String> нормаПоКод = new LinkedHashMap<>();
    нормаПоКод.put("pl_soc_rabotno_obleklo", "ЗДДФЛ чл. 24 ал. 2 т. 3 · НЕВД чл. 1 ал. 8 т. 4");
    нормаПоКод.put("pl_soc_uniformeno_obleklo", "ЗДДФЛ чл. 24 ал. 2 т. 3 · НЕВД чл. 1 ал. 8 т. 4");
    нормаПоКод.put(
        "pl_soc_predstavitelno_obleklo", "ЗДДФЛ чл. 24 ал. 2 т. 3 · НЕВД чл. 1 ал. 8 т. 4");
    нормаПоКод.put(
        "pl_soc_bezplatna_hrana_285", "ЗДДФЛ чл. 24 ал. 2 т. 1 б. „а“ · НЕВД чл. 1 ал. 8 т. 2");
    нормаПоКод.put("pl_soc_zadalzhitelni_zastrahovki", "ЗДДФЛ чл. 24 ал. 2 т. 13 · НЕВД чл. 2а");
    нормаПоКод.put("pl_soc_pomosht_lechenie", "ЗДДФЛ чл. 24 ал. 2 т. 10 · НЕВД чл. 2 ал. 2");
    нормаПоКод.put("pl_soc_transport", "ЗКПО чл. 210 ал. 1 · ЗДДФЛ чл. 24 ал. 2 т. 9");
    нормаПоКод.put("pl_soc_transport_trudnodostapen", "ЗКПО чл. 210 ал. 3");

    List<String> лоши = new ArrayList<>();
    for (Map.Entry<String, String> e : нормаПоКод.entrySet()) {
      Map<String, String> r = base.get(e.getKey());
      if (r == null) {
        лоши.add(e.getKey() + " — редът липсва изобщо");
        continue;
      }
      if (!"false".equals(r.get("bgIsTaxable")) || !"false".equals(r.get("bgIsInsuranceIncome"))) {
        лоши.add(
            e.getKey()
                + " — освободен по "
                + e.getValue()
                + ", а е зареден облагаем="
                + r.get("bgIsTaxable")
                + " осиг.="
                + r.get("bgIsInsuranceIncome"));
      }
    }
    empty("булев ред, чиито флагове противоречат на изричната си норма", лоши);
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ АСИМЕТРИЯ 3: „в натура“ маха данъка на лицето, но НЕ осигуровките")
  void inKindRemovesTheTaxButNotTheContributions() {
    Map<String, Map<String, String>> base = byKey(BASE, "code");

    Map<String, String> вНатура = base.get("pl_soc_v_natura_oblozhen");
    assertEquals(
        "false",
        вНатура.get("bgIsTaxable"),
        "ЗДДФЛ чл. 24 ал. 2 т. 9 — социалните разходи, ОБЛОЖЕНИ по реда на ЗКПО, не са облагаем"
            + " доход на лицето");
    assertEquals(
        "true",
        вНатура.get("bgIsInsuranceIncome"),
        "⚠️ НАЙ-ЧЕСТО ЧЕТЕНАТА ГРЕШНО НОРМА В ТАЗИ ГРУПА. НЕВД чл. 2 ал. 1 дължи вноски върху"
            + " социалните разходи, изплатени пряко, постоянно или периодично „В ПАРИ ИЛИ В НАТУРА“ —"
            + " натурата е ИЗРИЧНО вътре. Изключения са само изброените в чл. 2 ал. 2 и чл. 1 ал. 8."
            + " Свален, флагът маха вноски върху всяка придобивка в натура извън тези два списъка");
    assertEquals(
        "true",
        вНатура.get("bgIsCompanyExpenseTaxed"),
        "името на реда Е квалификацията: целият размер влиза в месечната ЗКПО основа");

    Map<String, String> неНатура = base.get("pl_soc_ne_v_natura_dohod");
    assertEquals("true", неНатура.get("bgIsTaxable"), "ЗКПО чл. 205 — облага се по реда на ЗДДФЛ");
    assertEquals("true", неНатура.get("bgIsInsuranceIncome"), "НЕВД чл. 2 ал. 1");
    assertEquals(
        "false",
        неНатура.get("bgIsCompanyExpenseTaxed"),
        "⚠️ Не е в натура, тоест НЕ е обект по чл. 204 ал. 1 т. 2 — данък върху разходите не се"
            + " дължи. Вдигнат, същата сума се облага ДВА пъти");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · транспортът е ТРИ хипотези — ал. 1, ал. 2 и ал. 3 на чл. 210, с обратни флагове")
  void transportIsThreeHypothesesNotOne() {
    Map<String, Map<String, String>> base = byKey(BASE, "code");

    assertEquals("false", base.get("pl_soc_transport").get("bgIsCompanyExpenseTaxed"), "ал. 1");

    Map<String, String> кола = base.get("pl_soc_transport_lek_avtomobil");
    assertEquals(
        "true",
        кола.get("bgIsCompanyExpenseTaxed"),
        "⚠️ ЗКПО чл. 210 ал. 2: „Алинея 1 НЕ СЕ ПРИЛАГА, когато транспортът е осъществен с лек"
            + " автомобил или по допълнителни автобусни линии.“ Освобождаването отпада НАПЪЛНО и праг"
            + " не се дава — затова признак, а не капачка");
    assertEquals("true", кола.get("bgIsTaxable"), "облагаем за лицето по признака на перото");
    assertEquals("true", кола.get("bgIsInsuranceIncome"));

    Map<String, String> труден = base.get("pl_soc_transport_trudnodostapen");
    assertEquals(
        "false",
        труден.get("bgIsCompanyExpenseTaxed"),
        "⚠️ Ал. 3 ВРЪЩА облекчението — но при ДВЕ условия, свързани с „и“: трудно достъпен и"
            + " отдалечен район И без разхода дейността да не може да се осъществи");
    assertEquals("false", труден.get("bgIsTaxable"));
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ контра-перото 630 съществува и е DEBIT в 6xx, инак натурата влиза в НЕТОТО")
  void theInKindCounterEntryExists() {
    Map<String, String> row = byKey(BASE, "code").get("pl_soc_v_natura_predadeno");
    assertNotNull(row, "перото 630 липсва — виж защо съществува по-долу");
    assertEquals("630", row.get("number"));
    assertEquals(
        "PAYMENTS",
        row.get("bgGroup"),
        "⚠️ BgPaySlipResolver#netTotal брои всяко начисление извън 5xx, тоест перо от 2xx със знак"
            + " CREDIT ВЛИЗА В НЕТОТО. За социален разход в ПАРИ това е вярно — той е в плика. За"
            + " натурата не е: ваучерът не е стотинка от нетото за получаване. Групата 6xx е мястото"
            + " за „вече предадено извън плика“ — там седи и авансът (600)");
    assertEquals("DEBIT", row.get("bgSign"), "знакът е онова, което го изважда от нетото");
    assertEquals(
        "false",
        row.get("bgIsTaxable"),
        "⚠️ Не е доход и не е удръжка от доход — то е самата придобивка. Вдигнат, флагът щеше да"
            + " извади стойността от данъчната основа, а тя не е там: taxBase вади единствено DEBIT"
            + " от група 5xx");
    assertEquals("false", row.get("bgIsInsuranceIncome"));
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ bgIsCompanyExpenseTaxed е вдигнат на ТОЧНО два реда, и то поименно")
  void exactlyTwoRowsCarryTheWholeAmountIntoTheCompanyBase() {
    Map<String, Map<String, String>> base = byKey(BASE, "code");
    List<String> вдигнати = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> e : base.entrySet()) {
      if ("true".equals(e.getValue().get("bgIsCompanyExpenseTaxed"))) {
        вдигнати.add(e.getKey());
      }
    }
    assertEquals(
        Arrays.asList("pl_soc_transport_lek_avtomobil", "pl_soc_v_natura_oblozhen"),
        вдигнати,
        "⚠️ Броят сам би минал и при разместени флагове, затова тук е ПОИМЕННО. Флаг, вдигнат „за"
            + " всеки случай“ на облеклото или на безплатната храна, щеше да начисли данък върху"
            + " разходите на нещо, което изобщо не е социален разход по чл. 294 КТ — то е задължение"
            + " на работодателя по КТ чл. 296 и чл. 285");
  }

  // ═══ ДВЕТЕ ОСИ НЕ СЕ ЗАСТЪПВАТ ═══════════════════════════════════════════

  /**
   * Редовете, които днес ВСЕ ОЩЕ влизат и в основата по ЗКПО, и в облагаемия доход на лицето —
   * изрично, поименно и с причина. Не са решени: чакат правно решение.
   */
  private static final Map<String, String> ЧАКА_ПОТВЪРЖДЕНИЕ = new LinkedHashMap<>();

  static {
    ЧАКА_ПОТВЪРЖДЕНИЕ.put(
        "pl_soc_vaucheri_previshenie",
        "ЧАКА РЕШЕНИЕ: т. 1 б. „е“ („в размера и по реда на ЗКПО“) като специална норма"
            + " срещу т. 9");
    ЧАКА_ПОТВЪРЖДЕНИЕ.put(
        "pl_soc_transport_lek_avtomobil",
        "ЧАКА РЕШЕНИЕ: първата половина на т. 9 („обложени по реда на ЗКПО“) срещу втората"
            + " (транспортът, „който не подлежи на облагане“)");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ всеки 2xx ред, който влиза в основата по ЗКПО, е НЕОБЛАГАЕМ за лицето (т. 9)")
  void whatEntersTheCompanyBaseIsNotTaxedAgainAtThePerson() {
    // ЗДДФЛ чл. 24 ал. 2 т. 9: в облагаемия трудов доход не се включват „социалните разходи за
    // сметка на работодателя, обложени по реда на ЗКПО“. Ред, който сяда в месечната основа по ЗКПО
    // (данък върху разходите, чл. 216 ал. 1) И в основата по чл. 42 ЗДДФЛ, е обложен два пъти.
    //
    // В основата по ЗКПО влизат два вида редове:
    //   · без капачка — с вдигнат bgIsCompanyExpenseTaxed (BgSocialExpenseService#monthlyTaxBase);
    //   · под капачка с ФИРМЕНА ОС (bgCompanyTaxApplies) — и двата реда на позицията: „в рамките“
    //     носи цялото начислено и е входът, „превишение“ носи остатъка. Еднократната помощ няма
    //     фирмена ос (чл. 205 ЗКПО) и нейното превишение законно е доход на лицето.
    Map<String, Map<String, String>> caps = byKey(CAPS, "code");
    Map<String, String> вОсноватаПоЗкпо = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> e : byKey(SOCIAL, "code").entrySet()) {
      Map<String, String> cap = caps.get(e.getValue().get("bgSocialExpenseCap"));
      if (cap != null && "true".equals(cap.get("bgCompanyTaxApplies"))) {
        вОсноватаПоЗкпо.put(e.getKey(), e.getValue().get("bgIsTaxable"));
      }
    }
    for (Map.Entry<String, Map<String, String>> e : byKey(BASE, "code").entrySet()) {
      if ("true".equals(e.getValue().get("bgIsCompanyExpenseTaxed"))) {
        вОсноватаПоЗкпо.put(e.getKey(), e.getValue().get("bgIsTaxable"));
      }
    }
    assertTrue(
        вОсноватаПоЗкпо.keySet().containsAll(ЧАКА_ПОТВЪРЖДЕНИЕ.keySet()),
        "изключение за ред, който изобщо не е в основата по ЗКПО: " + ЧАКА_ПОТВЪРЖДЕНИЕ.keySet());

    List<String> двойно = new ArrayList<>();
    List<String> излишниИзключения = new ArrayList<>();
    for (Map.Entry<String, String> e : вОсноватаПоЗкпо.entrySet()) {
      boolean облагаем = !"false".equals(e.getValue());
      if (ЧАКА_ПОТВЪРЖДЕНИЕ.containsKey(e.getKey())) {
        if (!облагаем) {
          излишниИзключения.add(
              e.getKey()
                  + " — вече е необлагаем; махнете го от ЧАКА_ПОТВЪРЖДЕНИЕ заедно с решението");
        }
      } else if (облагаем) {
        двойно.add(
            e.getKey()
                + " — в основата по ЗКПО И облагаем за лицето (bgIsTaxable="
                + e.getValue()
                + "), а ЗДДФЛ чл. 24 ал. 2 т. 9 изключва обложеното по ЗКПО");
      }
    }
    empty("ред, обложен два пъти — с данък върху разходите и с авансов данък", двойно);
    empty("изключение, което вече не е нужно", излишниИзключения);
  }

  @Test
  @DisplayName("ФАЙЛ · превишенията на ДДО/ДЗО/„Живот“ (201/203/205) са необлагаеми по т. 9")
  void theDdoHealthLifeExcessIsNotTaxedAtThePerson() {
    Map<String, Map<String, String>> rows = byKey(SOCIAL, "code");
    for (String code :
        Arrays.asList(
            "pl_soc_ddo_previshenie", "pl_soc_dzo_previshenie", "pl_soc_zhivot_previshenie")) {
      Map<String, String> r = rows.get(code);
      assertEquals(
          "false",
          r.get("bgIsTaxable"),
          code
              + ": превишението над 60 лв. е основата по ЗКПО чл. 213 ал. 2 — обложено по ЗКПО,"
              + " тоест извън облагаемия доход по ЗДДФЛ чл. 24 ал. 2 т. 9");
      assertTrue(
          r.get("bgLegalBasis").contains("ЗДДФЛ чл. 24 ал. 2 т. 9"),
          code
              + ": основанието трябва да назовава т. 9, не чл. 24 ал. 1 — "
              + r.get("bgLegalBasis"));
      assertEquals("true", r.get("bgIsCapExcess"), code + ": редът е превишение по признак");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ „превишение“ е признак bgIsCapExcess и съвпада ТОЧНО с DERIVED 2xx")
  void theCapExcessFlagIsExactlyTheDerivedSocialExpenseRows() {
    // Двата пазача четат различни неща: BgSocialExpenseService — bgIsCapExcess (кой ред НЕ е вход
    // на
    // резолвера), а отказът на ръчно въведения ред — DERIVED в 2xx (кое перо не се въвежда на
    // ръка).
    // Разминат ли се, или вход се отказва, или изход се подава втори път.
    List<String> лоши = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> e : byKey(SOCIAL, "code").entrySet()) {
      Map<String, String> r = e.getValue();
      boolean derived = "DERIVED".equals(r.get("bgCalculationBase"));
      boolean excess = "true".equals(r.get("bgIsCapExcess"));
      if (!"true".equals(r.get("bgIsCapExcess")) && !"false".equals(r.get("bgIsCapExcess"))) {
        лоши.add(e.getKey() + " — bgIsCapExcess е празен, а колоната решава вход или изход");
      } else if (derived != excess) {
        лоши.add(
            e.getKey()
                + " — bgCalculationBase="
                + r.get("bgCalculationBase")
                + ", bgIsCapExcess="
                + r.get("bgIsCapExcess"));
      }
    }
    for (Map.Entry<String, Map<String, String>> e : byKey(BASE, "code").entrySet()) {
      Map<String, String> r = e.getValue();
      if (Integer.parseInt(r.get("number").trim()) / 100 == 2
          && "DERIVED".equals(r.get("bgCalculationBase"))) {
        лоши.add(e.getKey() + " — DERIVED 2xx без капачка: няма какво да го смята");
      }
    }
    empty("2xx ред, при който „смята се от системата“ и „превишение“ се разминават", лоши);
  }
}
