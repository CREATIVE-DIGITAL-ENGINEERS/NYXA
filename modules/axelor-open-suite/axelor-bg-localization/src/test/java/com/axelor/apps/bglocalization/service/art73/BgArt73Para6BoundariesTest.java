package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАНИЦИТЕ НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 6 — че са НАПИСАНИ, и то с числа.
 *
 * <p><b>Защо това изобщо се тества.</b> Указание т. 4 към образеца: „Справка, която не съдържа
 * всички изискуеми данни, не се счита за достоверна и не се приема!“, а по чл. 82 ЗДДФЛ за неверни
 * или непълни данни се носи отговорност. Текстът с границите е това, което човекът чете, преди да
 * поеме тази отговорност — тоест той е ЧАСТ ОТ ОТГОВОРА, а не украса, и мълчаливото му изчезване е
 * дефект като всеки друг.
 */
class BgArt73Para6BoundariesTest {

  /**
   * ⚠️ ПРАЗНИ КОЛОНИ 6 И 17–22 ЗА ВСИЧКИ ИМАТ ДВЕ ПРИЧИНИ, КОИТО ИЗГЛЕЖДАТ ЕДНАКВО. Или нито едно
   * лице няма основно ТПО към 31 декември, или ставката не е заредена и преизчисляване изобщо не е
   * правено. Първото е твърдение за хората, второто — за средата, и поправките им нямат нищо общо.
   */
  @Test
  @DisplayName("⚠️ БЕЗ СТАВКА · текстът казва, че преизчисляване НЕ Е правено за никого")
  void aMissingTaxRateIsNamedAsTheReasonForEmptyColumns() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 47;
    facts.taxRateMissing = true;

    String text = BgArt73Para6Boundaries.report(facts);

    assertTrue(
        text.contains("INCOME_TAX_RATE"), "липсата се назовава с КОДА на параметъра: " + text);
    assertTrue(
        text.contains("НЕ Е ПРАВЕНО ЗА НИКОГО"),
        "празни колони без обяснение се четат като „лицата нямат основа“: " + text);
    assertFalse(
        text.contains("Преизчислени са"),
        "не се твърди брой преизчислени лица, когато преизчисляване няма: " + text);
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · колко от колко лица са преизчислени се казва с ЧИСЛА")
  void theNumberOfRecalculatedPeopleIsStated() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 47;
    facts.mainEmployerPeople = 12;

    String text = BgArt73Para6Boundaries.report(facts);

    assertTrue(
        text.contains("Преизчислени са 12 от 47 лица"),
        "„не всички са преизчислени“ не праща никого никъде: " + text);
    assertTrue(text.contains("чл. 49 ал. 1"), "разликата се обяснява с нормата: " + text);
  }

  /**
   * ⚠️ НУЛА ПРЕИЗЧИСЛЕНИ ОТ ЧЕТИРИЙСЕТ И СЕДЕМ Е ЛИБО ВЯРНО, ЛИБО НЕПОПЪЛНЕН ФЛАГ. Признакът
   * „Основен трудов договор“ е с подразбиране TRUE за нови записи, но заварените редове носят false
   * — тоест цяла заварена база може да излезе с нула преизчислени лица и справката да изглежда
   * просто „празна“.
   */
  @Test
  @DisplayName("⚠️ НУЛА ОТ ВСИЧКИ · текстът праща към флага и датите на договорите")
  void zeroRecalculatedPeopleRaisesTheContractFlag() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 47;
    facts.mainEmployerPeople = 0;

    String text = BgArt73Para6Boundaries.report(facts);

    assertTrue(text.contains("НИТО ЕДНО ЛИЦЕ"), text);
    assertTrue(text.contains("Основен трудов договор"), "казва КЪДЕ да се погледне: " + text);
  }

  /**
   * ⚠️ НЕЗАРЕДЕНАТА СУМА СЕ НАЗОВАВА С КОДА СИ, ЗАЩОТО ИНАЧЕ НЕ Е ЗАРЕЖДАЕМА. „Облекчението за деца
   * не е приложено“ не казва какво да се направи; „CHILD_RELIEF_TWO (чл. 22в ал. 1 т. 2)“ казва.
   */
  @Test
  @DisplayName("⚠️ НЕЗАРЕДЕНИ СУМИ · назовават се поименно, заедно с източника ЗДБРБ")
  void unloadedReliefAmountsAreNamedByCode() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 3;
    facts.mainEmployerPeople = 3;
    facts.missingReliefAmounts =
        new ArrayList<>(
            Arrays.asList("CHILD_RELIEF_ONE (чл. 22в ал. 1 т. 1)", "CHILD_DISABILITY_RELIEF"));

    String text = BgArt73Para6Boundaries.report(facts);

    assertTrue(text.contains("CHILD_RELIEF_ONE (чл. 22в ал. 1 т. 1)"), text);
    assertTrue(text.contains("CHILD_DISABILITY_RELIEF"), text);
    assertTrue(
        text.contains("ЗДБРБ"),
        "размерът идва от закона за бюджета, не от чл. 22в — това е цялата причина да липсва: "
            + text);
  }

  @Test
  @DisplayName("МЪЛЧАНИЕ · заредени суми не оставят тревога в текста")
  void loadedAmountsLeaveNoWarning() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 3;
    facts.mainEmployerPeople = 3;

    String text = BgArt73Para6Boundaries.report(facts);

    assertFalse(
        text.contains("НЕ СА ЗАРЕДЕНИ"),
        "тревога, която стои и когато няма какво да се провери, престава да се чете: " + text);
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 49 АЛ. 4 · лицата без документи се броят и се назовават")
  void peopleWithoutTheArtFortyNineDocumentsAreCounted() {
    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.people = 5;
    facts.mainEmployerPeople = 5;
    facts.withoutArt49Documents = 4;

    String text = BgArt73Para6Boundaries.report(facts);

    assertTrue(text.contains("ЗА 4 ПРЕИЗЧИСЛЕНИ ЛИЦА"), text);
    assertTrue(text.contains("30 НОЕМВРИ"), "срокът по ал. 4 е част от указанието: " + text);
  }

  /**
   * ⚠️ ЧЛ. 22а НЕ Е ПРОПУСНАТ, А НЕПРИЛОЖИМ ОТ РАБОТОДАТЕЛЯ. Две независими доказателства: чл. 49
   * ал. 3 изброява поименно чл. 18, 19, 20, 22, 22в и 22г и той не е сред тях; и образецът няма
   * колона за него. Ако това не е НАПИСАНО, следващият прочит на списъка ще го добави „за пълнота“.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22а · текстът казва, че работодателят НЕ МОЖЕ да го приложи")
  void articleTwentyTwoAIsNamedAsInapplicable() {
    String text = BgArt73Para6Boundaries.report(new BgArt73Para6Boundaries.Facts());

    assertTrue(text.contains("ЧЛ. 22а"), text);
    assertTrue(text.contains("НЕ МОЖЕ"), "„не е построено“ и „не се прилага“ са различни: " + text);
    assertTrue(text.contains("чл. 50"), "казва къде СЕ ползва вместо това: " + text);
  }

  /**
   * ⚠️ ЛИПСВАЩИЯТ ДОХОД СЕ ОБЯВЯВА, ИНАЧЕ ИЗГЛЕЖДА КАТО ЗАГУБЕН. Фишовете по граждански договор не
   * пораждат ред в справката по ал. 6 — вярно по чл. 29, но за ТРЗ-то това е доход, който може да
   * очаква да види в справката. Без изричен ред в текста разликата се чете като дефект и се
   * „поправя“ на ръка, тоест декларира се втори път.
   */
  @Test
  @DisplayName("⚠️ ГРАЖДАНСКИ ДОГОВОР · текстът казва, че доходът е по ал. 1, а не че липсва")
  void civilContractIncomeIsNamedAsBelongingToParagraphOne() {
    String text = BgArt73Para6Boundaries.report(new BgArt73Para6Boundaries.Facts());

    assertTrue(text.contains("ГРАЖДАНСКИ ДОГОВОР"), text);
    assertTrue(text.contains("вид осигурен 14"), "казва по кой признак: " + text);
    assertTrue(
        text.contains("ЧЛ. 73 АЛ. 1"), "казва КЪДЕ е доходът, а не само че го няма тук: " + text);
    assertTrue(text.contains("чл. 29"), "и на какво основание: " + text);
  }

  /**
   * ⚠️ НОМЕРАТА СЕ БРОЯТ, А НЕ СЕ ПИШАТ. Три от точките са условни. Изписани с постоянни числа,
   * списъкът излиза „1, 5, 6…“ и дупката се чете като изчезнала точка — тоест като дефект на
   * приложението — от човек, който тъкмо поема отговорност по чл. 82 ЗДДФЛ.
   */
  @Test
  @DisplayName("⚠️ НОМЕРАЦИЯ · списъкът с границите няма дупки при нито една комбинация")
  void theNumberedListNeverHasGaps() {
    assertNumbering(new BgArt73Para6Boundaries.Facts());

    BgArt73Para6Boundaries.Facts all = new BgArt73Para6Boundaries.Facts();
    all.people = 9;
    all.mainEmployerPeople = 9;
    all.withoutArt49Documents = 2;
    all.cappedReliefs = 1;
    all.missingReliefAmounts = new ArrayList<>(Arrays.asList("CHILD_RELIEF_ONE"));
    assertNumbering(all);
  }

  private static void assertNumbering(BgArt73Para6Boundaries.Facts facts) {
    List<Integer> seen = new ArrayList<>();
    for (String line : BgArt73Para6Boundaries.report(facts).split("\n")) {
      int dot = line.indexOf(". ");
      if (dot > 0 && dot <= 2 && line.substring(0, dot).chars().allMatch(Character::isDigit)) {
        seen.add(Integer.parseInt(line.substring(0, dot)));
      }
    }
    for (int i = 0; i < seen.size(); i++) {
      assertEquals(i + 1, seen.get(i).intValue(), "точка " + (i + 1) + " липсва: " + seen);
    }
    assertTrue(seen.size() >= 6, "границите се стопиха до " + seen.size() + " точки: " + seen);
  }
}
