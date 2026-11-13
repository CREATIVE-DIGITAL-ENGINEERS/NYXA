package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Says which record a {@code Partner#id} reference names, and whether this user could change it.
 *
 * <h2>Why this had to exist</h2>
 *
 * {@link PartnerWriteTargetState} was written to make a lost {@code manage_supplier} update
 * provable, and it cannot do that on its own. {@code AiWriteReconciliation} canonicalises the
 * target <em>before</em> it asks for a fingerprint, and no resolver anywhere claimed {@code
 * Partner#} — so the canonical form was always null, the fingerprint provider was never reached,
 * and every ambiguous failure on a supplier update ended as {@code uncertain_write} instead of a
 * provable retry. About master data that decides where money is paid.
 *
 * <p>The same gap also refused any authorised-target set holding a {@code Partner#} reference,
 * because {@code AiTargetRegistry.sameTarget} could not resolve either side of the comparison.
 *
 * <h2>Ownership</h2>
 *
 * One resolver owns one kind of reference. This one answers {@code Partner#} and nothing else;
 * uploads and invoices are {@code OcrTargetIdentity}'s, journal entries are {@code
 * MoveTargetIdentity}'s. Anything else returns null so the registry can ask the module that does
 * own it, and refuse when none does.
 *
 * <h2>Company scoping is part of the answer</h2>
 *
 * A partner is shared: an empty {@code companySet} means "usable by every company", a populated one
 * lists the companies it belongs to. {@link AiCompanyScope#partnerInScope} is the rule every
 * partner read here follows, and a partner outside it resolves to null — exactly as though it did
 * not exist, because a reference the caller cannot read must never satisfy an authorisation.
 */
@Singleton
public class PartnerTargetIdentity implements AiTargetIdentity {

  private static final String PARTNER_KIND = "Partner";

  @Override
  public String canonical(String ref) {
    Long id = partnerId(ref);
    if (id == null) {
      return null;
    }
    Partner partner = Beans.get(PartnerRepository.class).find(id);
    if (partner == null || !AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      return null;
    }
    return PARTNER_KIND + "#" + id;
  }

  /**
   * Whether this user could change the partner by hand.
   *
   * <p>Asked of Axelor's own permission layer, so a partner the accountant has no write access to
   * cannot enter an authorised set.
   */
  @Override
  public Boolean writable(String ref) {
    String canonical = canonical(ref);
    if (canonical == null) {
      // Either not ours, or not identifiable in this company. Both are the registry's to turn into
      // a refusal; saying "false" here would claim ownership of a reference we may not own.
      return null;
    }
    Long id = partnerId(canonical);
    if (id == null) {
      return false;
    }
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_WRITE, Partner.class, id);
  }

  /** The id of a {@code Partner#id} reference, or null for anything else. */
  private static Long partnerId(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0 || !PARTNER_KIND.equals(ref.substring(0, hash))) {
      return null;
    }
    try {
      return Long.parseLong(ref.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
