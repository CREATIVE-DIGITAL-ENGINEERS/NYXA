package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.prompt.AiAccountantMessages;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two ways this guard can be wrong, both of which it has been.
 *
 * <p>Too loose, and a model that ran no tools reports three invoices fixed. Too tight, and a user
 * who asked for nothing but a read-only check is told the answer they were just given was not saved
 * — which is what happened to «само потвърди дали е осчетоводена».
 */
class AiTurnTruthCheckTest {

  @BeforeEach
  void openTurn() {
    AiTurnToolLog.begin();
  }

  @AfterEach
  void closeTurn() {
    AiTurnToolLog.end();
  }

  // ── the dangerous case, which must keep firing ────────────────────────────

  @Test
  void flagsAFirstPersonWriteClaimWhenNothingWasWritten() {
    assertTrue(AiTurnTruthCheck.claimsUnsupportedWrite("Готово — осчетоводих фактурата."));
    assertTrue(AiTurnTruthCheck.claimsUnsupportedWrite("Валидирах я и я записах."));
    assertTrue(AiTurnTruthCheck.claimsUnsupportedWrite("Създадох доставчика СИ ТИ ДИ ЕООД."));
    assertTrue(AiTurnTruthCheck.claimsUnsupportedWrite("I posted it and set the origin date."));
    assertNotNull(AiTurnTruthCheck.falseSuccessCorrection("Коригирах датата."));
  }

  @Test
  void staysQuietWhenAWriteReallySucceeded() {
    AiTurnToolLog.record("edit_supplier_invoice", true, AiTurnToolLog.Status.OK);
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Готово — осчетоводих фактурата."));
    assertNull(AiTurnTruthCheck.falseSuccessCorrection("Коригирах датата."));
  }

  // ── false positives that would break read-only confirmations ──────────────

  @Test
  void doesNotFlagAStatementAboutARecordsExistingState() {
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Да — фактурата е осчетоводена."));
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Тя вече е валидирана."));
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Invoice 602 is POSTED."));
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("The supplier already exists."));
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Move 102 exists (journal PCH)."));
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Записът е успешно осчетоводен на 12.08."));
  }

  @Test
  void doesNotFlagADenial() {
    assertFalse(AiTurnTruthCheck.claimsUnsupportedWrite("Не съм я осчетоводил — има блокер."));
  }

  // ── promises of work that can never happen ────────────────────────────────

  @Test
  void flagsAPromiseToKeepWorkingAfterTheResponse() {
    assertTrue(
        AiTurnTruthCheck.promisesFutureWork(
            "Започвам веднага и ще върна кратък резултат щом приключа."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Обработвам ги сега."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Ще ви кажа когато приключа."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("I'll process these now and report back."));
    assertTrue(
        AiTurnTruthCheck.promisesFutureWork("Starting now — I'll let you know once I'm done."));
  }

  @Test
  void doesNotFlagLegitimateFutureStatements() {
    // Conditional: describes what would happen if the USER does something.
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "Ако по-късно качите документа, ще ви кажа какво липсва."));
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork("If you confirm, I will post it in the next message."));
    // An offer to act, which rule 3 explicitly asks for.
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork("Мога да коригирам датата — да го направя ли?"));
    // A plain blocker report.
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "Фактурата не може да се осчетоводи: липсват invoice terms."));
    assertFalse(AiTurnTruthCheck.promisesFutureWork(""));
    assertFalse(AiTurnTruthCheck.promisesFutureWork(null));
  }

  /**
   * A successful READ must not disarm the guard. Reading the records is exactly what the model does
   * immediately before announcing work it then never does, so counting it as work done let the real
   * failure through.
   */
  @Test
  void stillFlagsWhenOnlyReadsSucceeded() {
    AiTurnToolLog.record("search_ocr_records", false, AiTurnToolLog.Status.OK);
    AiTurnToolLog.record("get_ocr_record", false, AiTurnToolLog.Status.OK);
    assertTrue(
        AiTurnTruthCheck.promisesFutureWork(
            "Намерих 6 записа. Започвам да ги обработвам и ще върна резултат."),
        "reading changes nothing; the work was still not done");
  }

  @Test
  void doesNotFlagWhenAWriteActuallyLanded() {
    AiTurnToolLog.record("get_ocr_record", false, AiTurnToolLog.Status.OK);
    AiTurnToolLog.record("edit_supplier_invoice", true, AiTurnToolLog.Status.OK);
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork("Оправих първата, ще обработя останалите."),
        "re-running the model here risks repeating a write that already committed");
  }

  @Test
  void stillFlagsWhenEveryWriteFailed() {
    AiTurnToolLog.record("edit_supplier_invoice", true, AiTurnToolLog.Status.FAILED);
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Обработвам ги сега, ще ви кажа щом приключа."));
  }

  /**
   * A promise with zero tool calls behind it, in its plainest phrasing.
   *
   * <p>«Обработвам останалите и после ще докладвам» has neither an adverb on {@code обработвам} ("в
   * момента обработвам", "обработвам ги сега") nor a pronoun on {@code докладвам} ("ще ви
   * докладвам"). Both are ordinary Bulgarian without them, so a guard that is a list of the
   * phrasings somebody thought of misses the one the model actually uses.
   */
  @Test
  void catchesThePromiseThatSlippedThroughWithNoToolCallsAtAll() {
    assertTrue(
        AiTurnTruthCheck.promisesFutureWork("Обработвам останалите и после ще докладвам."),
        "a promise with zero tool calls behind it");
  }

  /** The pronoun is not part of the promise; requiring it would miss the plain form. */
  @Test
  void aReportingPromiseIsCaughtWithOrWithoutThePronoun() {
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Ще докладвам резултата."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Ще ви докладвам резултата."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Ще съобщя когато е готово."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Ще уведомя щом приключа."));
  }

  /**
   * The shape rule behind the phrase list: a mention of the records that are LEFT together with a
   * first-person verb of working or reporting. It is what a fake-background promise is made of, and
   * it does not depend on guessing the wording.
   */
  @Test
  void catchesAPromiseAboutTheRemainingRecordsInAnyWording() {
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Другите ги подготвям и ще кажа как е минало."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("Остатъка ще довърша след малко."));
    assertTrue(AiTurnTruthCheck.promisesFutureWork("I am handling the rest and will report."));
  }

  /**
   * The shape rule must not fire on a plain statement about what is left, which is exactly what
   * rule 22 asks the assistant to say after a partial batch.
   */
  @Test
  void doesNotFlagAnHonestReportOfWhatRemains() {
    AiTurnToolLog.record("ventilate_supplier_invoice", true, AiTurnToolLog.Status.OK);
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "Фактури 611 и 612 са осчетоводени. Остават 614 и 615."));

    AiTurnToolLog.end();
    AiTurnToolLog.begin();
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork("Остават 614 и 615 — кажете дали да продължа."),
        "naming the remaining records is not a promise to work on them unprompted");
  }

  // ── removing the promise, not annotating it ───────────────────────────────

  /**
   * The user must never read the promise. Appending the truth under it leaves two contradictory
   * sentences and no way to tell which is authoritative — and the misleading one is the one written
   * in the assistant's confident voice.
   */
  @Test
  void deletesThePromiseAndKeepsTheRest() {
    String kept =
        AiTurnTruthCheck.withoutFutureWorkPromises(
            "Фактура 611 е осчетоводена (move 102, journal PCH)."
                + " Обработвам останалите и после ще докладвам.");

    assertFalse(kept.contains("Обработвам останалите"));
    assertFalse(kept.contains("ще докладвам"));
    assertTrue(kept.contains("Фактура 611 е осчетоводена (move 102, journal PCH)."));
  }

  /** Every phrasing the guard recognises must also be one it can remove. */
  @Test
  void removesEveryShapeOfPromiseItDetects() {
    for (String promise :
        new String[] {
          "Обработвам останалите и после ще докладвам.",
          "Ще продължа с останалите.",
          "Ще докладвам после.",
          "Започвам веднага и ще върна резултат.",
          "I'll process these and report back."
        }) {
      String kept =
          AiTurnTruthCheck.withoutFutureWorkPromises("Фактура 611 е осчетоводена. " + promise);
      assertFalse(
          AiTurnTruthCheck.promisesFutureWork(kept), "still promises after removal: " + promise);
      assertTrue(kept.contains("Фактура 611 е осчетоводена."), promise);
    }
  }

  /** A reply that is nothing but a promise leaves nothing behind, and must not invent anything. */
  @Test
  void aReplyThatIsOnlyAPromiseBecomesEmpty() {
    assertEquals("", AiTurnTruthCheck.withoutFutureWorkPromises("Обработвам останалите."));
    assertEquals("", AiTurnTruthCheck.withoutFutureWorkPromises("   "));
    assertEquals("", AiTurnTruthCheck.withoutFutureWorkPromises(null));
  }

  /** An ordinary answer passes through untouched — removal only ever applies to a promise. */
  @Test
  void leavesAnHonestReplyExactlyAsItWas() {
    String text = "Фактура 946 е валидирана, но не е осчетоводена. Липсват invoice terms.";
    assertEquals(text, AiTurnTruthCheck.withoutFutureWorkPromises(text));
  }

  /**
   * The iteration-cap wording survives, because it is the one legitimate way a turn ends with work
   * outstanding: it asks the USER to act rather than promising the assistant will.
   */
  @Test
  void keepsTheRequestToSayContinue() {
    String notice =
        AiAccountantMessages.TRUNCATED_BG
            + AiAccountantMessages.TRUNCATED_STATE_BG
            + "invoice 606 is a DRAFT"
            + AiAccountantMessages.TRUNCATED_CONTINUE_BG;

    assertEquals(notice.trim(), AiTurnTruthCheck.withoutFutureWorkPromises(notice));
    assertTrue(AiTurnTruthCheck.withoutFutureWorkPromises(notice).contains("продължи"));
  }

  /**
   * The server's own truncation notice must not trip the guard it sits next to. Waiting for the
   * user to say «продължи» is the correct end of a turn that ran out of room, and the broadened
   * patterns would otherwise read it as a promise to keep working.
   */
  @Test
  void doesNotFlagTheServersOwnRequestToContinue() {
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            AiAccountantMessages.TRUNCATED_BG
                + AiAccountantMessages.TRUNCATED_STATE_BG
                + "invoice 606 is a DRAFT"
                + AiAccountantMessages.TRUNCATED_CONTINUE_BG));
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            AiAccountantMessages.TRUNCATED_EN + AiAccountantMessages.TRUNCATED_CONTINUE_EN));
  }

  /** A read-only turn that promises nothing stays untouched — the guard needs both halves. */
  @Test
  void leavesOrdinaryReadOnlyAnswersAlone() {
    AiTurnToolLog.record("get_ocr_record", false, AiTurnToolLog.Status.OK);
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork("Фактура 946 е валидирана, но не е осчетоводена."));
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "Липсват invoice terms — затова не може да се осчетоводи."));
  }

  // ── the conditional exemption, and the hole it must not have ──────────────

  /**
   * The hole. «Кажете», «потвърдете» and {@code confirm} must not exempt any sentence containing
   * them, or a promise survives simply by mentioning confirmation — a guard that switches off when
   * you say a common word is not a guard.
   *
   * <p>These are imperatives addressed to the user, not conditionals. Nothing about them makes the
   * clause beside them hypothetical.
   */
  @Test
  void aPromiseCannotEscapeByMentioningConfirmation() {
    for (String promise :
        new String[] {
          "Потвърдете и обработвам останалите сега.",
          "Кажете ми номера и продължавам да обработвам останалите.",
          "Confirm when ready — I'll process the rest and report back.",
          "Just let me know; I am handling the rest and will report."
        }) {
      assertTrue(AiTurnTruthCheck.promisesFutureWork(promise), promise);
      assertFalse(
          AiTurnTruthCheck.promisesFutureWork(AiTurnTruthCheck.withoutFutureWorkPromises(promise)),
          "survived removal: " + promise);
    }
  }

  /**
   * What replaced it: the server's own notices are recognised by identity, not by vocabulary.
   *
   * <p>They are exempt because they came out of {@code AiAccountantMessages}, so the exemption
   * covers exactly them and cannot be borrowed by anything the model writes.
   */
  @Test
  void theServersOwnSentencesAreExemptByIdentity() {
    assertTrue(AiTurnTruthCheck.isServerNotice("Кажете „продължи“ и ще довърша останалото."));
    assertTrue(AiTurnTruthCheck.isServerNotice("Say \"continue\" and I will finish the rest."));
    assertFalse(
        AiTurnTruthCheck.isServerNotice("Кажете „продължи“ и ще обработя останалите фактури."),
        "a near-miss the model wrote is not the server's sentence");
    assertFalse(AiTurnTruthCheck.isServerNotice("Готово."), "too short to identify anything");
    assertFalse(AiTurnTruthCheck.isServerNotice(null));
  }

  /** A genuine subordinate clause is still not a promise — that part was never the problem. */
  @Test
  void keepsExemptingRealConditionals() {
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "Ако качите останалите документи, ще ги обработя следващия път."));
    assertFalse(
        AiTurnTruthCheck.promisesFutureWork(
            "If you upload the rest, I'll process them next time."));
  }

  // ── finished-at-the-cap ───────────────────────────────────────────────────

  @Test
  void reportsCompletionInsteadOfAskingToContinue() {
    AiTurnTruthCheck.TurnState state =
        new AiTurnTruthCheck.TurnState(Collections.singletonList("invoice 606 is POSTED"), true);

    String reply = AiTurnTruthCheck.truncatedReply("Готово, фактурата е осчетоводена.", state);

    assertTrue(reply.contains("invoice 606 is POSTED"));
    assertFalse(reply.contains("продължи"), "nothing is left to continue");
    assertFalse(reply.contains("достигна лимита"), "the cap is internal and irrelevant here");
  }

  @Test
  void asksToContinueOnlyWhenWorkIsGenuinelyOutstanding() {
    AiTurnTruthCheck.TurnState state =
        new AiTurnTruthCheck.TurnState(
            Arrays.asList("invoice 606 is POSTED", "invoice 607 is still a DRAFT"), false);

    String reply = AiTurnTruthCheck.truncatedReply(null, state);

    assertTrue(reply.contains("достигна лимита"));
    assertTrue(reply.contains("invoice 607 is still a DRAFT"));
    assertTrue(reply.contains("продължи"));
  }

  @Test
  void claimsNothingWhenNoStateCouldBeEstablished() {
    AiTurnTruthCheck.TurnState state =
        new AiTurnTruthCheck.TurnState(Collections.emptyList(), false);

    String reply = AiTurnTruthCheck.truncatedReply(null, state);

    assertTrue(reply.contains("Част от действията може да са изпълнени"));
    // The failure that started all of this: a count of tool calls presented as business progress.
    assertFalse(reply.matches("(?s).*\\d+ от \\d+.*"));
  }

  @Test
  void dropsAModelSummaryThatClaimsAnUnsupportedWrite() {
    AiTurnTruthCheck.TurnState state =
        new AiTurnTruthCheck.TurnState(
            Collections.singletonList("invoice 607 is still a DRAFT"), false);

    String reply = AiTurnTruthCheck.truncatedReply("Осчетоводих и трите фактури.", state);

    assertFalse(reply.contains("Осчетоводих и трите"));
  }
}
