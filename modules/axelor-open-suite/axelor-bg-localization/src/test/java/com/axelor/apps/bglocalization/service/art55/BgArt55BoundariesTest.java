package com.axelor.apps.bglocalization.service.art55;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАНИЦИТЕ НА ДЕКЛАРАЦИЯТА ПО ЧЛ. 55 — текстът, който човек чете, преди да поеме отговорността по
 * образеца („За деклариране на неверни данни се носи наказателна отговорност по Наказателния
 * кодекс“).
 *
 * <p>⚠️ Тестът пази ЧИСЛАТА и НОМЕРАТА НА РАЗПОРЕДБИТЕ, а не формулировките. Тъкмо те правят
 * разликата между „възможно е да има нещо“ и „ето толкова, ето къде“.
 */
class BgArt55BoundariesTest {

  // ═══ ЧЕТВЪРТОТО ТРИМЕСЕЧИЕ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · Q4 назовава потиснатата СУМА и 31 януари, а не само правилото")
  void fourthQuarterNamesTheSuppressedAmount() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.quarter = 4;
    facts.q4SuppressedRows = 2;
    facts.q4SuppressedAmount = new BigDecimal("165.00");

    String text = BgArt55Boundaries.report(facts);

    assertTrue(cites(text, "чл. 65 ал. 13"), "разпоредбата е ал. 13, не ал. 12: " + text);
    assertTrue(text.contains("165.00"), "сумата, която се връща ръчно, стои в текста: " + text);
    assertTrue(text.contains("2 реда с авансов данък"), "и броят редове: " + text);
    assertTrue(text.contains("31 ЯНУАРИ"), "другият срок при писмено поискано удържане: " + text);
    assertTrue(text.contains("чл. 44а, ал. 1"), "ал. 13 обхваща и чл. 44а: " + text);
  }

  /**
   * ⚠️ ВАЛУТАТА Е ЧАСТ ОТ ЧИСЛОТО, А НЕ УКРАСА. Сумата тук се ПРЕПИСВА на ръка в портала на НАП:
   * човек чете „165.00 …“ и набира толкова. От 01.01.2026 г. обр. 4001 се подава в евро, а текстът
   * казваше „лв.“ — единственият валутен литерал, който модулът изписва по време на работа, и той
   * твърдеше другата валута. Разликата между двете е 1,95583 пъти, тоест не е козметична.
   *
   * <p>⚠️ И ЗАБРАНАТА, А НЕ САМО ПРИСЪСТВИЕТО: „€“ някъде в текста не опровергава „лв.“ другаде.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · потиснатата сума е в ЕВРО — никъде в границите не пише „лв.“")
  void suppressedAmountIsDenominatedInEuro() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.quarter = 4;
    facts.q4SuppressedRows = 1;
    facts.q4SuppressedAmount = new BigDecimal("165.00");

    String text = BgArt55Boundaries.report(facts);

    assertTrue(text.contains("165.00 €"), "сумата се чете заедно с валутата си: " + text);
    assertFalse(
        text.contains("лв."),
        "обр. 4001 се подава в евро от 01.01.2026 г. — левът няма какво да прави тук: " + text);
  }

  @Test
  @DisplayName("извън Q4 текстът казва, че правилото НЕ се прилага — и не показва суми")
  void otherQuartersSayTheRuleDoesNotApply() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.quarter = 1;

    String text = BgArt55Boundaries.report(facts);

    assertTrue(text.contains("Не се прилага за това тримесечие"), text);
    assertFalse(
        text.contains("И СРОКЪТ ТОГАВА Е ДРУГ"),
        "указанието да се върне сума и да се смени датата е САМО за Q4: " + text);
    assertFalse(
        text.contains("реда с авансов данък"),
        "потиснати редове извън Q4 няма — броят им не бива да се показва: " + text);
  }

  // ═══ ОБЯВЕНИТЕ ИЗВЪН ОБХВАТА ═══════════════════════════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · редове 4 и 5 (ЗКПО чл. 194/195) са НАЗОВАНИ като извън обхвата")
  void corporateWithholdingRowsAreNamed() {
    String text = BgArt55Boundaries.report(new BgArt55Boundaries.Facts());

    assertTrue(text.contains("чл. 194"), text);
    assertTrue(text.contains("чл. 195"), text);
    assertTrue(text.contains("ФИЗИЧЕСКИ ЛИЦА"), "причината е обхватът на регистъра: " + text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · приложенията 4011/4021/4031/4041 са назовани, а не построени")
  void annexesAreNamedNotBuilt() {
    String text = BgArt55Boundaries.report(new BgArt55Boundaries.Facts());

    assertTrue(text.contains("4011"), text);
    assertTrue(text.contains("4041"), text);
    assertTrue(text.contains("чл. 73а"), "основанието за автоматичния обмен: " + text);
    assertTrue(text.contains("чл. 123 ал. 10 ЗДДС"), "основанието за образец 4041: " + text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · колона 4 е обявена като ЧУЖДА, с броя отклонени редове")
  void recipientColumnIsDeclaredForeign() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.selfInsured = 1;

    String text = BgArt55Boundaries.report(facts);

    assertTrue(text.contains("КОЛОНА 4"), text);
    assertTrue(text.contains("1 ред е отклонен натам"), "единствено число при един ред: " + text);
    assertTrue(cites(text, "чл. 43 ал. 5"), text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 67 ал. 4 (източник в чужбина) е обявен като НЕнаправено изключване")
  void foreignSourceExclusionIsDeclaredOpen() {
    String text = BgArt55Boundaries.report(new BgArt55Boundaries.Facts());

    assertTrue(cites(text, "чл. 67, ал. 4"), text);
    assertTrue(text.contains("30 април"), text);
    assertTrue(text.contains("чл. 50"), "къде се декларира вместо тук: " + text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 65 ал. 6 е обявен заедно с кодовете, които каталогът носи иначе")
  void recipientPaidFinalTaxIsDeclared() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.recipientPaidFinal = 3;

    String text = BgArt55Boundaries.report(facts);

    assertTrue(cites(text, "чл. 65 ал. 6"), text);
    assertTrue(text.contains("810, 811, 812"), "кодовете, измерени в каталога: " + text);
    assertTrue(text.contains("815"), text);
    assertTrue(text.contains("3 реда на ред 3"), text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 55 ал. 3 (трудови доходи и чл. 41а) е цитиран дословно")
  void employmentExclusionQuotesTheLaw() {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.employment = 4;

    String text = BgArt55Boundaries.report(facts);

    assertTrue(cites(text, "чл. 55 ал. 3"), text);
    assertTrue(text.contains("чл. 41а"), "алтернативният данък също е изваден: " + text);
    assertTrue(text.contains("4 с трудови доходи"), text);
  }

  /**
   * ⚠️ СРАВНЯВА СЕ БЕЗ ОГЛЕД НА ГЛАВНИТЕ БУКВИ. Текстът е за четене от човек и започва изреченията
   * с главна буква; свидетелят пази КОЯ РАЗПОРЕДБА е цитирана — ал. 13, а не ал. 12 — а не как е
   * изписана. Закован за точния регистър, тестът би падал при преформулиране на изречението и би
   * мълчал при смяна на самата алинея.
   */
  private static boolean cites(String text, String provision) {
    return text.toLowerCase().contains(provision.toLowerCase());
  }

  @Test
  @DisplayName("нула редове се пише „Няма такива редове“, а не като празно място")
  void zeroIsWrittenOut() {
    String text = BgArt55Boundaries.report(new BgArt55Boundaries.Facts());
    assertTrue(text.contains("Няма такива редове"), text);
    assertTrue(text.contains("НИЩО НЕ Е ИЗПРАТЕНО КЪМ НАП"), "закачането не е подаване: " + text);
  }
}
