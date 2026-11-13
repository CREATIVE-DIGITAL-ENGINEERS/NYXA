package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a different partner on a DRAFT invoice, deliberately and by name.
 *
 * <h2>Why this is a service and not a header field</h2>
 *
 * Changing who an invoice is for is not like changing its note. The invoicing address, the bank
 * account, the payment mode and the payment condition all followed from the old partner, and
 * leaving them behind would produce an invoice addressed to one company and paid to another's
 * account. The form runs an {@code onChange} chain for exactly this reason; a service has to do the
 * same work explicitly, and burying it among the header fields would hide that it happens at all.
 *
 * <h2>Explicit, never inferred</h2>
 *
 * The partner is resolved from an id or a registration code the caller actually sent. Nothing here
 * searches by name, picks the closest match, or falls back to "the only customer with a similar
 * name" — an invoice silently addressed to the wrong company is a wrong bill sent to a real person.
 * A code that resolves to more than one partner is a refusal, not a choice to make.
 *
 * <h2>What follows the partner, and what does not</h2>
 *
 * The address, the default bank account and the payment defaults are re-resolved, because they are
 * derived from whoever the partner is. The currency follows only while the invoice has no lines,
 * for the same reason the form locks it then: once amounts exist they are expressed in it. The
 * partner account is deliberately left alone — validation resolves it from the accounting
 * situation, and guessing one here would pre-empt that with a value nobody chose.
 */
@Singleton
public class InvoicePartnerReassignService {

  /** What the reassignment changed. */
  public static final class Result {
    private final List<AiInvoiceFieldChange> changes = new ArrayList<>();

    public List<AiInvoiceFieldChange> getChanges() {
      return changes;
    }

    public boolean isChanged() {
      return !changes.isEmpty();
    }
  }

  /**
   * Points the invoice at another partner and re-resolves what depended on the old one.
   *
   * <p>Does not save; the caller owns the transaction.
   *
   * @param partner the already-resolved partner — resolution and its role check belong to the
   *     caller, which knows whether it wants a customer or a supplier
   */
  public Result reassign(Invoice invoice, InvoiceSide side, Partner partner) {
    if (partner == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS, "No partner was given to move the invoice to.");
    }
    Result result = new Result();
    Partner previous = invoice.getPartner();
    if (previous != null && previous.getId() != null && previous.getId().equals(partner.getId())) {
      return result;
    }
    result.changes.add(
        new AiInvoiceFieldChange(
            "partner", previous == null ? null : previous.getFullName(), partner.getFullName()));
    invoice.setPartner(partner);

    // The address the new partner is invoiced at, through Axelor's own choice of it.
    Address address = null;
    try {
      address = Beans.get(PartnerService.class).getInvoicingAddress(partner);
    } catch (RuntimeException e) {
      // No invoicing address configured. Left empty rather than kept from the previous partner,
      // which is the one outcome that would be actively wrong.
    }
    if (address != invoice.getAddress()) {
      result.changes.add(
          new AiInvoiceFieldChange(
              "address",
              invoice.getAddress() == null ? null : invoice.getAddress().getFullName(),
              address == null ? null : address.getFullName()));
    }
    invoice.setAddress(address);
    invoice.setAddressStr(
        address == null
            ? null
            : Beans.get(com.axelor.apps.base.service.address.AddressService.class)
                .computeAddressStr(address));

    // The new partner's own default account, the same way InvoiceGenerator picks one when it builds
    // an invoice from scratch. Never the previous partner's: that is somebody else's bank account.
    BankDetails bankDetails = defaultBankDetails(partner);
    if (bankDetails != invoice.getBankDetails()) {
      result.changes.add(
          new AiInvoiceFieldChange(
              "bankDetails",
              invoice.getBankDetails() == null ? null : invoice.getBankDetails().getIban(),
              bankDetails == null ? null : bankDetails.getIban()));
    }
    invoice.setBankDetails(bankDetails);

    // Payment defaults come from the partner first and the company's configuration second — exactly
    // InvoiceToolService's own ladder, called rather than reimplemented.
    try {
      invoice.setPaymentMode(InvoiceToolService.getPaymentMode(invoice));
      invoice.setPaymentCondition(InvoiceToolService.getPaymentCondition(invoice));
      result.changes.add(
          new AiInvoiceFieldChange(
              "paymentMode",
              null,
              invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getCode()));
      result.changes.add(
          new AiInvoiceFieldChange(
              "paymentCondition",
              null,
              invoice.getPaymentCondition() == null
                  ? null
                  : invoice.getPaymentCondition().getCode()));
    } catch (Exception e) {
      // No default configured anywhere. The fields keep whatever they had; validation will say so
      // rather than this refusing a reassignment the user asked for.
    }

    // Only while there is nothing expressed in the old currency yet.
    if ((invoice.getInvoiceLineList() == null || invoice.getInvoiceLineList().isEmpty())
        && partner.getCurrency() != null) {
      if (invoice.getCurrency() == null
          || !partner.getCurrency().getId().equals(invoice.getCurrency().getId())) {
        result.changes.add(
            new AiInvoiceFieldChange(
                "currency",
                invoice.getCurrency() == null ? null : invoice.getCurrency().getCode(),
                partner.getCurrency().getCode()));
        invoice.setCurrency(partner.getCurrency());
      }
    }
    return result;
  }

  /** The partner's default active bank account, or null when it has none. */
  protected BankDetails defaultBankDetails(Partner partner) {
    if (partner.getBankDetailsList() == null) {
      return null;
    }
    return partner.getBankDetailsList().stream()
        .filter(b -> Boolean.TRUE.equals(b.getActive()) && Boolean.TRUE.equals(b.getIsDefault()))
        .findFirst()
        .orElse(null);
  }
}
