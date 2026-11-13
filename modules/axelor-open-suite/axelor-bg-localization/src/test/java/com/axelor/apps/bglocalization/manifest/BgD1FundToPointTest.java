package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.d1.BgD1Point;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * § 2 АЛ. 1 → {@code BgContributionFund.bgD1Field}: всеки фонд сочи СВОЯТА точка.
 *
 * <p>Колоната „точка в Д1“ е в речника от WS-1 и до този клон я четеше нула реда код. Сега {@code
 * BgD1ContributionAggregator} групира по нея — тоест грешна стойност вече не е неточност в
 * документацията, а сума на грешен ред в декларация.
 *
 * <p><b>Източникът е § 2 ал. 1 от Наредба Н-13</b>, който изброява деветте начина, по които се
 * определят вноските, и всеки път назовава точката. Преписан е тук с номер на подточка — за да може
 * човек да свери с ДВ, не с кода. (Същият довод като в {@code BgD1FlagsAgainstNaredbaTest}: препис
 * е правилен само там, където сравнението на два независими източника Е целта.)
 */
class BgD1FundToPointTest {

  private static final String FILE = "bg_contribution_fund.csv";

  /**
   * „фонд → точка“ по § 2 ал. 1, дословно:
   *
   * <ul>
   *   <li>т. 1 — „за фондовете на държавното обществено осигуряване, без размера на вноската за
   *       фонд „Трудова злополука и професионална болест““ → <b>т. 22</b>
   *   <li>т. 2 — ТЗПБ → <b>т. 22.2</b>
   *   <li>т. 3 — Учителския пенсионен фонд → <b>т. 23</b>
   *   <li>т. 4 — универсален пенсионен фонд → <b>т. 26</b>
   *   <li>т. 5 — професионален пенсионен фонд → <b>т. 25</b>
   *   <li>т. 7 — здравно осигуряване → <b>т. 22.1</b>
   *   <li>т. 9 — фонд „Гарантирани вземания…“ → <b>т. 30</b>
   * </ul>
   *
   * <p>⚠️ Подточки <b>6</b> (т. 17/18) и <b>8</b> (т. 27/28) НЯМАТ ред в речника ни и това е
   * обявена граница, не пропуск — виж {@code BgD1PhaseBoundariesTest}.
   */
  private static final Map<String, String> PARA_2_AL_1 = new LinkedHashMap<>();

  static {
    PARA_2_AL_1.put("PENSION", BgD1Point.T22_DOO);
    PARA_2_AL_1.put("PENSION_ART69", BgD1Point.T22_DOO);
    PARA_2_AL_1.put("OZM", BgD1Point.T22_DOO);
    PARA_2_AL_1.put("UNEMPLOYMENT", BgD1Point.T22_DOO);
    PARA_2_AL_1.put("TZPB", BgD1Point.T22_2_TZPB);
    PARA_2_AL_1.put("TEACHER_PF", BgD1Point.T23_TEACHER_PF);
    PARA_2_AL_1.put("UPF", BgD1Point.T26_UPF);
    PARA_2_AL_1.put("PPF", BgD1Point.T25_PPF);
    PARA_2_AL_1.put("HEALTH", BgD1Point.T22_1_HEALTH);
    PARA_2_AL_1.put("GVRS", BgD1Point.T30_GVRS);
  }

  /** „код на фонд → точка“, както го дава seed-ът. */
  private static Map<String, String> seedMapping() {
    List<String> h = BgSeed.header(FILE);
    Map<String, String> out = new TreeMap<>();
    for (String[] r : BgSeed.rows(FILE)) {
      out.put(BgSeed.col(h, r, "code"), BgSeed.col(h, r, "bgD1Field"));
    }
    return out;
  }

  @Test
  @DisplayName("§ 2 ал. 1 · всеки от 10-те фонда сочи точката, която нормата му дава")
  void everyFundPointsAtItsOwnParagraph2Point() {
    Map<String, String> seed = seedMapping();
    for (Map.Entry<String, String> e : PARA_2_AL_1.entrySet()) {
      assertEquals(
          e.getValue(),
          seed.get(e.getKey()),
          "фонд "
              + e.getKey()
              + " трябва да сочи т. "
              + e.getValue()
              + " по § 2 ал. 1, а речникът дава „"
              + seed.get(e.getKey())
              + "“. ⚠️ От тази колона зависи в кой ред на декларацията сяда сумата —"
              + " BgD1ContributionAggregator групира ПО НЕЯ.");
    }
  }

  @Test
  @DisplayName(
      "§ 2 ал. 1 т. 1 · четирите фонда на ДОО дават ЕДНО число в т. 22, и това не е дублет")
  void theFourDooFundsCollapseIntoOnePoint() {
    Map<String, String> seed = seedMapping();
    TreeSet<String> intoT22 = new TreeSet<>();
    for (Map.Entry<String, String> e : seed.entrySet()) {
      if (BgD1Point.T22_DOO.equals(e.getValue())) {
        intoT22.add(e.getKey());
      }
    }
    assertEquals(
        new TreeSet<>(java.util.Arrays.asList("OZM", "PENSION", "PENSION_ART69", "UNEMPLOYMENT")),
        intoT22,
        "т. 22 иска фондовете на ДОО БЕЗ ТЗПБ — Пенсии, Пенсии по чл. 69, ОЗМ и Безработица."
            + " Четири реда към една точка е УСТРОЙСТВОТО, не дублет: декларацията ги слива, а"
            + " фишът ги иска поотделно. Речникът остава по-фин от декларацията и не се свива до"
            + " нея.");
    assertTrue(
        !intoT22.contains("TZPB"),
        "ТЗПБ НЕ влиза в т. 22 — тя има собствена т. 22.2 (§ 2 ал. 1 т. 2). Сборът ѝ с останалите"
            + " ДОО фондове е точно грешката, срещу която т. 22 е формулирана „без размера на"
            + " вноската за фонд „Трудова злополука и професионална болест““.");
  }

  @Test
  @DisplayName("речникът носи 10 фонда и нито един без точка")
  void everyFundHasAPoint() {
    Map<String, String> seed = seedMapping();
    assertEquals(
        10,
        seed.size(),
        "речникът на фондовете се промени. Нов фонд иска и нов ред в § 2 ал. 1 — сверѝ го, преди"
            + " да вдигнеш това число.");
    for (Map.Entry<String, String> e : seed.entrySet()) {
      assertTrue(
          e.getValue() != null && !e.getValue().trim().isEmpty(),
          "фонд "
              + e.getKey()
              + " няма bgD1Field. Празна точка значи, че вноските по този фонд ще изпаднат от"
              + " декларацията МЪЛЧАЛИВО — агрегаторът пропуска перата без точка.");
    }
  }

  @Test
  @DisplayName("точките, които емитерът чака от речника, са точно тези, които речникът дава")
  void theEmittedPointsAreExactlyWhatTheDictionaryOffers() {
    TreeSet<String> fromSeed = new TreeSet<>(seedMapping().values());
    assertEquals(
        new TreeSet<>(BgD1Point.EMITTED_FROM_FUNDS),
        fromSeed,
        "BgD1Point.EMITTED_FROM_FUNDS и стойностите в речника се разминаха. Едното е това, което"
            + " записът има КОЛОНИ да приеме, другото — това, което перата могат да произведат."
            + " Разминаването значи или точка без колона (сумата изчезва), или колона без точка"
            + " (винаги празна).");
  }
}
