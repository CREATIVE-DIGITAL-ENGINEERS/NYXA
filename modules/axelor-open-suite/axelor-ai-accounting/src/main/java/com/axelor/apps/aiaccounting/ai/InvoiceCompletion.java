package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadiness;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadinessService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether a repair or creation job on an {@code Invoice#id} is actually finished.
 *
 * <h2>What "done" means when there is no stage to reach</h2>
 *
 * «Оправи 641» asks for a change, not for a status, so completion cannot be read off the lifecycle
 * the way a posting's can — a repaired invoice is still a DRAFT. It also cannot be read off the
 * tool ledger: a successful line edit is one of four repairs, and calling the job finished after
 * the first would be the false claim this whole layer exists to prevent. So it is read from
 * readiness: the job is done when the invoice can actually move.
 *
 * <h2>Ownership: this answers for {@code Invoice#}, the OCR resolver for {@code Upload#}</h2>
 *
 * Both reference forms name the same document, and both are in ordinary use — the OCR tools produce
 * {@code Upload#}, while {@code AiWriteEffects} and the invoice search tools produce {@code
 * Invoice#}. Splitting the completion rule between them by which name was used would give the same
 * invoice two different answers depending on how it was reached. So the rule lives here, once, and
 * the OCR-specific part of it arrives through {@code AiInvoiceReadinessContributor}, which {@link
 * InvoiceReadinessService} already folds in. An OCR invoice therefore gets its OCR blockers counted
 * whichever way it was addressed, with no dependency pointing from this module at that one.
 */
@Singleton
public class InvoiceCompletion implements AiCompletionResolver {

  /**
   * The tasks this resolver answers for.
   *
   * <p>VALIDATE and POST are deliberately absent: their completion is the status itself, which the
   * workflow controller already reads from the lifecycle. Answering for them here would put a
   * second opinion beside a fact.
   */
  private static final Set<String> ANSWERED_TASKS =
      new HashSet<>(
          Arrays.asList(
              "REPAIR_SUPPLIER_INVOICE",
              "CREATE_SUPPLIER_INVOICE",
              "REPAIR_CUSTOMER_INVOICE",
              "CREATE_CUSTOMER_INVOICE"));

  /**
   * Creation asks for a document to exist, not for it to be postable.
   *
   * <p>«Направи фактура за X» is answered by a DRAFT with the lines that were asked for. Whether
   * that draft can also be posted depends on the company's journals, sequences and account
   * configuration — none of which the user asked to be fixed, and some of which no tool here can
   * fix. Holding the job open on them would leave a created invoice reported as unfinished work
   * forever, and invite the model to keep going at things nobody asked it to touch.
   */
  private static final Set<String> CREATE_TASKS =
      new HashSet<>(Arrays.asList("CREATE_SUPPLIER_INVOICE", "CREATE_CUSTOMER_INVOICE"));

  @Override
  public Boolean isComplete(String ref, String task) {
    if (task == null || !ANSWERED_TASKS.contains(task)) {
      return null;
    }
    Long id = InvoiceRecordResolver.invoiceId(ref);
    if (id == null) {
      // Not an Invoice# reference. The OCR module answers for its own upload references.
      return null;
    }
    Invoice invoice = InvoiceRecordResolver.find(id);
    if (invoice == null) {
      // Not this company's, or gone. Silence rather than false: an unverifiable outcome is not a
      // finished one, and the registry treats no answer as "still active".
      return null;
    }
    if (CREATE_TASKS.contains(task)) {
      // The draft the turn asked for is on record and reachable. That is the whole request.
      return Boolean.TRUE;
    }
    InvoiceReadiness readiness = Beans.get(InvoiceReadinessService.class).check(invoice, task);
    // Validation readiness only. A repair is about the document — its partner, its dates, its
    // lines, its references — and validation is the transition those feed. Posting additionally
    // needs the company's own accounting configuration in place: a journal for the type, a
    // sequence for the year, accounts on the partner. Requiring that here would report «оправи
    // 641» as unfinished because of a missing sequence the user never mentioned and this job was
    // never authorised to change.
    return readiness.isValidateReady();
  }
}
