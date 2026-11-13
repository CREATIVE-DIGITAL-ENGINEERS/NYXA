package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgInsuredTypeCategoryRule;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗЪТ ПРИ ЗАПИС НА ДОГОВОРА, СВЕРЕН СРЕЩУ ДЕЙСТВИТЕЛНИЯ КАТАЛОГ В БАЗАТА.
 *
 * <p><b>Защо срещу базата, а не срещу seed-а.</b> При ЗАПИС наборът категории идва от таблицата
 * {@value #T} през {@code BgInsuranceCalculationService#laborCategoriesWithRates}, не от файла.
 * Тоест решението, което операторът вижда, зависи от това, което App Install е доставил. Тест само
 * срещу файла би бил зелен и при празна таблица — тоест точно тогава, когато записът пуска всичко.
 *
 * <p><b>Какво се проверява.</b> Характеризиращият случай от договор #6, минат през СЪЩИЯ набор,
 * който минава и записът: {@code (01, първа категория)} → отказ с двуполево съобщение, {@code (01,
 * трета категория)} → минава. И анти-drift между двата ИЗТОЧНИКА на набора — таблицата и seed-а —
 * защото пазачът чете файла, а записът чете базата.
 *
 * <p><b>Пропуска се при две състояния на средата</b>, всяко с обявена причина: няма достъпна база,
 * или таблицата с каталога е празна (App Install не е пускан). Празна таблица НЕ е дефект в кода —
 * тя е липсваща доставка, и мълчаливо червено за нея приучава да не се гледа.
 */
class BgInsuredTypeCategorySaveGuardDbTest {

  /** Таблицата с каталога — {@code BgInsuranceContribution}, @Table.name() с ГЛАВНИ букви. */
  private static final String T = "bglocalization_bg_insurance_contribution";

  /** Кодът и категорията от находката: договор #6 носеше вид осигурен 01. */
  private static final String CODE = "01";

  private static Connection open() {
    String url = BgTestDb.url();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, BgTestDb.user(), BgTestDb.password());
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — твърдението „записът отказва невъзможната двойка“ НЕ е проверено срещу"
              + " действителния каталог. Правилото само по себе си тече без база в"
              + " BgInsuredTypeCategoryRuleTest.");
      return null;
    }
  }

  /**
   * Наборът, който записът сверява — СЪЩИЯТ прочит като {@code laborCategoriesWithRates}: без дата.
   */
  private static Map<String, Set<String>> categoriesByCode(Connection c) throws Exception {
    Map<String, Set<String>> out = new TreeMap<>();
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT bg_insured_type_code, labor_category FROM "
                    + T
                    + " WHERE bg_insured_type_code IS NOT NULL"
                    + " AND labor_category IS NOT NULL")) {
      while (rs.next()) {
        String code = rs.getString(1).trim();
        String category = rs.getString(2).trim();
        if (!code.isEmpty() && !category.isEmpty()) {
          out.computeIfAbsent(code, k -> new TreeSet<>()).add(category);
        }
      }
    }
    return out;
  }

  private static Map<String, Set<String>> catalogue(Connection c) throws Exception {
    Map<String, Set<String>> byCode = categoriesByCode(c);
    if (byCode.isEmpty()) {
      BgSkip.because(
          "таблицата "
              + T
              + " е ПРАЗНА ("
              + BgTestDb.configPath()
              + ") — App Install не е доставял каталога със ставките. Това е състояние на средата,"
              + " не на кода: без редове наборът е празен за всеки код и записът пропуска всичко,"
              + " защото МЪЛЧАНИЕТО НА ДАННИТЕ НЕ Е ЗАБРАНА.");
    }
    return byCode;
  }

  // ═══ ХАРАКТЕРИЗИРАЩИЯТ СЛУЧАЙ ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ БАЗА · (01, първа категория) се ОТКАЗВА при запис, с двуполево съобщение")
  void theImpossiblePairIsRefusedOnSave() throws Exception {
    try (Connection c = open()) {
      Set<String> forCode = catalogue(c).get(CODE);
      assertNotNull(
          forCode,
          "вид осигурен "
              + CODE
              + " няма нито един ред в "
              + T
              + " — това е най-често срещаният трудов договор и празнотата му сочи непълна"
              + " доставка, не правилно състояние");

      String refusal = BgInsuredTypeCategoryRule.disagreement(CODE, "1", forCode);
      assertNotNull(
          refusal,
          "⚠️ ТОВА Е НАХОДКАТА ОТ ДОГОВОР #6. Каталогът в базата носи редове за вид осигурен "
              + CODE
              + " само за категория(и) "
              + forCode
              + ", тоест двойката ("
              + CODE
              + ", 1) е ПРАЗЕН набор. Дотук тя се записваше без възражение и гърмеше чак на фиша.");

      assertTrue(refusal.contains("вид осигурен " + CODE), refusal);
      assertTrue(
          refusal.contains("категория труд 1"),
          "⚠️ Съобщението трябва да назове И ВТОРОТО поле. Старото казваше само вида осигурен и"
              + " пращаше оператора към грешния екран: "
              + refusal);
      assertTrue(refusal.contains("няма ставки за тази комбинация"), refusal);
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · КОНТРОЛА · (01, трета категория) МИНАВА записа")
  void theValidPairPassesOnSave() throws Exception {
    try (Connection c = open()) {
      Set<String> forCode = catalogue(c).get(CODE);
      assertNull(
          BgInsuredTypeCategoryRule.disagreement(CODE, "3", forCode),
          "⚠️ БЕЗ ТАЗИ КОНТРОЛА тестът отгоре би минал и с валидатор, който отказва ВСЯКА двойка —"
              + " тоест с пазач, който спира целия модул. Вид осигурен "
              + CODE
              + " при трета категория е обикновеният трудов договор и трябва да се записва.");
    }
  }

  // ═══ ДВАТА ИЗТОЧНИКА НА ЕДИН НАБОР ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ БАЗА · таблицата и seed-ът дават ЕДИН И СЪЩ набор категории за всеки код")
  void theTableAndTheSeedAgreeOnTheAllowedCategories() throws Exception {
    try (Connection c = open()) {
      Map<String, Set<String>> fromDb = catalogue(c);
      Map<String, Set<String>> fromSeed = BgInsuredTypeHomeFileTest.allowedLaborCategories();

      // ⚠️ ПРИСЪСТВИЕ, НЕ БРОЙ. Сверяват се само кодовете, които са в ДВАТА източника: код само в
      // seed-а значи „App Install не е пускан след последната промяна“, а код само в базата — стар
      // ред, който никой не е изтрил. И двете са състояние на средата и се докладват, не падат.
      List<String> onlyInSeed = new ArrayList<>(new TreeSet<>(fromSeed.keySet()));
      onlyInSeed.removeAll(fromDb.keySet());
      List<String> onlyInDb = new ArrayList<>(new TreeSet<>(fromDb.keySet()));
      onlyInDb.removeAll(fromSeed.keySet());
      if (!onlyInSeed.isEmpty() || !onlyInDb.isEmpty()) {
        System.out.println(
            "ℹ КОДОВЕ САМО В ЕДИН ИЗТОЧНИК (възрастта на базата, не дефект): само в seed-а "
                + onlyInSeed
                + " · само в базата "
                + onlyInDb);
      }

      List<String> shared = new ArrayList<>(new TreeSet<>(fromSeed.keySet()));
      shared.retainAll(fromDb.keySet());
      assertTrue(
          shared.size() >= 20,
          "котвата: само "
              + shared.size()
              + " общи кода между "
              + T
              + " и seed-а — сравнението отдолу не сверява почти нищо");

      List<String> mismatched = new ArrayList<>();
      for (String code : shared) {
        if (!fromSeed.get(code).equals(fromDb.get(code))) {
          mismatched.add(
              "вид осигурен "
                  + code
                  + ": seed "
                  + fromSeed.get(code)
                  + " ≠ база "
                  + fromDb.get(code));
        }
      }

      assertEquals(
          new ArrayList<String>(),
          mismatched,
          "⚠️ ДВАТА ИЗТОЧНИКА НА ЕДНО ПРАВИЛО СЕ РАЗМИНАВАТ. Пазачът (BgInsuredTypeHomeFileTest)"
              + " чете seed-а, а ОТКАЗЪТ ПРИ ЗАПИС чете базата — тоест договор може да мине записа"
              + " и да падне на сверката, или обратното, и нито едното не се вижда на екрана."
              + " Проверете дали App Install е пускан след последната промяна в"
              + " bg_insurance_contribution.csv.");
    }
  }
}
