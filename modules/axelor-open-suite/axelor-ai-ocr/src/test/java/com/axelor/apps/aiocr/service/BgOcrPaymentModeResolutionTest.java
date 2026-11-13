package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Payment-mode resolution for both OCR document paths — the supplier invoice and the non-invoice
 * Move.
 *
 * <p>These drive the real {@link BgOcrIntakeService} methods. Only the two JPA queries are replaced
 * (by {@link Intake} below), so every decision under test — which candidate wins, what happens when
 * the bank transfer is absent, whether a name is ever consulted — is production code. Nothing here
 * re-implements a predicate in order to assert it.
 *
 * <p>What is being pinned down: an OCR document nobody could classify must become an outgoing bank
 * transfer and nothing else, and when that mode is missing the failure has to be visible rather
 * than a null quietly left on a document that then reads as ready.
 *
 * <p>⚠️ {@code OUT_OTH} exists in the Bulgarian chart and carries {@code TYPE_OTHER}, so it is the
 * mode a plain by-type lookup returns. Several tests below put it in the store precisely so that
 * picking it counts as a failure: keeping the record and defaulting to it are different decisions,
 * and only the first one was taken.
 */
class BgOcrPaymentModeResolutionTest {

  // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

  /**
   * The outgoing bank transfer. Shares {@code TYPE_TRANSFER} with the SEPA direct debit, which is
   * the whole reason the resolution cannot be "pick an outgoing mode of this type".
   */
  private static PaymentMode outWt() {
    return mode("OUT_WT", "Изходящ банков превод", PaymentModeRepository.TYPE_TRANSFER);
  }

  private static PaymentMode outDd() {
    return mode("OUT_DD", "SEPA Директен дебит", PaymentModeRepository.TYPE_TRANSFER);
  }

  /**
   * The "other payment" mode, exactly as the seed still has it: {@code TYPE_OTHER}, and carrying
   * the Bulgarian name a by-name lookup would find. Present in these fixtures so that either a
   * by-type default or a by-name lookup lands on it and fails.
   */
  private static PaymentMode outOther() {
    return mode("OUT_OTH", "Друго плащане", PaymentModeRepository.TYPE_OTHER);
  }

  private static PaymentMode mode(String code, String name, int typeSelect) {
    PaymentMode m = new PaymentMode();
    m.setCode(code);
    m.setName(name);
    m.setTypeSelect(typeSelect);
    m.setInOutSelect(PaymentModeRepository.OUT);
    return m;
  }

  /**
   * {@link BgOcrIntakeService} with its two payment-mode queries replaced by an in-memory store.
   *
   * <p>The substitutes mirror the JPQL they stand in for, including the archived filter and the
   * lowest-id ordering (list order stands in for id order), and nothing else. Every rule stays in
   * the superclass.
   */
  private static final class Intake extends BgOcrIntakeService {
    private final List<PaymentMode> store;

    /** Every argument {@link #findPaymentModeByCode} was called with, in order. */
    private final List<String> codesAskedFor = new ArrayList<>();

    Intake(PaymentMode... modes) {
      super(null, null, null, null, null);
      this.store = new ArrayList<>(Arrays.asList(modes));
    }

    @Override
    protected PaymentMode findPaymentModeByCode(String code) {
      codesAskedFor.add(code);
      for (PaymentMode m : store) {
        if (m.getCode().equals(code) && !Boolean.TRUE.equals(m.getArchived())) {
          return m;
        }
      }
      return null;
    }

    @Override
    protected List<PaymentMode> fetchOutgoingPaymentModes(int typeSelect) {
      List<PaymentMode> out = new ArrayList<>();
      for (PaymentMode m : store) {
        if (m.getTypeSelect() != null
            && m.getTypeSelect() == typeSelect
            && PaymentModeRepository.OUT == m.getInOutSelect()
            && !Boolean.TRUE.equals(m.getArchived())) {
          out.add(m);
        }
      }
      return out;
    }
  }

  // ── Invoice OCR ─────────────────────────────────────────────────────────────────────────────

  /**
   * The headline case. TYPE_OTHER is redirected to the outgoing transfer lookup, which finds both
   * OUT_WT and OUT_DD — and the exact stable code has to win, in either candidate order. Order is
   * varied because the previous behaviour ("lowest id") would pass one arrangement and fail the
   * other, which is exactly how a bug like this survives a test suite.
   */
  @Test
  void invoiceOtherResolvesToExactBankTransferWhateverTheCandidateOrder() {
    PaymentMode wt = outWt();
    assertSame(
        wt,
        new Intake(outOther(), outDd(), wt)
            .resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER),
        "OUT_DD sorted first must not win: TYPE_OTHER may only become OUT_WT");

    PaymentMode wtFirst = outWt();
    assertSame(
        wtFirst,
        new Intake(wtFirst, outDd(), outOther())
            .resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER));
  }

  /**
   * The decision this branch exists for. OUT_OTH is configured, carries TYPE_OTHER and would be the
   * only candidate of a by-type lookup — and must still not be what OCR proposes.
   */
  @Test
  void invoiceOtherDoesNotPickOutOtherEvenThoughItIsConfiguredAndTypeMatches() {
    PaymentMode wt = outWt();
    assertSame(
        wt,
        new Intake(outOther(), wt).resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER),
        "keeping OUT_OTH in the chart is not the same decision as defaulting OCR to it");
  }

  /**
   * OUT_WT missing, OUT_DD present. The redirect alone would hand back a direct debit — a document
   * nobody could classify would be settled by pulling money from the company's account.
   */
  @Test
  void invoiceOtherRefusesDirectDebitWhenBankTransferIsMissing() {
    assertNull(
        new Intake(outDd(), outOther())
            .resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER),
        "with OUT_WT absent the default must refuse, never settle for another transfer mode");
  }

  /** The same refusal when OUT_DD is the only outgoing transfer mode of any kind. */
  @Test
  void invoiceOtherRefusesTheSingleRemainingTransferMode() {
    assertNull(new Intake(outDd()).resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER));
  }

  /** An archived OUT_WT is not configuration a new document may use. */
  @Test
  void invoiceOtherIgnoresAnArchivedBankTransfer() {
    PaymentMode archived = outWt();
    archived.setArchived(Boolean.TRUE);
    assertNull(
        new Intake(archived, outOther())
            .resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_OTHER),
        "an archived mode must not be handed to a new document");
  }

  /**
   * The refusal is visible rather than silent, in the two places that carry it: the message names
   * OUT_WT, and an invoice left with no payment mode is reported by the readiness service as a
   * ventilate blocker — so it cannot pass as ready.
   */
  @Test
  void invoiceWithNoPaymentModeIsBlockedAndTheMessageNamesOutWt() {
    String message = BgOcrIntakeService.missingBankTransferModeMessage();
    assertTrue(message.contains("OUT_WT"), "the configuration failure must name the exact code");
    assertTrue(
        message.contains("missing or not configured"),
        "the configuration failure must say what is wrong: " + message);

    OcrSupplierInvoiceReadinessService.Signals signals =
        new OcrSupplierInvoiceReadinessService.Signals();
    signals.paymentModePresent = false;
    OcrInvoiceReadiness readiness = new OcrSupplierInvoiceReadinessService().evaluate(signals);
    assertTrue(
        readiness.getVentilateBlockers().stream()
            .anyMatch(b -> b.startsWith("Payment mode missing")),
        "a null payment mode must block ventilation: " + readiness.getVentilateBlockers());
  }

  /**
   * Regression: the detected classifications are untouched. These run the whole production entry
   * point — OCR text in, invoice payment mode out — rather than the resolver alone.
   */
  @Test
  void detectedClassificationsKeepTheirExistingBehaviour() {
    PaymentMode cash = mode("OUT_CSH", "Плащане в брой", PaymentModeRepository.TYPE_CASH);
    PaymentMode fiscal = mode("OUT_FIS", "Изходящ касов", PaymentModeRepository.TYPE_CASH);
    PaymentMode card =
        mode("OUT_CC", "Плащане с кредитна карта", PaymentModeRepository.TYPE_BANK_CARD);
    PaymentMode wt = outWt();
    PaymentMode dd = outDd();
    // Deliberately ordered so that "lowest id" is the WRONG answer for cash and for transfer.
    Intake intake = new Intake(fiscal, cash, dd, wt, card, outOther());

    assertSame(cash, applyTo(intake, "Плащане в брой"), "cash must still prefer OUT_CSH");
    assertSame(card, applyTo(intake, "Плащане с кредитна карта"), "card must still prefer OUT_CC");
    assertSame(wt, applyTo(intake, "Плащане по банков път"), "transfer must still prefer OUT_WT");

    // Direct debit is reached by its own type, which no detection produces; resolved by type it is
    // still the only candidate and still comes back.
    assertSame(
        dd,
        new Intake(dd).resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_TRANSFER),
        "a detected transfer with only OUT_DD configured keeps the lowest-id fallback");
  }

  /** A detected cheque has no canonical code and no outgoing mode — behaviour is unchanged. */
  @Test
  void detectedChequeStillResolvesByTypeAlone() {
    PaymentMode cheque = mode("OUT_CHQ", "Плащане с чек", PaymentModeRepository.TYPE_CHEQUE);
    assertSame(
        cheque,
        new Intake(cheque, outOther())
            .resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_CHEQUE),
        "a type with no canonical code still takes its single candidate");
    assertNull(
        new Intake(outWt()).resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_CHEQUE),
        "and still returns null when no mode of that type exists");
  }

  /** A detected type whose canonical code is absent still falls back — that behaviour is kept. */
  @Test
  void detectedCashFallsBackToTheOtherCashModeWhenTheCanonicalOneIsMissing() {
    PaymentMode fiscal = mode("OUT_FIS", "Изходящ касов", PaymentModeRepository.TYPE_CASH);
    assertSame(
        fiscal,
        new Intake(fiscal).resolveOutgoingPaymentMode(PaymentModeRepository.TYPE_CASH),
        "only the TYPE_OTHER default is strict; a detected type keeps its deterministic fallback");
  }

  private static PaymentMode applyTo(BgOcrIntakeService intake, String paymentTerm) {
    Invoice invoice = new Invoice();
    OcrExtractResult result = new OcrExtractResult();
    result.setPaymentTerm(paymentTerm);
    intake.applyOcrPaymentMethod(invoice, result, new ArrayList<>());
    return invoice.getPaymentMode();
  }

  // ── Non-invoice Move OCR ────────────────────────────────────────────────────────────────────

  /**
   * The Move path asks for codes only, never for {@code self.name = 'Друго плащане'} — asserted
   * directly, because "no name lookup" is the actual requirement, and the mode whose name matches
   * is sitting in the store waiting to be picked by one.
   */
  @Test
  void moveWithoutPosResolvesToBankTransferByCodeAndNeverByName() {
    Intake intake = new Intake(outOther(), outDd(), outWt());
    PaymentMode resolved = intake.resolveMovePaymentMode("Фактура за консумативи, платена");

    assertNotNull(resolved);
    assertEquals("OUT_WT", resolved.getCode());
    assertEquals(
        Collections.singletonList("OUT_WT"),
        intake.codesAskedFor,
        "the Move path must ask for one stable code and nothing else");
  }

  /** Candidate order cannot change the answer: the lookup is by code, not by position. */
  @Test
  void moveResolutionIsIndependentOfCandidateOrder() {
    assertEquals(
        "OUT_WT", new Intake(outWt(), outDd(), outOther()).resolveMovePaymentMode(null).getCode());
    assertEquals(
        "OUT_WT", new Intake(outOther(), outDd(), outWt()).resolveMovePaymentMode(null).getCode());
  }

  /**
   * POS wording still reaches the POS terminal mode — now by code. {@code INC_POS} is an incoming
   * mode in the Bulgarian chart, which is what the name lookup selected too: the direction is
   * preserved and only the key changes.
   */
  @Test
  void movePosTextStillResolvesToThePosTerminalMode() {
    PaymentMode pos = mode("INC_POS", "POS терминал", PaymentModeRepository.TYPE_BANK_CARD);
    pos.setInOutSelect(PaymentModeRepository.IN);
    Intake intake = new Intake(pos, outWt(), outOther());

    PaymentMode resolved = intake.resolveMovePaymentMode("Плащане чрез ПОС терминал");
    assertSame(pos, resolved);
    assertEquals(PaymentModeRepository.IN, resolved.getInOutSelect().intValue());
    assertEquals(Collections.singletonList("INC_POS"), intake.codesAskedFor);
  }

  /**
   * OUT_WT missing on the Move path. This one has no review-note channel, so the configuration
   * error is raised — visibly, naming the code — instead of leaving a Move with no payment mode,
   * which is what the name lookup silently did in every non-Bulgarian installation.
   */
  @Test
  void moveWithoutBankTransferConfiguredFailsVisibly() {
    Intake intake = new Intake(outDd(), outOther());

    IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> intake.resolveMovePaymentMode(null));

    assertTrue(raised.getMessage().contains("OUT_WT"), raised.getMessage());
    assertTrue(raised.getMessage().contains("missing or not configured"), raised.getMessage());
    assertFalse(
        raised.getMessage().contains("Друго"),
        "the failure must point at the missing bank transfer, not at the mode it refuses to use");
  }

  /** An archived OUT_WT is no configuration at all, and must fail the same way. */
  @Test
  void moveTreatsAnArchivedBankTransferAsMissing() {
    PaymentMode archived = outWt();
    archived.setArchived(Boolean.TRUE);
    assertThrows(
        IllegalStateException.class,
        () -> new Intake(archived, outOther()).resolveMovePaymentMode(null));
  }
}
