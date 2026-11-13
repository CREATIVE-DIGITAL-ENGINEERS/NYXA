package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ПОТВЪРДЕНИЯТ ФИШ Е ЗАМРАЗЕН И НА ЕКРАНА, не само в услугата.
 *
 * <p><b>Какво лови.</b> Без {@code readonlyIf} на {@code bgPaySlipLineList} перата на ПОТВЪРДЕН фиш
 * се редактират направо в решетката и се записват с обикновен Save. Нито един от сервизните пазачи
 * не минава по този път: {@code confirm()}, {@code cancel()} и {@code remove()} пазят преходите и
 * записа, а редакцията на дъщерен ред не е нито едно от трите. {@code recompute()} пък е no-op
 * извън DRAFT — значи заглавните суми ОСТАВАТ старите, а перата под тях вече казват друго. Тихо е,
 * защото всички числа изглеждат пресметнати.
 *
 * <p><b>Защо „не е DRAFT“, а не „периодът е заключен“.</b> Заключването (ключ А/Б) е по-ТЯСНО
 * условие: ред, добавен на потвърден фиш в още отворен и недеклариран период, също не минава през
 * {@code recompute()} и също разминава перата със заглавието. Тоест правилото на екрана е
 * състоянието на ДОКУМЕНТА, а пазачът на декларираното е отделен и живее в услугата.
 *
 * <p>⚠️ Изгледът е ЛЮБЕЗНОСТ към човека пред екрана, а не пазач: REST и импортът минават покрай
 * него. Затова този тест пази ТОЧНО онова, което изгледът може да обещае, и нищо повече.
 */
class BgPaySlipFrozenFieldsTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/bg-payslip-views.xml");

  /** Условието, с което целият формуляр вече замразява входовете си. */
  private static final String FROZEN = "statusSelect != 'DRAFT'";

  /** Полетата на формуляра, по ред на появата им. */
  private static Map<String, Element> formFields() throws Exception {
    assertTrue(Files.isRegularFile(VIEW), "не намирам " + VIEW.toAbsolutePath());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document doc = factory.newDocumentBuilder().parse(VIEW.toFile());

    Element form = null;
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element candidate = (Element) forms.item(i);
      if ("bg-payslip-form".equals(candidate.getAttribute("name"))) {
        form = candidate;
      }
    }
    assertNotNull(form, "формулярът bg-payslip-form изчезна от изгледа");

    Map<String, Element> out = new LinkedHashMap<>();
    NodeList fields = form.getElementsByTagName("field");
    for (int i = 0; i < fields.getLength(); i++) {
      Element field = (Element) fields.item(i);
      out.putIfAbsent(field.getAttribute("name"), field);
    }
    assertTrue(out.size() > 10, "твърде малко полета — сгрешен ли е формулярът?");
    return out;
  }

  /** Името на панела, в който седи полето. */
  private static String panelOf(Element field) {
    for (Node p = field.getParentNode(); p != null; p = p.getParentNode()) {
      if (p instanceof Element && "panel".equals(p.getNodeName())) {
        return ((Element) p).getAttribute("name");
      }
    }
    return "";
  }

  /**
   * ⚠️ ЗАМРАЗЯВА ЛИ ИЗВЪН ЧЕРНОВА — питане за ИНВАРИАНТА, не за низа.
   *
   * <p>Проверката беше точно равенство с {@link #FROZEN} и това е правилното по подразбиране: то не
   * пуска нищо. Но полето може да има и ВТОРА причина да е заключено, и тогава условието е {@code
   * FROZEN || <още нещо>} — по-СТРОГО от изискваното, а не по-хлабаво. Такова условие пази
   * инварианта напълно: {@code A || B} е вярно винаги, когато {@code A} е вярно.
   *
   * <p>⚠️ И ТОЧНО ТАМ Е ГРАНИЦАТА: приема се само префикс {@code FROZEN + " || "}. {@code &&} НЕ се
   * приема — то би ОТСЛАБИЛО замразяването, тоест би пуснало редакция на потвърден фиш при някакво
   * второ условие, а това е точно дефектът, който този клас лови. Свободен текст също не се приема.
   *
   * <p>Днешният единствен ползвател е {@code bgOtherPayerInsurableIncome}: заключено е и когато е
   * чекнат {@code bgNoOtherPayerIncome}, за да не остане старият навик „пиши 0 за този месец“ —
   * нулата НЕ е изявление и пак ползва стоящата стойност на договора.
   */
  private static boolean freezesOutsideDraft(String readonlyIf) {
    return FROZEN.equals(readonlyIf) || readonlyIf.startsWith(FROZEN + " || ");
  }

  @Test
  @DisplayName("⚠️ Перата се редактират САМО в чернова — решетката е заключена извън DRAFT")
  void theLineGridIsFrozenOutsideDraft() throws Exception {
    Element lines = formFields().get("bgPaySlipLineList");
    assertNotNull(lines, "полето с перата изчезна от формуляра");
    assertEquals(
        FROZEN,
        lines.getAttribute("readonlyIf"),
        "⚠️ Без това условие перата на ПОТВЪРДЕН фиш се редактират и записват с обикновен Save —"
            + " покрай всеки сервизен пазач, и без recompute(), който извън DRAFT мълчи.");
  }

  @Test
  @DisplayName("⚠️ Всяко число на фиша е замразено извън чернова — без изключение по невнимание")
  void everyAmountOnTheSlipIsFrozenOutsideDraft() throws Exception {
    // ⚠️ ПАНЕЛИТЕ, а не изброени имена на полета: нов ред в „Суми“ или в „Замразени входове“ влиза
    // в проверката САМ. Изброени поименно, точно новото поле щеше да е единственото непокрито.
    List<String> panels =
        Arrays.asList("frozenPanel", "sickLeavePanel", "linesPanel", "totalsPanel");
    List<String> editable = new ArrayList<>();
    int checked = 0;
    for (Map.Entry<String, Element> entry : formFields().entrySet()) {
      Element field = entry.getValue();
      if (!panels.contains(panelOf(field))) {
        continue;
      }
      checked++;
      if ("true".equals(field.getAttribute("readonly"))) {
        continue;
      }
      if (!freezesOutsideDraft(field.getAttribute("readonlyIf"))) {
        editable.add(panelOf(field) + " · " + entry.getKey());
      }
    }
    assertTrue(checked >= 10, "очаквам поне десет полета в четирите панела, намерих " + checked);
    assertTrue(
        editable.isEmpty(),
        "⚠️ Тези полета се редактират на ПОТВЪРДЕН фиш, а нищо не ги преизчислява след това —"
            + " ръчната стойност остава върху заглавието, докато перата казват друго: "
            + editable);
  }

  @Test
  @DisplayName("⚠️ Датата на изплащане е ОБЯВЕНО изключение — без нея pay() е недостижим")
  void thePaidDateIsTheDeclaredException() throws Exception {
    Element paidDate = formFields().get("bgPaidDate");
    assertNotNull(paidDate, "полето с датата на изплащане изчезна");
    // ⚠️ Тя се въвежда точно върху ПОТВЪРДЕН фиш и BgPaySlipController я пренася от контекста в
    // pay(). Заключена до DRAFT, изплащането става невъзможно от екрана — тоест изключението е
    // част от правилото, а не пропуск в него, и стои записано тук, за да не бъде „поправено“.
    assertEquals("statusSelect == 'CANCELLED'", paidDate.getAttribute("readonlyIf"));
  }

  @Test
  @DisplayName("⚠️ Чекнат „този месец няма чужд доход“ → месечната сума е ЗАКЛЮЧЕНА")
  void theDeclaredAbsenceLocksTheMonthlyAmount() throws Exception {
    Map<String, Element> fields = formFields();

    Element flag = fields.get("bgNoOtherPayerIncome");
    assertNotNull(
        flag,
        "⚠️ ПРИЗНАКЪТ ТРЯБВА ДА Е НА ЕКРАНА, инак фиксът е наполовина: правилото съществува, а ТРЗ"
            + " няма как да го задейства. Точно това е дефектът „декларирано поле без изглед“.");
    assertEquals(
        FROZEN, flag.getAttribute("readonlyIf"), "признакът е вход като всеки друг — замразява се");

    Element amount = fields.get("bgOtherPayerInsurableIncome");
    assertNotNull(amount, "месечната сума изчезна от формуляра");
    assertEquals(
        FROZEN + " || bgNoOtherPayerIncome",
        amount.getAttribute("readonlyIf"),
        "⚠️ БЕЗ ТОВА СТАРИЯТ НАВИК ОСТАВА. Заявено „този месец няма“, полето за сумата трябва да е"
            + " недостъпно: нула в него НЕ е изявление (signum() > 0, не != null) и пак пропада към"
            + " стоящата стойност на договора. А сума > 0 до чекнат признак е противоречие и се"
            + " отказва при потвърждаване — екранът да не води дотам.");
    assertTrue(
        freezesOutsideDraft(amount.getAttribute("readonlyIf")),
        "и въпреки втората причина полето остава замразено извън чернова");
  }

  @Test
  @DisplayName("⚠️ Help-ът НЕ праща да се занулява договорът — това е симетричният дефект")
  void theHelpPointsAtTheCheckboxNotAtTheContract() throws Exception {
    String help = formFields().get("bgOtherPayerInsurableIncome").getAttribute("help");
    assertFalse(
        help.contains("занулете"),
        "⚠️ Старият съвет „занулете стойността на договора“ губеше стоящата стойност за ВСИЧКИ"
            + " останали месеци. Забравено възстановяване значи, че следващият месец се смята БЕЗ"
            + " координация, тоест МОД подът се прилага без основание — същите пари, но в"
            + " НАДВНАСЯНЕ. Съвет: "
            + help);
    assertTrue(
        help.contains("кутийката"),
        "help-ът трябва да сочи признака, а не заобиколката. Съвет: " + help);

    Path contractView = Paths.get("src/main/resources/views/EmploymentContract-bg.xml");
    String contractHelp = new String(Files.readAllBytes(contractView), StandardCharsets.UTF_8);
    assertFalse(
        contractHelp.contains("НЕ е възможно — занулете нея"),
        "⚠️ И на ДОГОВОРА съветът беше същият, и там заблуждава по същия начин.");
  }
}
