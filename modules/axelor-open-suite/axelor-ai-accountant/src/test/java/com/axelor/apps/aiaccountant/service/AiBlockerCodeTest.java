package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Refusals classified by what they are, not by the words they happen to use.
 *
 * <p>The behaviour these pin down is the one that used to be fragile: the same refusal classified
 * differently depending on its wording, and a reworded message silently changed whether the
 * assistant kept working or stopped to ask. Prose matching is still there as a fallback — the tests
 * for it are in {@code AiBlockerClassTest} — but it must never override a code.
 */
class AiBlockerCodeTest {

  /**
   * The point of the whole change: a coded refusal is classified by its code even when its message
   * says the opposite.
   *
   * <p>The message here is deliberately full of the wrong signals. "Duplicate" is the word the
   * fallback reads as a human decision, and this refusal is a missing expense account the assistant
   * can clear itself.
   */
  @Test
  void theCodeDecides_evenWhenTheWordsSayOtherwise() {
    AiBlockerClass blocker =
        AiBlockerClass.of(
            "NOT_READY_TO_POST",
            "tool_error",
            "Duplicate lines detected; a PFP acknowledgement may be needed.");

    assertEquals(
        AiBlockerClass.FIXABLE, blocker, "the code names it, the message only explains it");
  }

  /** And the reverse: a fixable-sounding message does not make a human decision into work. */
  @Test
  void aFixableSoundingMessageCannotDowngradeAHumanDecision() {
    AiBlockerClass blocker =
        AiBlockerClass.of(
            "DUPLICATE_INVOICE_NUMBER",
            "tool_error",
            "The account and the tax code are both present; nothing to reconcile.");

    assertEquals(AiBlockerClass.HUMAN_DECISION_REQUIRED, blocker);
  }

  /** The blocker detail outranks the envelope code, which is the coarser of the two. */
  @Test
  void theBusinessBlockerOutranksTheEnvelope() {
    assertEquals(
        AiBlockerClass.FIXABLE,
        AiBlockerClass.of("MISSING_INVOICE_TERMS", "tool_error", "no terms"));
    assertEquals(
        AiBlockerClass.CAPABILITY_MISSING,
        AiBlockerClass.of(null, "unknown_tool", "no tool named 'x'"),
        "with no business blocker the envelope still decides");
  }

  /**
   * An unrecognised code is not silently resolved to something. It falls through to the message,
   * which is the only remaining signal, and from there to the conservative default.
   */
  @Test
  void anUnknownCodeFallsBackRatherThanGuessing() {
    assertNull(AiBlockerCode.parse("SOMETHING_INVENTED"));
    assertEquals(
        AiBlockerClass.FIXABLE,
        AiBlockerClass.of("SOMETHING_INVENTED", "tool_error", "Line 2 has no expense account."),
        "unknown code, so the message is read");
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED,
        AiBlockerClass.of("SOMETHING_INVENTED", "tool_error", "Нещо съвсем неочаквано."),
        "unknown code and unreadable message, so it asks");
  }

  /** Parsing accepts the wire form and the constant's own name, in any case. */
  @Test
  void codesParseInEitherForm() {
    assertEquals(AiBlockerCode.UNKNOWN_TOOL, AiBlockerCode.parse("unknown_tool"));
    assertEquals(AiBlockerCode.UNKNOWN_TOOL, AiBlockerCode.parse("UNKNOWN_TOOL"));
    assertEquals(AiBlockerCode.NOT_READY_TO_POST, AiBlockerCode.parse("not_ready_to_post"));
    assertEquals(
        AiBlockerCode.MISSING_INVOICE_TERMS, AiBlockerCode.parse("  MISSING_INVOICE_TERMS  "));
    assertNull(AiBlockerCode.parse(null));
    assertNull(AiBlockerCode.parse("   "));
  }

  /** Every constant is usable: it has a wire form, a classification, and no twin. */
  @Test
  void theVocabularyIsWellFormed() {
    Set<String> seen = new HashSet<>();
    for (AiBlockerCode code : AiBlockerCode.values()) {
      assertNotNull(code.wire(), code.name());
      assertNotNull(code.classification(), code.name());
      assertEquals(code, AiBlockerCode.parse(code.wire()), code.name());
      assertTrue(seen.add(code.wire().toUpperCase(Locale.ROOT)), "duplicate wire " + code.wire());
    }
  }

  /**
   * A call that was never attempted stays work rather than becoming a question.
   *
   * <p>Same rule as before the codes existed, now stated by the code rather than by the envelope
   * switch: a batch abort must not read as a reason to stop and ask about records nobody tried.
   */
  @Test
  void anUnattemptedCallIsStillNotABlocker() {
    assertEquals(AiBlockerClass.FIXABLE, AiBlockerCode.NOT_EXECUTED.classification());
    assertEquals(AiBlockerClass.FIXABLE, AiBlockerCode.DUPLICATE_CALL.classification());
  }

  /**
   * The repeat guard is not something to grind against.
   *
   * <p>It refuses precisely because the record has not changed, so trying again is the one action
   * guaranteed to fail. This used to classify from a message that embedded the <em>earlier</em>
   * failure's text, so the same refusal landed in different classes depending on what had failed
   * before it.
   */
  @Test
  void aRepeatedCallIsNotRetriedForever() {
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED, AiBlockerCode.ALREADY_FAILED.classification());
  }

  /**
   * A missing payable account is not fixable here, and saying otherwise would send the assistant
   * after a tool that only flips flags on an account that already exists.
   */
  @Test
  void theAssistantOnlyClaimsRepairsItCanActuallyMake() {
    assertEquals(
        AiBlockerClass.FIXABLE,
        AiBlockerCode.PARTNER_ACCOUNT_MISCONFIGURED.classification(),
        "fix_partner_account turns exactly these flags on");
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED,
        AiBlockerCode.MISSING_PARTNER_ACCOUNT.classification(),
        "nothing here assigns a payable account");
  }
}
