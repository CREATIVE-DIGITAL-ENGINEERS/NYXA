package com.axelor.apps.aiaccounting.service.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * A correction is applied whole or not at all.
 *
 * <p>A header field the caller named and that cannot be applied must not merely be collected into
 * {@code rejected}, reported beside the fields that DID land, with the invoice saved anyway: «смени
 * датата и номера» with one bad value would then return "edited" for an invoice carrying half the
 * correction — and the next step would be validating it. A rejection refuses the whole request
 * before anything is computed or saved, and the surrounding transaction takes the in-memory changes
 * back with it.
 *
 * <p>What these cover is the refusal and the ordering: that nothing reaches the save path once a
 * field has been rejected. That the DATABASE then keeps nothing is the transaction's own property
 * and is not asserted here.
 */
class InvoiceEditAtomicityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A header editor that applies one field and rejects another, as the live one would. */
  private static InvoiceHeaderEditService rejecting(String field, String reason) {
    return new InvoiceHeaderEditService() {
      @Override
      public Result apply(
          Invoice invoice, InvoiceSide side, com.fasterxml.jackson.databind.JsonNode header) {
        Result result = new Result();
        result.getRejected().add(new Rejection(field, reason));
        return result;
      }
    };
  }

  @Test
  void aRejectedFieldRefusesTheWholeRequestAndNamesIt() {
    InvoiceHeaderEditService.Result result = new InvoiceHeaderEditService.Result();
    result.getRejected().add(new InvoiceHeaderEditService.Rejection("dueDate", "not a date"));
    result
        .getRejected()
        .add(new InvoiceHeaderEditService.Rejection("supplierInvoiceNb", "already used"));

    InvoiceBlocked blocked =
        assertThrows(
            InvoiceBlocked.class, () -> result.refuseIfAnyRejected("Nothing was changed."));

    assertEquals(AiBlockerCode.BAD_ARGUMENTS.wire(), blocked.getCode());
    assertTrue(blocked.getMessage().contains("dueDate"));
    assertTrue(blocked.getMessage().contains("supplierInvoiceNb"));
    assertTrue(blocked.getMessage().contains("Nothing was changed."));
  }

  @Test
  void nothingIsRefusedWhenNoFieldWasRejected() {
    new InvoiceHeaderEditService.Result().refuseIfAnyRejected("Nothing was changed.");
  }

  /** The ordering that makes the refusal atomic: the save path is never reached. */
  @Test
  void aRejectedHeaderFieldStopsTheEditBeforeAnythingIsSaved() {
    Invoice invoice = new Invoice();
    boolean[] saved = {false};

    InvoiceEditService service =
        new InvoiceEditService() {
          @Override
          protected InvoiceRecordGuard.Target resolveDraft(long invoiceId, InvoiceSide side) {
            return new InvoiceRecordGuard.Target(invoice, side);
          }

          @Override
          protected InvoiceHeaderEditService headerEditor() {
            return rejecting("dueDate", "not a date");
          }

          @Override
          protected void computeAndSave(Invoice toSave) {
            saved[0] = true;
          }
        };

    ObjectNode header = MAPPER.createObjectNode();
    header.put("dueDate", "not-a-date");

    assertThrows(
        InvoiceBlocked.class,
        () ->
            service.applyInTransaction(
                641L,
                InvoiceSide.SUPPLIER,
                new InvoiceEditService.EditRequest().setHeader(header)));

    assertFalse(saved[0], "a refused correction must never reach the save path");
  }
}
