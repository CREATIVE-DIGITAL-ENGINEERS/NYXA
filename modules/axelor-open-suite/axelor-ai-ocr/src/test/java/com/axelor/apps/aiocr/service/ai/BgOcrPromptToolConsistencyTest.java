package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.prompt.AiPromptResource;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiToolRegistry;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every tool any prompt mentions must actually reach the model.
 *
 * <p>This exists because of a real bug, twice. First the intake prompt hardcoded {@code
 * lookup_bg_city}, which has never existed anywhere in the codebase: the model dutifully called it,
 * got {@code unknown_tool} back, and burned one of its five tool iterations on every single
 * document. Then AI-3 put {@code apply_exact_product_code} in the repair playbook — a tool that is
 * registered, but deliberately {@linkplain AiTool#published() unpublished}, so the model was again
 * being offered a call it could only fail.
 *
 * <p>The second one is why these checks now test against {@code published()} rather than {@code
 * all()}: registration is not reachability. A tool the agent does not declare cannot be called, no
 * matter how completely it exists in Java.
 */
class BgOcrPromptToolConsistencyTest extends AiOcrTest {

  private static Set<String> publishedNames() {
    Set<String> names = new TreeSet<>();
    for (AiTool tool : Beans.get(AiToolRegistry.class).published()) {
      names.add(tool.name());
    }
    return names;
  }

  /** The intake correction prompt, whose tool list is derived from the registry. */
  @Test
  void theIntakePromptOnlyNamesPublishedTools() {
    Set<String> published = publishedNames();
    List<String> unknown = new ArrayList<>();
    for (String candidate :
        AiPromptResource.toolNames(Beans.get(BgOcrAiCorrectionService.class).instructions())) {
      if (!published.contains(candidate)) {
        unknown.add(candidate);
      }
    }
    assertTrue(unknown.isEmpty(), "the intake prompt names tools that do not exist: " + unknown);
  }

  /** The post-draft line repair prompt, whose one tool is named in the text. */
  @Test
  void theLineRepairPromptOnlyNamesPublishedTools() {
    Set<String> published = publishedNames();
    Set<String> mentioned =
        AiPromptResource.toolNames(Beans.get(BgOcrLineRepairService.class).instructions());
    assertTrue(mentioned.contains("find_product"), "the pass is built around that one lookup");
    for (String name : mentioned) {
      assertTrue(published.contains(name), name + " is offered to the repair pass but unpublished");
    }
  }

  @Test
  void theDerivedListIsNonEmptyAndReadOnly() {
    String names = Beans.get(BgOcrAiCorrectionService.class).readToolNames();
    assertFalse(names.isEmpty(), "there should be read tools to advertise");

    Map<String, AiTool> registered = Beans.get(AiToolRegistry.class).all();
    for (String name : names.split(",")) {
      AiTool tool = registered.get(name.trim());
      assertTrue(tool != null, name + " is advertised but not registered");
      assertTrue(
          tool.kind() == AiToolKind.READ,
          name + " is advertised to the correction pass but is not a READ tool");
      assertTrue(tool.published(), name + " is advertised but not declared on the agent");
    }
  }

  /**
   * AI-4: the prompts moved to resource files, and a resource that fails to load is the one failure
   * mode this whole arrangement has to be checked against — an empty prompt still "works".
   */
  @Test
  void bothIntakePromptsLoadWithTheirRulesIntact() {
    String correction = Beans.get(BgOcrAiCorrectionService.class).instructions();
    assertTrue(correction.contains("ONE JSON object"), "the reply contract");
    assertTrue(correction.contains("never invent an EIK, IBAN or partner"));
    assertTrue(correction.contains("only correct fields listed in problemFields"));
    assertFalse(correction.contains("{tools}"), "the placeholder must be substituted");

    String repair = Beans.get(BgOcrLineRepairService.class).instructions();
    assertTrue(repair.contains("ONE JSON object"));
    assertTrue(repair.contains("never invented"), "productId must come from find_product");
    assertTrue(
        repair.contains("Never suggest amounts, quantities, prices or taxes"),
        "the line repair pass must never touch a figure");
  }

  @Test
  void theStaleCityToolIsGoneForGood() {
    assertFalse(
        Beans.get(BgOcrAiCorrectionService.class).instructions().contains("lookup_bg_city"),
        "lookup_bg_city has never existed; it must not be advertised again");
  }
}
