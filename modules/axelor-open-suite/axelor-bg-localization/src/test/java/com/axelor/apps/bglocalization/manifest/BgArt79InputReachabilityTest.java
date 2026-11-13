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
 * Полетата на чл. 79 ЗДДС имат ЕКРАН — иначе целият режим е инертен.
 *
 * <p><b>Защо този пазач съществува.</b> Кръгът, който въведе корекцията по чл. 79 ал. 3, добави
 * домейн, услуги, селекции и тестове — и нито един изглед. Седем полета на актива и пет настройки
 * на {@code AccountConfig} останаха декларирани, но недостижими за човека. Резултатът не беше
 * червен билд, а нещо по-лошо: годишният съветник обхождаше нула активи, отписването пишеше
 * „корекция не е изчислена“, а протоколът не се раждаше — всичко това при зелени тестове, защото
 * всяко парче работеше вярно поотделно.
 *
 * <p><b>Какво точно твърди.</b> Всяко от изброените полета се среща като {@code <field>} в изглед
 * за СВОЯ модел. ⚠️ Това не доказва, че полето е на верния панел, нито че е редактируемо — доказва
 * само, че път до него съществува. Точно този, най-евтиният въпрос, остана незададен.
 */
class BgArt79InputReachabilityTest {

  private static final String FIXED_ASSET = "com.axelor.apps.account.db.FixedAsset";
  private static final String ACCOUNT_CONFIG = "com.axelor.apps.account.db.AccountConfig";
  private static final String WRITE_OFF = "com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff";

  /** Входът на ал. 3 — фактите от придобиването, които живеят на актива. */
  private static final List<String> ASSET_INPUTS =
      Arrays.asList(
          "bgVatAssetKindSelect",
          "bgVatChargedOnAcquisition",
          "bgVatCreditYear",
          "bgVatAcquisitionCreditTypeSelect",
          "bgVatCoefficientK0",
          "bgVatProportionNid0",
          "bgVatFirstUseYear");

  /** Настройките, без които протоколът по ал. 4 не се поражда. */
  private static final List<String> PROTOCOL_SETTINGS =
      Arrays.asList(
          "bgVatCorrectionJournal",
          "bgVatChargedAccount",
          "bgVatDeductibleAccount",
          "bgVatCorrectionIncomeAccount",
          "bgVatAnnualCorrectionExpenseAccount");

  /** Резултатът — изчисленото, което иначе би останало само в базата. */
  private static final List<String> WRITE_OFF_RESULTS =
      Arrays.asList(
          "vatCorrectionDirection",
          "vatCorrectionAmount",
          "vatCorrectionYears",
          "vatCorrectionPeriodYears",
          "vatCorrectionStartYear",
          "vatCorrectionK0",
          "vatCorrectionProportion",
          "vatCorrectionGround",
          "vatCorrectionMove");

  @Test
  @DisplayName("Седемте входа на чл. 79 ал. 3 са на изглед за FixedAsset")
  void входовете_на_актива_са_достижими() {
    assertReachable(FIXED_ASSET, ASSET_INPUTS, "входът на чл. 79 ал. 3 живее на АКТИВА");
  }

  @Test
  @DisplayName("Петте настройки на протокола са на изглед за AccountConfig")
  void настройките_на_протокола_са_достижими() {
    assertReachable(
        ACCOUNT_CONFIG,
        PROTOCOL_SETTINGS,
        "без тях BgArt79ProtocolService и BgArt79aProtocolService връщат null с бележка");
  }

  @Test
  @DisplayName("Деветте резултатни полета са на изглед за отписването")
  void резултатът_е_видим() {
    assertReachable(
        WRITE_OFF,
        WRITE_OFF_RESULTS,
        "изчислен и скрит данък е по-лош от неизчислен — не се вижда, че го има");
  }

  private void assertReachable(String model, List<String> fields, String why) {
    Set<String> shown = fieldsShownFor(model);
    List<String> missing =
        fields.stream().filter(field -> !shown.contains(field)).collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "Полета без нито един изглед за " + model + " (" + why + "): " + missing);
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

  /** Заделено, за да не се хване празен списък за „всичко е наред“. */
  @Test
  @DisplayName("Пазачът наистина чете изгледи — иначе празният списък би минавал винаги")
  void пазачът_чете_нещо() {
    Set<String> shown = fieldsShownFor(FIXED_ASSET);
    assertTrue(
        shown.size() > ASSET_INPUTS.size(),
        "очаквах изгледът на актива да носи и заварените ЗКПО полета, а прочетох: " + shown);
  }
}
