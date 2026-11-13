package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgInsuredTypeCategoryRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВЕРЯВАЩИЯТ ТЕСТ: пазачът и валидаторът при запис са ЕДНО правило, не две.
 *
 * <p><b>Какво пази.</b> Правилото „двойката е валидна ⇔ каталогът носи ≥1 ред за нея“ се задава на
 * ДВЕ места по различно време: при ЗАПИС на трудовия договор ({@code
 * BgEmploymentContractManagementRepository}) и при СВЕРКАТА на съхраненото ({@code
 * BgInsuredTypeHomeFileTest#disagreements}, същият метод, който ползва и {@code
 * BgInsuredTypeHomeDbTest}). Две копия на едно правило се разминават при първата поправка, а
 * разминаването е тихо — тогава договор минава записа и пада на сверката, или обратното.
 *
 * <p><b>Как го пази.</b> Двете страни получават ЕДИН И СЪЩ вход — кода, категорията и набора
 * категории от seed-а — и трябва да дадат ЕДИН И СЪЩ отговор, дума по дума. Обхожда се цялата
 * решетка: всеки код от каталога срещу всяка категория от номенклатурата, плюс една несъществуваща.
 *
 * <p>⚠️ Тестът е СТРУКТУРЕН, не съдържателен: не твърди кои двойки са валидни днес (това е работа
 * на {@code BgInsuredTypeHomeFileTest}), а че двата пътя не могат да се разминат.
 */
class BgInsuredTypeCategoryDriftTest {

  /** Номенклатурата {@code bg.labor.category.select} плюс стойност, която НЕ съществува. */
  private static final List<String> CATEGORIES = Arrays.asList("1", "2", "3", "7", null, "", "  ");

  @Test
  @DisplayName(
      "⚠️ БЕЗ DRIFT · валидаторът и disagreements(...) отговарят ЕДНАКВО на един и същ вход")
  void theSaveValidatorAndTheGuardAgreeOnEveryPair() {
    Map<String, Set<String>> allowed = BgInsuredTypeHomeFileTest.allowedLaborCategories();
    assertTrue(
        allowed.size() >= 20,
        "котвата: прочетох само "
            + allowed.size()
            + " кода със ставки — решетката отдолу би била празна и тестът щеше да мине, без да е"
            + " сверил нищо");

    List<String> drift = new ArrayList<>();
    int refusals = 0;

    for (String code : new TreeSet<>(allowed.keySet())) {
      for (String category : CATEGORIES) {
        // Страна А: правилото, както го задава ЗАПИСЪТ на договора.
        String validator =
            BgInsuredTypeCategoryRule.disagreement(code, category, allowed.get(code));

        // Страна Б: правилото, както го задава ПАЗАЧЪТ на съхраненото. Учителството и изборът по
        // чл. 4а ал. 2 са свалени, за да остане в изхода само разминаването по категория —
        // другите две правила са ДРУГИ правила и не се сверяват тук.
        List<String> guard =
            BgInsuredTypeHomeFileTest.disagreements("X", code, category, false, false);
        String fromGuard = null;
        for (String sentence : guard) {
          if (sentence.contains("няма ставки за тази комбинация")) {
            fromGuard = sentence;
          }
        }

        if (validator == null && fromGuard == null) {
          continue;
        }
        if (validator == null || fromGuard == null) {
          drift.add(
              "(" + code + ", " + category + "): валидатор=" + validator + " · пазач=" + fromGuard);
          continue;
        }
        refusals++;
        if (!("X: " + validator).equals(fromGuard)) {
          drift.add(
              "("
                  + code
                  + ", "
                  + category
                  + "): текстовете се разминават\n    валидатор: "
                  + validator
                  + "\n    пазач:     "
                  + fromGuard);
        }
      }
    }

    assertTrue(
        refusals > 0,
        "⚠️ КОТВАТА: нито една двойка не беше отказана от ДВЕТЕ страни, тоест сравнението не е"
            + " сравнило нищо и тестът би минал и при два счупени пътя. Кодовете 01, 02 и 03 имат"
            + " редове само за по една категория — поне те трябва да дадат откази.");
    assertEquals(
        new ArrayList<String>(),
        drift,
        "⚠️ РАЗМИНАВАНЕ МЕЖДУ ДВАТА ПЪТЯ. Едно правило, задавано на две места, вече не е едно:"
            + " договор ще мине записа и ще падне на сверката, или обратното — и нито едното не се"
            + " вижда, докато някой не прочете отчета.");
  }

  @Test
  @DisplayName("Котва · (01, 1) е отказана, (01, 3) минава — и по двата пътя")
  void theContractSixCaseIsRefusedByBothPaths() {
    Set<String> forCode01 = BgInsuredTypeHomeFileTest.allowedLaborCategories().get("01");
    assertNotNull(forCode01, "код 01 няма нито един ред в каталога — прочитът чете нещо друго");

    assertNotNull(
        BgInsuredTypeCategoryRule.disagreement("01", "1", forCode01),
        "⚠️ Двойката от договор #6. Валидаторът при запис трябва да я отказва.");
    assertTrue(
        !BgInsuredTypeHomeFileTest.disagreements("договор #6", "01", "1", false, false).isEmpty(),
        "⚠️ И пазачът на съхраненото трябва да я отказва — инак единият път пуска това, което"
            + " другият спира.");

    assertEquals(
        null,
        BgInsuredTypeCategoryRule.disagreement("01", "3", forCode01),
        "(01, трета категория) е обикновеният трудов договор — записът минава");
    assertEquals(
        new ArrayList<String>(),
        BgInsuredTypeHomeFileTest.disagreements("договор #6", "01", "3", false, false),
        "(01, трета категория) не е разминаване и за пазача");
  }
}
