package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ДОПЪЛНИТЕЛНОТО СПОРАЗУМЕНИЕ ИМА ЕКРАН — иначе целият чл. 119 е инертен.
 *
 * <p><b>Защо този пазач съществува.</b> Същият капан като при чл. 79 ЗДДС, и вече изигран веднъж
 * точно тук: кръгът #186 добави домейн {@code BgContractAmendment}, сглобяване на ЕТЗ тип 2,
 * миграция и двайсет и един теста — и нито един изглед. Тринайсет полета останаха декларирани и
 * недостижими за човека, а единственият производствен създател на ред беше миграционният вносител
 * от Microinvest. Билдът беше зелен през цялото време, защото всяко парче работеше вярно поотделно.
 *
 * <p><b>Какво точно твърди.</b> Всяко от изброените полета се среща като {@code <field>} в изглед
 * за СВОЯ модел. ⚠️ Не доказва, че полето е на верния панел, нито че е редактируемо — доказва само,
 * че път до него съществува. Точно този, най-евтиният въпрос, остана незададен.
 */
class BgAmendmentReachabilityTest {

  private static final String AMENDMENT = "com.axelor.apps.bglocalization.db.BgContractAmendment";
  private static final String CONTRACT = "com.axelor.apps.hr.db.EmploymentContract";
  private static final String PAY_SLIP = "com.axelor.apps.bglocalization.db.BgPaySlip";

  /**
   * Реквизитите на анекса. {@code newSalary} и {@code entryDate} са в един списък с останалите
   * нарочно: те МЕСТЯТ ПАРИ и недостижими значат, че датираната заплата е вградена и невъведима.
   */
  private static final List<String> AMENDMENT_FIELDS =
      Arrays.asList(
          "employmentContract",
          "changeDate",
          "entryDate",
          "changeNo",
          "bgChangeGround",
          "newSalary",
          "newNkpd",
          "newWorkplaceEkatte",
          "newHoursCode",
          "newWorkingHours",
          "newTermDate",
          "newVacationDays",
          "napSubmission");

  @Test
  @DisplayName("Тринайсетте реквизита на анекса са на изглед за BgContractAmendment")
  void анексът_е_достижим() {
    assertReachable(
        AMENDMENT, AMENDMENT_FIELDS, "записът съществуваше от #186 без нито един екран");
  }

  @Test
  @DisplayName("Списъкът с анекси е на формата на договора — иначе до тях няма път")
  void панелът_на_договора_съществува() {
    assertReachable(
        CONTRACT,
        Arrays.asList("bgContractAmendmentList"),
        "панелът е единственият вход към анексите — меню те нямат");
  }

  @Test
  @DisplayName("Признакът за авто-попълване е на фиша — иначе ръчната заплата няма как да се върне")
  void признакът_е_достижим() {
    // Пада ли, операторът може да СВАЛИ признака (пише сума), но не може да го вдигне обратно —
    // тоест фишът остава завинаги на ръчна заплата без видима причина.
    assertReachable(
        PAY_SLIP,
        Arrays.asList("bgAutoFillBaseSalary"),
        "изключен признак без начин да се върне е еднопосочна врата");
  }

  private void assertReachable(String model, List<String> fields, String why) {
    Set<String> shown = fieldsShownFor(model);
    List<String> missing =
        fields.stream().filter(field -> !shown.contains(field)).collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "Полета без нито един изглед за " + model + " (" + why + "): " + missing);
  }

  /** Заделено, за да не се хване празен списък за „всичко е наред“. */
  @Test
  @DisplayName("Пазачът наистина чете изгледи — иначе празният списък би минавал винаги")
  void пазачът_чете_нещо() {
    Set<String> shown = fieldsShownFor(AMENDMENT);
    assertTrue(
        shown.size() >= AMENDMENT_FIELDS.size(),
        "прочетох само " + shown.size() + " полета за " + AMENDMENT + " — пазачът не чете нищо");
  }

  /** Имената на всички {@code <field>}, показани в изглед, чийто модел е {@code model}. */
  private Set<String> fieldsShownFor(String model) {
    Set<String> names = new LinkedHashSet<>();
    for (Path file : viewFiles()) {
      Element root = parse(file);
      NodeList views = root.getChildNodes();
      for (int i = 0; i < views.getLength(); i++) {
        Node view = views.item(i);
        if (!(view instanceof Element)) {
          continue;
        }
        Element element = (Element) view;
        if (!model.equals(element.getAttribute("model"))) {
          continue;
        }
        collectFieldNames(element, names);
      }
    }
    return names;
  }

  /**
   * ⚠️ Събира и {@code <panel-related>}, не само {@code <field>}: списъкът с анекси е точно
   * panel-related и търсен като field, той би липсвал винаги.
   */
  private void collectFieldNames(Element parent, Set<String> into) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (!(child instanceof Element)) {
        continue;
      }
      Element element = (Element) child;
      if ("field".equals(element.getTagName()) && !element.getAttribute("name").isEmpty()) {
        into.add(element.getAttribute("name"));
      }
      if ("panel-related".equals(element.getTagName())
          && !element.getAttribute("field").isEmpty()) {
        into.add(element.getAttribute("field"));
      }
      collectFieldNames(element, into);
    }
  }

  private List<Path> viewFiles() {
    Path views = BgDomainColumns.moduleRoot().resolve("src/main/resources/views");
    try (Stream<Path> found = Files.walk(views)) {
      List<Path> files =
          found
              .filter(p -> p.getFileName().toString().endsWith(".xml"))
              .sorted()
              .collect(Collectors.toList());
      assertTrue(!files.isEmpty(), "не намерих нито един изглед под " + views);
      return files;
    } catch (Exception e) {
      throw new IllegalStateException("не мога да прочета " + views, e);
    }
  }

  private Element parse(Path file) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      return factory.newDocumentBuilder().parse(new File(file.toString())).getDocumentElement();
    } catch (Exception e) {
      throw new IllegalStateException("невалиден изглед: " + file, e);
    }
  }
}
