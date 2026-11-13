package com.axelor.apps.aiaccountant.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import org.junit.jupiter.api.Test;

/**
 * The context block's job: say what is on screen accurately, including when nothing is.
 *
 * <p>No Guice here. Every registry lookup inside the builder is individually guarded, so outside an
 * injector it degrades to exactly the block a resolver-less deployment would produce — which is the
 * part being asserted.
 */
class AiTurnContextBuilderTest {

  private static final String UPLOAD = "com.axelor.apps.aiocr.db.AiOcrUpload";

  private static PageContext page(String model, Long recordId, String viewType) {
    PageContext context = new PageContext();
    context.setModel(model);
    context.setRecordId(recordId);
    context.setViewType(viewType);
    return context;
  }

  @Test
  void namesTheOpenRecordWhenAFormIsOnScreen() {
    String block = new AiTurnContextBuilder().pageContext(page(UPLOAD, 946L, "form"), null);

    assertTrue(block.contains("Open record: " + UPLOAD + " id 946"));
    assertTrue(block.contains("uploadId=946"), "an upload id is directly usable by the tools");
    assertFalse(block.contains("NO record is open"));
  }

  /**
   * From the server's side: with nothing said about the page, the model fills the silence with
   * whatever record it last heard about.
   */
  @Test
  void saysExplicitlyThatNoRecordIsOpenOnAList() {
    String block = new AiTurnContextBuilder().pageContext(page(UPLOAD, null, "grid"), null);

    assertTrue(block.contains("Current page: " + UPLOAD + " list"));
    assertTrue(block.contains("NO record is open"));
    assertFalse(block.contains("Open record:"));
    assertFalse(block.contains("946"));
  }

  @Test
  void statesThatAnExplicitScopeBeatsTheOpenRecord() {
    String block = new AiTurnContextBuilder().pageContext(page(UPLOAD, 946L, "form"), null);

    assertTrue(block.contains("needsReview"), "the precedence sentence names the filters");
    assertTrue(block.contains("work on THOSE and ignore what is open"));
  }

  @Test
  void saysNothingAboutARecordWhenNoModuleIsOpen() {
    String block = new AiTurnContextBuilder().pageContext(null, null);

    assertFalse(block.contains("Open record:"));
    assertFalse(block.contains("Current page:"));
    assertTrue(block.contains("Today:"), "the date is always worth its two tokens");
  }

  /** Exactly one playbook, chosen by the task, and none at all for GENERAL. */
  @Test
  void injectsOnlyTheRelevantPlaybook() {
    AiTurnContextBuilder builder = new AiTurnContextBuilder();

    String posting =
        builder.build(
            "осчетоводи я",
            page(UPLOAD, 946L, "form"),
            false,
            null,
            null,
            AiTask.POST_SUPPLIER_INVOICE);
    assertTrue(posting.contains("[Playbook: POST"));
    assertFalse(posting.contains("[Playbook: CREATE"), "only one playbook per turn");
    assertFalse(posting.contains("[Playbook: REVIEW"));

    String general =
        builder.build("коя е активната фирма?", null, false, null, null, AiTask.GENERAL);
    assertFalse(general.contains("[Playbook:"), "GENERAL pays for no playbook");
    assertFalse(general.contains("[Domain:"), "nor for a domain guide");
  }

  /**
   * The reason the domain layer exists. «Осчетоводи я» routes to POST and to POST only, but
   * reaching POSTED on a bad draft means repairing it first — so a POST turn has to arrive carrying
   * the repair invariants, not a pointer to a playbook it will never be sent.
   */
  @Test
  void aPostTurnCarriesTheRulesForARepairItMayHaveToMake() {
    String out =
        new AiTurnContextBuilder()
            .build(
                "осчетоводи я",
                page(UPLOAD, 946L, "form"),
                true,
                null,
                null,
                AiTask.POST_SUPPLIER_INVOICE);

    assertTrue(out.contains("KEEP THE DOCUMENT'S LINES"), "line fidelity, on a posting turn");
    assertTrue(out.contains("ONE CORRECTION, ONE edit_supplier_invoice CALL"));
    assertTrue(out.contains("useForPartnerBalance"), "the payable-account prerequisite");
    assertTrue(out.contains("only EXACT means the figures agree"));
    assertTrue(out.contains("get_ocr_evidence"));
    // And still exactly one route: the repair rules arrive without the repair playbook's goal.
    assertTrue(out.contains("[Playbook: POST"));
    assertFalse(out.contains("[Playbook: REPAIR"));
  }

  /**
   * The order is what keeps the prompt cache working, and it follows how often each block changes:
   * the procedure never, the domain guide only when the kind of document does, the playbook only on
   * a change of job, then everything volatile, then the message.
   */
  @Test
  void ordersTheBlocksSoTheCachedPrefixSurvives() {
    String out =
        new AiTurnContextBuilder()
            .build(
                "осчетоводи я",
                page(UPLOAD, 946L, "form"),
                true,
                null,
                null,
                AiTask.POST_SUPPLIER_INVOICE);

    int procedure = out.indexOf("[Nyxa AI Accountant");
    int domain = out.indexOf("[Domain:");
    int playbook = out.indexOf("[Playbook:");
    // lastIndexOf, not indexOf: the operating procedure's own rule 9 mentions "[Nyxa context]"
    // descriptively near the top ("THE [Nyxa context] BLOCK IS ALREADY THE ANSWER..."), so the
    // first occurrence is prose, not the block itself — the block is always the final one.
    int context = out.lastIndexOf("[Nyxa context]");
    assertTrue(procedure >= 0 && domain > procedure, "domain guide follows the procedure");
    assertTrue(playbook > domain, "playbook follows the domain guide");
    assertTrue(context > playbook, "volatile context follows the playbook");
    assertTrue(out.endsWith("осчетоводи я"), "the message always comes last");
  }

  @Test
  void carriesTheRecentWorkflowShapeSoSameAgainHasAReferent() {
    AiConversationState state = new AiConversationState();
    state.setShape("EDIT_VALIDATE_VENTILATE");
    state.setShapeTarget("Invoice#606");
    state.setShapeOk(true);

    String block = new AiTurnContextBuilder().pageContext(page(UPLOAD, null, "grid"), state);

    assertTrue(block.contains("[Recent]"));
    assertTrue(block.contains("EDIT → VALIDATE → VENTILATE"));
    assertTrue(block.contains("Invoice#606"));
    assertTrue(block.contains("never reuse an amount"), "a shape is not a set of values");
  }
}
