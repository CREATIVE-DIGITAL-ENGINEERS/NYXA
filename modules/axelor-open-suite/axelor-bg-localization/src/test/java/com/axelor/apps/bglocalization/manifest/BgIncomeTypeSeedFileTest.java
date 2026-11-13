package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НОМЕНКЛАТУРАТА НА ВИДА ДОХОД — файловата половина: „ВЯРНО ЛИ Е ЗАПИСАНО“.
 *
 * <p><b>Мери се срещу ОБРАЗЕЦИТЕ, не срещу спомен.</b> Двата източника са „Справка_73 ал. 1 2025“ и
 * „Справка_73 ал. 6 2025“ на НАП; членът, разходите и ставките — срещу ЗДДФЛ (чл. 29, 31, 33, 35,
 * 37, 38, 42, 44а и 46). Броевете по-долу са ПРЕБРОЕНИ в образците, а не закръглени очаквания: ал.
 * 1 носи 79 кода в четири части, ал. 6 носи 11.
 *
 * <p><b>⚠️ ЧЕТЕ СЕ ПРЕЗ {@link BgSeed#quotedRows}, А НЕ ПРЕЗ {@code rows}.</b> Описанието на код
 * 101 изброява буквите на § 1 т. 26 от ДР, разделени с ТОЧКА И ЗАПЕТАЯ — същия знак, който дели
 * колоните. Голото цепене му дава над 60 колони вместо 13 и {@code BgSeed.col} връща съседна
 * стойност МЪЛЧЕШКОМ, вместо да падне.
 *
 * <p><b>Базовата половина</b> ({@code BgIncomeTypeDbTest}) пита другото — стигнал ли е seed-ът до
 * базата — и се пропуска с обявена причина, когато App Install не е пускан.
 */
class BgIncomeTypeSeedFileTest {

  private static final String FILE = "bg_income_type.csv";

  /** Броевете, ПРЕБРОЕНИ в образците. Разминаване тук значи сгрешен seed, не сгрешено очакване. */
  private static final int CODES_ART73_PARA1 = 79;

  private static final int CODES_ART73_PARA6 = 11;

  private Map<String, Map<String, String>> catalogue() {
    List<String> header = BgSeed.quotedHeader(FILE);
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      assertEquals(
          header.size(),
          row.length,
          "ред с различен брой колони от заглавния — ще влезе разместен: " + Arrays.toString(row));
      Map<String, String> cells = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        cells.put(header.get(i), row[i]);
      }
      out.put(cells.get("code"), cells);
    }
    return out;
  }

  /**
   * ⚠️ ДВА ФЛАГА, ДВА РАЗЛИЧНИ СПИСЪКА — и точно затова тестът изброява и двата.
   *
   * <p>Указание т. 5 към справката по ал. 1, ДОСЛОВНО: „Колони от 2 до 9, Част ІІ не се попълват за
   * изплатени доходи с кодове 5031, 8141 и 820. Колона 14 „Брой лица“ се попълва САМО за доходи с
   * кодове 5031 и 8141.“ Тоест 820 е СБОРЕН ред, но брой лица НЕ носи. Изведена от {@code
   * isAggregate}, колона 14 би получила число и там — попълнена колона, която образецът не иска, на
   * ред, който няма кого да брои.
   */
  @Test
  @DisplayName("⚠️ СБОРНИ · 5031, 8141 и 820 са без имена, но брой лица носят САМО 5031 и 8141")
  void theAggregateFlagAndThePersonCountFlagAreNotTheSameList() {
    Map<String, Map<String, String>> c = catalogue();

    Set<String> aggregate = new java.util.TreeSet<>();
    Set<String> withPersonCount = new java.util.TreeSet<>();
    for (Map.Entry<String, Map<String, String>> e : c.entrySet()) {
      if ("true".equals(e.getValue().get("isAggregate"))) {
        aggregate.add(e.getKey());
      }
      if ("true".equals(e.getValue().get("reportsPersonCount"))) {
        withPersonCount.add(e.getKey());
      }
    }

    assertEquals(
        new java.util.TreeSet<>(Arrays.asList("5031", "8141", "820")),
        aggregate,
        "сборните редове по указание т. 5 — колони 2–9 не се попълват");
    assertEquals(
        new java.util.TreeSet<>(Arrays.asList("5031", "8141")),
        withPersonCount,
        "⚠️ колона 14 се попълва САМО за 5031 и 8141 — код 820 е сборен, но брой лица няма");
    assertTrue(
        aggregate.containsAll(withPersonCount),
        "ред с брой лица, който не е сборен, няма смисъл: колона 14 живее само на сборен ред");
  }

  @Test
  @DisplayName("КАТАЛОГ · и деветдесетте кода от двата образеца са заведени, всеки по веднъж")
  void everyCodeOfBothFormsIsPresentExactlyOnce() {
    List<String[]> rows = BgSeed.quotedRows(FILE);
    Set<String> codes = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (String[] row : rows) {
      if (!codes.add(row[1])) {
        duplicates.add(row[1]);
      }
    }
    assertTrue(duplicates.isEmpty(), "повтарящ се код в каталога: " + duplicates);
    assertEquals(
        CODES_ART73_PARA1 + CODES_ART73_PARA6,
        rows.size(),
        "образецът по ал. 1 носи "
            + CODES_ART73_PARA1
            + " кода, а по ал. 6 — "
            + CODES_ART73_PARA6);

    Map<String, Integer> perForm = new TreeMap<>();
    for (Map<String, String> row : catalogue().values()) {
      perForm.merge(row.get("declarations"), 1, Integer::sum);
    }
    assertEquals(
        Integer.valueOf(CODES_ART73_PARA1),
        perForm.get("73-1"),
        "кодове в справката по чл. 73 ал. 1");
    assertEquals(
        Integer.valueOf(CODES_ART73_PARA6),
        perForm.get("73-6"),
        "кодове в справката по чл. 73 ал. 6");
  }

  /**
   * ⚠️ ОТСЪСТВИЯТА СА ТВЪРДЕНИЕ, А НЕ ПРОПУСК. Образецът за 2025 г. НЕ носи тези кодове: 818 отпада
   * с отмяната на чл. 38 ал. 10 (ДВ бр. 106/2023, в сила от 01.01.2024). Заведени „за всеки
   * случай“, те биха предложили на оператора код, който НАП не приема — и грешката се вижда чак при
   * подаване.
   */
  @Test
  @DisplayName("КАТАЛОГ · кодовете, които образецът за 2025 г. НЕ носи, ги няма")
  void codesAbsentFromTheTwoThousandTwentyFiveFormAreNotSeeded() {
    Map<String, Map<String, String>> catalogue = catalogue();
    for (String absent : Arrays.asList("602", "818", "821", "901", "905", "906")) {
      assertFalse(
          catalogue.containsKey(absent),
          "код " + absent + " не съществува в образеца за 2025 г., а е заведен");
    }
  }

  @Test
  @DisplayName("ЧЛ. 29 · нормативните разходи са 60, 40 или 25 на сто според точката")
  void expenseRatesFollowArticleTwentyNine() {
    Map<String, Map<String, String>> c = catalogue();
    // т. 1 — регистриран земеделски стопанин за непреработени продукти
    assertRow(c, "301", "чл. 29, ал. 1, т. 1", "A", "60.00", "0.00", "10.00");
    // т. 2, букви „а“–„г“ — четирите случая с 40 на сто
    assertRow(c, "302", "чл. 29, ал. 1, т. 2, б. „а“", "A", "40.00", "0.00", "10.00");
    assertRow(c, "303", "чл. 29, ал. 1, т. 2, б. „а“", "A", "40.00", "0.00", "10.00");
    assertRow(c, "304", "чл. 29, ал. 1, т. 2, б. „б“", "A", "40.00", "0.00", "10.00");
    assertRow(c, "305", "чл. 29, ал. 1, т. 2, б. „в“", "A", "40.00", "0.00", "10.00");
    // ⚠️ 3061 (адвокат) е по б. „г“ — 40 на сто, НЕ 25. Съседният му код 306 е по т. 3 и е 25.
    assertRow(c, "3061", "чл. 29, ал. 1, т. 2, б. „г“", "A", "40.00", "0.00", "10.00");
    assertRow(c, "306", "чл. 29, ал. 1, т. 3", "A", "25.00", "0.00", "10.00");
    // т. 3 — услугите 307x, всичките 25 на сто
    for (String code :
        Arrays.asList(
            "3071", "3072", "3073", "3074", "3075", "3076", "3077", "3078", "3079", "30710",
            "30711", "30712", "30713", "30714")) {
      assertRow(c, code, "чл. 29, ал. 1, т. 3", "A", "25.00", "0.00", "10.00");
    }
  }

  @Test
  @DisplayName("ЧЛ. 31 · наемът е 10 на сто разходи и 10 на сто авансов данък")
  void rentIsTenPercentExpenses() {
    Map<String, Map<String, String>> c = catalogue();
    for (String code : Arrays.asList("401", "402", "403")) {
      assertRow(c, code, "чл. 31", "B", "10.00", "0.00", "10.00");
    }
  }

  /**
   * ⚠️ Чл. 44а ал. 1: „Паричните доходи по чл. 35 се облагат авансово с данък, когато платецът на
   * дохода е предприятие или самоосигуряващо се лице… данъчна ставка 10 на сто.“ Разходи НЯМА — чл.
   * 35 не признава нормативни разходи на нито една от петте си точки.
   */
  @Test
  @DisplayName("ЧЛ. 35 · други източници — без разходи, авансов данък 10 на сто")
  void otherSourcesHaveNoExpenses() {
    Map<String, Map<String, String>> c = catalogue();
    assertRow(c, "601", "чл. 35, т. 1", "E", "0.00", "0.00", "10.00");
    assertRow(c, "603", "чл. 35, т. 3", "E", "0.00", "0.00", "10.00");
    assertRow(c, "604", "чл. 35, т. 4", "E", "0.00", "0.00", "10.00");
    assertRow(c, "605", "чл. 35, т. 5", "E", "0.00", "0.00", "10.00");
    assertRow(c, "606", "чл. 35, т. 6", "E", "0.00", "0.00", "10.00");
  }

  /**
   * ⚠️ ДВЕ ТВЪРДЕНИЯ НАВЕДНЪЖ, И ВТОРОТО Е ПО-ВАЖНОТО. Първото: платецът НЕ удържа данък по чл. 33
   * — доходът влиза в общата годишна данъчна основа и лицето го декларира само̀. Второто: {@code
   * expenseRate} стои на НУЛА, макар чл. 33 ал. 1 и ал. 3 да говорят за „10 на сто разходи“ —
   * защото там те се прилагат върху ПОЛОЖИТЕЛНАТА РАЗЛИКА между продажната цена и цената на
   * придобиване, а не върху бруто. Записани като 10, те биха дали основа „бруто × 0,9“ на всяка
   * сделка.
   */
  @Test
  @DisplayName("ЧЛ. 33 · платецът не удържа, а десетте на сто НЕ са върху бруто")
  void transferCodesWithholdNothingAndCarryNoGrossExpenseRate() {
    Map<String, Map<String, String>> c = catalogue();
    for (Map.Entry<String, Map<String, String>> e : c.entrySet()) {
      if (!"D".equals(e.getValue().get("taxPattern"))) {
        continue;
      }
      assertEquals(
          "false",
          e.getValue().get("withheldByPayer"),
          "код " + e.getKey() + ": платецът не удържа данък по чл. 33");
      assertEquals(
          "0.00",
          e.getValue().get("expenseRate"),
          "код "
              + e.getKey()
              + ": десетте на сто по чл. 33 са върху разликата, не върху бруто — виж домейна");
      assertEquals("0.00", e.getValue().get("advanceTaxRate"), "код " + e.getKey());
      assertEquals("0.00", e.getValue().get("finalTaxRate"), "код " + e.getKey());
    }
    assertRow(c, "501", "чл. 33, ал. 1", "D", "0.00", "0.00", "0.00");
    assertRow(c, "505", "чл. 33, ал. 9", "D", "0.00", "0.00", "0.00");
    assertRow(c, "507", "чл. 33, ал. 11", "D", "0.00", "0.00", "0.00");
    assertRow(c, "508", "чл. 33, ал. 3", "D", "0.00", "0.00", "0.00");
  }

  /**
   * СТАВКИТЕ НА ГЛАВА ШЕСТА, ДОСЛОВНО ПО ЧЛ. 46: ал. 1 — 10 на сто за чл. 37 и за чл. 38 ал. 5, 8,
   * 11, 12, 14 и 15; ал. 2 — 7 на сто за чл. 38 ал. 8 при „Живот“ с срок 15 или повече години; ал.
   * 3 — 5 на сто за чл. 38 ал. 1.
   *
   * <p><b>⚠️ СЕДЕМТЕ НА СТО СА ЕДИН-ЕДИНСТВЕН КОД.</b> 817 и 8171 са по СЪЩАТА алинея (чл. 38 ал.
   * 8), но с различна ставка — затова са два реда, а не един. Слети, дългосрочната застраховка
   * „Живот“ би се обложила с 10 на сто.
   */
  @Test
  @DisplayName("ЧЛ. 46 · окончателният данък е 10, 5 или 7 на сто — всеки код по своята алинея")
  void finalTaxRatesFollowArticleFortySix() {
    Map<String, Map<String, String>> c = catalogue();
    assertRow(c, "801", "чл. 37, ал. 1, т. 1", "C", "0.00", "10.00", "0.00");
    // ⚠️ 809 е управление и контрол на ЧУЖДЕСТРАННО лице — окончателен данък, различно от ДУК по
    // код 101, който е трудово правоотношение по § 1 т. 26 б. „з“.
    assertRow(c, "809", "чл. 37, ал. 1, т. 9", "C", "0.00", "10.00", "0.00");
    assertRow(c, "813", "чл. 37, ал. 1 във връзка с чл. 8, ал. 11", "C", "0.00", "10.00", "0.00");
    assertRow(c, "814", "чл. 38, ал. 1", "C", "0.00", "5.00", "0.00");
    assertRow(c, "8141", "чл. 38, ал. 1", "C", "0.00", "5.00", "0.00");
    assertRow(c, "815", "чл. 38, ал. 5", "C", "0.00", "10.00", "0.00");
    assertRow(c, "817", "чл. 38, ал. 8", "C", "0.00", "10.00", "0.00");
    assertRow(c, "8171", "чл. 38, ал. 8", "C", "0.00", "7.00", "0.00");
    assertRow(c, "819", "чл. 38, ал. 11", "C", "0.00", "10.00", "0.00");
    assertRow(c, "820", "чл. 38, ал. 12", "C", "0.00", "10.00", "0.00");
    assertRow(c, "822", "чл. 38, ал. 14", "C", "0.00", "10.00", "0.00");
  }

  /**
   * РАЗПОРЕДБИТЕ, ПРИ КОИТО ОКОНЧАТЕЛНИЯТ ДАНЪК СЕ ВНАСЯ ОТ ПОЛУЧАТЕЛЯ — чл. 65 ал. 6.
   *
   * <p>Чл. 65 ал. 6, ДОСЛОВНО: „Данъкът по чл. 46 за доходите по чл. 37, ал. 1, т. 10 - 12 и чл.
   * 38, ал. 5 се внася по реда на чл. 67 от лицето, придобило дохода.“ Тези четири разпоредби са
   * ЕДИНСТВЕНИТЕ в глава шеста без удържане: за всичко останало чл. 65 има своя алинея, която го
   * възлага на платеца — ал. 1 (чл. 37 ал. 1 т. 1–9 и чл. 38 ал. 12), ал. 2 (дивидентите по чл. 38
   * ал. 2), ал. 4 (ликвидационните дялове по чл. 38 ал. 4), ал. 7 (чл. 38 ал. 8, 11 и 14) и ал. 10
   * (чл. 8 ал. 11). Мълчание няма — всеки от двайсет и двата кода е назован някъде.
   *
   * <p><b>⚠️ „НЕ СЕ УДЪРЖА“ НЕ Е „НЕ СЕ ДЪЛЖИ“.</b> Данъкът по чл. 46 се дължи и по четирите —
   * просто го внася лицето по реда на чл. 67, в КОЛОНА 4 на НЕГОВАТА декларация. Записан с „удържа
   * се“ = ДА, платецът го смята за себе си и го декларира в своята колона 3: дължим данък, платен
   * два пъти, и то от когото не трябва.
   *
   * <p><b>⚠️ ИЗМЕРВА СЕ ПО РАЗПОРЕДБАТА, А НЕ ПО НОМЕРА НА КОДА,</b> защото номерата са на ЧУЖДА
   * номенклатура — на заповедта за справката по чл. 73 ал. 1 — и се преномерират без ЗДДФЛ да е
   * пипнат. Списъкът на кодовете отдолу е ВТОРО, независимо твърдение: то улавя обратното — нова
   * разпоредба, вкарана под стар код.
   *
   * <p><b>⚠️ ЧЕТИРИТЕ ПРЕФИКСА СА ПРЕПИСАНИ ТУК, А НЕ ВЗЕТИ ОТ {@code BgArt55Aggregator}.</b> Той
   * носи същия списък, за да БРОИ такива редове; заемат ли се, сгрешен там списък би направил
   * очакването и резултата едно и също и червено нямаше да се появи никога.
   */
  @Test
  @DisplayName("ЧЛ. 65 · платецът удържа цялата глава шеста ОСВЕН ал. 6 — 810, 811, 812 и 815")
  void chapterSixWithholdingFollowsArticleSixtyFive() {
    List<String> paidByRecipient = new ArrayList<>();
    int seen = 0;
    for (Map.Entry<String, Map<String, String>> e : catalogue().entrySet()) {
      if (!"C".equals(e.getValue().get("taxPattern"))) {
        continue;
      }
      seen++;
      String article = e.getValue().get("article");
      boolean recipient = paidByRecipientUnderArticle65Para6(article);
      if (recipient) {
        paidByRecipient.add(e.getKey());
      }
      assertEquals(
          recipient ? "false" : "true",
          e.getValue().get("withheldByPayer"),
          "код " + e.getKey() + " (" + article + "): чл. 65 казва кой внася данъка");
    }

    assertEquals(22, seen, "кодове с шаблон C — цялата глава шеста в образеца за 2025 г.");
    java.util.Collections.sort(paidByRecipient);
    assertEquals(
        Arrays.asList("810", "811", "812", "815"),
        paidByRecipient,
        "кодовете по чл. 65 ал. 6: чл. 37 ал. 1 т. 10, 11 и 12 плюс чл. 38 ал. 5");
  }

  /**
   * ⚠️ 814 И 8141 НЕ СА СРЕД ТЯХ, И ТОВА СЕ ПРОВЕРЯВА ОТДЕЛНО. Дивидентите са по чл. 38 ал. 1 —
   * разпоредба, която чл. 65 ал. 6 НЕ изброява. Данъкът им се определя по чл. 38 ал. 2 и се удържа
   * и внася от ПРЕДПРИЯТИЕТО по чл. 65 ал. 2 („в срок до края на месеца, следващ тримесечието, през
   * което е взето решението за разпределяне на дивидент“); ликвидационните дялове по същия код са
   * по чл. 38 ал. 4 и по чл. 65 ал. 4 — пак платецът.
   *
   * <p><b>⚠️ ЕДИНСТВЕНОТО ИЗКЛЮЧЕНИЕ Е НА ПЛАЩАНЕТО, НЕ НА КОДА.</b> Чл. 65 ал. 5, дословно:
   * „Алинея 4 не се прилага за доходите от ликвидационни дялове в случаите на прехвърляне на
   * имущество при преобразуване по реда на чл. 265 от Търговския закон, за които данъкът се внася
   * по реда на чл. 67 от едноличния търговец, придобил дохода.“ Това е ОБСТОЯТЕЛСТВО НА СДЕЛКАТА —
   * преобразуване по чл. 265 ТЗ и получател ЕТ — а не свойство на код 814, който покрива и
   * обикновения дивидент. Записан на кода, той би обърнал признака на ВСЕКИ дивидент.
   */
  @Test
  @DisplayName("ЧЛ. 65 АЛ. 2 и 4 · дивидентът и ликвидационният дял се удържат от платеца")
  void dividendsStayWithThePayer() {
    Map<String, Map<String, String>> c = catalogue();
    for (String code : Arrays.asList("814", "8141")) {
      assertEquals("чл. 38, ал. 1", c.get(code).get("article"), "код " + code);
      assertEquals("true", c.get(code).get("withheldByPayer"), "код " + code);
    }
  }

  /**
   * Разпоредбите на чл. 65 ал. 6, нормализирани — разредката и регистърът не решават коя е коя.
   *
   * <p><b>⚠️ СЛЕД ПРЕФИКСА СЕ ИСКА НЕ-ЦИФРА.</b> „чл. 37, ал. 1, т. 1“ (код 801, обезщетения за
   * пропуснати ползи) е ЧИСТ ПРЕФИКС на „чл. 37, ал. 1, т. 10“ и обратното — сравнени наивно, ал. 6
   * би глътнала и деветте кода на ал. 1. Същото важи за „чл. 38, ал. 1“ спрямо „чл. 38, ал. 12“.
   */
  private static boolean paidByRecipientUnderArticle65Para6(String article) {
    String normalized = normalizeArticle(article);
    for (String provision :
        Arrays.asList("чл.37,ал.1,т.10", "чл.37,ал.1,т.11", "чл.37,ал.1,т.12", "чл.38,ал.5")) {
      if (normalized.startsWith(provision)
          && (normalized.length() == provision.length()
              || !Character.isDigit(normalized.charAt(provision.length())))) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeArticle(String article) {
    StringBuilder out = new StringBuilder(article.length());
    for (char symbol : article.toCharArray()) {
      if (!Character.isWhitespace(symbol)) {
        out.append(Character.toLowerCase(symbol));
      }
    }
    return out.toString();
  }

  /**
   * ⚠️ ТОЧНО ТРИ, И ТЕ СА НАЗОВАНИ В УКАЗАНИЕТО. Указание т. 5 към справката по ал. 1, дословно:
   * „Колони от 2 до 9, Част ІІ не се попълват за изплатени доходи с кодове 5031, 8141 и 820.“
   * Четвърти сборен код би подал сума без имена там, където НАП очаква имена.
   */
  @Test
  @DisplayName("СБОРНИ · сборните редове са точно 5031, 8141 и 820")
  void exactlyThreeCodesAreAggregate() {
    List<String> aggregate = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> e : catalogue().entrySet()) {
      if ("true".equals(e.getValue().get("isAggregate"))) {
        aggregate.add(e.getKey());
      }
    }
    java.util.Collections.sort(aggregate);
    assertEquals(Arrays.asList("5031", "8141", "820"), aggregate);
  }

  /**
   * ⚠️ КОД 103 НОСИ 1 НА СТО, А НЕ 10 — и това е единственото изключение в трите трудови кода. Чл.
   * 42 ал. 4, дословно: „…се умножи по данъчна ставка 10 на сто, а за доходите, придобити като
   * морско лице — по данъчна ставка 1 на сто.“ Записано като 10, морското лице би се обложило
   * десетократно и разликата не изглежда като грешка на нито един фиш.
   */
  @Test
  @DisplayName("ЧЛ. 73 АЛ. 6 · трите облагаеми трудови кода, и морското лице с 1 на сто")
  void employmentCodesCarryTheirOwnAdvanceRate() {
    Map<String, Map<String, String>> c = catalogue();
    assertRow(
        c, "101", "§ 1, т. 26, б. „а“–„з“ и „л“ от ДР на ЗДДФЛ", "TR", "0.00", "0.00", "10.00");
    assertRow(c, "102", "§ 1, т. 26, б. „и“ от ДР на ЗДДФЛ", "TR", "0.00", "0.00", "10.00");
    assertRow(c, "103", "§ 1, т. 54 от ДР на ЗДДФЛ", "TR", "0.00", "0.00", "1.00");
    for (String code : Arrays.asList("101", "102", "103")) {
      assertEquals("73-6", c.get(code).get("declarations"), "код " + code);
      assertEquals("true", c.get(code).get("withheldByPayer"), "код " + code);
    }
  }

  /**
   * ⚠️ НЕОБЛАГАЕМИЯТ ДОХОД НЕ СЕ РАЗПОЗНАВА ПО „НУЛА ДАНЪК“. Генерираният getter за decimal връща
   * ZERO и за непопълнено поле ({@code BgDefaultedGetterTest}), тоест „нула“ и „не е решено“ са
   * едно и също число. Признакът е ШАБЛОНЪТ — затова кодове 104–111 и 9xx носят „F“, а не се
   * извеждат от ставките си.
   */
  @Test
  @DisplayName("НЕОБЛАГАЕМИ · шаблон F не удържа данък и няма нито една ставка")
  void nonTaxableCodesCarryNoRateAtAll() {
    Map<String, Map<String, String>> c = catalogue();
    int seen = 0;
    for (Map.Entry<String, Map<String, String>> e : c.entrySet()) {
      if (!"F".equals(e.getValue().get("taxPattern"))) {
        continue;
      }
      seen++;
      assertEquals("0.00", e.getValue().get("expenseRate"), "код " + e.getKey());
      assertEquals("0.00", e.getValue().get("finalTaxRate"), "код " + e.getKey());
      assertEquals("0.00", e.getValue().get("advanceTaxRate"), "код " + e.getKey());
      assertEquals("false", e.getValue().get("withheldByPayer"), "код " + e.getKey());
    }
    // осемте необлагаеми трудови (104–111) плюс тринайсетте по чл. 13 в Част ІV
    assertEquals(21, seen, "кодове с шаблон F");
    assertRow(c, "907", "чл. 13, ал. 1, т. 24", "F", "0.00", "0.00", "0.00");
    // ⚠️ 9021 е по ЧЛ. 38 АЛ. 9, а не по чл. 13 — единственият код в Част ІV, чието основание за
    // освобождаване е в глава шеста. Взето от описанието му, не по съседство.
    assertRow(c, "9021", "чл. 38, ал. 9", "F", "0.00", "0.00", "0.00");
    assertRow(c, "104", "чл. 24, ал. 2, т. 1, б. „е“", "F", "0.00", "0.00", "0.00");
    assertRow(c, "110", "чл. 24, ал. 2, т. 5, б. „б“", "F", "0.00", "0.00", "0.00");
  }

  @Test
  @DisplayName("КАТАЛОГ · всеки ред носи член, шаблон, справка и начална дата")
  void everyRowIsFullyDescribed() {
    for (Map.Entry<String, Map<String, String>> e : catalogue().entrySet()) {
      Map<String, String> row = e.getValue();
      String code = e.getKey();
      assertFalse(row.get("name").trim().isEmpty(), "код " + code + ": описание");
      assertFalse(row.get("article").trim().isEmpty(), "код " + code + ": разпоредба");
      assertTrue(
          Arrays.asList("A", "B", "C", "D", "E", "F", "TR").contains(row.get("taxPattern")),
          "код " + code + ": непознат шаблон " + row.get("taxPattern"));
      assertEquals("2025-01-01", row.get("validFrom"), "код " + code);
      assertTrue(row.get("validTo").isEmpty(), "код " + code + ": образецът за 2025 г. е в сила");
      assertEquals("INCTYPE-" + code + "-2025", row.get("importId"), "importId по код");
    }
  }

  private static void assertRow(
      Map<String, Map<String, String>> catalogue,
      String code,
      String article,
      String pattern,
      String expense,
      String finalTax,
      String advance) {
    Map<String, String> row = catalogue.get(code);
    assertTrue(row != null, "липсва код " + code);
    assertEquals(article, row.get("article"), "код " + code + ": разпоредба");
    assertEquals(pattern, row.get("taxPattern"), "код " + code + ": шаблон");
    assertEquals(expense, row.get("expenseRate"), "код " + code + ": нормативни разходи");
    assertEquals(finalTax, row.get("finalTaxRate"), "код " + code + ": окончателен данък");
    assertEquals(advance, row.get("advanceTaxRate"), "код " + code + ": авансов данък");
  }
}
