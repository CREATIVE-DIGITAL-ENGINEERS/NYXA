package com.axelor.apps.aiocr.service.supplier;

import com.axelor.apps.account.db.AccountingSituation;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.address.AddressService;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact projections of a supplier, for AI tool results.
 *
 * <p>Two shapes on purpose, because the cost that matters here is round trips. {@link
 * #row(Partner)} is a candidate line in a list of matches — just enough to choose between them.
 * {@link #detail(Partner)} is everything an accountant would look at on the supplier form:
 * identity, addresses, bank accounts, payment and accounting settings, contacts. A lookup that
 * resolves to exactly one supplier returns the detail immediately, so the old
 * find-then-fetch-bank-accounts pair of calls collapses into one.
 *
 * <p>Never a whole entity: an AI tool result is model-visible text, so only fields with a
 * legitimate reconciliation or maintenance purpose are exposed. Bank accounts and accounting
 * situations are additionally filtered by {@link AiCompanyScope} — Axelor shares partner master
 * data across companies, but another company's bank account or accounting configuration is not this
 * caller's to read.
 */
public final class SupplierView {

  /** Hard ceiling on rows any supplier lookup may return. */
  public static final int MAX_MATCHES = 20;

  /** Ceilings on the collections embedded in a detail projection. */
  private static final int MAX_ADDRESSES = 10;

  private static final int MAX_BANK_ACCOUNTS = 10;

  private static final int MAX_CONTACTS = 10;

  private SupplierView() {}

  /** One candidate line: identity only, enough to pick between matches. */
  public static Map<String, Object> row(Partner p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("supplierId", p.getId());
    m.put("name", p.getSimpleFullName() == null ? p.getName() : p.getSimpleFullName());
    m.put("eik", p.getRegistrationCode());
    m.put("vatNumber", p.getTaxNbr());
    m.put("isSupplier", Boolean.TRUE.equals(p.getIsSupplier()));
    // Surfaced because it is the difference between "create a new supplier" and "tick one box on
    // the
    // partner that already exists": an existing customer with this EIK must never be duplicated.
    m.put("isCustomer", Boolean.TRUE.equals(p.getIsCustomer()));
    m.put("city", cityOf(p.getMainAddress()));
    return m;
  }

  /** Everything worth knowing about one supplier, in the shape the maintenance tool writes. */
  public static Map<String, Object> detail(Partner p) {
    Map<String, Object> m = row(p);
    m.put("reference", p.getPartnerSeq());
    m.put(
        "partnerType",
        p.getPartnerTypeSelect() != null
                && p.getPartnerTypeSelect() == PartnerRepository.PARTNER_TYPE_INDIVIDUAL
            ? "individual"
            : "company");
    m.put("fixedPhone", p.getFixedPhone());
    m.put("mobilePhone", p.getMobilePhone());
    m.put("email", p.getEmailAddress() == null ? null : p.getEmailAddress().getAddress());
    m.put("webSite", p.getWebSite());
    m.put("mainAddress", address(p.getMainAddress()));
    m.put("addresses", addresses(p));
    m.put("bankAccounts", bankAccounts(p));

    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("currency", code(p.getCurrency() == null ? null : p.getCurrency().getCode()));
    settings.put(
        "outPaymentMode", p.getOutPaymentMode() == null ? null : p.getOutPaymentMode().getCode());
    settings.put(
        "paymentCondition",
        p.getPaymentCondition() == null ? null : p.getPaymentCondition().getCode());
    settings.put(
        "fiscalPosition", p.getFiscalPosition() == null ? null : p.getFiscalPosition().getCode());
    settings.put("blockedAccount", Boolean.TRUE.equals(p.getHasBlockedAccount()));
    m.put("settings", settings);

    // Whether this company has an AccountingSituation for the partner decides where its payable
    // account comes from, which is exactly what the invoice readiness check reports on.
    m.put("accountingForThisCompany", accountingSituation(p));
    m.put("contacts", contacts(p));
    // Empty means "usable by every company" in Axelor's shared-partner model, which is what a
    // supplier created through the assistant currently gets.
    m.put("sharedAcrossAllCompanies", p.getCompanySet() == null || p.getCompanySet().isEmpty());
    return m;
  }

  /** The partner's postal addresses, with the flags that decide which one an invoice uses. */
  private static List<Map<String, Object>> addresses(Partner p) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<PartnerAddress> list = p.getPartnerAddressList();
    if (list == null) {
      return out;
    }
    for (PartnerAddress pa : list) {
      if (pa == null || pa.getAddress() == null) {
        continue;
      }
      Map<String, Object> m = address(pa.getAddress());
      m.put("isInvoicing", Boolean.TRUE.equals(pa.getIsInvoicingAddr()));
      m.put("isDelivery", Boolean.TRUE.equals(pa.getIsDeliveryAddr()));
      m.put("isDefault", Boolean.TRUE.equals(pa.getIsDefaultAddr()));
      out.add(m);
      if (out.size() >= MAX_ADDRESSES) {
        break;
      }
    }
    return out;
  }

  /** One address, structured, plus the single rendered line Axelor itself displays. */
  public static Map<String, Object> address(Address a) {
    if (a == null) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("addressId", a.getId());
    m.put("street", a.getStreetName());
    m.put("number", a.getBuildingNumber());
    m.put("zip", a.getZip());
    m.put("city", a.getCity() == null ? null : a.getCity().getName());
    m.put("country", a.getCountry() == null ? null : a.getCountry().getAlpha2Code());
    String rendered = a.getFullName();
    if (rendered == null || rendered.trim().isEmpty()) {
      rendered = Beans.get(AddressService.class).computeAddressStr(a);
    }
    m.put("formatted", rendered);
    return m;
  }

  /**
   * The partner's bank accounts, minus anything stamped with another company.
   *
   * <p>A partner's own accounts carry no company, but the column exists and a company account can
   * be pointed at a partner, so the filter is not theoretical.
   */
  public static List<Map<String, Object>> bankAccounts(Partner p) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<BankDetails> list = p.getBankDetailsList();
    if (list == null) {
      return out;
    }
    for (BankDetails bd : list) {
      if (bd == null) {
        continue;
      }
      if (bd.getCompany() != null && !AiCompanyScope.owns(bd.getCompany())) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("bankDetailsId", bd.getId());
      m.put("iban", bd.getIban());
      m.put("bic", bd.getIbanBic());
      m.put("label", bd.getLabel());
      m.put("bank", bd.getBank() == null ? null : bd.getBank().getFullName());
      m.put("active", Boolean.TRUE.equals(bd.getActive()));
      out.add(m);
      if (out.size() >= MAX_BANK_ACCOUNTS) {
        break;
      }
    }
    return out;
  }

  /** The scope company's own accounting situation for this partner, or null when there is none. */
  private static Map<String, Object> accountingSituation(Partner p) {
    List<AccountingSituation> list = p.getAccountingSituationList();
    if (list == null) {
      return null;
    }
    for (AccountingSituation s : list) {
      if (s == null || !AiCompanyScope.owns(s.getCompany())) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("accountingSituationId", s.getId());
      m.put(
          "supplierAccount",
          s.getSupplierAccount() == null ? null : s.getSupplierAccount().getCode());
      m.put("vatSystem", s.getVatSystemSelect());
      return m;
    }
    return null;
  }

  /** Contact people attached to the supplier. */
  private static List<Map<String, Object>> contacts(Partner p) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (p.getContactPartnerSet() == null) {
      return out;
    }
    for (Partner c : p.getContactPartnerSet()) {
      if (c == null) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("contactId", c.getId());
      m.put("name", c.getSimpleFullName() == null ? c.getName() : c.getSimpleFullName());
      m.put("email", c.getEmailAddress() == null ? null : c.getEmailAddress().getAddress());
      m.put("phone", c.getFixedPhone() == null ? c.getMobilePhone() : c.getFixedPhone());
      m.put("function", c.getJobTitleFunction() == null ? null : c.getJobTitleFunction().getName());
      out.add(m);
      if (out.size() >= MAX_CONTACTS) {
        break;
      }
    }
    return out;
  }

  private static String cityOf(Address a) {
    if (a == null || a.getCity() == null) {
      return null;
    }
    return a.getCity().getName();
  }

  private static String code(String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }
}
