package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТНИЯТ ОБРАЗЕЦ НА Д6 — <b>структурата, проверена без BIRT и без база</b>.
 *
 * <p><b>⚠️ КАКВО ТОЗИ СВИДЕТЕЛ ДОКАЗВА И КАКВО НЕ.</b> Той доказва, че отчетът е СЪГЛАСЕН СЪС САМИЯ
 * СЕБЕ СИ: всяко поле на образеца има ред в набора данни, всеки ред от набора има точно едно място,
 * където се показва, и нито едно от осемнайсетте полета на Приложение № 4 не липсва. Той НЕ
 * доказва, че PDF-ът излиза красив — това се вижда само с очи, и е човешка стъпка.
 *
 * <p><b>⚠️ И ЗАЩО НЕ РЕНДИРА.</b> Рендирането иска работещ BIRT engine И жива база (наборът данни е
 * JDBC заявка). Тест, който ги иска, се ПРОПУСКА на всяка машина без база — тоест твърдението му би
 * съществувало само на хартия. Двете половини стоят разделени по същия довод като при схемата:
 * файловата тече винаги, базовата пита нещо, което файлът не може да знае. Че ИМЕНАТА на колоните
 * съществуват, се сверява в {@code BgReportQueryColumnTest}; че заявката се ИЗПЪЛНЯВА — в {@code
 * BgD1ReportQueryDbTest}, който се пропуска без база.
 */
class BgD6ReportDesignTest {

  private static final String DESIGN = "src/main/resources/reports/BgD6Declaration.rptdesign";

  /**
   * Всичките ОСЕМНАЙСЕТ полета на Приложение № 4, преброени по указанието.
   *
   * <p>⚠️ Т. 9 нарочно я НЯМА в набора данни: месецът и годината идват като ПАРАМЕТЪР от периода на
   * декларацията, а не като колона — {@code bgPeriod} е препратка, не две числа. Затова тя се
   * проверява отделно, като параметър.
   */
  private static final List<String> POINTS =
      Arrays.asList(
          "t1", "t2", "t3", "t4", "t5", "t6", "t71", "t81", "t10", "t11", "t12", "t13", "t14",
          "t15", "t16", "t17", "t18");

  private static String design() throws IOException {
    Path path = Paths.get("").toAbsolutePath().resolve(DESIGN);
    assertTrue(Files.isRegularFile(path), "липсва самият образец: " + path);
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("всичките 17 полета от набора данни имат ред, връзка и място за показване")
  void everyPointIsWiredEndToEnd() throws IOException {
    String xml = design();
    List<String> problems = new ArrayList<>();

    for (String alias : POINTS) {
      if (!xml.contains("<property name=\"nativeName\">" + alias + "</property>")) {
        problems.add(alias + ": няма ред в resultSet на набора данни");
      }
      if (!xml.contains("dataSetRow[\"" + alias + "\"]")) {
        problems.add(alias + ": няма връзка (boundDataColumns) — стойността няма откъде да дойде");
      }
      if (!xml.contains("<property name=\"resultSetColumn\">" + alias + "</property>")) {
        problems.add(alias + ": няма елемент, който да я ПОКАЗВА — полето е вързано и невидимо");
      }
    }
    assertTrue(
        problems.isEmpty(),
        "⚠️ Вързана и непоказана стойност е най-тихият дефект в BIRT: отчетът се рендира БЕЗ грешка"
            + " и просто няма полето. Проверката е в ТРИ стъпки нарочно — трите места се пишат"
            + " поотделно и всяко от тях може да остане недописано.\n  "
            + String.join("\n  ", problems));
  }

  @Test
  @DisplayName("⚠️ т. 9 (месец и година) идва като ПАРАМЕТЪР, а не като колона")
  void thePeriodComesAsAParameter() throws IOException {
    String xml = design();
    assertTrue(
        xml.contains("<scalar-parameter name=\"PeriodLabel\""),
        "т. 9 се пълни от периода на декларацията, а той е ПРЕПРАТКА (Period), не две числа на реда."
            + " Колона за нея не съществува и не бива да се измисля.");
    assertTrue(
        xml.contains("params[\"PeriodLabel\"]"),
        "параметърът съществува и НЕ се ползва — тоест т. 9 липсва от разпечатката");
  }

  @Test
  @DisplayName("наборът данни чете ЕДИН ред — Д6 е агрегат на ДРУЖЕСТВОТО")
  void theDataSetReadsExactlyOneRow() throws IOException {
    String xml = design();
    assertTrue(
        xml.contains("from bglocalization_bg_d6_declaration"),
        "източникът е самата декларация, не записи по лице");
    assertTrue(
        xml.contains("where id = ?"),
        "⚠️ ПО ID НА ДЕКЛАРАЦИЯТА, а не по родител. Д6 няма дъщерни записи — построен по образеца на"
            + " Д1 (таблица с детайлен ред), отчетът щеше да иска колекция, която не съществува.");
    assertTrue(
        !xml.contains("<table "),
        "⚠️ ФОРМУЛЯР, А НЕ ТАБЛИЦА. Таблица с един ред би отпечатала осемнайсет числа без имена — а"
            + " човекът, който подписва, сверява ПО НОМЕР НА ТОЧКА срещу образеца на НАП.");
  }

  @Test
  @DisplayName("⚠️ трите разлики в групирането спрямо Д1 са НАПИСАНИ върху разпечатката")
  void theThreeGroupingDifferencesAreOnThePaper() throws IOException {
    String xml = design();
    assertTrue(
        xml.contains("ТЗПБ е ВЪТРЕ в т. 10"),
        "⚠️ ЕДИНСТВЕНАТА разлика, която мени СУМА: Д1 т. 22 е „БЕЗ ТЗПБ“ и ѝ дава своя точка 22.2, а"
            + " Д6 т. 10 иска вноските за фондовете на ДОО без изключение. Човек, който сверява двете"
            + " декларации, вижда разлика от порядъка на половин процент и няма как да знае защо.");
    assertTrue(
        xml.contains("т. 12 е УПФ и т. 13 е ППФ"),
        "ДЗПО е с ОБЪРНАТИ номера спрямо Д1 (там т. 25 е ППФ, т. 26 е УПФ), а двете суми са от един"
            + " и същи порядък — тоест размяната не се вижда от числата");
    assertTrue(
        xml.contains("НАМАЛЯВА"),
        "⚠️ Корекцията на Д6 работи ОБРАТНО на интуицията (чл. 9 ал. 7). Обратното четене дава"
            + " декларация, която НАП ПРИЕМА и която удвоява задължението.");
  }

  @Test
  @DisplayName("всеки резултатен ред на набора данни се ПОКАЗВА — няма мъртви колони")
  void noResultSetColumnIsUnused() throws IOException {
    String xml = design();
    Set<String> declared = new LinkedHashSet<>();
    Matcher m =
        Pattern.compile("<property name=\"nativeName\">([a-z0-9]+)</property>").matcher(xml);
    while (m.find()) {
      declared.add(m.group(1));
    }
    assertEquals(
        POINTS.size(),
        declared.size(),
        "наборът данни носи точно толкова колони, колкото точки се печатат: " + declared);
    for (String alias : declared) {
      assertTrue(
          xml.contains("<property name=\"resultSetColumn\">" + alias + "</property>"),
          "колона „"
              + alias
              + "“ се чете от базата и НИКЪДЕ не се показва. ⚠️ Тя не е безвредна: заявката я носи,"
              + " тоест някой е смятал, че полето е на разпечатката.");
    }
  }
}
