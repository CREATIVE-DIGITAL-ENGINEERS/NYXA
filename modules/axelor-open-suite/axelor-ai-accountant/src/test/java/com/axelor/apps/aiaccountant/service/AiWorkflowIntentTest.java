package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * The half of the completion check that reads the user, rather than the database.
 *
 * <p>Every ambiguity here must resolve to null. A wrong "cannot prove" costs a vaguer message; a
 * wrong "POSTED was authorised" would let the server announce a finished workflow the user never
 * asked for.
 */
class AiWorkflowIntentTest {

  @Test
  void recognisesTheOutcomeThatWasAuthorised() {
    assertEquals(
        AiWorkflowIntent.Stage.POSTED,
        AiWorkflowIntent.requested("оправи, валидирай и осчетоводи фактурата"));
    assertEquals(AiWorkflowIntent.Stage.POSTED, AiWorkflowIntent.requested("публикувай я"));
    assertEquals(
        AiWorkflowIntent.Stage.POSTED, AiWorkflowIntent.requested("validate and ventilate"));
    assertEquals(AiWorkflowIntent.Stage.VALIDATED, AiWorkflowIntent.requested("валидирай я"));
  }

  @Test
  void refusesToGuess() {
    assertNull(AiWorkflowIntent.requested("оправи датата"), "a correction has no lifecycle target");
    assertNull(AiWorkflowIntent.requested("защо е в ревю?"));
    assertNull(AiWorkflowIntent.requested(null));
    assertNull(
        AiWorkflowIntent.requested("недей да осчетоводяваш, само потвърди"),
        "a negation abandons the parse rather than trying to work out what it negates");
    assertNull(AiWorkflowIntent.requested("don't post it yet"));
  }

  @Test
  void identifiesAContinuation() {
    assertTrue(AiWorkflowIntent.isContinuation("продължи"));
    assertTrue(AiWorkflowIntent.isContinuation("продължавай с останалите"));
    assertTrue(AiWorkflowIntent.isContinuation("continue"));
    assertFalse(AiWorkflowIntent.isContinuation("оправи следващата"));
  }

  /** C6: «нямам право да избера случаен запис без uploadId» — they had just given that right. */
  @Test
  void recognisesWhenTheUserHandedOverTheChoiceOfRecord() {
    assertTrue(AiWorkflowIntent.delegatesSelection("вземи случаен запис needs review"));
    assertTrue(AiWorkflowIntent.delegatesSelection("който и да е needsReview запис"));
    assertTrue(AiWorkflowIntent.delegatesSelection("вземи произволна фактура"));
    assertTrue(AiWorkflowIntent.delegatesSelection("pick one and make a draft"));
    assertTrue(AiWorkflowIntent.delegatesSelection("take any one of them"));
    assertFalse(AiWorkflowIntent.delegatesSelection("оправи фактура 946"));
    assertFalse(AiWorkflowIntent.delegatesSelection(null));
  }

  /** C6.1: an instruction names a destination, and "draft" is one of them. */
  @Test
  void recognisesADraftGoalWithoutStealingAPostingOne() {
    assertEquals(
        AiWorkflowIntent.Stage.DRAFT, AiWorkflowIntent.requested("направи чернова от 946"));
    assertEquals(
        AiWorkflowIntent.Stage.DRAFT, AiWorkflowIntent.requested("create a draft from it"));
    assertEquals(
        AiWorkflowIntent.Stage.POSTED,
        AiWorkflowIntent.requested("оправи draft-а и я осчетоводи"),
        "posting wins over the word 'draft' appearing in the same sentence");
    assertEquals(
        AiWorkflowIntent.Stage.VALIDATED,
        AiWorkflowIntent.requested("валидирай тази draft фактура"));
  }

  /** C6.1: validating on the way to POSTED is work the user asked for by implication. */
  @Test
  void countsAPrerequisiteThatRanOnTheWayToTheGoal() {
    assertTrue(
        AiWorkflowIntent.executedPrerequisite(
            AiWorkflowIntent.Stage.POSTED,
            Arrays.asList("validate_supplier_invoice", "ventilate_supplier_invoice")));
    assertTrue(
        AiWorkflowIntent.executedPrerequisite(
            AiWorkflowIntent.Stage.VALIDATED, Collections.singletonList("edit_supplier_invoice")));
    assertFalse(
        AiWorkflowIntent.executedPrerequisite(
            AiWorkflowIntent.Stage.POSTED, Collections.singletonList("ventilate_supplier_invoice")),
        "posting an already-validated invoice needed no prerequisite");
    assertFalse(
        AiWorkflowIntent.executedPrerequisite(null, Arrays.asList("edit_supplier_invoice")));
    assertFalse(
        AiWorkflowIntent.executedPrerequisite(
            AiWorkflowIntent.Stage.POSTED, Collections.emptyList()));
  }

  @Test
  void ranksLifecycleAgainstTheAuthorisedOutcome() {
    assertTrue(AiWorkflowIntent.satisfies(AiWorkflowIntent.POSTED, AiWorkflowIntent.Stage.POSTED));
    assertTrue(
        AiWorkflowIntent.satisfies(AiWorkflowIntent.POSTED, AiWorkflowIntent.Stage.VALIDATED),
        "posted is past validated");
    assertFalse(
        AiWorkflowIntent.satisfies(AiWorkflowIntent.VALIDATED, AiWorkflowIntent.Stage.POSTED));
    assertFalse(AiWorkflowIntent.satisfies(AiWorkflowIntent.DRAFT, AiWorkflowIntent.Stage.POSTED));
    assertTrue(
        AiWorkflowIntent.satisfies(AiWorkflowIntent.DRAFT, AiWorkflowIntent.Stage.DRAFT),
        "a created draft satisfies a request for a draft");
    assertFalse(
        AiWorkflowIntent.satisfies(AiWorkflowIntent.CANCELLED, AiWorkflowIntent.Stage.DRAFT),
        "cancelled still satisfies nothing");
    assertFalse(
        AiWorkflowIntent.satisfies(AiWorkflowIntent.CANCELLED, AiWorkflowIntent.Stage.POSTED),
        "a cancelled invoice has satisfied nothing");
    assertFalse(AiWorkflowIntent.satisfies(null, AiWorkflowIntent.Stage.POSTED));
    assertFalse(AiWorkflowIntent.satisfies(AiWorkflowIntent.POSTED, null));
  }

  @Test
  void namesTheOperationSequenceAndNothingElse() {
    assertEquals(
        "EDIT_VALIDATE_VENTILATE",
        AiWorkflowIntent.shape(
            Arrays.asList(
                "edit_supplier_invoice",
                "validate_supplier_invoice",
                "ventilate_supplier_invoice")));
    assertEquals(
        "REFRESH_TERMS_VENTILATE",
        AiWorkflowIntent.shape(
            Arrays.asList("refresh_supplier_invoice_terms", "ventilate_supplier_invoice")));
    assertNull(AiWorkflowIntent.shape(Collections.emptyList()));
    assertNull(
        AiWorkflowIntent.shape(Collections.singletonList("get_ocr_record")),
        "reads are not a shape");
  }

  @Test
  void readsBackAsSomethingAModelCanFollow() {
    assertEquals(
        "EDIT → VALIDATE → VENTILATE", AiWorkflowIntent.readable("EDIT_VALIDATE_VENTILATE"));
    assertEquals("REFRESH_TERMS → VENTILATE", AiWorkflowIntent.readable("REFRESH_TERMS_VENTILATE"));
    assertNull(AiWorkflowIntent.readable(null));
  }

  // ── isAffirmativeConfirmation ────────────────────────────────────────────

  @Test
  void recognisesABareYes() {
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("да"));
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("дадада създай доставчика"));
    assertTrue(
        AiWorkflowIntent.isAffirmativeConfirmation(
            "дадада създай доставчика. информацията е правилна"));
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("да, добре"));
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("yes please"));
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("ok"));
    assertTrue(AiWorkflowIntent.isAffirmativeConfirmation("confirm"));
  }

  @Test
  void aNegationIsNeverReadAsConfirmation() {
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation("не"));
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation("не, недей"));
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation("no"));
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation(null));
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation(""));
  }

  @Test
  void ordinaryInstructionsAreNotConfirmations() {
    // These carry their own explicit destination — AiWorkflowIntent.requested / AiTaskResolver
    // already authorise them on their own words; they must not additionally be read as confirming
    // some other, unrelated pending proposal.
    assertFalse(AiWorkflowIntent.isAffirmativeConfirmation("осчетоводи фактурата"));
    assertFalse(
        AiWorkflowIntent.isAffirmativeConfirmation("Създай доставчика 831642181 за Upload#11"));
  }
}
