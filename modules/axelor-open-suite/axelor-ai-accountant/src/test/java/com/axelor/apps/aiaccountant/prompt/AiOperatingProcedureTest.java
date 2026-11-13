package com.axelor.apps.aiaccountant.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the operating procedure now that it is a resource rather than a compiled constant.
 *
 * <p>A missing or truncated resource does not break a build — it produces an assistant that runs
 * with no rules at all and looks perfectly normal doing it. These are the cheapest possible checks
 * that the text is present, is the right text, and has not had its two most expensive corrections
 * quietly reverted.
 */
class AiOperatingProcedureTest {

  @Test
  void loadsFromTheClasspathAndIsNotEmpty() {
    String text = AiOperatingProcedure.text();
    assertFalse(text.isEmpty());
    assertTrue(text.startsWith("[Nyxa AI Accountant"), "the header the model keys on");
    assertTrue(AiOperatingProcedure.bytes() > 4000, "a truncated resource would still 'load'");
  }

  /**
   * The accounting invariant, now stated once.
   *
   * <p>It used to be a rule in the universal procedure AND a bullet in the supplier-invoice domain
   * guide, saying the same thing twice about a document type only one of them is scoped to. The
   * guide keeps it — every supplier-invoice turn is sent the guide, so nothing is lost — and the
   * procedure no longer carries a rule about {@code amountsReconcile}, which is a block only this
   * domain's tools return. The guard follows the text rather than the file it used to be in.
   */
  @Test
  void statesThatAccountedAmountsComeFromAccountingTotals() {
    String guide = AiPlaybookLibrary.domainGuide(AiTask.POST_SUPPLIER_INVOICE);
    assertTrue(guide.contains("accountingTotals"));
    assertFalse(
        guide.contains("the calculated one is what Axelor posts"),
        "the stale ROUNDING wording said the line-derived figure is what gets posted");
    assertTrue(guide.contains("accountingImpact=NONE"), "ROUNDING stays diagnostic");
    assertFalse(
        AiOperatingProcedure.text().contains("amountsReconcile"),
        "a supplier-invoice block has no place in the universal rules");
  }

  /** The batching default, and the override for tool descriptions that read as sequencing. */
  @Test
  void keepsTheBatchingRuleAndItsOverride() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("EMIT THE WHOLE AUTHORISED WORKFLOW IN ONE RESPONSE"));
    assertTrue(text.contains("a separate authorised TOOL, not a separate response"));
    assertFalse(
        text.contains("ONE INTENT, ONE CALL"),
        "the old heading read as one tool call per response and fought rule 17");
  }

  /** C6: the assistant has no worker thread and must never claim to have one. */
  @Test
  void forbidsPromisingBackgroundWork() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("YOU HAVE NO BACKGROUND EXECUTION"));
    assertTrue(text.contains("cannot send a later message"));
  }

  /** C6: the outcome is authorised, not each intermediate step. */
  @Test
  void keepsTheAutonomyAndScopePrecedenceRules() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("AUTHORISATION IS FOR THE OUTCOME, NOT FOR EVERY STEP"));
    assertTrue(text.contains("DELEGATED SELECTION"));
    assertTrue(text.contains("WHAT THE USER SAYS BEATS WHAT IS ON SCREEN"));
    assertTrue(text.contains("BULK IS NORMAL"));
    assertTrue(
        text.contains("If the eligible set is EMPTY"),
        "delegated selection must never invent a record");
  }

  /**
   * A batch abort skips writes for records that never failed, so "the others completed" is a claim
   * the batch runner does not support. Three outcomes have to be reported, not two.
   */
  @Test
  void requiresAllThreeBatchOutcomesToBeReported() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("THREE outcomes, not two"));
    assertTrue(
        text.contains("stops the whole batch instead"),
        "an unattributable failure must still mark the other records not_executed");
    assertTrue(text.contains("not_executed"));
    assertTrue(text.contains("NOT EXECUTED and are therefore unchanged"));
  }

  /** C6.1: an instruction names a destination, and the assistant walks the route to it. */
  @Test
  void treatsAnInstructionAsAGoalRatherThanASingleToolCall() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("AN INSTRUCTION NAMES A DESTINATION, NOT A TOOL"));
    assertTrue(
        text.contains("NEVER answer a refusal by telling the user to rephrase"),
        "telling the user to rephrase blames their wording for the record's state");
    assertTrue(text.contains("NEVER OFFER A NUMBERED MENU OF IMPLEMENTATION STEPS"));
    assertTrue(text.contains("DO NOT ASK FOR WHAT YOU CAN READ"));
  }

  /** C6.1: a capped preview must never define the size of the job. */
  @Test
  void resolvesAllToTheCompleteEligibleSet() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("MEANS ALL OF THEM, NOT THE ONES YOU CAN SEE"));
    assertTrue(text.contains("totalMatches"));
    assertTrue(text.contains("the SCOPE stays whatever the user named"));
  }

  /**
   * AI-3: the procedure now holds only what is true whatever the user is doing. Everything
   * supplier-invoice-specific moved into the playbooks, where {@code AiTaskResolverTest} guards it.
   */
  @Test
  void holdsOnlyUniversalRules() {
    String text = AiOperatingProcedure.text();
    assertTrue(
        text.contains("[Playbook]"), "it has to tell the model the playbook exists and wins");
    assertTrue(text.contains("[Domain]"), "and that a domain block may come before it");
    assertFalse(text.contains("KEEP THE DOCUMENT'S LINES"), "moved to the repair playbook");
    assertFalse(text.contains("acknowledgeDuplicate"), "moved to validate/post");
    assertFalse(text.contains("manage_supplier"), "moved to the create playbook");
    assertFalse(text.contains("bankAccount.iban"), "moved to the create playbook");
    assertFalse(text.contains("fix_partner_account"), "moved to repair/validate");
    assertFalse(text.contains("PARTNER ACCOUNTS"), "moved to the repair playbook");
  }

  /**
   * AI-4. The rules were numbered 0, 1, 2, 2z, 2y, 2a, 2c, 2b, 4, 4a, 4b, 5, 12d, 13… — the holes
   * left by AI-3's extraction, and an order in which 2z came before 2y and 2b came last. Numbers
   * are only labels to the model, but a document that visibly skips six rules invites the next
   * reader to go looking for what was lost, and a rule referring to "rule 12" that is not there is
   * worse than no reference at all.
   */
  @Test
  void numbersItsRulesContiguously() {
    Matcher m = Pattern.compile("(?m)^(\\d+)\\.").matcher(AiOperatingProcedure.text());
    List<Integer> numbers = new ArrayList<>();
    while (m.find()) {
      numbers.add(Integer.parseInt(m.group(1)));
    }
    assertTrue(numbers.size() > 15, "the rules should still all be there");
    for (int i = 0; i < numbers.size(); i++) {
      assertEquals(i + 1, numbers.get(i).intValue(), "rule numbering must have no holes");
    }
    // Every in-text cross-reference must point at a rule that exists.
    Matcher ref = Pattern.compile("rule (\\d+)").matcher(AiOperatingProcedure.text());
    while (ref.find()) {
      int target = Integer.parseInt(ref.group(1));
      assertTrue(
          target >= 1 && target <= numbers.size(), "dangling cross-reference to rule " + target);
    }
  }

  /**
   * AI-4. Rule 2 used to name "creating a City" as a manual step to hand back to the user, while
   * the create playbook says the opposite for the same situation: ask for the settlement name, and
   * do not send them off to add it by hand. Two layers, one situation, contradictory instructions.
   */
  @Test
  void doesNotSendTheUserOffToCreateACity() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("Describe a manual step only when NO tool can do it"));
    assertFalse(text.contains("creating a City"), "the create playbook forbids exactly this");
    assertTrue(
        text.contains("follow the playbook"), "and the rule now defers to it for missing records");
  }

  /**
   * AI-4. Rule 17's worked examples were the supplier-invoice route spelled out — edit, validate,
   * ventilate, refresh terms — in the layer that is supposed to hold only what is true of any
   * document. The POST playbook states that route, for the turns that need it.
   */
  @Test
  void keepsBatchingUniversalRatherThanRestatingThePostingRoute() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("A three-step instruction on one record is ONE response of three"));
    assertFalse(text.contains("refresh_supplier_invoice_terms"), "the POST playbook owns this");
    assertFalse(
        text.contains("→ validate_supplier_invoice + ventilate_supplier_invoice"),
        "so does the concrete posting sequence");
  }

  /** Standing authorisation must never widen to records the user never named. */
  @Test
  void scopesResumedAuthorisationToItsOwnRecords() {
    String text = AiOperatingProcedure.text();
    assertTrue(text.contains("covers ONLY the records it originally named"));
    assertTrue(text.contains("'СЪЩОТО'"), "«същото» needs an explicit rule of its own");
  }
}
