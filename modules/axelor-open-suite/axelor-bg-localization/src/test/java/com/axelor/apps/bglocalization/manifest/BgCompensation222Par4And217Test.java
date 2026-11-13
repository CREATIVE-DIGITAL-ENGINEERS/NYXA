package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ЧЛ. 222 АЛ. 4 И ЧЛ. 217 КТ — характеризиращ тест СЪС ЧИСЛА.
 *
 * <p><b>Защо е отделен от {@code BgTerminationCompensationTreatmentTest}.</b> Онзи клас пази КАК СЕ
 * ТРЕТИРА платеното — двойката флагове и кодът за Д1. Този пази КОЛКО СЕ ПЛАЩА и оттам мери {@link
 * BgCompensationResolver#compute}, тоест чиста функция с вход и изход. Двата въпроса се четат от
 * един и същи ред и точно затова стоят в различни класове: слети, „сумата е вярна“ и „третирането е
 * вярно“ падат заедно и не се знае кое от двете е счупено.
 *
 * <p><b>⚠️ СВИДЕТЕЛЯТ НОСИ ЧИСЛА, а не само флагове.</b> Модулът няма mock-подпора — заявка към
 * базата се сверява само структурно. Затова правилото, което иска свидетел с числа, се проверява
 * там, където е чиста функция: {@code compute(baseKind, quantity, monthlyGross, baseWorkingDays)}.
 * Твърдение от вида „смята се като ал. 3“ без число минава зелено и при сгрешена аритметика.
 *
 * <p><b>Двата случая са нарочно противоположни.</b> Чл. 222 ал. 4 СЕ СМЯТА (2 × БТВ, по механизма
 * на ал. 3); чл. 217 НЕ СЕ СМЯТА и връща {@code null} — периодът му се определя от факти, а
 * аритметиката е WS-3c. {@code null} тук значи „не може да се изведе от подадените данни“, НЕ нула:
 * нулата би минала за изчислена сума и заповедта би се подписала с нея.
 *
 * <p>Тече без Postgres — файловата половина на каталога плюс чиста функция.
 */
class BgCompensation222Par4And217Test {

  private static final String CATALOGUE = "bg_compensation_type.csv";
  private static final String PAY_SLIP_LINES = "bg_pay_slip_line_type_compensation.csv";

  private static Map<String, Map<String, String>> read(String file) {
    List<String> header = BgSeed.quotedHeader(file);
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      assertEquals(
          header.size(),
          row.length,
          "ред с различен брой колони от заглавния — ще влезе разместен: " + row[0]);
      Map<String, String> cells = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        cells.put(header.get(i), row[i]);
      }
      out.put(cells.get("code"), cells);
    }
    return out;
  }

  // ───────────────────────── чл. 222 ал. 4 · смята се като ал. 3 ─────────────────────────

  /**
   * ⚠️ АЛ. 4 СЕ СМЯТА ПО АЛ. 3, И ТОВА Е ПРОВЕРИМО С ЧИСЛА.
   *
   * <p>Ал. 4 не носи собствен размер — тя казва „Алинея 3 се прилага“. Тоест носителят ѝ ({@code
   * comp_kt_222_3}) трябва да стои на {@code BTV_MONTHS} с {@code baseMonths = 2.00}, а {@code
   * compute} върху тези две стойности да дава точно 2 × БТВ.
   *
   * <p>1840,00 × 2 = 3680,00. Числото е произволно, но фиксирано: смисълът е, че множителят идва от
   * seed-а, а не от теста.
   */
  @Test
  @DisplayName("⚠️ чл. 222 ал. 4 · смята се като ал. 3 — 2 × БТВ, от seed-а")
  void paragraphFourIsComputedExactlyAsParagraphThree() {
    Map<String, String> row = read(CATALOGUE).get("comp_kt_222_3");
    assertNotNull(row, "comp_kt_222_3 липсва — носителят на ал. 3 И ал. 4");

    assertEquals(
        BgCompensationResolver.BTV_MONTHS,
        row.get("baseKind"),
        "ал. 4 се плаща по ал. 3, тоест базата е БТВ × месеци");
    BigDecimal months = new BigDecimal(row.get("baseMonths"));
    assertEquals(
        0,
        months.compareTo(new BigDecimal("2.00")),
        "чл. 222 ал. 3 дава 2 БТВ в общия случай (6 при 10 г. стаж — не е изчислимо днес)");

    BigDecimal gross = new BigDecimal("1840.00");
    BigDecimal computed = BgCompensationResolver.compute(row.get("baseKind"), months, gross, null);
    assertNotNull(
        computed, "ал. 3/ал. 4 СЕ смятат — null тук значи счупен механизъм, не липсващ вход");
    assertEquals(
        0,
        computed.compareTo(new BigDecimal("3680.00")),
        "2 × 1840,00 = 3680,00, а сметката даде " + computed);
  }

  /** ⚠️ ТРЕТИРАНЕТО НА АЛ. 4 — необлагаемо и без вноски, тоест същото като ал. 3. */
  @Test
  @DisplayName("⚠️ чл. 222 ал. 4 · без вноски и без данък — НЕВД т. 7 пряко, ЗДДФЛ по препращане")
  void paragraphFourIsNeitherInsurableNorTaxable() {
    Map<String, String> row = read(CATALOGUE).get("comp_kt_222_3");
    assertNotNull(row);

    assertFalse(
        Boolean.parseBoolean(row.get("socialContributionsDue")),
        "НЕВДПОВ чл. 1 ал. 8 т. 7 назовава „чл. 222, ал. 2, 3 и 4“ ПРЯКО — вноски не се дължат");
    assertFalse(
        Boolean.parseBoolean(row.get("isTaxable")),
        "ЗДДФЛ чл. 24 ал. 2 т. 8 изброява „чл. 222, ал. 2 и 3“, а ал. 4 стига дотам по"
            + " препращането в самата ал. 4 („Алинея 3 се прилага“)");
    assertEquals(
        "", row.get("d1Code"), "код за Д1 се носи само когато сумата е осигурителен доход");
    assertEquals(
        "чл. 222 ал. 3 КТ",
        row.get("legalBasis"),
        "основанието остава ал. 3: сменено на „ал. 3 и 4“, то скъсва препращането, което носи"
            + " необлагаемостта");
    assertTrue(
        row.get("note").contains("чл. 68а"),
        "бележката трябва да назовава чл. 68а КСО — иначе ал. 4 е невидима за следващия читател");
  }

  // ───────────────────────────── чл. 217 · нов ред ─────────────────────────────

  /** ⚠️ ЧЛ. 217 — с вноски и с данък, Д1 код 20, и осигуряване ВЪРХУ САМОТО обезщетение. */
  @Test
  @DisplayName("⚠️ чл. 217 · вноски ДА, данък ДА, Д1 20, осигурителен доход ДА")
  void articleTwoSeventeenCarriesItsTreatment() {
    Map<String, String> row = read(CATALOGUE).get("comp_kt_217");
    assertNotNull(row, "comp_kt_217 липсва от " + CATALOGUE);

    assertEquals("чл. 217 КТ", row.get("legalBasis"));
    assertTrue(
        Boolean.parseBoolean(row.get("socialContributionsDue")),
        "КСО чл. 9 ал. 3 т. 4 внася „за сметка на осигурителя върху полагащото се обезщетение“, а"
            + " НЕВДПОВ чл. 1 ал. 8 т. 7 НЕ изброява чл. 217");
    assertTrue(
        Boolean.parseBoolean(row.get("isTaxable")), "ЗДДФЛ чл. 24 ал. 2 т. 8 не изброява чл. 217");
    assertEquals("20", row.get("d1Code"), "вид осигурен 20");
    assertTrue(
        Boolean.parseBoolean(row.get("countsAsInsuranceService")),
        "КСО чл. 9 ал. 3 започва с „За осигурителен стаж се зачита и времето“");

    // ⚠️ ОСИГУРИТЕЛНИЯТ ДОХОД Е НА РЕДА НА ФИША, не на каталога — и е ТОЧНО този ред, защото т. 4
    // внася върху полагащото се обезщетение, а не върху последното брутно (т. 1).
    Map<String, String> line = null;
    for (Map<String, String> candidate : read(PAY_SLIP_LINES).values()) {
      if ("comp_kt_217".equals(candidate.get("bgCompensationType"))) {
        line = candidate;
      }
    }
    assertNotNull(line, "чл. 217 няма ред на фиша — няма къде да се отпечата");
    assertEquals("928", line.get("number"), "перо 928 (927 беше последното заето)");
    assertTrue(
        Boolean.parseBoolean(line.get("bgIsInsuranceIncome")),
        "базата по КСО чл. 9 ал. 3 т. 4 Е самото обезщетение");
    assertEquals(
        row.get("name"),
        line.get("name"),
        "името се преписва от каталога — BgPaySlipLineTypeCatalogueTest пада на същото срещу"
            + " базата");
  }

  /**
   * ⚠️ ЧЛ. 217 НЕ СЕ СМЯТА, и null-ът е самото твърдение.
   *
   * <p>Периодът е „от деня, в който получи предписанието … до неговото изпълнение“ — два факта, не
   * брой месеци. {@code compute} не познава {@code BTV_ACTUAL_PERIOD} и връща {@code null}, тоест
   * сумата се въвежда ръчно. ⚠️ Нула тук би била дефект, не подразбиране: тя изглежда като
   * изчислена сума и минава през потвърждаването мълчаливо.
   */
  @Test
  @DisplayName("⚠️ чл. 217 · compute() връща null, а НЕ нула — сумата се въвежда")
  void articleTwoSeventeenIsNotComputedByTheSystem() {
    Map<String, String> row = read(CATALOGUE).get("comp_kt_217");
    assertNotNull(row);
    assertEquals("BTV_ACTUAL_PERIOD", row.get("baseKind"));
    assertEquals(
        "",
        row.get("baseMonths"),
        "празно значи „периодът няма законов таван“ — чл. 217 не поставя такъв");
    assertTrue(
        Boolean.parseBoolean(row.get("isAmountAdjustable")),
        "щом не се смята, единственият път до сума е ръчният вход");

    assertNull(
        BgCompensationResolver.compute(
            row.get("baseKind"), new BigDecimal("1.00"), new BigDecimal("1840.00"), 21),
        "BTV_ACTUAL_PERIOD няма аритметика (WS-3c). Нула би минала за изчислена сума.");
  }

  /** ⚠️ Ал. 2 отнема правото по ФАКТ, не по договорка — затова бележка, а не втори ред. */
  @Test
  @DisplayName("⚠️ чл. 217 ал. 2 · няма свой ред — отпадането по отказ е бележка")
  void paragraphTwoOfArticleTwoSeventeenIsANoteNotARow() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    for (String code : new String[] {"comp_kt_217_1", "comp_kt_217_2"}) {
      if (catalogue.containsKey(code)) {
        fail(
            code
                + " е добавен като отделен ред. Ал. 2 не е второ обезщетение и не е друго"
                + " третиране, а отпадане на СЪЩОТО право по факт — както ал. 2 и ал. 3 на чл."
                + " 225.");
      }
    }
    Map<String, String> row = catalogue.get("comp_kt_217");
    assertNotNull(row);
    assertTrue(row.get("note").contains("ал. 2"), "отпадането по ал. 2 трябва да е записано");
    assertFalse(
        Boolean.parseBoolean(row.get("canBeWaived")),
        "КТ чл. 8 ал. 4 обявява отказа от трудови права за недействителен. Отпадането по ал. 2"
            + " настъпва ПО ЗАКОН при отказ да се приеме работата, не по споразумение.");
    assertEquals(
        "",
        row.get("triggerGrounds"),
        "чл. 217 се поражда от събитие ПО ВРЕМЕ на правоотношението, не от прекратяването му");
    assertFalse(Boolean.parseBoolean(row.get("appliesToAnyGround")));
  }

  // ───────────────────────────── регресия ─────────────────────────────

  /**
   * ⚠️ СЕДЕМТЕ РЕДА ПО ЧЛ. 222 КТ И ЧЛ. 106 ЗДСл СА НЕПРОМЕНЕНИ, И ЧЛ. 221 / ЧЛ. 200 СА ЖИВИ.
   *
   * <p>Втората проверка не е излишна. App Install е upsert по importId: ред, вкаран със ЗАЕТ
   * importId, ПРЕИМЕНУВА заварения ред вместо да добави нов, и то мълчаливо. {@code
   * CT-12/13/14-2026} принадлежат на {@code comp_kt_221_1}, {@code comp_kt_221_2} и {@code
   * comp_kt_200}, трите алинеи на чл. 106 ЗДСл са на {@code CT-26/27/28-2026}, а {@code
   * comp_kt_217} е на {@code CT-29-2026}. Затова присъствието им се сверява изрично при всяка
   * промяна по този файл.
   *
   * <p><b>⚠️ УНИКАЛНОСТТА НЕ СТИГА.</b> {@code BgSeedImportIdUniquenessTest} пази, че номер не се
   * ПОВТАРЯ — но не пази КОЙ ред държи кой номер. Конфликт при сливане, решен в полза на грешната
   * страна, дава пак 29 УНИКАЛНИ номера и пак минава онзи тест: чл. 221 ал. 1 просто носи чужд
   * номер. Тук се заковава именно съответствието ред ↔ номер за трите съседни реда.
   */
  @Test
  @DisplayName("РЕГРЕСИЯ · седемте реда непроменени, номерата на съседите запазени, CT-29 веднъж")
  void theEarlierRowsAreUntouchedAndTheirNeighboursSurvive() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    List<String> wrong = new ArrayList<>();

    Map<String, String> nine = new LinkedHashMap<>();
    nine.put("comp_kt_222_1", "чл. 222 ал. 1 КТ");
    nine.put("comp_kt_222_2", "чл. 222 ал. 2 КТ");
    nine.put("comp_kt_222_3", "чл. 222 ал. 3 КТ");
    nine.put("comp_zdsl_106_2", "чл. 106 ал. 2 ЗДСл");
    nine.put("comp_zdsl_106_3", "чл. 106 ал. 3 ЗДСл");
    nine.put("comp_zdsl_106_3a", "чл. 106 ал. 3а ЗДСл");
    nine.put("comp_zdsl_106_4", "чл. 106 ал. 4 ЗДСл");
    for (Map.Entry<String, String> e : nine.entrySet()) {
      Map<String, String> row = catalogue.get(e.getKey());
      if (row == null) {
        wrong.add(e.getKey() + " — ЛИПСВА");
      } else if (!e.getValue().equals(row.get("legalBasis"))) {
        wrong.add(
            e.getKey() + " · основание „" + row.get("legalBasis") + "“ ≠ „" + e.getValue() + "“");
      }
    }

    // ⚠️ Съседите, които ред със зает importId би преименувал — и то с ТЕХНИТЕ номера.
    // Присъствието не стига: сливане, решено в полза на грешната страна, ги оставя живи, но с
    // чужд номер, и уникалността пак излиза чиста.
    Map<String, String> restoredByHotfix = new LinkedHashMap<>();
    restoredByHotfix.put("comp_kt_221_1", "CT-12-2026");
    restoredByHotfix.put("comp_kt_221_2", "CT-13-2026");
    restoredByHotfix.put("comp_kt_200", "CT-14-2026");
    for (Map.Entry<String, String> e : restoredByHotfix.entrySet()) {
      Map<String, String> row = catalogue.get(e.getKey());
      if (row == null) {
        wrong.add(
            e.getKey()
                + " — ЛИПСВА. Ред със зает importId го преименува; виновникът е нов ред с чужд"
                + " номер, не този тест.");
      } else if (!e.getValue().equals(row.get("importId"))) {
        wrong.add(
            e.getKey()
                + " · importId „"
                + row.get("importId")
                + "“, а #157 го върна на „"
                + e.getValue()
                + "“ — сблъсъкът с чл. 106 ЗДСл е върнат назад");
      }
    }

    // И огледалното: трите реда по чл. 106 стоят на номерата, които #157 им даде.
    Map<String, String> movedByHotfix = new LinkedHashMap<>();
    movedByHotfix.put("comp_zdsl_106_3", "CT-26-2026");
    movedByHotfix.put("comp_zdsl_106_3a", "CT-27-2026");
    movedByHotfix.put("comp_zdsl_106_4", "CT-28-2026");
    for (Map.Entry<String, String> e : movedByHotfix.entrySet()) {
      Map<String, String> row = catalogue.get(e.getKey());
      if (row != null && !e.getValue().equals(row.get("importId"))) {
        wrong.add(
            e.getKey()
                + " · importId „"
                + row.get("importId")
                + "“, а #157 го премести на „"
                + e.getValue()
                + "“");
      }
    }

    // Новият ред не влошава положението: CT-29-2026 се държи от точно един код.
    List<String> claimants = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> e : catalogue.entrySet()) {
      if ("CT-29-2026".equals(e.getValue().get("importId"))) {
        claimants.add(e.getKey());
      }
    }
    if (!Arrays.asList("comp_kt_217").equals(claimants)) {
      wrong.add("CT-29-2026 се държи от " + claimants + ", а трябва само от comp_kt_217");
    }

    if (!wrong.isEmpty()) {
      fail("регресия в каталога — " + wrong.size() + ":\n  " + String.join("\n  ", wrong));
    }
  }
}
