package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * {@link PartnerBankAccountCompletion}, active and running with no Guice injector and no database —
 * the same seam-and-stub style as {@code PartnerCreateCompletionTest}.
 *
 * <h2>What this replaces</h2>
 *
 * {@code PartnerRecordCompletion} answered TRUE for any {@code Partner#id} that merely existed in
 * scope, which meant a FAILED bank-account update — the partner obviously still exists — could be
 * reported complete. These tests prove the replacement resolver only ever answers TRUE when the
 * exact requested IBAN is actually present on the company-scoped partner, never from existence
 * alone.
 */
class PartnerBankAccountCompletionTest {

  private final PartnerBankAccountCompletion completion = new PartnerBankAccountCompletion();

  @Test
  void aRefThisResolverDoesNotOwnIsNull() {
    assertNull(completion.isComplete("Partner#84", "REPAIR_SUPPLIER_INVOICE"));
    assertNull(completion.isComplete("PartnerCreate:2:831257890", "REPAIR_SUPPLIER_INVOICE"));
    assertNull(completion.isComplete(null, "REPAIR_SUPPLIER_INVOICE"));
  }

  @Test
  void aMalformedReferenceIsNull() {
    assertNull(completion.isComplete("PartnerBankAccount:2:84", "REPAIR_SUPPLIER_INVOICE"));
    assertNull(
        completion.isComplete("PartnerBankAccount:notanumber:84:BG75", "REPAIR_SUPPLIER_INVOICE"));
    assertNull(
        completion.isComplete("PartnerBankAccount:2:notanumber:BG75", "REPAIR_SUPPLIER_INVOICE"));
  }

  private static BankDetails bankDetails(String iban) {
    BankDetails details = new BankDetails();
    details.setIban(iban);
    return details;
  }

  private static PartnerRepository fakeRepository(long id, Partner partner) {
    return new PartnerRepository() {
      @Override
      public Partner find(Long partnerId) {
        return id == partnerId ? partner : null;
      }
    };
  }

  private static PartnerBankAccountCompletion completionWith(PartnerRepository repository) {
    return new PartnerBankAccountCompletion() {
      @Override
      protected PartnerRepository partnerRepository() {
        return repository;
      }
    };
  }

  /** Existence alone is not enough: a partner with no matching IBAN is not complete. */
  @Test
  void aPartnerWithoutTheRequestedIbanIsNotComplete() {
    Partner partner = new Partner();
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () ->
                resolver.isComplete(
                    "PartnerBankAccount:2:84:BG75UBBS80021029841118", "REPAIR_SUPPLIER_INVOICE"));

    assertFalse(Boolean.TRUE.equals(result));
  }

  /** This is the exact regression: the partner exists but the requested write never landed. */
  @Test
  void anExistingPartnerAloneDoesNotCompleteAFailedIbanUpdate() {
    Partner partner = new Partner();
    partner.setBankDetailsList(Collections.singletonList(bankDetails("BG18RZBB91550123456789")));
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () ->
                resolver.isComplete(
                    "PartnerBankAccount:2:84:BG75UBBS80021029841118", "REPAIR_SUPPLIER_INVOICE"));

    assertFalse(
        Boolean.TRUE.equals(result), "an unrelated existing account must not complete this IBAN");
  }

  @Test
  void theExactRequestedIbanCompletesTheEffect() {
    Partner partner = new Partner();
    partner.setBankDetailsList(
        Arrays.asList(
            bankDetails("BG18RZBB91550123456789"), bankDetails("BG75 UBBS 8002 1029 8411 18")));
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () ->
                resolver.isComplete(
                    "PartnerBankAccount:2:84:BG75UBBS80021029841118", "REPAIR_SUPPLIER_INVOICE"));

    assertTrue(result, "the exact IBAN, once normalised, must complete the effect");
  }

  /** The task carries no meaning for this resolver either — only the exact IBAN does. */
  @Test
  void completionDoesNotDependOnTheTask() {
    Partner partner = new Partner();
    partner.setBankDetailsList(Collections.singletonList(bankDetails("BG75UBBS80021029841118")));
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () -> resolver.isComplete("PartnerBankAccount:2:84:BG75UBBS80021029841118", null));

    assertTrue(result);
  }

  @Test
  void aDifferentIbanDoesNotCompleteIt() {
    Partner partner = new Partner();
    partner.setBankDetailsList(Collections.singletonList(bankDetails("BG75UBBS80021029841118")));
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () ->
                resolver.isComplete(
                    "PartnerBankAccount:2:84:BG18RZBB91550123456789", "REPAIR_SUPPLIER_INVOICE"));

    assertFalse(Boolean.TRUE.equals(result));
  }

  /**
   * The same ЕИК-style scoping rule as {@code PartnerCreateCompletion}: a target written for one
   * company must not be answered from another company's active scope, even with the same partner id
   * and IBAN text.
   */
  @Test
  void theSameIbanUnderAnotherCompanyDoesNotCompleteIt() {
    Partner partner = new Partner();
    partner.setBankDetailsList(Collections.singletonList(bankDetails("BG75UBBS80021029841118")));
    PartnerBankAccountCompletion resolver = completionWith(fakeRepository(84L, partner));

    // The active scope is company 3; the target names company 2.
    Boolean result =
        AiExecutionContext.callAsSystem(
            3L,
            "test",
            () ->
                resolver.isComplete(
                    "PartnerBankAccount:2:84:BG75UBBS80021029841118", "REPAIR_SUPPLIER_INVOICE"));

    assertNull(result, "a target scoped to another company is not this resolver's to answer");
  }
}
