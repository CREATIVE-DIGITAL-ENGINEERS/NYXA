package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiWriteTargetState;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.List;
import javax.persistence.Query;

/**
 * Fingerprints a supplier, so a {@code manage_supplier} update whose answer was lost can be proven
 * not to have landed.
 *
 * <h2>Why this had to exist</h2>
 *
 * {@code manage_supplier} was the one existing-record write with no target and no state provider at
 * all. Every ambiguous failure on it was therefore unprovable, so a lost answer left the accountant
 * with "something may have changed on a supplier, go and look" — about master data that decides
 * where money is paid. It is now reconcilable like every other record write.
 *
 * <h2>What is included, and why</h2>
 *
 * <ul>
 *   <li><b>the partner</b> — version and last update. Hibernate's counter moves on any save from
 *       any source, so this alone answers the question the reconciliation asks;
 *   <li><b>the counts of what an update adds</b> — addresses, bank accounts and contacts. The tool
 *       is additive: it attaches an address or an IBAN, and those writes touch the child rows.
 *       Axelor does bump the parent's version when its collections change, but relying on that
 *       alone would make the proof depend on a cascade setting rather than on the thing being
 *       counted, and an under-inclusive fingerprint turns "prove it did not happen" into a wrong
 *       "prove it did not happen".
 * </ul>
 *
 * <h2>Company scoping</h2>
 *
 * A partner is shared: an empty {@code companySet} means "usable by every company", and a populated
 * one lists the companies it belongs to. {@link AiCompanyScope#partnerInScope} is the same rule
 * every partner read here follows, and a partner outside it fingerprints as null — it cannot settle
 * a question about a write made in these books.
 */
@Singleton
public class PartnerWriteTargetState implements AiWriteTargetState {

  private static final String PARTNER_PREFIX = "Partner#";

  @Override
  public String fingerprint(String targetKey) {
    if (targetKey == null || !targetKey.startsWith(PARTNER_PREFIX)) {
      return null;
    }
    long partnerId;
    try {
      partnerId = Long.parseLong(targetKey.substring(PARTNER_PREFIX.length()).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    Partner partner = Beans.get(PartnerRepository.class).find(partnerId);
    if (partner == null || !AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      return null;
    }
    Query query =
        JPA.em()
            .createQuery(
                "select p.version, p.updatedOn, p.isSupplier,"
                    + " (select count(a) from PartnerAddress a where a.partner = p),"
                    + " (select count(b) from BankDetails b where b.partner = p),"
                    + " (select count(c) from Partner c where c.mainPartner = p)"
                    + " from Partner p where p.id = :id");
    query.setParameter("id", partnerId);
    List<?> rows = query.getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.get(0);
    StringBuilder sb = new StringBuilder(PARTNER_PREFIX).append(partnerId);
    for (Object value : row) {
      sb.append('|').append(value == null ? "-" : value);
    }
    return sb.toString();
  }
}
