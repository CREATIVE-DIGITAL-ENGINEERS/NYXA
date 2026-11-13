package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether a {@code Create:<DOMAIN>:<companyId>} job actually produced the document it was for.
 *
 * <h2>Why the job has to be able to settle</h2>
 *
 * A target nothing can complete stays "remaining" for the life of the job: the status line keeps
 * reporting outstanding work on an invoice that was created three turns ago, {@code
 * shouldKeepWorking} keeps saying there is more to do, and the next unrelated instruction can
 * inherit a job that should have closed. That is exactly what happened to {@code PartnerCreate:}
 * before {@code PartnerCreateCompletion} existed, and a creation target with no resolver would
 * repeat it.
 *
 * <h2>Read from the record, not from the tool</h2>
 *
 * {@link AiWriteEffects} is the server's own turn-scoped ledger of records a committed write
 * touched — the tool's claim of success is not consulted, and a rolled-back write never reaches it.
 * Each id it names is loaded and checked: it must exist, be this company's, and be the right kind
 * of document (an {@code Invoice} on the side the target names, a {@code Move} for a journal
 * entry). Two independent facts, both from the database.
 *
 * <p>Confining the search to ids this turn wrote is what keeps it honest. "Does this company have a
 * supplier invoice?" is true of every company and would complete the job before it started; "did
 * this turn commit one?" is the postcondition the instruction actually asked for.
 *
 * <p>FALSE rather than null when nothing matches: the resolver owns this reference kind, and "no
 * document of this kind was created this turn" is a real answer about it. Null only for a reference
 * it does not own, or another company's.
 */
@Singleton
public class AiCreationCompletion implements AiCompletionResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AiCreationCompletion.class);

  @Override
  public Boolean isComplete(String ref, String task) {
    AiCreationTarget.Parsed parsed = AiCreationTarget.parse(ref);
    if (parsed == null || AiCreationTargetIdentity.entity(parsed.domain()) == null) {
      return null;
    }
    if (!AiCompanyScope.ownsId(parsed.companyId())) {
      // Not this company's target to answer for; left to whatever else might own it.
      return null;
    }
    for (AiWriteEffects.Ref effect : AiWriteEffects.snapshot()) {
      if (matches(parsed.domain(), effect)) {
        return Boolean.TRUE;
      }
    }
    return Boolean.FALSE;
  }

  /** Whether one committed write of this turn is the document the creation target named. */
  protected boolean matches(AiDomain domain, AiWriteEffects.Ref effect) {
    if (effect == null || effect.getId() == null) {
      return false;
    }
    try {
      if (domain == AiDomain.JOURNAL_ENTRY) {
        if (!Move.class.getName().equals(effect.getModel())) {
          return false;
        }
        Move move = JPA.em().find(Move.class, effect.getId());
        return move != null && AiCompanyScope.owns(move.getCompany());
      }
      if (!Invoice.class.getName().equals(effect.getModel())) {
        return false;
      }
      Invoice invoice = JPA.em().find(Invoice.class, effect.getId());
      if (invoice == null || !AiCompanyScope.owns(invoice.getCompany())) {
        return false;
      }
      InvoiceSide side =
          domain == AiDomain.SUPPLIER_INVOICE ? InvoiceSide.SUPPLIER : InvoiceSide.CUSTOMER;
      return side.covers(invoice);
    } catch (RuntimeException e) {
      // A record that cannot be read is not proof that one was created. Never throws: a completion
      // resolver that failed the turn would cost the user the answer to work that may well have
      // succeeded.
      LOG.debug("AI Accountant: could not verify a created record: {}", e.getMessage());
      return false;
    }
  }
}
