package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.context.AiTargetRelationship;
import com.google.inject.Singleton;

/**
 * Says whether a {@code Partner#id} reference names the current partner of an {@code Invoice#id}
 * reference — the one relationship {@code manage_supplier(update)} needs so a supplier fix from
 * inside an authorised invoice job can reach the supplier itself, without the job's frozen target
 * set ever having to name the partner directly.
 *
 * <h2>Fresh, every time</h2>
 *
 * Reads {@link InvoiceRecordResolver#find}, which is the same request-scoped, company-scoped lookup
 * every other answer about this invoice uses. Never cached across a call, so a partner reassigned
 * earlier in the same turn is seen correctly by the next.
 */
@Singleton
public class InvoicePartnerRelationship implements AiTargetRelationship {

  private static final String PARTNER_KIND = "Partner";

  @Override
  public Boolean isRelated(String canonicalA, String canonicalB) {
    String invoiceRef = invoiceRef(canonicalA, canonicalB);
    String partnerRef = partnerRef(canonicalA, canonicalB);
    if (invoiceRef == null || partnerRef == null) {
      return null;
    }
    Long invoiceId = InvoiceRecordResolver.invoiceId(invoiceRef);
    Long partnerId = partnerId(partnerRef);
    if (invoiceId == null || partnerId == null) {
      return null;
    }
    Invoice invoice = InvoiceRecordResolver.find(invoiceId);
    if (invoice == null || invoice.getPartner() == null || invoice.getPartner().getId() == null) {
      return false;
    }
    return partnerId.equals(invoice.getPartner().getId());
  }

  private static String invoiceRef(String a, String b) {
    if (InvoiceRecordResolver.invoiceId(a) != null) {
      return a;
    }
    return InvoiceRecordResolver.invoiceId(b) != null ? b : null;
  }

  private static String partnerRef(String a, String b) {
    if (partnerId(a) != null) {
      return a;
    }
    return partnerId(b) != null ? b : null;
  }

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
