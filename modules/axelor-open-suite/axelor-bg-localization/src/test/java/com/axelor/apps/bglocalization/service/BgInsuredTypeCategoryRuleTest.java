package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВИЛОТО „вид осигурен ↔ категория труд“, проверено БЕЗ база и БЕЗ seed.
 *
 * <p>Наборът категории се подава като стойност, тоест твърденията отдолу са за самото ПРАВИЛО, а не
 * за днешното съдържание на каталога. Кой набор идва от кой източник се проверява другаде: от
 * {@code BgInsuredTypeHomeFileTest} за seed-а и от {@code BgInsuredTypeCategorySaveGuardDbTest} за
 * таблицата.
 */
class BgInsuredTypeCategoryRuleTest {

  /**
   * Днешният набор на код 01: САМО трета категория. Стойност, не прочит — виж класовия коментар.
   */
  private static final Set<String> ONLY_THIRD = only("3");

  private static Set<String> only(String... categories) {
    return new TreeSet<>(Arrays.asList(categories));
  }

  // ═══ ОТКАЗЪТ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · (01, първа категория) — двойка без нито един ред със ставки")
  void anImpossiblePairIsRefused() {
    String refusal = BgInsuredTypeCategoryRule.disagreement("01", "1", ONLY_THIRD);
    assertNotNull(
        refusal,
        "⚠️ Двойката (01, 1) няма нито един ред в "
            + BgInsuredTypeCategoryRule.CATALOGUE
            + " и не бива да се записва без възражение, за да гърми чак на фиша.");
  }

  @Test
  @DisplayName("⚠️ СЪОБЩЕНИЕТО НАЗОВАВА И ДВЕТЕ ПОЛЕТА")
  void theRefusalNamesBothFields() {
    String refusal = BgInsuredTypeCategoryRule.disagreement("01", "1", ONLY_THIRD);

    assertTrue(
        refusal.contains("вид осигурен 01"),
        "съобщението не назовава ПЪРВОТО поле със стойността му: " + refusal);
    assertTrue(
        refusal.contains("категория труд 1"),
        "⚠️ Съобщение само с „вид осигурен: 01“ праща оператора да поправя първото поле, а"
            + " сгрешено може да е второто. Ключът в каталога е"
            + " ДВОЙКАТА и празният набор не казва кое от двете е сгрешено: "
            + refusal);
    assertTrue(
        refusal.contains("няма ставки за тази комбинация"),
        "съобщението не казва ЗАЩО двойката е отказана: " + refusal);
    assertTrue(
        refusal.contains("Employee.bgLaborCategory")
            && refusal.contains("EmploymentContract.bgInsuredTypeCode"),
        "⚠️ Двете полета са на ДВА РАЗЛИЧНИ ЕКРАНА — категорията на служителя, видът осигурен на"
            + " договора. Съобщение, което не казва къде се води всяко, оставя оператора да търси"
            + " второто поле там, където го няма: "
            + refusal);
    assertTrue(
        refusal.contains("3"),
        "съобщението трябва да каже КОЕ е позволено, инак операторът не знае с какво да го замени: "
            + refusal);
  }

  @Test
  @DisplayName("Отказът на фиша и отказът при запис назовават двойката с ЕДИН И СЪЩ низ")
  void thePayslipAndTheSaveNameThePairIdentically() {
    String head = BgInsuredTypeCategoryRule.noRatesFor("01", "1");
    assertEquals("вид осигурен 01 + категория труд 1: няма ставки за тази комбинация", head);
    assertTrue(
        BgInsuredTypeCategoryRule.disagreement("01", "1", ONLY_THIRD).startsWith(head),
        "⚠️ Отказът при ЗАПИС трябва да започва с точно същото изречение, с което отказва и фишът"
            + " (BgContributionResolver → NO_CONTRIBUTIONS_FOUND). Разминат текст за едно и също"
            + " състояние прави двата екрана да изглеждат като два различни проблема.");
  }

  /** Непопълненото поле се назовава като непопълнено, а не изчезва от изречението. */
  @Test
  void theNameOfThePairSurvivesAMissingField() {
    assertEquals(
        "вид осигурен 01 + категория труд (непопълнен): няма ставки за тази комбинация",
        BgInsuredTypeCategoryRule.noRatesFor("01", null));
    assertEquals(
        "вид осигурен (непопълнен) + категория труд 3: няма ставки за тази комбинация",
        BgInsuredTypeCategoryRule.noRatesFor("  ", "3"));
  }

  // ═══ КОНТРОЛИТЕ · без тях правилото би минало зелено и като „отказвай всичко“ ══

  @Test
  @DisplayName("КОНТРОЛА · (01, трета категория) — съгласувана двойка, записът минава")
  void thePairWithRowsPasses() {
    assertNull(
        BgInsuredTypeCategoryRule.disagreement("01", "3", ONLY_THIRD),
        "код 01 има редове точно за трета категория — това е обикновеният трудов договор и"
            + " пазачът не бива да го спира");
  }

  @Test
  @DisplayName("КОНТРОЛА · код с трите категории приема всяка от тях")
  void aCodeCoveringEveryCategoryPassesForEach() {
    Set<String> all = only("1", "2", "3");
    for (String category : all) {
      assertNull(
          BgInsuredTypeCategoryRule.disagreement("04", category, all),
          "код 04 носи редове и за трите категории — категория " + category + " е съгласувана");
    }
  }

  @Test
  @DisplayName("⚠️ МЪЛЧАНИЕТО НА ДАННИТЕ НЕ Е ЗАБРАНА · код без нито един ред не ограничава нищо")
  void anEmptyCatalogueForTheCodeForbidsNothing() {
    assertNull(
        BgInsuredTypeCategoryRule.disagreement("99", "1", Collections.emptySet()),
        "⚠️ Празният набор значи „каталогът МЪЛЧИ за този код“, не „нищо не е позволено“. Прочетен"
            + " като забрана, той би отказал ВСЕКИ договор с код без ставки — тоест правилото щеше"
            + " да мери липсата на данни вместо самите данни.");
    assertNull(
        BgInsuredTypeCategoryRule.disagreement("99", "1", null),
        "липсващият набор (кодът изобщо го няма в речника) се чете като същото мълчание");
  }

  @Test
  @DisplayName("Незададеният въпрос не е разминаване — празно поле се отказва другаде")
  void anEmptyFieldIsNotADisagreement() {
    assertNull(BgInsuredTypeCategoryRule.disagreement(null, "1", ONLY_THIRD));
    assertNull(BgInsuredTypeCategoryRule.disagreement("   ", "1", ONLY_THIRD));
    assertNull(BgInsuredTypeCategoryRule.disagreement("01", null, ONLY_THIRD));
    assertNull(BgInsuredTypeCategoryRule.disagreement("01", "   ", ONLY_THIRD));

    assertTrue(BgInsuredTypeCategoryRule.isAnswerable("01", "1"));
    assertTrue(!BgInsuredTypeCategoryRule.isAnswerable("01", " "));
    assertTrue(!BgInsuredTypeCategoryRule.isAnswerable(null, "1"));
  }

  /** Обкръжаващите интервали не правят валидната двойка невалидна — ключът се тримва навсякъде. */
  @Test
  void surroundingBlanksDoNotChangeTheVerdict() {
    assertNull(BgInsuredTypeCategoryRule.disagreement(" 01 ", " 3 ", only(" 3 ")));
    assertNotNull(BgInsuredTypeCategoryRule.disagreement(" 01 ", " 1 ", only(" 3 ")));
  }

  /**
   * Редът в съобщението е подреден, не какъвто дойде — инак текстът се мени без промяна в данните.
   */
  @Test
  void theAllowedCategoriesAreListedInAStableOrder() {
    Set<String> shuffled = new LinkedHashSet<>(Arrays.asList("3", "1", "2"));
    String refusal = BgInsuredTypeCategoryRule.disagreement("04", "9", shuffled);
    assertTrue(refusal.contains("1, 2, 3"), refusal);
  }
}
