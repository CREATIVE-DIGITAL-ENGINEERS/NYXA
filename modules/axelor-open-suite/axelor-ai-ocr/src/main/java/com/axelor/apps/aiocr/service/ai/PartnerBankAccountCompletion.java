package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Whether a supplier now has the exact bank account a job's own words asked for.
 *
 * <h2>Why a typed reference, and not "the partner exists"</h2>
 *
 * {@code manage_supplier} adding an IBAN and {@code manage_supplier} failing to add one leave the
 * same fact behind — the partner still exists — so existence can never distinguish them. That was
 * the defect {@code PartnerRecordCompletion} had: it answered TRUE for any {@code Partner#id} in
 * scope, which meant a FAILED bank-account update, once {@code AiWorkflowController} added it to
 * the target list as a blocked record, could be reported complete by the very next completion check
 * in the same turn — clearing a refusal for a change that never happened.
 *
 * <p>{@code PartnerBankAccount:<companyId>:<partnerId>:<normalizedIban>} names the one thing that
 * actually distinguishes success from failure: the exact IBAN. It is TRUE only when that partner,
 * as read fresh from the database, now carries a bank account with that exact normalised IBAN — not
 * a different one, not none at all. A failed write never gets to answer TRUE, because the IBAN it
 * was trying to add is, by definition, not on the record.
 *
 * <h2>How this reference reaches the workflow</h2>
 *
 * {@code ManageSupplierTool.targetKey} still answers a plain {@code Partner#id} for an update —
 * that identity is authorisation's own, used by the repeat guard and by a batch abort, and is
 * unchanged. {@code ManageSupplierTool.completionTarget} is the new, separate answer, asked only by
 * {@code AiToolBatchRunner} when it builds the turn's ledger entry: for an update naming a bank
 * account, it names this exact typed reference instead of the plain one, computed from the
 * request's own arguments and the active company scope — never from a result or a message. A FAILED
 * call and a later SUCCESSFUL retry for the very same requested IBAN land under the identical
 * reference; a retry for a different IBAN does not, so it cannot clear the first one's blocker. A
 * bare {@code Partner#id} written along the way for anything else (an address, a contact, a payment
 * term) is handled instead by {@code AiWorkflowController.isAffectedRecordOnly}, which keeps it out
 * of required-work tracking entirely rather than asking any resolver to vouch for it.
 */
@Singleton
public class PartnerBankAccountCompletion implements AiCompletionResolver {

  private static final String PREFIX = "PartnerBankAccount:";

  @Override
  public Boolean isComplete(String ref, String task) {
    Parsed parsed = parse(ref);
    if (parsed == null) {
      return null;
    }
    if (!AiCompanyScope.ownsId(parsed.companyId)) {
      // Not this company's target to answer for; left to whatever else might recognise it (nothing
      // does today), which is the safe default.
      return null;
    }
    Partner partner = partnerRepository().find(parsed.partnerId);
    if (partner == null) {
      return null;
    }
    if (partner.getBankDetailsList() == null) {
      return Boolean.FALSE;
    }
    for (BankDetails details : partner.getBankDetailsList()) {
      if (details != null
          && parsed.iban.equals(BgOcrBankDetails.normalizeIban(details.getIban()))) {
        return Boolean.TRUE;
      }
    }
    return Boolean.FALSE;
  }

  /**
   * The seam a test overrides to substitute a fake lookup without a running Guice injector — the
   * same idiom {@link PartnerCreateCompletion#supplierMaintenanceService()} uses.
   */
  protected PartnerRepository partnerRepository() {
    return Beans.get(PartnerRepository.class);
  }

  /**
   * The parsed halves of a well-formed {@code PartnerBankAccount:<companyId>:<partnerId>:<iban>}.
   */
  private static final class Parsed {
    final Long companyId;
    final Long partnerId;
    final String iban;

    Parsed(Long companyId, Long partnerId, String iban) {
      this.companyId = companyId;
      this.partnerId = partnerId;
      this.iban = iban;
    }
  }

  /** Null for anything that is not this exact, well-formed reference shape. */
  private static Parsed parse(String ref) {
    if (ref == null || !ref.startsWith(PREFIX)) {
      return null;
    }
    String[] parts = ref.substring(PREFIX.length()).split(":", 3);
    if (parts.length != 3) {
      return null;
    }
    Long companyId = parseId(parts[0]);
    Long partnerId = parseId(parts[1]);
    String iban = BgOcrBankDetails.normalizeIban(parts[2]);
    if (companyId == null || partnerId == null || iban == null) {
      return null;
    }
    return new Parsed(companyId, partnerId, iban);
  }

  private static Long parseId(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException | NullPointerException e) {
      return null;
    }
  }
}
