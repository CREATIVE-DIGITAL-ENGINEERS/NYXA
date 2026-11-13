package com.axelor.apps.nyxasecurity.posting;

import static com.axelor.apps.account.db.repo.MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT;
import static com.axelor.apps.account.db.repo.MoveRepository.FUNCTIONAL_ORIGIN_SALE;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_ACCOUNTED;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_CANCELED;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_DAYBOOK;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_NEW;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_SIMULATED;
import static com.axelor.apps.account.db.repo.MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC;
import static com.axelor.apps.account.db.repo.MoveRepository.TECHNICAL_ORIGIN_ENTRY;
import static com.axelor.apps.account.db.repo.MoveRepository.TECHNICAL_ORIGIN_IMPORT;
import static com.axelor.apps.account.db.repo.MoveRepository.TECHNICAL_ORIGIN_MASS_ENTRY;
import static com.axelor.apps.account.db.repo.MoveRepository.TECHNICAL_ORIGIN_TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManualValidationPolicyTest {

  private static final String MOVE = "com.axelor.apps.account.db.Move";
  private static final String INVOICE = "com.axelor.apps.account.db.Invoice";
  private static final String STOCK_MOVE = "com.axelor.apps.stock.db.StockMove";

  private static final Integer[] EVERY_ORIGIN = {
    TECHNICAL_ORIGIN_ENTRY,
    TECHNICAL_ORIGIN_AUTOMATIC,
    TECHNICAL_ORIGIN_TEMPLATE,
    TECHNICAL_ORIGIN_IMPORT,
    TECHNICAL_ORIGIN_MASS_ENTRY,
    null,
    99
  };

  private static String source(Object... propertyValuePairs) {
    Map<String, Object> move = new HashMap<>();
    for (int i = 0; i < propertyValuePairs.length; i += 2) {
      move.put((String) propertyValuePairs[i], propertyValuePairs[i + 1]);
    }
    return ManualValidationPolicy.sourceModel(move::get);
  }

  private static String required(
      Integer from, Integer to, Integer origin, boolean created, String source) {
    return ManualValidationPolicy.requiredModel(from, to, origin, created, source);
  }

  @Test
  void theSourceIsReadFromTheMove() {
    Object any = new Object();
    assertEquals(INVOICE, source("invoice", any, "functionalOriginSelect", FUNCTIONAL_ORIGIN_SALE));
    assertEquals(STOCK_MOVE, source("stockMove", any));
    assertEquals("com.axelor.apps.account.db.PaymentVoucher", source("paymentVoucher", any));
    assertEquals("com.axelor.apps.account.db.PaymentSession", source("paymentSession", any));
    assertEquals("com.axelor.apps.hr.db.Expense", source("expense", any));
    assertEquals("com.axelor.apps.hr.db.Expense", source("expensePayment", any));
    // the payment document wins over the invoice it pays
    assertEquals(
        "com.axelor.apps.account.db.PaymentVoucher", source("paymentVoucher", any, "invoice", any));
    // an invoice payment, a bank order, a reversal: Payment origin, no source → write on Move
    assertNull(source("functionalOriginSelect", FUNCTIONAL_ORIGIN_PAYMENT));
    assertNull(source());
    assertNull(source("functionalOriginSelect", FUNCTIONAL_ORIGIN_SALE));
  }

  @Test
  void aModuleFlowNeedsWriteOnItsSourceDocument() {
    // ventilation, stock issue, payment: created and validated in the same transaction
    assertEquals(
        INVOICE, required(STATUS_NEW, STATUS_ACCOUNTED, TECHNICAL_ORIGIN_AUTOMATIC, true, INVOICE));
    assertEquals(
        STOCK_MOVE, required(null, STATUS_ACCOUNTED, TECHNICAL_ORIGIN_AUTOMATIC, true, STOCK_MOVE));
    // the source cannot be told: write on Move, as for an unknown origin
    assertEquals(
        MOVE, required(STATUS_NEW, STATUS_ACCOUNTED, TECHNICAL_ORIGIN_AUTOMATIC, true, null));
  }

  @Test
  void everythingElseNeedsWriteOnMove() {
    for (Integer origin : EVERY_ORIGIN) {
      // a saved draft, whatever its origin and even with a source document
      for (String source : new String[] {null, INVOICE}) {
        assertEquals(MOVE, required(STATUS_NEW, STATUS_ACCOUNTED, origin, false, source));
        assertEquals(MOVE, required(STATUS_NEW, STATUS_DAYBOOK, origin, false, source));
        assertEquals(MOVE, required(STATUS_DAYBOOK, STATUS_ACCOUNTED, origin, false, source));
        assertEquals(MOVE, required(STATUS_SIMULATED, STATUS_ACCOUNTED, origin, false, source));
      }
      // created in the same flow, but not by a module
      if (origin == null || origin != TECHNICAL_ORIGIN_AUTOMATIC) {
        assertEquals(MOVE, required(null, STATUS_ACCOUNTED, origin, true, INVOICE));
      }
    }
  }

  @Test
  void otherChangesNeedNothing() {
    for (Integer origin : EVERY_ORIGIN) {
      assertNull(required(STATUS_NEW, STATUS_NEW, origin, false, null));
      assertNull(required(STATUS_ACCOUNTED, STATUS_ACCOUNTED, origin, false, null));
      assertNull(required(STATUS_DAYBOOK, STATUS_DAYBOOK, origin, false, null));
      assertNull(required(STATUS_DAYBOOK, STATUS_CANCELED, origin, false, null));
      assertNull(required(null, STATUS_NEW, origin, true, INVOICE));
    }
  }

  @Test
  void theAutomaticPostingUserPostsOnlyItsOwnStockIssue() {
    Long stk = 7L;
    assertTrue(may(TECHNICAL_ORIGIN_AUTOMATIC, true, STOCK_MOVE, stk, stk));
    // another journal, no stock issue journal, a hand-entered move, an invoice, no source, a draft
    assertFalse(may(TECHNICAL_ORIGIN_AUTOMATIC, true, STOCK_MOVE, 8L, stk));
    assertFalse(may(TECHNICAL_ORIGIN_AUTOMATIC, true, STOCK_MOVE, stk, null));
    assertFalse(may(TECHNICAL_ORIGIN_ENTRY, true, STOCK_MOVE, stk, stk));
    assertFalse(may(TECHNICAL_ORIGIN_AUTOMATIC, true, INVOICE, stk, stk));
    assertFalse(may(TECHNICAL_ORIGIN_AUTOMATIC, true, null, stk, stk));
    assertFalse(may(TECHNICAL_ORIGIN_AUTOMATIC, false, STOCK_MOVE, stk, stk));
  }

  private static boolean may(
      Integer origin, boolean created, String source, Long journal, Long stockIssueJournal) {
    return ManualValidationPolicy.autoPostingMay(
        origin, created, source, journal, stockIssueJournal);
  }

  @Test
  void anAutomaticEntryInThePayrollJournalIsPostedFromThePaySlip() {
    Long pay = 9L;
    String slip = ManualValidationPolicy.PAY_SLIP;
    assertEquals("com.axelor.apps.bglocalization.db.BgPaySlip", slip);
    assertEquals(slip, payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, pay, pay));
    // Paying the slip then needs write on the slip, not on Move — validated or daybook.
    assertEquals(
        slip,
        required(
            STATUS_NEW,
            STATUS_ACCOUNTED,
            TECHNICAL_ORIGIN_AUTOMATIC,
            true,
            payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, pay, pay)));
    assertEquals(
        slip,
        required(
            STATUS_NEW,
            STATUS_DAYBOOK,
            TECHNICAL_ORIGIN_AUTOMATIC,
            true,
            payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, pay, pay)));
  }

  @Test
  void anythingElseInThePayrollJournalStaysAMoveOrItsOwnSource() {
    Long pay = 9L;
    assertNull(payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, 8L, pay), "another journal");
    assertNull(payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, pay, null), "no payroll journal");
    assertNull(payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, null, null, null), "no journal at all");
    assertNull(payroll(TECHNICAL_ORIGIN_AUTOMATIC, false, null, pay, pay), "a draft saved before");
    for (Integer origin : EVERY_ORIGIN) {
      if (origin == null || origin != TECHNICAL_ORIGIN_AUTOMATIC) {
        assertNull(payroll(origin, true, null, pay, pay), "typed, imported, template: " + origin);
      }
    }
    assertEquals(INVOICE, payroll(TECHNICAL_ORIGIN_AUTOMATIC, true, INVOICE, pay, pay));
    assertEquals(
        MOVE,
        required(
            STATUS_NEW,
            STATUS_ACCOUNTED,
            TECHNICAL_ORIGIN_AUTOMATIC,
            false,
            payroll(TECHNICAL_ORIGIN_AUTOMATIC, false, null, pay, pay)),
        "a payroll draft validated later needs write on Move");
  }

  private static String payroll(
      Integer origin, boolean created, String source, Long journal, Long payrollJournal) {
    return ManualValidationPolicy.payrollSource(origin, created, source, journal, payrollJournal);
  }

  @Test
  void writePermissionIsNeeded() {
    assertTrue(ManualValidationPolicy.mayValidate(false, 1));
    assertTrue(ManualValidationPolicy.mayValidate(true, 0));
    assertFalse(ManualValidationPolicy.mayValidate(false, 0));
  }

  @Test
  void aModelIsMatchedByClassOrPackageWildcard() {
    assertEquals(
        Set.of(MOVE, "com.axelor.apps.account.db.*"), ManualValidationPolicy.objectsFor(MOVE));
    assertEquals(
        Set.of(STOCK_MOVE, "com.axelor.apps.stock.db.*"),
        ManualValidationPolicy.objectsFor(STOCK_MOVE));
  }
}
