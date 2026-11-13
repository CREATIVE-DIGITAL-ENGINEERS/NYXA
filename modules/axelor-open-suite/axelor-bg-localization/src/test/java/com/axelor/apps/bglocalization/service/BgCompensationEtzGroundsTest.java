package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.bglocalization.service.etz.BgEtzGrounds;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБЕЗЩЕТЕНИЯТА ПРИ РАЗДЕЛЕНИТЕ ОСНОВАНИЯ ЗА ПРЕКРАТЯВАНЕ.
 *
 * <p>Сборните „327_1“ (друга точка извън т. 2, 3 и 3а) и „330_2“ (друга точка извън т. 6) са
 * разделени на точките на т. 27, а основанията по КТ са допълнени. Предложението за обезщетение
 * сравнява основанието ТОЧНО ({@link BgCompensationProposalResolver#matches}), тоест основание,
 * което не е в {@code triggerGrounds}, мълчаливо губи обезщетението.
 *
 * <p>Чл. 222 ал. 3 КТ („независимо от основанието за прекратяването“; ал. 4 — същото при чл. 68а
 * КСО) затова е на {@code appliesToAnyGround} с празен списък: списъкът би растял с всяка точка и
 * не се побира в колоната си (255 знака). Ал. 1 и ал. 2 остават със своите списъци. Тук се сверява
 * истинският каталог ({@code bg_compensation_type.csv}) с истинския списък основания ({@code
 * Selects.xml}).
 *
 * <p>⚠️ ПРАВОТО НА ПЕНСИЯ НЕ Е ВХОД: моделът няма такова поле и предложението не го проверява —
 * нито преди, нито сега. Свидетел „без право на пенсия → няма ал. 3“ затова липсва нарочно.
 *
 * <p>Видът договор (68, 121a и точките им) не участва: {@link BgCompensationProposalResolver#plan}
 * изобщо не приема вид договор, а обезщетенията не четат {@code bgContractType}.
 */
class BgCompensationEtzGroundsTest {

  private static final String CATALOGUE = "bg_compensation_type.csv";
  private static final String SELECTS = "src/main/resources/views/Selects.xml";

  private static final String PAR_1 = "comp_kt_222_1";
  private static final String PAR_2 = "comp_kt_222_2";
  private static final String PAR_3 = "comp_kt_222_3";

  /** Сборната стойност → точките, които я заместват. */
  private static final Map<String, List<String>> SPLIT =
      Map.of(
          "327_1",
          List.of(
              "327_1_1",
              "327_1_4",
              "327_1_6",
              "327_1_7",
              "327_1_7a",
              "327_1_8",
              "327_1_9",
              "327_1_10",
              "327_1_11",
              "327_1_12"),
          "330_2",
          List.of(
              "330_2_1",
              "330_2_2",
              "330_2_3",
              "330_2_5",
              "330_2_7",
              "330_2_8",
              "330_2_9",
              "330_2_10",
              "330_2_11"));

  /** Точките, които и преди разделянето са свои стойности, а не част от сборната. */
  private static final Map<String, List<String>> ALREADY_OWN =
      Map.of("327_1", List.of("327_1_2", "327_1_3", "327_1_3a"), "330_2", List.of("330_2_6"));

  /** Префиксът на етикета в {@code Selects.xml} → {@code EndOfContractReason.bgLegalFamily}. */
  private static final Map<String, String> ACT_BY_LABEL = new LinkedHashMap<>();

  static {
    ACT_BY_LABEL.put("КТ ", "KT");
    ACT_BY_LABEL.put("ЗВО ", "ZVO");
    ACT_BY_LABEL.put("ЗДСл ", "ZDSL");
    ACT_BY_LABEL.put("Закон за администрацията ", "ZA");
    ACT_BY_LABEL.put("Закон за съдебната власт ", "ZSV");
  }

  private static final BigDecimal GROSS = new BigDecimal("1840.00");

  private static List<BgCompensationType> catalogue() {
    List<String> header = BgSeed.quotedHeader(CATALOGUE);
    List<BgCompensationType> out = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(CATALOGUE)) {
      BgCompensationType t = new BgCompensationType();
      t.setCode(row[header.indexOf("code")]);
      t.setActive(Boolean.parseBoolean(row[header.indexOf("active")]));
      t.setAppliesToAnyGround(Boolean.parseBoolean(row[header.indexOf("appliesToAnyGround")]));
      t.setTriggerGrounds(row[header.indexOf("triggerGrounds")]);
      t.setLegalFamily(row[header.indexOf("legalFamily")]);
      t.setBaseKind(row[header.indexOf("baseKind")]);
      String months = row[header.indexOf("baseMonths")];
      t.setBaseMonths(months.isEmpty() ? null : new BigDecimal(months));
      t.setD1Code(row[header.indexOf("d1Code")]);
      t.setArisesOnTermination(Boolean.parseBoolean(row[header.indexOf("arisesOnTermination")]));
      out.add(t);
    }
    return out;
  }

  private static TreeSet<String> proposed(
      List<BgCompensationType> types, String ground, String act) {
    return BgCompensationProposalResolver.proposedFor(types, ground, act).stream()
        .map(BgCompensationType::getCode)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static BgCompensationType byCode(List<BgCompensationType> types, String code) {
    return types.stream().filter(t -> code.equals(t.getCode())).findFirst().orElse(null);
  }

  /** Всяка стойност на {@code bg.termination.ground.select} → актът ѝ; {@code null} за „064“. */
  private static Map<String, String> groundActs() throws Exception {
    String xml = new String(Files.readAllBytes(Paths.get(SELECTS)), StandardCharsets.UTF_8);
    Matcher block =
        Pattern.compile("<selection name=\"bg.termination.ground.select\">([\\s\\S]*?)</selection>")
            .matcher(xml);
    assertTrue(block.find(), "bg.termination.ground.select липсва в Selects.xml");
    Map<String, String> acts = new LinkedHashMap<>();
    Matcher option =
        Pattern.compile("<option value=\"([^\"]*)\">([^<]*)</option>").matcher(block.group(1));
    while (option.find()) {
      String value = option.group(1);
      String label = option.group(2);
      String act = null;
      for (Map.Entry<String, String> e : ACT_BY_LABEL.entrySet()) {
        if (label.startsWith(e.getKey())) {
          act = e.getValue();
        }
      }
      assertTrue(
          act != null || BgEtzGrounds.OTHER.equals(value), "непознат акт в етикета: " + label);
      acts.put(value, act);
    }
    return acts;
  }

  @Test
  @DisplayName("Списъкът на точките е пълен: всяка точка на чл. 327 ал. 1 и чл. 330 ал. 2 е тук")
  void theSplitCoversTheSelection() throws Exception {
    List<String> values = new ArrayList<>(groundActs().keySet());
    for (Map.Entry<String, List<String>> e : SPLIT.entrySet()) {
      String catchAll = e.getKey();
      assertTrue(values.contains(catchAll), "сборната " + catchAll + " остава за заварените");
      TreeSet<String> inSelect =
          values.stream()
              .filter(v -> v.startsWith(catchAll + "_"))
              .filter(v -> !ALREADY_OWN.get(catchAll).contains(v))
              .collect(Collectors.toCollection(TreeSet::new));
      assertEquals(new TreeSet<>(e.getValue()), inSelect, "точките, които заместват " + catchAll);
    }
  }

  @Test
  @DisplayName("а) Ново основание (дисциплинарно по чл. 330 ал. 2 т. 6 и всяко друго) → ал. 3")
  void paragraphThreeIsProposedOnEveryEmploymentGround() throws Exception {
    List<BgCompensationType> types = catalogue();
    assertTrue(proposed(types, "330_2_6", "KT").contains(PAR_3), "дисциплинарно уволнение");

    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> e : groundActs().entrySet()) {
      if ("ZDSL".equals(e.getValue())) {
        continue; // служебно правоотношение — там е comp_zdsl_106_3
      }
      if (!proposed(types, e.getKey(), e.getValue()).contains(PAR_3)) {
        missing.add(e.getKey());
      }
    }
    assertEquals(
        List.of(), missing, "чл. 222 ал. 3 се дължи „независимо от основанието за прекратяването“");

    BgCompensationType par3 = byCode(types, PAR_3);
    assertTrue(par3.getAppliesToAnyGround(), "ал. 3 е на флага „всяко основание“");
    assertEquals("", par3.getTriggerGrounds(), "при флага списък не се ползва");
    BigDecimal amount =
        BgCompensationResolver.compute(par3.getBaseKind(), par3.getBaseMonths(), GROSS, null);
    assertEquals(0, new BigDecimal("3680.00").compareTo(amount), "2 × 1840,00 по ал. 3: " + amount);
  }

  @Test
  @DisplayName("б) „064 — друго“ → ал. 3; служебно правоотношение по ЗДСл → не")
  void paragraphThreeIsProposedOnOther() {
    List<BgCompensationType> types = catalogue();
    assertTrue(proposed(types, BgEtzGrounds.OTHER, null).contains(PAR_3), "064 без акт");
    assertTrue(proposed(types, BgEtzGrounds.OTHER, "KT").contains(PAR_3), "064 с акт КТ");
    assertFalse(proposed(types, "106_1_1", "ZDSL").contains(PAR_3), "ЗДСл не е трудово");
  }

  /**
   * ⚠️ ПРЕДЛОЖЕНИЕТО НЕ Е ПАРИ. Ал. 3 се предлага при всяко прекратяване, а право на пенсия не се
   * проверява — тоест непрегледан ред по погрешка би бил 2 или 6 брутни заплати. Фишът чете само
   * ПОТВЪРДЕНИ документи ({@code BgPayrollInputBuilder#compensationDocumentIds}), документ се прави
   * само от потвърдено право, и потвърденият документ иска потвърдено право. Тук: двата отказа по
   * истинския ред на каталога — {@code arisesOnTermination} идва от seed-а, не от теста.
   */
  @Test
  @DisplayName(
      "Непрегледан PROPOSED ред по ал. 3: нито документ, нито потвърждаване — не стига фиша")
  void aProposedParagraphThreeNeverReachesThePaySlip() {
    BgCompensationType par3 = byCode(catalogue(), PAR_3);
    assertTrue(par3.getArisesOnTermination(), "ал. 3 възниква при прекратяване — иска право");

    BgTerminationCompensation right = new BgTerminationCompensation();
    right.setBgCompensationType(par3);
    right.setStatus(BgCompensationProposalResolver.STATUS_PROPOSED);

    String noDocument = BgCompensationResolver.refuseDocumentFromEntitlement(right, null);
    assertNotNull(noDocument, "„Създай документ“ от непрегледано право");
    assertTrue(noDocument.contains("ПОТВЪРДЕНО"), noDocument);

    BgCompensation doc = new BgCompensation();
    doc.setBgCompensationType(par3);
    doc.setBgTerminationCompensation(right);
    String noConfirm = BgCompensationResolver.refuseWithoutEntitlement(doc);
    assertNotNull(noConfirm, "„Потвърди“ на документ по непрегледано право");
    assertTrue(noConfirm.contains("не е потвърдено"), noConfirm);
  }

  @Test
  @DisplayName("г) Ал. 1 и ал. 2 — само за своите основания; списъците им се побират в колоната")
  void paragraphsOneAndTwoKeepTheirOwnGrounds() throws Exception {
    List<BgCompensationType> types = catalogue();
    Map<String, String> own = new LinkedHashMap<>();
    own.put(PAR_1, "328_1_1,328_1_2,328_1_3,328_1_4,328_1_7,328_1_8");
    own.put(PAR_2, "325_1_9");

    for (Map.Entry<String, String> o : own.entrySet()) {
      BgCompensationType type = byCode(types, o.getKey());
      assertFalse(type.getAppliesToAnyGround(), o.getKey() + " не е „всяко основание“");
      assertEquals(o.getValue(), type.getTriggerGrounds(), o.getKey());
      assertTrue(type.getTriggerGrounds().length() <= 255, "колоната е varchar(255)");

      List<String> wanted = List.of(o.getValue().split(","));
      for (Map.Entry<String, String> g : groundActs().entrySet()) {
        assertEquals(
            wanted.contains(g.getKey()),
            proposed(types, g.getKey(), g.getValue()).contains(o.getKey()),
            o.getKey() + " при " + g.getKey());
      }
    }

    BgCompensationType par1 = byCode(types, PAR_1);
    assertEquals("27", par1.getD1Code(), "чл. 222 ал. 1 влиза в Д1 с вид 27");
    BigDecimal amount =
        BgCompensationResolver.compute(par1.getBaseKind(), par1.getBaseMonths(), GROSS, null);
    assertNotNull(amount);
    assertEquals(0, new BigDecimal("1840.00").compareTo(amount), "1 × 1840,00 по ал. 1: " + amount);
  }

  @Test
  @DisplayName("Новата точка поражда същите обезщетения като сборната, която замества")
  void aPointProposesWhatItsCatchAllProposed() {
    List<BgCompensationType> types = catalogue();
    for (Map.Entry<String, List<String>> e : SPLIT.entrySet()) {
      TreeSet<String> expected = proposed(types, e.getKey(), "KT");
      for (String point : e.getValue()) {
        assertEquals(expected, proposed(types, point, "KT"), point + " срещу " + e.getKey());
      }
    }
  }

  @Test
  @DisplayName("Заварен договор с гола стойност: обезщетението работи, ЕТЗ отказва")
  void aLegacyValueKeepsItsCompensationButTheEtzRefuses() {
    List<BgCompensationType> types = catalogue();
    for (String catchAll : SPLIT.keySet()) {
      assertTrue(proposed(types, catchAll, "KT").contains(PAR_3), catchAll);
      assertThrows(
          BgEtzGrounds.BgEtzUnmappedGroundException.class,
          () -> BgEtzGrounds.terminationGroundFor(catchAll, "KT", "01"),
          catchAll + " е сборна и няма свой код в т. 27");
    }
    for (String bare : new String[] {"68", "121a"}) {
      assertThrows(
          BgEtzGrounds.BgEtzUnmappedGroundException.class,
          () -> BgEtzGrounds.contractGroundFor(bare, "01"),
          bare + " е сборна и няма свой код в т. 12");
    }
  }
}
