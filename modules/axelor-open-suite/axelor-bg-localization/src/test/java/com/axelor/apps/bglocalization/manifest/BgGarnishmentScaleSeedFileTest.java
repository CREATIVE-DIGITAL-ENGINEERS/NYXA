package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СКАЛАТА ПО ГПК ЧЛ. 446 Е ЗАВЕДЕНА КАТО ДАННИ — файловата половина: „вярно ли е ЗАПИСАНО“. Без
 * Postgres, без App Install.
 *
 * <p><b>Защо изобщо тест за шест реда.</b> Тези шест реда СА нормата — резолверът не знае нито едно
 * от числата им и ги получава наготово. Сгрешен знаменател тук не пада никъде: той просто удържа
 * друга сума и фишът излиза сметнат. Затова редовете се сверяват срещу твърдение, написано отделно
 * от тях.
 *
 * <p><b>⚠️ ТВЪРДЕНИЕТО ТУК Е ПРЕПИСАНО ОТ НОРМАТА, НЕ ОТ SEED-А.</b> Тест, който чете очакваното от
 * същия файл, който проверява, мери само че файлът е равен на себе си. Затова шестте реда стоят
 * ИЗБРОЕНИ в {@link #EXPECTED} — при промяна в seed-а някой трябва да пипне и двете места и точно
 * това е смисълът.
 *
 * <p><b>Границата на този тест.</b> Той пита дали ДАННИТЕ са верни. Дали АЛГОРИТЪМЪТ ги прилага
 * вярно е друг въпрос и си има златни числа в {@code BgGarnishmentResolverTest}; дали са ДОСТАВЕНИ
 * в базата е трети и е базовата половина. Трите не се сливат: слети, отговорът „App Install не е
 * пускан“ би маскирал отговора „знаменателят е сгрешен“.
 *
 * <p>Провенанс: ГПК чл. 446 ал. 1 (ред. ДВ бр. 86/2017), ал. 3 (нова, ДВ бр. 55/2025); сверено
 * срещу официалния ГПК (justice.government.bg) на 22.08.2026 г. Дословният текст живее в самите
 * редове на seed-а и в домейн-коментара на {@code BgGarnishmentScale} — тук се проверява, че е ТАМ,
 * а не се преписва трети път.
 */
class BgGarnishmentScaleSeedFileTest {

  private static final String FILE = "bg_garnishment_scale.csv";

  /**
   * ⚠️ ОЧАКВАНОТО, ПРЕПИСАНО ОТ НОРМАТА: праг · с деца → от×МРЗ, до×МРЗ, дял, оставащо×МРЗ.
   *
   * <p>{@code null} значи „тази колона не важи за този праг“ — горна граница за праг 3, дял за праг
   * 3, оставащо кратно за прагове 1 и 2.
   */
  private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("1|false", "1.00|2.00|1/3|null");
    EXPECTED.put("1|true", "1.00|2.00|1/4|null");
    EXPECTED.put("2|false", "2.00|4.00|1/2|null");
    EXPECTED.put("2|true", "2.00|4.00|1/3|null");
    EXPECTED.put("3|false", "4.00|null|null|2.00");
    EXPECTED.put("3|true", "4.00|null|null|2.50");
  }

  private static String blankAsNull(String value) {
    return value == null || value.trim().isEmpty() ? "null" : value.trim();
  }

  /** Ред от seed-а, сведен до „от|до|дял|оставащо“ — същата форма като {@link #EXPECTED}. */
  private static Map<String, String> actual() {
    List<String> header = BgSeed.quotedHeader(FILE);
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String numerator = blankAsNull(BgSeed.col(header, row, "bgNumerator"));
      String denominator = blankAsNull(BgSeed.col(header, row, "bgDenominator"));
      String share = "null".equals(numerator) ? "null" : numerator + "/" + denominator;
      out.put(
          BgSeed.col(header, row, "bgThreshold").trim()
              + "|"
              + BgSeed.col(header, row, "bgWithChildren").trim(),
          blankAsNull(BgSeed.col(header, row, "bgFromMultiple"))
              + "|"
              + blankAsNull(BgSeed.col(header, row, "bgToMultiple"))
              + "|"
              + share
              + "|"
              + blankAsNull(BgSeed.col(header, row, "bgKeepMultiple")));
    }
    return out;
  }

  // ═══ 1 · САМАТА СКАЛА ═════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "ФАЙЛ · шестте реда са точно скалата по чл. 446 ал. 1 — 1/3 · 1/4 · 1/2 · 1/3 · 2× · 2,5×")
  void theSixRowsAreTheStatutoryScale() {
    assertEquals(
        EXPECTED,
        actual(),
        "⚠️ seed-ът се е разминал с чл. 446 ал. 1. Сгрешен знаменател НЕ пада никъде — той просто"
            + " удържа друга сума и фишът излиза сметнат.");
  }

  /**
   * ⚠️ ДЯЛЪТ Е ДРОБ, НЕ ДЕСЕТИЧНО ЧИСЛО — и това е закова́но, защото поправката е изкушаваща.
   *
   * <p>Една колона {@code bgShare = 0.3333} изглежда по-просто и дава върху 930,30 сумата
   * 310,0899…, тоест 310,09 вместо верните 310,10. Стотинка, която никой отчет не може да защити, и
   * разлика, която не се вижда на нито един фиш.
   */
  @Test
  @DisplayName("⚠️ ФАЙЛ · дяловете са числител/знаменател, не закръглена десетична дроб")
  void theShareIsKeptAsAFractionNotADecimal() {
    List<String> header = BgSeed.quotedHeader(FILE);
    assertTrue(
        header.contains("bgNumerator") && header.contains("bgDenominator"), header.toString());
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String numerator = blankAsNull(BgSeed.col(header, row, "bgNumerator"));
      String denominator = blankAsNull(BgSeed.col(header, row, "bgDenominator"));
      if ("null".equals(numerator)) {
        assertEquals(
            "null",
            denominator,
            "⚠️ полудял: числителят е празен, а знаменателят не — редът не казва нищо смислено");
        continue;
      }
      assertTrue(
          numerator.matches("\\d+") && denominator.matches("\\d+"),
          "⚠️ дялът трябва да е ЦЕЛИ числа: " + numerator + "/" + denominator);
      assertTrue(
          Integer.parseInt(denominator) > 0,
          "знаменател нула би разделил на нула при първото прилагане");
    }
  }

  /**
   * ⚠️ ТРИТЕ ПРАГА ПОКРИВАТ ЦЯЛАТА ОС БЕЗ ДУПКА И БЕЗ ПРИПОКРИВАНЕ.
   *
   * <p>Дупка значи доход, за който скалата мълчи — а мълчанието се чете като „не се удържа нищо“,
   * тоест взискателят губи. Припокриване значи два реда за един доход и тогава кой печели зависи от
   * подредбата на заявката, не от нормата.
   */
  @Test
  @DisplayName("⚠️ ФАЙЛ · праговете се долепят: 1→2→4→∞, без дупка и без припокриване")
  void theThreeBandsTileTheAxis() {
    List<String> header = BgSeed.quotedHeader(FILE);
    for (String withChildren : new String[] {"false", "true"}) {
      List<String> bounds = new ArrayList<>();
      for (String[] row : BgSeed.quotedRows(FILE)) {
        if (withChildren.equals(BgSeed.col(header, row, "bgWithChildren").trim())) {
          bounds.add(
              blankAsNull(BgSeed.col(header, row, "bgFromMultiple"))
                  + "→"
                  + blankAsNull(BgSeed.col(header, row, "bgToMultiple")));
        }
      }
      assertEquals(
          "[1.00→2.00, 2.00→4.00, 4.00→null]",
          bounds.toString(),
          "⚠️ горната граница на всеки праг трябва да Е долната на следващия (с деца: "
              + withChildren
              + ")");
    }
  }

  // ═══ 2 · ПРОВЕНАНСЪТ И КАПАНЪТ ════════════════════════════════════════════

  /**
   * ⚠️ ЛЕВОВАТА ВЕРСИЯ Е ЗАСТОЯЛА И ТОВА Е ЗАПИСАНО ВЪВ ВСЕКИ РЕД.
   *
   * <p>Праговете 300 / 600 / 1200 лв. са от редакцията ОТПРЕДИ ДВ бр. 86/2017 и още се срещат в
   * стари наръчници и форуми. Изглеждат правдоподобно — затова капанът е записан ТАМ, където ще го
   * прочете човекът, който тръгва да „поправя“ реда, а не в отделен документ, който няма да отвори.
   */
  @Test
  @DisplayName("⚠️ ФАЙЛ · всеки ред носи акта, датата и КАПАНА с левовите прагове")
  void everyRowCarriesItsProvenanceAndTheStaleTrap() {
    List<String> header = BgSeed.quotedHeader(FILE);
    int rows = 0;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String basis = BgSeed.col(header, row, "bgLegalBasis");
      String id = BgSeed.col(header, row, "importId");
      assertTrue(basis.contains("чл. 446 ал. 1"), id + ": липсва членът — " + basis);
      assertTrue(basis.contains("ДВ бр. 86/2017"), id + ": липсва редакцията — " + basis);
      assertTrue(
          basis.contains("300/600/1200"), id + ": липсва капанът с левовите прагове — " + basis);
      // ⚠️ ДОСЛОВНИЯТ ТЕКСТ, А НЕ ПРЕРАЗКАЗ. Тези редове носят самия текст на нормата, сверен
      // срещу официалния ГПК. Проверява се откъс, който НЕ може да оцелее при преразказване —
      // точната формулировка на условието за децата, — и обявата, че сверката е срещу официалния
      // източник.
      assertTrue(
          basis.contains("ако е с деца, които издържа"),
          id + ": липсва дословната формулировка на нормата — " + basis);
      assertTrue(basis.contains("ДОСЛОВНО"), id + ": редът не обявява, че цитира — " + basis);
      assertTrue(
          basis.contains("justice.government.bg"),
          id + ": липсва срещу какво е сверен текстът — " + basis);
      rows++;
    }
    assertEquals(6, rows, "скалата е три прага по две редакции — нито ред повече, нито по-малко");
  }

  /**
   * ⚠️ ПОДЪТ ПО АЛ. 3 Е ОБЯВЕН ТАМ, КЪДЕТО ОБВЪРЗВА — при праг 1.
   *
   * <p>При доход малко над МРЗ делът отнема повече, отколкото ал. 3 позволява, и удържимото пада до
   * „нето минус МРЗ“. При прагове 2 и 3 подът е в сила, но никога не стига да се задейства — затова
   * се обявява на реда, на който има значение.
   */
  @Test
  @DisplayName("ФАЙЛ · подът по ал. 3 (ДВ бр. 55/2025) е записан на прага, на който обвързва")
  void theFloorFromParagraphThreeIsRecordedWhereItBinds() {
    List<String> header = BgSeed.quotedHeader(FILE);
    TreeSet<String> mentioning = new TreeSet<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      if (BgSeed.col(header, row, "bgLegalBasis").contains("ДВ бр. 55/2025")) {
        mentioning.add(BgSeed.col(header, row, "bgThreshold").trim());
      }
    }
    if (!mentioning.contains("1")) {
      fail(
          "подът по ал. 3 не е обявен на праг 1, а точно там обвързва. Обявен на прагове: "
              + mentioning);
    }
  }

  /** Скалата важи за периодите, които модулът смята — 2026 г. */
  @Test
  @DisplayName("ФАЙЛ · всички редове са в сила от 01.01.2026 г. и без край")
  void everyRowIsInForceForTheTwentyTwentySixPeriods() {
    List<String> header = BgSeed.quotedHeader(FILE);
    for (String[] row : BgSeed.quotedRows(FILE)) {
      assertEquals(
          "2026-01-01",
          BgSeed.col(header, row, "validFrom").trim(),
          BgSeed.col(header, row, "importId"));
      assertEquals(
          "",
          BgSeed.col(header, row, "validTo").trim(),
          "⚠️ затворен край би оставил периодите след него БЕЗ скала, тоест без удържане изобщо");
    }
  }
}
