package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountingSituation;
import com.axelor.apps.account.db.FiscalPosition;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.util.HashSet;

/**
 * ПАРТНЬОРИТЕ НА ДЕМО ДРУЖЕСТВОТО — {@code partners.csv}. Всички са на това дружество (партньори не
 * се споделят); номерът идва от поредицата му.
 *
 * <p>Колони: {@code code;type;name;firstName;customer;supplier;country;eik;vat;egn;fiscalPosition;
 * currency;iban;bic;bank;city;zip;street;paymentDays;customerAccount;supplierAccount}. {@code type}
 * е {@code C} (юридическо лице) или {@code I} (физическо лице — ЕГН, идентификатор „0“ за справката
 * по чл. 73). {@code customerAccount}/{@code supplierAccount} подменят 411/401 в счетоводната
 * ситуация — дружеството от групата е на 405/415.
 */
final class BgDemoPartnerStep {

  private BgDemoPartnerStep() {}

  static void run(BgDemoContext ctx) throws AxelorException {
    int n = 0;
    for (BgDemoCsv.Row r : ctx.rows("partners")) {
      JPA.runInTransaction(() -> partner(ctx, r));
      n++;
    }
    ctx.log("Партньори: " + n);
  }

  private static void partner(BgDemoContext ctx, BgDemoCsv.Row r) {
    Company company = ctx.company();
    Partner p = new Partner();
    boolean individual = "I".equals(r.get("type"));
    p.setPartnerTypeSelect(
        individual
            ? PartnerRepository.PARTNER_TYPE_INDIVIDUAL
            : PartnerRepository.PARTNER_TYPE_COMPANY);
    p.setName(r.get("name"));
    if (individual) {
      p.setFirstName(r.get("firstName"));
      p.setRegistrationCode(r.get("egn"));
      p.setBgIdentifierType("0");
    } else {
      p.setRegistrationCode(r.get("eik"));
    }
    p.setTaxNbr(r.opt("vat").isEmpty() ? null : r.get("vat"));
    p.setIsCustomer(r.bool("customer"));
    p.setIsSupplier(r.bool("supplier"));
    p.setIsContact(false);
    p.setCurrency(BgDemoCompanyStep.currency(r.get("currency")));
    p.setFiscalPosition(
        JPA.all(FiscalPosition.class).filter("self.code = ?1", r.get("fiscalPosition")).fetchOne());
    if (p.getFiscalPosition() == null) {
      throw new IllegalArgumentException(
          r.where() + ": няма фискална позиция " + r.get("fiscalPosition"));
    }
    if (r.has("paymentDays")) {
      p.setPaymentDelay(r.decimal("paymentDays"));
    }
    p.setCompanySet(new HashSet<>());
    p.getCompanySet().add(company);

    Address a =
        BgDemoCompanyStep.address(
            r.get("street"), r.get("zip"), r.get("city"), r.get("country"), r.opt("region"));
    Beans.get(PartnerService.class).addPartnerAddress(p, a, true, true, true);
    p.setMainAddress(a);

    if (r.has("iban")) {
      BankDetails bd = new BankDetails();
      bd.setBank(BgDemoCompanyStep.bank(r.get("bic"), r.get("bank"), r.get("country")));
      bd.setIban(r.get("iban"));
      bd.setCurrency(p.getCurrency());
      bd.setActive(true);
      bd.setIsDefault(true);
      bd.setOwnerName(individual ? r.get("firstName") + " " + r.get("name") : r.get("name"));
      bd.setPartner(p);
      p.addBankDetailsListItem(bd);
    }
    p = Beans.get(PartnerRepository.class).save(p);
    ctx.put("partner", r.get("code"), p);

    if (r.has("customerAccount") || r.has("supplierAccount")) {
      AccountingSituation s =
          Beans.get(AccountingSituationService.class).getAccountingSituation(p, company);
      if (s == null) {
        throw new IllegalStateException(r.where() + ": партньорът няма счетоводна ситуация");
      }
      if (r.has("customerAccount")) {
        s.setCustomerAccount(partnerAccount(company, r.get("customerAccount")));
      }
      if (r.has("supplierAccount")) {
        s.setSupplierAccount(partnerAccount(company, r.get("supplierAccount")));
      }
      JPA.save(s);
    }
  }

  /**
   * Сметка за разчети с партньора (405/415 — свързани лица): в BG_COA тя е без „лентиране“ и без
   * „салдо по партньор“, а Axelor ги иска за сметката на фактурата. Отметките се слагат на сметката
   * на това дружество — както в картона на сметката.
   */
  static Account partnerAccount(Company company, String code) {
    Account a = account(company, code);
    if (!a.getReconcileOk() || !a.getUseForPartnerBalance()) {
      a.setReconcileOk(true);
      a.setUseForPartnerBalance(true);
      JPA.save(a);
    }
    return a;
  }

  static Account account(Company company, String code) {
    Account a =
        JPA.all(Account.class)
            .filter("self.company = ?1 AND self.code = ?2", company, code)
            .fetchOne();
    if (a == null) {
      throw new IllegalArgumentException("Сметка " + code + " липсва в сметкоплана на дружеството");
    }
    return a;
  }

  static BigDecimal zeroIfEmpty(BgDemoCsv.Row r, String col) {
    return r.has(col) ? r.decimal(col) : BigDecimal.ZERO;
  }
}
