package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which refusals are work and which are questions.
 *
 * <p>The distinction the assistant did not have: every refusal arrived as the same envelope, so a
 * missing expense account and a duplicate invoice number looked identical, and the model did with
 * both what anyone does with things they cannot tell apart — it stopped and asked.
 */
class AiBlockerClassTest {

  /** The four that genuinely need a person, in the words the tools actually use. */
  @Test
  void aDecisionOnlyAHumanCanMakeAsksTheUser() {
    for (String message :
        new String[] {
          "Another invoice has the same supplier invoice number for this supplier and year.",
          "Дублиран номер на фактура — нужно е потвърждение.",
          "Several partners match the document ambiguously.",
          "Населеното място не е разпознато.",
          "A PFP acknowledgement is required."
        }) {
      AiBlockerClass blocker = AiBlockerClass.of("tool_error", message);
      assertEquals(AiBlockerClass.HUMAN_DECISION_REQUIRED, blocker, message);
      assertTrue(blocker.needsUser(), message);
      assertFalse(blocker.shouldContinue(), message);
    }
  }

  /** Everything the assistant has a tool for is work it is already authorised to do. */
  @Test
  void aRepairableBlockerKeepsTheAssistantWorking() {
    for (String message :
        new String[] {
          "The payable account is not reconcilable.",
          "Line 2 has no expense account.",
          "Ред 3 няма данъчен код.",
          "The validated invoice has no payment terms.",
          "Readiness reports a blocker: the record is not usable.",
          "Сумите не съвпадат с редовете."
        }) {
      AiBlockerClass blocker = AiBlockerClass.of("tool_error", message);
      assertEquals(AiBlockerClass.FIXABLE, blocker, message);
      assertTrue(blocker.shouldContinue(), message);
      assertFalse(blocker.needsUser(), message);
    }
  }

  /** A capability that is absent or switched off: say so, do not grind. */
  @Test
  void anAbsentCapabilityStopsTruthfully() {
    assertEquals(
        AiBlockerClass.CAPABILITY_MISSING, AiBlockerClass.of("unknown_tool", "No tool named 'x'."));
    assertEquals(
        AiBlockerClass.CAPABILITY_MISSING,
        AiBlockerClass.of("write_not_allowed", "Tool 'edit_supplier_invoice' is disabled."));
  }

  /** Something broke rather than refused. */
  @Test
  void afaultIsNeitherAQuestionNorRepairWork() {
    for (String code :
        new String[] {"tool_failed", "bad_arguments", "no_user", "no_company_scope"}) {
      AiBlockerClass blocker = AiBlockerClass.of(code, "boom");
      assertEquals(AiBlockerClass.TECHNICAL_FAILURE, blocker, code);
      assertFalse(blocker.needsUser(), code);
      assertFalse(blocker.shouldContinue(), code);
    }
  }

  /**
   * A call that was never attempted is not a blocked record. Classifying it as a question would
   * turn a batch abort into a reason to stop and ask about invoices nobody has tried yet.
   */
  @Test
  void anUnattemptedCallIsNotABlocker() {
    assertEquals(
        AiBlockerClass.FIXABLE,
        AiBlockerClass.of("not_executed", "An earlier tool failed on this same record."));
    assertEquals(
        AiBlockerClass.FIXABLE, AiBlockerClass.of("duplicate_call", "It was executed once."));
  }

  /**
   * The conservative direction. An unrecognised refusal costs one round trip if it was really
   * fixable, and a loop that burns the whole turn budget if it really needed a person.
   */
  @Test
  void anUnrecognisedRefusalAsksRatherThanGrinds() {
    assertEquals(AiBlockerClass.HUMAN_DECISION_REQUIRED, AiBlockerClass.of("tool_error", null));
    assertEquals(AiBlockerClass.HUMAN_DECISION_REQUIRED, AiBlockerClass.of("tool_error", ""));
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED,
        AiBlockerClass.of("tool_error", "Нещо съвсем неочаквано се случи."));
  }

  /** A message naming both is a human decision: the duplicate has to be settled first. */
  @Test
  void aHumanDecisionWinsOverARepairableOneInTheSameMessage() {
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED,
        AiBlockerClass.of(
            "tool_error", "Duplicate supplier invoice number, and line 2 has no expense account."));
  }
}
