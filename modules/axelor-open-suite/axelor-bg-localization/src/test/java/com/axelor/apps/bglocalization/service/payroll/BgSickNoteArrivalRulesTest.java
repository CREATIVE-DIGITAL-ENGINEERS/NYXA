package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ОТКАЗИТЕ НА ПРИСТИГНАЛИЯ БОЛНИЧЕН — по един свидетел на всеки, с назования му текст.
 *
 * <p>Чисти функции над дати: без база, без Guice, без контейнер. Всеки отказ спира случая ПРЕДИ да
 * се е родило копие, преди период да е местен и преди заявка да е отменена — тоест цената на
 * спрения случай е нула, а на пуснатия е корекция, която мести пари и декларация наведнъж.
 */
class BgSickNoteArrivalRulesTest {

  private static final LocalDate SICK_FROM = LocalDate.of(2026, 12, 7);
  private static final LocalDate SICK_TO = LocalDate.of(2026, 12, 9);
  private static final LocalDate TODAY = LocalDate.of(2027, 1, 20);

  // ═══ ДАТАТА НА ПРЕДСТАВЯНЕ ═══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Непопълнена дата на представяне → отказ, който казва ЗАЩО я иска")
  void amissingReceivedDateIsRefused() {
    String refusal = BgSickNoteArrivalRules.refuseReceivedDate(null, SICK_FROM, TODAY);
    assertNotNull(refusal);
    assertTrue(refusal.contains("т. 25"), refusal);
    assertTrue(
        refusal.contains("Приложение № 9"),
        "⚠️ Причината назовава ПОСЛЕДИЦАТА, не определението: от тази дата се определя месецът, в"
            + " който удостоверението отива към НОИ. Намерено: "
            + refusal);
  }

  @Test
  @DisplayName("⚠️ Представен ПРЕДИ началото на листа → отказ; сравнява се с ЛИСТА, не с периода")
  void areceivedDateBeforeTheSickNoteStartIsRefused() {
    String refusal =
        BgSickNoteArrivalRules.refuseReceivedDate(LocalDate.of(2026, 12, 5), SICK_FROM, TODAY);
    assertNotNull(refusal);
    assertTrue(refusal.contains("05.12.2026") && refusal.contains("07.12.2026"), refusal);
    assertTrue(
        refusal.contains("НАЧАЛОТО НА ЛИСТА"),
        "⚠️ И КОТВАТА НА СРАВНЕНИЕТО Е В ТЕКСТА. Сравнена с началото на ПЕРИОДА, същата проверка би"
            + " отказала напълно нормалния случай „лист за края на декември, представен през"
            + " януари“. Намерено: "
            + refusal);
  }

  @Test
  @DisplayName("Представен в ДЕНЯ на започване е законен — границата е „преди“, не „преди или на“")
  void areceivedDateOnTheFirstDayPasses() {
    assertNull(BgSickNoteArrivalRules.refuseReceivedDate(SICK_FROM, SICK_FROM, TODAY));
  }

  @Test
  @DisplayName("⚠️ Дата В БЪДЕЩЕТО → отказ: тя насочва пакета към ненастъпил месец")
  void afutureReceivedDateIsRefused() {
    String refusal = BgSickNoteArrivalRules.refuseReceivedDate(TODAY.plusDays(1), SICK_FROM, TODAY);
    assertNotNull(refusal);
    assertTrue(refusal.contains("БЪДЕЩЕТО"), refusal);
    assertTrue(refusal.contains("21.01.2027") && refusal.contains("20.01.2027"), refusal);
  }

  @Test
  @DisplayName("Нормалната закъсняла дата минава — заради нея съществува целият поток")
  void anormalLateReceivedDatePasses() {
    assertNull(
        BgSickNoteArrivalRules.refuseReceivedDate(LocalDate.of(2027, 1, 12), SICK_FROM, TODAY),
        "лист от декември, представен на 12.01 — точно случаят, който отразяваме");
  }

  // ═══ ДВАТА МЕСЕЦА — ОБЯВЕНАТА ГРАНИЦА ════════════════════════════════════

  @Test
  @DisplayName("⚠️ Лист през граница на месеца → отказ, ОБЯВЕН като граница, не като дефект")
  void asickNoteSpanningTwoMonthsIsRefusedAsADeclaredBoundary() {
    String refusal =
        BgSickNoteArrivalRules.refuseSpansTwoMonths(
            LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 8));
    assertNotNull(refusal);
    assertTrue(refusal.contains("ОБЯВЕНА ГРАНИЦА"), refusal);
    assertTrue(
        refusal.contains("две корекции"),
        "⚠️ Причината назовава КАКВО липсва — оркестрацията на две корекции, — а не „не се"
            + " поддържа“. Разделянето на дните по месеци е вярно. Намерено: "
            + refusal);
    assertTrue(
        refusal.contains("Коригирай"),
        "и сочи ръчния изход, вместо да остави човека без ход: " + refusal);
  }

  @Test
  @DisplayName("Лист в ЕДИН месец минава — включително през границата на годината отвън")
  void asickNoteInsideOneMonthPasses() {
    assertNull(BgSickNoteArrivalRules.refuseSpansTwoMonths(SICK_FROM, SICK_TO));
    assertNull(
        BgSickNoteArrivalRules.refuseSpansTwoMonths(
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)),
        "цял декември е един месец");
  }

  @Test
  @DisplayName("Лист без дати → отказ, а не мълчаливо „един месец“")
  void asickNoteWithoutDatesIsRefused() {
    assertNotNull(BgSickNoteArrivalRules.refuseSpansTwoMonths(null, SICK_TO));
    assertNotNull(BgSickNoteArrivalRules.refuseSpansTwoMonths(SICK_FROM, null));
  }

  // ═══ ТОЧНОТО ПОКРИТИЕ НА ЗАЯВКАТА ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Заявка ИЗВЪН листа → отказ с ДВАТА записа; без автоматично цепене")
  void apartiallyOverlappingLeaveRequestIsRefused() {
    String refusal =
        BgSickNoteArrivalRules.refuseInexactCover(
            SICK_FROM,
            SICK_TO,
            Collections.singletonList(
                new BgSickNoteArrivalRules.LeaveSpan(
                    1L, LocalDate.of(2026, 12, 5), LocalDate.of(2026, 12, 12), "Непредставен")),
            "БЛ-77/2026");
    assertNotNull(refusal);
    assertTrue(refusal.contains("Непредставен 05.12.2026 – 12.12.2026"), refusal);
    assertTrue(refusal.contains("БЛ-77/2026"), "и листът е назован: " + refusal);
    assertTrue(
        refusal.contains("БЕЗ АВТОМАТИЧНО ЦЕПЕНЕ"),
        "⚠️ Дните, които листът не покрива, са отсъствие по НЕИЗВЕСТНА причина. Разцепена"
            + " автоматично, заявката би родила остатък, който никой не е въвел — и той пак ще"
            + " сваля заплата. Намерено: "
            + refusal);
  }

  @Test
  @DisplayName("Заявка ТОЧНО по листа минава — тя е онова, което листът замества")
  void anexactlyCoveredLeaveRequestPasses() {
    assertNull(
        BgSickNoteArrivalRules.refuseInexactCover(
            SICK_FROM,
            SICK_TO,
            Collections.singletonList(
                new BgSickNoteArrivalRules.LeaveSpan(1L, SICK_FROM, SICK_TO, "Непредставен")),
            "БЛ-77/2026"));
  }

  @Test
  @DisplayName("⚠️ Заявка ВЪТРЕ в листа също минава — границата е „излиза извън“, не „не съвпада“")
  void aleaveRequestStrictlyInsideTheSickNotePasses() {
    assertNull(
        BgSickNoteArrivalRules.refuseInexactCover(
            SICK_FROM,
            SICK_TO,
            Collections.singletonList(
                new BgSickNoteArrivalRules.LeaveSpan(
                    1L, LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 8), "Непредставен")),
            "БЛ-77/2026"),
        "⚠️ Заявка за един ден вътре в тридневен лист е СЪЩОТО отсъствие, само по-тясно въведено —"
            + " листът я покрива цяла и тя се отменя цяла. Отказ тук би спрял верния случай.");
  }

  @Test
  @DisplayName("Без заявка изобщо → няма какво да се отменя, няма и отказ")
  void nooverlappingLeaveMeansNoRefusal() {
    assertNull(
        BgSickNoteArrivalRules.refuseInexactCover(
            SICK_FROM, SICK_TO, Collections.<BgSickNoteArrivalRules.LeaveSpan>emptyList(), "БЛ-1"));
    assertNull(BgSickNoteArrivalRules.refuseInexactCover(SICK_FROM, SICK_TO, null, "БЛ-1"));
  }

  @Test
  @DisplayName("⚠️ Всички разминати заявки се изброяват, не само първата")
  void everyPartialSpanIsNamed() {
    List<BgSickNoteArrivalRules.LeaveSpan> spans =
        Arrays.asList(
            new BgSickNoteArrivalRules.LeaveSpan(
                1L, LocalDate.of(2026, 12, 5), LocalDate.of(2026, 12, 7), "Непредставен А"),
            new BgSickNoteArrivalRules.LeaveSpan(
                2L, LocalDate.of(2026, 12, 9), LocalDate.of(2026, 12, 12), "Непредставен Б"));
    String refusal =
        BgSickNoteArrivalRules.refuseInexactCover(SICK_FROM, SICK_TO, spans, "БЛ-77/2026");
    assertTrue(refusal.contains("Непредставен А") && refusal.contains("Непредставен Б"), refusal);
  }

  // ═══ ПЛАНЪТ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ Планът назовава И ТРИТЕ последици: копие, отменена заявка, разлика в ТЕКУЩИЯ фиш")
  void theplanNamesEveryConsequence() {
    String plan =
        BgSickNoteArrivalRules.plan(
            "БЛ-77/2026",
            SICK_FROM,
            SICK_TO,
            "12/2026",
            Collections.singletonList(
                new BgSickNoteArrivalRules.LeaveSpan(1L, SICK_FROM, SICK_TO, "Непредставен")),
            LocalDate.of(2027, 1, 1));

    assertTrue(plan.contains("12/2026"), "кой месец: " + plan);
    assertTrue(plan.contains("КОРИГИРАЩО копие"), "какво се отваря: " + plan);
    assertTrue(
        plan.contains("ОТМЕНЯ СЕ") && plan.contains("не се трие"),
        "⚠️ Коя заявка и че следата остава — човекът трябва да знае това ПРЕДИ да натисне, не"
            + " след това: "
            + plan);
    assertTrue(
        plan.contains("статус „Отменен“"),
        "⚠️ СТАТУСЪТ Е 5 = „Отменен“, не „Отказана“ (4). Диалогът обещава същото, което излиза на"
            + " екрана на отпуските: "
            + plan);
    assertFalse(plan.contains("Отказан"), "и думата „Отказан“ я няма: " + plan);
    assertTrue(
        plan.contains("т. 9"),
        "⚠️ ПЛАНЪТ ОБЕЩАВА Т. 9: разбивът пише редовете по месеци върху листа, а"
            + " Приложение № 9 ги чете — виж BgApp9Point9FromArrivalTest: "
            + plan);
    assertTrue(
        plan.contains("ЗЗО чл. 40 ал. 1 т. 5"),
        "⚠️ ПЛАНЪТ ОБЕЩАВА ЗДРАВНАТА ВНОСКА ПО ЧЛ. 40 АЛ. 1 Т. 5: копието се пресмята и перото"
            + " 516 по т. 5 е на него — виж BgPaySlipRetroGeneratorsTest: "
            + plan);
    assertTrue(plan.contains("01.2027"), "месецът за Приложение № 9: " + plan);
    assertTrue(
        plan.contains("ТЕКУЩИЯ отворен фиш"),
        "⚠️ И КЪДЕ ОТИВАТ ПАРИТЕ. Без този ред операторът очаква доплащане за онзи месец: " + plan);
    assertTrue(
        plan.contains("НЕ се потвърждава само"),
        "модел (б): предложение, което човек потвърждава — не автоматика: " + plan);
  }

  @Test
  @DisplayName("⚠️ Планът БЕЗ заявка го КАЗВА, вместо да премълчи празния списък")
  void theplanSaysWhenThereIsNothingToCancel() {
    String plan =
        BgSickNoteArrivalRules.plan(
            "БЛ-77/2026",
            SICK_FROM,
            SICK_TO,
            "12/2026",
            Collections.<BgSickNoteArrivalRules.LeaveSpan>emptyList(),
            null);
    assertTrue(
        plan.contains("НЯМА заявка за отпуск"),
        "⚠️ Празният списък е ЗАКОНЕН случай (дните може да са свалени РЪЧНО от отработените), но"
            + " премълчан, той изглежда като „нищо няма да се промени“: "
            + plan);
  }

  // ═══ ЕТИКЕТЪТ НА ОТМЕНЕНАТА ЗАЯВКА ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ „Отменен“ е преводът на HR за STATUS_CANCELED — не дума, написана наум")
  void thecancelledLabelIsTheHrTranslationOfStatusCanceled() throws Exception {
    String title =
        optionTitle(
            "hrs.leave.request.status.select",
            String.valueOf(LeaveRequestRepository.STATUS_CANCELED));
    assertEquals("Canceled", title, "опцията на HR за STATUS_CANCELED");
    assertEquals(
        hrTranslation(title),
        BgSickNoteArrivalRules.LEAVE_CANCELED_LABEL,
        "⚠️ ДИАЛОГЪТ ПОВТАРЯ ДУМАТА, КОЯТО ЕКРАНЪТ НА ОТПУСКИТЕ ПОКАЗВА. Сменят ли превода в HR,"
            + " този свидетел пада, вместо диалогът пак да обещава статус, който никъде не се"
            + " вижда.");
  }

  // ═══ ОТКАЗАНА ПРИСЪДА НА РАЗБИВА ═════════════════════════════════════════

  @Test
  @DisplayName("Сметнати присъди и законно „няма перо“ → разбивът не се отказва")
  void computedAndNoEmployerDaysAreNotRefusals() {
    assertNull(
        BgSickNoteArrivalRules.refuseOutcomes(
            "БЛ-77/2026",
            Arrays.asList(
                BgSickLeaveOutcome.computed(
                    BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
                    new java.math.BigDecimal("76.36"),
                    new java.math.BigDecimal("2"),
                    new java.math.BigDecimal("54.5455")),
                BgSickLeaveOutcome.noEmployerDays(
                    BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY, "продължение — само ДОО")),
            null),
        "⚠️ NO_EMPLOYER_DAYS е законно „няма перо“ (например продължение), не отказ");
  }

  @Test
  @DisplayName("⚠️ Отказана или неподадена присъда → отказ на ЦЯЛОТО действие, с причината")
  void arefusedOrMissingOutcomeRefusesTheWholeAction() {
    String refused =
        BgSickNoteArrivalRules.refuseOutcomes(
            "БЛ-77/2026",
            Collections.singletonList(
                BgSickLeaveOutcome.refused(
                    BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY, "стажът не е подаден")),
            "Попълнете стажа.");
    assertNotNull(refused);
    assertTrue(refused.contains("№ БЛ-77/2026 НЕ е отразен"), refused);
    assertTrue(refused.contains("стажът не е подаден"), "причината: " + refused);
    assertTrue(refused.contains("Попълнете стажа."), "какво да се попълни: " + refused);
    assertTrue(refused.contains("Нищо не е отменено и копие не е създадено"), refused);

    assertNotNull(
        BgSickNoteArrivalRules.refuseOutcomes(
            "БЛ-77/2026",
            Collections.singletonList(
                BgSickLeaveOutcome.sourceMissing(
                    BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY, "липсва вход")),
            null),
        "SOURCE_MISSING също спира — липсващ вход не е нула");
    assertNotNull(
        BgSickNoteArrivalRules.refuseOutcomes(
            "БЛ-77/2026", Collections.<BgSickLeaveOutcome>emptyList(), null),
        "празен списък значи, че разбивът не е върнал нищо — и това спира");
  }

  // ═══ ВТОРО ОТРАЗЯВАНЕ ВЪРХУ СЪЩИЯ ОРИГИНАЛ ═══════════════════════════════

  @Test
  @DisplayName("Без неотказани копия пазачът мълчи — първото отразяване не се спира")
  void noRetroCopyMeansNoRefusal() {
    assertNull(
        BgSickNoteArrivalRules.refuseExistingRetro(
            "FISH-2026-0012",
            "12/2026",
            Collections.<BgSickNoteArrivalRules.RetroCopy>emptyList()));
  }

  @Test
  @DisplayName("⚠️ Заличен оригинал → отказ със „заличен“, не с „коригиран“")
  void avoidedOriginalIsNamedAsVoided() {
    String refusal =
        BgSickNoteArrivalRules.refuseExistingRetro(
            "FISH-2026-0012",
            "12/2026",
            Collections.singletonList(
                new BgSickNoteArrivalRules.RetroCopy(
                    "FISH-2026-0099", BgPaySlipResolver.PAID, true)));
    assertNotNull(refusal);
    assertTrue(refusal.contains("вече е заличен"), refusal);
    assertTrue(refusal.contains("заличаващ фиш № FISH-2026-0099"), refusal);
    assertTrue(refusal.contains("„Изплатен“"), refusal);
    assertTrue(refusal.contains("Повторно отразяване не е разрешено"), refusal);
  }

  /** Заглавието на опция от изборите на HR — четено от самия модул, не преписано тук. */
  private static String optionTitle(String selection, String value) throws Exception {
    for (URL url : hrResources("views/Selects.xml")) {
      try (InputStream in = url.openStream()) {
        NodeList selections =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(in)
                .getElementsByTagName("selection");
        for (int i = 0; i < selections.getLength(); i++) {
          Element sel = (Element) selections.item(i);
          if (!selection.equals(sel.getAttribute("name"))) {
            continue;
          }
          NodeList options = sel.getElementsByTagName("option");
          for (int j = 0; j < options.getLength(); j++) {
            Element option = (Element) options.item(j);
            if (value.equals(option.getAttribute("value"))) {
              return option.getTextContent().trim();
            }
          }
        }
      }
    }
    throw new AssertionError("Няма опция " + value + " в " + selection + " на HR модула");
  }

  /** Българският превод на HR модула за даден ключ. */
  private static String hrTranslation(String key) throws Exception {
    String prefix = "\"" + key + "\",\"";
    for (URL url : hrResources("i18n/messages_bg.csv")) {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith(prefix)) {
            return line.substring(prefix.length(), line.indexOf('"', prefix.length()));
          }
        }
      }
    }
    throw new AssertionError("Няма превод на „" + key + "“ в i18n на HR модула");
  }

  private static List<URL> hrResources(String path) throws Exception {
    List<URL> out = new ArrayList<>();
    java.util.Enumeration<URL> all =
        BgSickNoteArrivalRulesTest.class.getClassLoader().getResources(path);
    while (all.hasMoreElements()) {
      URL url = all.nextElement();
      if (url.toString().contains("axelor-human-resource")) {
        out.add(url);
      }
    }
    assertFalse(out.isEmpty(), "HR модулът не е на classpath-а за " + path);
    return out;
  }
}
