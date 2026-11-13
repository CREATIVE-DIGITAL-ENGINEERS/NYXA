package com.axelor.apps.aiocr.service.supplier;

import com.axelor.apps.account.db.FiscalPosition;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.AccountConfigRepository;
import com.axelor.apps.account.db.repo.FiscalPositionRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.PaymentConditionRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.BgOcrAddressParser;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.BankRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.CurrencyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.address.AddressService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.partner.registrationnumber.RegistrationNumberValidator;
import com.axelor.apps.base.service.partner.registrationnumber.factory.PartnerRegistrationValidatorFactoryService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.axelor.message.db.EmailAddress;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and edits suppliers, through Axelor's own partner logic.
 *
 * <p>Reusable by any caller — an AI tool, a controller, a future import. Nothing here is coupled to
 * OCR or to AI JSON: the input is a typed {@link SupplierSpec} of intended values, and every
 * derived or validated value is produced by the service Axelor already uses for it.
 *
 * <h2>What is reused rather than reimplemented</h2>
 *
 * <ul>
 *   <li><b>{@code PartnerRepository.save}</b> — the whole bound chain (HR → Account → Base →
 *       JpaRepository), which is where the real work happens: {@code PartnerService.onSave}
 *       (partnerSeq sequence, e-mail uniqueness, address defaulting, {@code fullName} / {@code
 *       simpleFullName}), the single-address default flags, and {@code
 *       AccountingSituationInitService} for accounting situations and partner accounts. This is
 *       deliberately the opposite of the {@code JPA.save} decision taken for invoices: on an
 *       Invoice the chain recomputes amounts we must not touch, whereas on a Partner the chain
 *       <em>is</em> the business logic.
 *   <li><b>Registration-code validity</b> — {@code PartnerRegistrationValidatorFactoryService} plus
 *       the country's {@code RegistrationNumberTemplate}, exactly as {@code
 *       PartnerController.checkRegistrationCode} does.
 *   <li><b>Duplicate ЕИК, scoped to the active company</b> — {@link
 *       #assertNoDuplicateRegistrationCode}, never {@code
 *       PartnerService.checkIfRegistrationCodeExists}: that check is installation-wide, which is
 *       right for the ordinary Partner form (one company's own contact database) and wrong here.
 *       Companies are isolated — confirmed by the accountant, suppliers are never shared between
 *       them — so the same ЕИК legitimately exists as a separate Partner per company, and this
 *       refuses only a duplicate the ACTIVE company can already see, using the exact visibility
 *       {@code find_supplier} and every other AI-facing lookup use ({@link
 *       SupplierEik#matchForCompany}).
 *   <li><b>IBAN</b> — {@code BankDetailsService.validateIban} for the checksum and {@code
 *       detailsIban} to split out bank/sort/account codes, as the partner form's {@code
 *       checkIbanValidity} and bank-details editor do.
 *   <li><b>Addresses</b> — {@code BgOcrAddressParser} to structure a raw address line, {@code
 *       BgOcrIntakeService.resolveExistingBgCity} / {@code resolveCountryByIso} to resolve city and
 *       country from real reference data (never creating either), {@code
 *       AddressService.computeFullName} to render, and {@code PartnerService.addPartnerAddress} to
 *       attach with the right invoicing/delivery/default flags.
 *   <li><b>Contacts</b> — {@code PartnerService.createContact} and {@code addContactToPartner}.
 *   <li><b>New-supplier defaults</b> — the same values the partner form's {@code onNew} action
 *       group applies, read from the active company: currency, invoice sending format, owning user
 *       and team, partner localization, and {@code AccountConfig}'s out payment mode and default
 *       payment condition.
 * </ul>
 *
 * <h2>Boundaries</h2>
 *
 * <ul>
 *   <li><b>No field patching.</b> {@link SupplierSpec} is a closed set of typed fields. There is no
 *       property name, no path, no map of arbitrary values.
 *   <li><b>Company scope.</b> An existing supplier is only visible when it is in scope by Axelor's
 *       shared-partner rule ({@link AiCompanyScope#partnerInScope}), and an out-of-scope id answers
 *       exactly as an unknown one. It is only <em>editable</em> when it belongs to the active
 *       company alone — see {@link #isSharedBeyondCompany}: a partner whose {@code companySet} is
 *       empty (Axelor: usable by every company) or also names another company is shared master
 *       data, and changing it from one company's books would change another company's records. Such
 *       an update is refused; it is never auto-linked, cloned or migrated. Everything resolved by
 *       code — payment mode, payment condition, fiscal position — is resolved against the scope
 *       company, so a code cannot reach another company's configuration.
 *   <li><b>A created supplier is associated with the active company; an existing one is left
 *       alone.</b> {@code create} adds {@link AiCompanyScope#requireCompany()} to {@code
 *       companySet} before saving, which is exactly what the partner form's {@code onNew} does
 *       ({@code action-partner-attrs-set-default} adds {@code
 *       PartnerController.getActiveCompany()}). That is not cosmetic: {@code
 *       AccountingSituationInitService.createAccountingSituation} iterates {@code companySet}, so
 *       it is the association that causes this company's {@code AccountingSituation} and its
 *       supplier account to be created during the save. {@code update} never touches {@code
 *       companySet}, and refuses a shared partner outright rather than re-homing it: re-homing
 *       master data somebody else set up is not a decision that follows from a document.
 *   <li><b>Reference data is selected, never invented.</b> Cities, countries and <b>banks</b> are
 *       looked up; none of them is created here. A BIC or bank name that resolves to nothing is
 *       reported and the account is registered without a bank link — see {@link
 *       #resolveExistingBank}.
 *   <li><b>Nothing is deleted.</b> No address, bank account or contact is removed, and no partner
 *       is archived. Addresses, bank accounts and contacts are added; scalar fields that are
 *       supplied (name, ЕИК, VAT number, phones, e-mail, web site, currency, payment
 *       mode/condition, fiscal position) replace the current value. Nothing is cleared.
 * </ul>
 */
@Singleton
public class SupplierMaintenanceService {

  private static final Logger LOG = LoggerFactory.getLogger(SupplierMaintenanceService.class);

  /** Same ceiling the partner form's own fields carry. */
  private static final int MAX_TEXT = 255;

  private final PartnerRepository partnerRepo;

  @Inject
  public SupplierMaintenanceService(PartnerRepository partnerRepo) {
    this.partnerRepo = partnerRepo;
  }

  // ---------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------

  /** A structured postal address, as a caller means it. */
  public static final class AddressSpec {
    private String street;
    private String number;
    private String zip;
    private String city;
    private String countryIso;
    private String raw;

    public AddressSpec setStreet(String street) {
      this.street = street;
      return this;
    }

    public AddressSpec setNumber(String number) {
      this.number = number;
      return this;
    }

    public AddressSpec setZip(String zip) {
      this.zip = zip;
      return this;
    }

    public AddressSpec setCity(String city) {
      this.city = city;
      return this;
    }

    public AddressSpec setCountryIso(String countryIso) {
      this.countryIso = countryIso;
      return this;
    }

    /** A whole address line, parsed when the structured parts are not given. */
    public AddressSpec setRaw(String raw) {
      this.raw = raw;
      return this;
    }

    boolean isEmpty() {
      return blank(street) && blank(zip) && blank(city) && blank(raw);
    }
  }

  /** One bank account to register on the supplier. */
  public static final class BankAccountSpec {
    private String iban;
    private String bic;
    private String bankName;
    private String label;

    public BankAccountSpec setIban(String iban) {
      this.iban = iban;
      return this;
    }

    /** SWIFT/BIC exactly as printed. Resolved against existing banks only; never invented. */
    public BankAccountSpec setBic(String bic) {
      this.bic = bic;
      return this;
    }

    /**
     * The bank's name as printed, for the common case where the document shows a name and no BIC.
     *
     * <p>It exists so a caller is never pushed into supplying a BIC it does not have: an AI asked
     * for a bank identifier will produce a plausible one, and a plausible BIC is indistinguishable
     * from a correct one. A name is matched against the banks Nyxa already knows.
     */
    public BankAccountSpec setBankName(String bankName) {
      this.bankName = bankName;
      return this;
    }

    public BankAccountSpec setLabel(String label) {
      this.label = label;
      return this;
    }
  }

  /** One contact person to attach to the supplier. */
  public static final class ContactSpec {
    private String name;
    private String firstName;
    private String email;
    private String phone;

    public ContactSpec setName(String name) {
      this.name = name;
      return this;
    }

    public ContactSpec setFirstName(String firstName) {
      this.firstName = firstName;
      return this;
    }

    public ContactSpec setEmail(String email) {
      this.email = email;
      return this;
    }

    public ContactSpec setPhone(String phone) {
      this.phone = phone;
      return this;
    }

    boolean isEmpty() {
      return blank(name) && blank(firstName) && blank(email) && blank(phone);
    }
  }

  /**
   * The complete set of supplier values a caller may supply.
   *
   * <p>Closed by construction. {@code null} means "leave alone" on an edit and "use the Axelor
   * default" on a create — it never means "clear".
   */
  public static final class SupplierSpec {
    private String name;
    private String eik;
    private String vatNumber;
    private String partnerType;
    private String fixedPhone;
    private String mobilePhone;
    private String email;
    private String webSite;
    private AddressSpec address;
    private BankAccountSpec bankAccount;
    private String paymentConditionCode;
    private String paymentModeCode;
    private String currencyCode;
    private String fiscalPositionCode;
    private ContactSpec contact;

    public SupplierSpec setName(String name) {
      this.name = name;
      return this;
    }

    public SupplierSpec setEik(String eik) {
      this.eik = eik;
      return this;
    }

    public SupplierSpec setVatNumber(String vatNumber) {
      this.vatNumber = vatNumber;
      return this;
    }

    /** {@code company} (default) or {@code individual}. */
    public SupplierSpec setPartnerType(String partnerType) {
      this.partnerType = partnerType;
      return this;
    }

    public SupplierSpec setFixedPhone(String fixedPhone) {
      this.fixedPhone = fixedPhone;
      return this;
    }

    public SupplierSpec setMobilePhone(String mobilePhone) {
      this.mobilePhone = mobilePhone;
      return this;
    }

    public SupplierSpec setEmail(String email) {
      this.email = email;
      return this;
    }

    public SupplierSpec setWebSite(String webSite) {
      this.webSite = webSite;
      return this;
    }

    public SupplierSpec setAddress(AddressSpec address) {
      this.address = address;
      return this;
    }

    public SupplierSpec setBankAccount(BankAccountSpec bankAccount) {
      this.bankAccount = bankAccount;
      return this;
    }

    public SupplierSpec setPaymentConditionCode(String paymentConditionCode) {
      this.paymentConditionCode = paymentConditionCode;
      return this;
    }

    /** The mode this company pays the supplier by — {@code outPaymentMode} on the partner. */
    public SupplierSpec setPaymentModeCode(String paymentModeCode) {
      this.paymentModeCode = paymentModeCode;
      return this;
    }

    public SupplierSpec setCurrencyCode(String currencyCode) {
      this.currencyCode = currencyCode;
      return this;
    }

    public SupplierSpec setFiscalPositionCode(String fiscalPositionCode) {
      this.fiscalPositionCode = fiscalPositionCode;
      return this;
    }

    public SupplierSpec setContact(ContactSpec contact) {
      this.contact = contact;
      return this;
    }
  }

  /** What happened, in enough detail to audit and to answer the caller. */
  public static final class SupplierResult {
    private final List<String> changes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Long supplierId;
    private boolean created;
    private Map<String, Object> supplier = new LinkedHashMap<>();

    public List<String> getChanges() {
      return changes;
    }

    /** Things that are true and worth saying but did not stop the write. */
    public List<String> getWarnings() {
      return warnings;
    }

    public Long getSupplierId() {
      return supplierId;
    }

    public boolean isCreated() {
      return created;
    }

    /** The saved supplier, re-read through {@link SupplierView#detail}. */
    public Map<String, Object> getSupplier() {
      return supplier;
    }
  }

  // ---------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------

  /**
   * Creates a supplier.
   *
   * <p>A name and an ЕИК are both required: a supplier with no identity number cannot be matched to
   * an invoice afterwards, which is the entire reason for creating it. The ЕИК is checked for
   * validity and for an existing owner before anything is written, so this can never produce the
   * second partner for one ЕИК.
   */
  @Transactional(rollbackOn = Exception.class)
  public SupplierResult create(SupplierSpec spec, String reason) {
    requireCaller();
    if (spec == null || blank(spec.name)) {
      throw new IllegalArgumentException("A supplier needs a name.");
    }
    String eik = SupplierEik.canonical(spec.eik);
    if (eik == null) {
      throw new IllegalArgumentException(
          "A supplier needs a valid ЕИК (9-13 digits). Without it the supplier cannot be matched to"
              + " an invoice, so it must not be created from an unreadable number.");
    }

    Company company = AiCompanyScope.requireCompany();
    SupplierResult result = new SupplierResult();
    result.created = true;

    Partner partner = new Partner();
    partner.setIsSupplier(Boolean.TRUE);
    partner.setIsContact(Boolean.FALSE);
    partner.setPartnerTypeSelect(
        "individual".equalsIgnoreCase(nz(spec.partnerType))
            ? PartnerRepository.PARTNER_TYPE_INDIVIDUAL
            : PartnerRepository.PARTNER_TYPE_COMPANY);
    applyNewSupplierDefaults(partner, company, result);

    partner.setName(text(spec.name, "name"));
    partner.setRegistrationCode(eik);
    result.changes.add("name: " + partner.getName());
    result.changes.add("ЕИК: " + eik);

    // The address goes on before the ЕИК is validated: the validator picks its rule from the
    // address country's RegistrationNumberTemplate, so validating first would always use the
    // default rule.
    applyAddress(partner, spec.address, result);
    applyIdentity(partner, spec, result);
    applyContactDetails(partner, spec, result);
    applySettings(partner, spec, company, result);
    checkRegistrationCode(partner, result);
    assertNoDuplicateRegistrationCode(partner);

    // Checked BEFORE the partner exists. The repository chain creates the accounting situation and
    // the supplier account as part of saving, and it does that by throwing when the company is not
    // configured for it — which, mid-save, would leave a partner behind with no usable accounting.
    // So the same conditions are evaluated first, and a misconfigured company is refused here.
    assertCompanyCanInitialiseSupplier(company);

    // The association the partner form makes on New (action-partner-attrs-set-default adds
    // PartnerController.getActiveCompany() to companySet). Adding it before the save is what makes
    // AccountingSituationInitService.createAccountingSituation — which iterates companySet — create
    // this company's AccountingSituation and resolve its supplier account.
    partner.addCompanySetItem(company);
    result.changes.add("associated with company: " + company.getName());

    // The repository chain, not JPA.save: this is where partnerSeq, fullName, address defaulting
    // and
    // the accounting situations come from. See the class javadoc.
    partner = partnerRepo.save(partner);

    applyBankAccount(partner, spec.bankAccount, result);
    applyContact(partner, spec.contact, result);
    partner = partnerRepo.save(partner);

    LOG.info(
        "Supplier {} (ЕИК {}) created by {}.",
        partner.getId(),
        eik,
        AiExecutionContext.callerDescription());
    return finish(partner, result, reason);
  }

  /**
   * Edits an existing supplier, changing only what was supplied.
   *
   * <p>An address, bank account or contact is added, never replaced or removed. A supplied scalar
   * (name, ЕИК, VAT number, phones, e-mail, web site, payment and currency settings) replaces the
   * stored value. Clearing a field is not offered — it is not something that can be judged from a
   * document. Only a partner belonging to the active company alone may be edited; a shared one is
   * refused before anything changes ({@link #assertOwnedOnlyByActiveCompany}).
   */
  @Transactional(rollbackOn = Exception.class)
  public SupplierResult update(long supplierId, SupplierSpec spec, String reason) {
    requireCaller();
    Partner partner = partnerRepo.find(supplierId);
    // Out of scope answers exactly as unknown: an id from another company must not reveal that the
    // record exists.
    if (partner == null || !AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      throw new IllegalArgumentException("No supplier with id " + supplierId + ".");
    }
    if (Boolean.TRUE.equals(partner.getArchived())) {
      throw new IllegalArgumentException(
          "Supplier " + supplierId + " is archived; restore it in Nyxa before editing it.");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_WRITE, Partner.class, partner.getId())) {
      throw new IllegalArgumentException(
          "You do not have permission to change supplier " + supplierId + ".");
    }
    if (isCompanyIdentity(partner)) {
      throw new IllegalArgumentException(
          "Partner "
              + supplierId
              + " is a company's own partner (its legal identity), not a supplier from Purchases"
              + " → Suppliers. It is changed on the company form under Application config →"
              + " Companies, not here.");
    }
    assertOwnedOnlyByActiveCompany(partner, supplierId);
    if (spec == null) {
      throw new IllegalArgumentException("No supplier values were supplied.");
    }

    Company company = AiCompanyScope.requireCompany();
    SupplierResult result = new SupplierResult();

    if (!Boolean.TRUE.equals(partner.getIsSupplier())) {
      // The common and important case: a partner that already exists as a customer. Marking it is
      // right; creating a second partner for the same ЕИК is not.
      partner.setIsSupplier(Boolean.TRUE);
      result.changes.add("marked as a supplier");
    }
    if (!blank(spec.name) && !spec.name.trim().equals(partner.getName())) {
      String before = partner.getName();
      partner.setName(text(spec.name, "name"));
      result.changes.add("name: " + before + " → " + partner.getName());
    }
    applyAddress(partner, spec.address, result);
    applyIdentity(partner, spec, result);
    applyContactDetails(partner, spec, result);
    applySettings(partner, spec, company, result);
    checkRegistrationCode(partner, result);
    assertNoDuplicateRegistrationCode(partner);
    applyBankAccount(partner, spec.bankAccount, result);
    applyContact(partner, spec.contact, result);

    if (result.changes.isEmpty()) {
      result.warnings.add("Nothing was different from what the supplier already had.");
    }
    partner = partnerRepo.save(partner);
    LOG.info(
        "Supplier {} updated by {}: {}",
        partner.getId(),
        AiExecutionContext.callerDescription(),
        String.join("; ", result.changes));
    return finish(partner, result, reason);
  }

  // ---------------------------------------------------------------------------
  // Field groups
  // ---------------------------------------------------------------------------

  /**
   * The same values the partner form's {@code onNew} action group sets, from the active company.
   *
   * <p>Read from the company and its {@code AccountConfig} rather than hard-coded, so a supplier
   * created here starts out configured exactly like one created in the UI.
   */
  protected void applyNewSupplierDefaults(Partner partner, Company company, SupplierResult result) {
    partner.setCurrency(company.getCurrency());
    partner.setInvoiceSendingFormatSelect("emailpaper");
    User user = AuthUtils.getUser();
    if (user != null) {
      partner.setUser(user);
      partner.setTeam(user.getActiveTeam());
    }
    try {
      partner.setLocalization(
          Beans.get(AppBaseService.class).getAppBase().getDefaultPartnerLocalization());
    } catch (RuntimeException e) {
      LOG.info("No default partner localization configured: {}", e.getMessage());
    }
    try {
      AccountConfigService accountConfigService = Beans.get(AccountConfigService.class);
      com.axelor.apps.account.db.AccountConfig accountConfig =
          accountConfigService.getAccountConfig(company);
      if (accountConfig != null) {
        partner.setInPaymentMode(accountConfig.getInPaymentMode());
        partner.setOutPaymentMode(accountConfig.getOutPaymentMode());
        partner.setPaymentCondition(accountConfig.getDefPaymentCondition());
      }
    } catch (Exception e) {
      // A company with no AccountConfig is a configuration problem the invoice readiness check
      // already reports; it must not stop the supplier being created.
      result.warnings.add(
          "This company has no accounting configuration, so the supplier got no default payment"
              + " mode or payment condition.");
    }
  }

  /** ЕИК and VAT number. */
  protected void applyIdentity(Partner partner, SupplierSpec spec, SupplierResult result) {
    if (!blank(spec.eik)) {
      String eik = SupplierEik.canonical(spec.eik);
      if (eik == null) {
        throw new IllegalArgumentException(
            "'" + spec.eik + "' is not a plausible ЕИК (expected 9-13 digits).");
      }
      if (!eik.equals(partner.getRegistrationCode())) {
        String before = partner.getRegistrationCode();
        partner.setRegistrationCode(eik);
        if (before != null) {
          result.changes.add("ЕИК: " + before + " → " + eik);
        }
      }
    }
    if (!blank(spec.vatNumber)) {
      String vat = spec.vatNumber.trim().replaceAll("\\s+", "").toUpperCase();
      if (!vat.equals(partner.getTaxNbr())) {
        String before = partner.getTaxNbr();
        partner.setTaxNbr(text(vat, "vatNumber"));
        result.changes.add("VAT number: " + (before == null ? "(empty)" : before) + " → " + vat);
      }
    }
  }

  /** Phone, e-mail, web site. */
  protected void applyContactDetails(Partner partner, SupplierSpec spec, SupplierResult result) {
    if (!blank(spec.fixedPhone)) {
      partner.setFixedPhone(normalizePhone(spec.fixedPhone));
      result.changes.add("fixed phone: " + partner.getFixedPhone());
    }
    if (!blank(spec.mobilePhone)) {
      partner.setMobilePhone(normalizePhone(spec.mobilePhone));
      result.changes.add("mobile phone: " + partner.getMobilePhone());
    }
    if (!blank(spec.webSite)) {
      partner.setWebSite(text(spec.webSite, "webSite"));
      result.changes.add("web site: " + partner.getWebSite());
    }
    if (!blank(spec.email)) {
      String address = spec.email.trim();
      if (!address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
        throw new IllegalArgumentException("'" + address + "' is not a valid e-mail address.");
      }
      EmailAddress existing = partner.getEmailAddress();
      if (existing == null) {
        EmailAddress created = new EmailAddress();
        created.setAddress(address);
        partner.setEmailAddress(created);
        result.changes.add("e-mail: " + address);
      } else if (!address.equalsIgnoreCase(nz(existing.getAddress()))) {
        String before = existing.getAddress();
        existing.setAddress(address);
        result.changes.add("e-mail: " + before + " → " + address);
      }
    }
  }

  /** Currency, payment condition, payment mode and fiscal position, resolved by code. */
  protected void applySettings(
      Partner partner, SupplierSpec spec, Company company, SupplierResult result) {
    if (!blank(spec.currencyCode)) {
      Currency currency =
          Beans.get(CurrencyRepository.class)
              .all()
              .filter("UPPER(self.code) = :c")
              .bind("c", spec.currencyCode.trim().toUpperCase())
              .fetchOne();
      if (currency == null) {
        throw new IllegalArgumentException(
            "No currency with code '" + spec.currencyCode.trim() + "'.");
      }
      partner.setCurrency(currency);
      result.changes.add("currency: " + currency.getCode());
    }
    if (!blank(spec.paymentConditionCode)) {
      PaymentCondition condition =
          Beans.get(PaymentConditionRepository.class)
              .all()
              .filter("UPPER(self.code) = :c")
              .bind("c", spec.paymentConditionCode.trim().toUpperCase())
              .fetchOne();
      if (condition == null) {
        throw new IllegalArgumentException(
            "No payment condition with code '" + spec.paymentConditionCode.trim() + "'.");
      }
      partner.setPaymentCondition(condition);
      result.changes.add("payment condition: " + condition.getCode());
    }
    if (!blank(spec.paymentModeCode)) {
      PaymentMode mode =
          Beans.get(PaymentModeRepository.class)
              .all()
              .filter("UPPER(self.code) = :c")
              .bind("c", spec.paymentModeCode.trim().toUpperCase())
              .fetchOne();
      if (mode == null) {
        throw new IllegalArgumentException(
            "No payment mode with code '" + spec.paymentModeCode.trim() + "'.");
      }
      // outPaymentMode: this company pays the supplier. Setting inPaymentMode here would describe
      // money coming in, which is not what a supplier record means.
      partner.setOutPaymentMode(mode);
      result.changes.add("payment mode (outgoing): " + mode.getCode());
    }
    if (!blank(spec.fiscalPositionCode)) {
      FiscalPosition position =
          Beans.get(FiscalPositionRepository.class)
              .all()
              .filter("UPPER(self.code) = :c")
              .bind("c", spec.fiscalPositionCode.trim().toUpperCase())
              .fetchOne();
      if (position == null) {
        throw new IllegalArgumentException(
            "No fiscal position with code '" + spec.fiscalPositionCode.trim() + "'.");
      }
      partner.setFiscalPosition(position);
      result.changes.add("fiscal position: " + position.getCode());
    }
    if (partner.getCurrency() == null && company != null) {
      partner.setCurrency(company.getCurrency());
    }
  }

  /**
   * Resolves and attaches a postal address.
   *
   * <p>City and country are <b>selected</b> from reference data, never created: an unknown
   * settlement or an unconfigured country is refused, because inventing either produces an address
   * that looks right and cannot be used. An address identical to one the partner already has is
   * reused instead of duplicated.
   */
  protected void applyAddress(Partner partner, AddressSpec spec, SupplierResult result) {
    if (spec == null || spec.isEmpty()) {
      return;
    }
    BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);

    String street = nzOrNull(spec.street);
    String number = nzOrNull(spec.number);
    String zip = nzOrNull(spec.zip);
    String cityName = nzOrNull(spec.city);
    String iso = nzOrNull(spec.countryIso);

    // A raw line fills in whatever the structured parts left out, using the same extraction the
    // intake uses on a supplier address read off a document.
    //
    // buildSupplier, NOT parse. parse() only accepts a city after an explicit "гр./град/с./село"
    // marker or as a whole-string single word, so the commonest shape printed on a BG invoice —
    // "София, ул. Самоковско шосе 2Л, ТЦ БОИЛА, ет.3, офис 321" — yielded no city at all and
    // supplier creation was refused for want of a city the address plainly contained. buildSupplier
    // runs the block/line logic (firstAddressBlock → cityFromLine) that already reads exactly this
    // shape, including the leading-comma-segment case and the "skip the country segment" rule.
    // Structured values still win: the raw line only fills gaps.
    if (!blank(spec.raw) && (street == null || cityName == null || zip == null)) {
      BgOcrAddressParser.Parsed parsed =
          BgOcrAddressParser.buildSupplier(spec.raw, null, null, null, null);
      street = street != null ? street : nzOrNull(parsed.street);
      number = number != null ? number : nzOrNull(parsed.number);
      zip = zip != null ? zip : nzOrNull(parsed.zip);
      cityName = cityName != null ? cityName : nzOrNull(parsed.cityName);
      iso = iso != null ? iso : nzOrNull(parsed.countryIso);
    }
    // The written forms: "1000 София", "София 1000", "гр. София", each optionally followed by the
    // street in a later segment. Neither buildSupplier nor parse reads these — both require a П.К.
    // or a гр./с. marker, which nobody types and most invoice headers omit — so an address a person
    // dictated in the chat was refused for want of a city and a postcode it plainly contained.
    if (!blank(spec.raw) && (cityName == null || zip == null)) {
      BgOcrAddressParser.Parsed bare = BgOcrAddressParser.bareZipCity(spec.raw);
      cityName = cityName != null ? cityName : nzOrNull(bare.cityName);
      zip = zip != null ? zip : nzOrNull(bare.zip);
    }
    // Last resort for a city: a "Град:"/"гр." label anywhere in the supplied text, accepted only
    // when the whole string names exactly ONE distinct city — the same never-guess rule the intake
    // applies when a vendor block carries no city of its own.
    if (cityName == null && !blank(spec.raw)) {
      cityName = nzOrNull(BgOcrAddressParser.singleLabeledCity(spec.raw));
    }
    if (cityName == null) {
      throw new IllegalArgumentException(
          "No settlement could be read from this address. Give address.city explicitly (the"
              + " settlement name alone, e.g. 'София'), keeping the rest in address.raw.");
    }
    // The street is NOT required. "1000 София" is a complete address in Axelor's model (city +
    // postcode) and is sometimes all a document prints; refusing it forced the caller to invent a
    // street or to give up on the address altogether. A missing street simply makes a shorter
    // address, and the invoice readiness check already reports what is incomplete.

    Country country = intake.resolveCountryByIso(iso == null ? "BG" : iso);
    if (country == null) {
      throw new IllegalArgumentException(
          "Country '"
              + (iso == null ? "BG" : iso)
              + "' is not configured in Nyxa, so the address cannot be created.");
    }
    City city = intake.resolveExistingBgCity(cityName, null, null);
    if (city == null) {
      throw new IllegalArgumentException(
          "'"
              + cityName
              + "' is not a single known settlement in the Nyxa settlements dataset, so the address"
              + " cannot be created. Check the spelling, or add the address in Nyxa.");
    }
    String effectiveZip = zip != null ? zip : nzOrNull(city.getZip());
    if (effectiveZip == null) {
      throw new IllegalArgumentException(
          "The address has no postcode and '" + cityName + "' has none in the dataset.");
    }

    // Reuse an identical address the partner already has, rather than adding a duplicate row — and
    // make sure it is actually usable as the invoicing address, which is the part that was missing:
    // an address that exists but is not flagged for invoicing leaves the supplier invoice with no
    // address at all, so the record stays in review and the reused row looks like a no-op.
    if (partner.getPartnerAddressList() != null) {
      for (PartnerAddress pa : partner.getPartnerAddressList()) {
        Address existing = pa == null ? null : pa.getAddress();
        if (existing != null && sameAddress(existing, city, effectiveZip, street, number)) {
          markForInvoicing(partner, pa, result);
          return;
        }
      }
    }

    Address address = new Address();
    address.setCountry(country);
    address.setCity(city);
    address.setZip(effectiveZip);
    if (street != null) {
      address.setStreetName(street);
      // The template line fields, so the rendered names are not empty — same as the intake does.
      address.setAddressL4(street);
    }
    if (number != null) {
      address.setBuildingNumber(number);
    }
    String l6 = (effectiveZip + " " + city.getName()).trim();
    if (!l6.isEmpty()) {
      address.setAddressL6(l6);
    }
    String fullName = Beans.get(AddressService.class).computeFullName(address);
    address.setFullName(
        blank(fullName)
            ? ((street == null ? "" : street + ", ") + effectiveZip + " " + city.getName())
            : fullName);
    Beans.get(AddressRepository.class).save(address);

    // Axelor's own attach logic, including the reset of any previous default address. The flags are
    // (isDefault, isInvoicing, isDelivery) — all three, because a supplier with one address uses it
    // for everything, and isInvoicing is what PartnerService.getInvoicingAddress looks for when an
    // invoice needs the supplier's address.
    Beans.get(PartnerService.class)
        .addPartnerAddress(partner, address, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
    if (partner.getMainAddress() == null) {
      partner.setMainAddress(address);
    }
    result.changes.add("address: " + address.getFullName());
  }

  /**
   * Makes an address the partner already has usable as its invoicing address.
   *
   * <p>Additive, like every other edit here: the flags are only ever turned on, and only on the row
   * that matched. Turning {@code isInvoicingAddr} on where it was off is the difference between
   * "the supplier has this address" and "an invoice for this supplier can find it".
   */
  protected void markForInvoicing(Partner partner, PartnerAddress pa, SupplierResult result) {
    Address existing = pa.getAddress();
    if (!Boolean.TRUE.equals(pa.getIsInvoicingAddr())) {
      pa.setIsInvoicingAddr(Boolean.TRUE);
      result.changes.add("existing address marked for invoicing: " + existing.getFullName());
    }
    if (partner.getMainAddress() == null) {
      partner.setMainAddress(existing);
      result.changes.add("main address set to the supplier's existing address");
    }
    if (result.changes.isEmpty()) {
      result.warnings.add(
          "The supplier already has this address; nothing needed changing: "
              + existing.getFullName());
    }
  }

  /**
   * Registers a bank account on the supplier.
   *
   * <p>Refuses an IBAN that already belongs to a different partner. That is not a formality: paying
   * a supplier into somebody else's account is the failure the whole OCR bank-details path is built
   * to prevent, and it would be introduced here rather than caught here.
   */
  protected void applyBankAccount(Partner partner, BankAccountSpec spec, SupplierResult result) {
    if (spec == null || blank(spec.iban)) {
      return;
    }
    String iban = BgOcrBankDetails.normalizeIban(spec.iban);
    if (iban == null) {
      throw new IllegalArgumentException("'" + spec.iban + "' is not a readable IBAN.");
    }
    // Axelor's own validator, the one the partner form runs, on top of the structural check.
    try {
      Beans.get(BankDetailsService.class).validateIban(iban);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "'" + spec.iban + "' is not a valid IBAN (" + e.getClass().getSimpleName() + ").");
    }

    if (partner.getBankDetailsList() != null) {
      for (BankDetails existing : partner.getBankDetailsList()) {
        if (existing != null
            && iban.equalsIgnoreCase(BgOcrBankDetails.normalizeIban(existing.getIban()))) {
          return; // already registered
        }
      }
    }
    long ownedElsewhere =
        Beans.get(com.axelor.apps.base.db.repo.BankDetailsRepository.class)
            .all()
            .filter(
                "REPLACE(UPPER(self.iban), ' ', '') = :iban AND self.partner IS NOT NULL"
                    + " AND self.partner != :partner")
            .bind("iban", iban)
            .bind("partner", partner)
            .count();
    if (ownedElsewhere > 0) {
      throw new IllegalArgumentException(
          "This IBAN is already registered on a different partner. Paying a supplier into another"
              + " partner's account is exactly the mistake this refuses to make — check the"
              + " document and resolve it in Nyxa.");
    }

    Bank bank = resolveExistingBank(spec, iban, result);

    BankDetails details =
        Beans.get(BankDetailsService.class)
            .createBankDetails(null, null, null, bank, partner.getName(), partner, null);
    details.setIban(iban);
    details.setActive(Boolean.TRUE);
    if (!blank(spec.label)) {
      details.setLabel(text(spec.label, "bankAccount.label"));
    }
    if (bank != null) {
      details.setIbanBic(iban + " " + nz(bank.getCode()));
    }
    // Splits bankCode / sortCode / accountNbr / bbanKey out of the IBAN, as the form does.
    Beans.get(BankDetailsService.class).detailsIban(details);
    partner.addBankDetailsListItem(details);
    result.changes.add("bank account: " + iban);
  }

  /**
   * The {@code Bank} for a new account — found, never created.
   *
   * <p>This is the fix for an invented BIC. The previous version called {@code
   * BgOcrIntakeService.resolveBank}, which creates a minimal {@code Bank} row when the code is
   * unknown. That is right for intake, where the BIC was read off the document by Azure, and wrong
   * here, where it was typed by a model: asked for a bank identifier it does not have, a model
   * supplies a plausible one — {@code STSABGSF} for a UBB account, in the case that prompted this —
   * and the result was a permanent, authoritative-looking bank record built from a guess.
   *
   * <p>So three deterministic routes, in order, and none of them writes anything:
   *
   * <ol>
   *   <li>the <b>BIC</b>, if it is shaped like one and matches a registered bank (exactly, or on
   *       its 8-character institution prefix when that is unique);
   *   <li>the <b>bank name</b>, matched exactly on the normalised name, then as a unique
   *       containment. Several matches is a refusal to choose, not a coin toss;
   *   <li>the <b>IBAN itself</b>. A Bulgarian IBAN carries the bank's 4-letter institution code in
   *       positions 5-8 ({@code BG30UBBS…} → {@code UBBS}), which is the head of its BIC. Resolving
   *       from it is derivation from the document's own number, not a guess, and it is what makes a
   *       correct bank link possible with no BIC at all.
   * </ol>
   *
   * <p>Unresolved is reported as a warning and the account is still registered: the IBAN is what
   * money moves by, {@code Bank} is a reference link, and failing an approved supplier creation
   * over a missing reference link would be a worse outcome than a stated gap. The one thing that
   * never happens is a fabricated bank.
   *
   * <p>A BIC that contradicts the IBAN's own institution code is reported and discarded — that
   * disagreement is exactly what an invented BIC looks like.
   */
  protected Bank resolveExistingBank(BankAccountSpec spec, String iban, SupplierResult result) {
    String ibanBankCode = bgInstitutionCode(iban);
    String bic = spec == null ? null : normalizeCode(spec.bic);

    if (bic != null && !bic.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
      result.warnings.add(
          "'" + spec.bic + "' is not shaped like a BIC, so it was ignored; no bank was linked.");
      bic = null;
    }
    if (bic != null && ibanBankCode != null && !bic.startsWith(ibanBankCode)) {
      result.warnings.add(
          "BIC "
              + bic
              + " does not match the IBAN's own bank code "
              + ibanBankCode
              + ", so it was not used. Check the document — one of the two is wrong.");
      bic = null;
    }

    BankRepository banks = Beans.get(BankRepository.class);
    if (bic != null) {
      Bank exact = banks.all().filter("UPPER(self.code) = :c").bind("c", bic).fetchOne();
      if (exact != null) {
        return exact;
      }
      String institution = bic.length() >= 8 ? bic.substring(0, 8) : bic;
      List<Bank> prefixed =
          banks.all().filter("UPPER(self.code) LIKE :c").bind("c", institution + "%").fetch(2);
      if (prefixed.size() == 1) {
        return prefixed.get(0);
      }
      result.warnings.add(
          "BIC "
              + bic
              + " is not a bank registered in Nyxa, so the account was created without a bank"
              + " link. Add the bank in Nyxa if it is needed.");
    }

    String name = spec == null ? null : nzOrNull(spec.bankName);
    if (name != null) {
      Bank exact =
          banks
              .all()
              .filter("UPPER(TRIM(self.bankName)) = :n")
              .bind("n", name.toUpperCase())
              .fetchOne();
      if (exact != null) {
        return exact;
      }
      List<Bank> like =
          banks
              .all()
              .filter("UPPER(self.bankName) LIKE :n")
              .bind("n", "%" + name.toUpperCase() + "%")
              .fetch(2);
      if (like.size() == 1) {
        return like.get(0);
      }
      result.warnings.add(
          like.isEmpty()
              ? "'"
                  + name
                  + "' matches no bank registered in Nyxa, so the account was created without a"
                  + " bank link. Give the bank's exact name or the BIC printed on the document."
              : "'"
                  + name
                  + "' matches more than one registered bank, so none was chosen. Give the exact"
                  + " name or the printed BIC.");
    }

    if (ibanBankCode != null) {
      List<Bank> byIban =
          banks.all().filter("UPPER(self.code) LIKE :c").bind("c", ibanBankCode + "%").fetch(2);
      if (byIban.size() == 1) {
        result.changes.add(
            "bank resolved from the IBAN's bank code "
                + ibanBankCode
                + ": "
                + byIban.get(0).getBankName());
        return byIban.get(0);
      }
    }
    return null;
  }

  /**
   * The 4-letter institution code inside a Bulgarian IBAN (positions 5-8), or null.
   *
   * <p>{@code BG30UBBS80021062502410} → {@code UBBS}. Only BG, because this position is only
   * defined that way for the Bulgarian BBAN; guessing it for another country's IBAN would be
   * exactly the kind of invention this method supports avoiding.
   */
  protected static String bgInstitutionCode(String iban) {
    if (iban == null || iban.length() < 8 || !iban.startsWith("BG")) {
      return null;
    }
    String code = iban.substring(4, 8);
    return code.matches("[A-Z]{4}") ? code : null;
  }

  /** Attaches a contact person, through Axelor's own contact creation. */
  protected void applyContact(Partner partner, ContactSpec spec, SupplierResult result) {
    if (spec == null || spec.isEmpty()) {
      return;
    }
    if (blank(spec.name)) {
      throw new IllegalArgumentException("A contact needs at least a surname in contact.name.");
    }
    EmailAddress email = null;
    if (!blank(spec.email)) {
      email = new EmailAddress();
      email.setAddress(spec.email.trim());
    }
    PartnerService partnerService = Beans.get(PartnerService.class);
    Partner contact =
        partnerService.createContact(
            partner,
            text(spec.name, "contact.name"),
            nzOrNull(spec.firstName),
            blank(spec.phone) ? null : normalizePhone(spec.phone),
            null,
            email,
            partner.getMainAddress());
    partnerService.addContactToPartner(contact);
    partnerRepo.save(contact);
    result.changes.add("contact: " + contact.getSimpleFullName());
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * Axelor's own registration-number validation, warned about rather than refused.
   *
   * <p>The rule comes from the address country's {@code RegistrationNumberTemplate}, so it only
   * applies once an address is set and only for the countries that configure one. It is a warning
   * because the printed number on a real document is the fact and the template is a heuristic —
   * refusing would block a legitimate supplier over a checksum Nyxa may not have configured. The
   * duplicate check below is the one that is absolute.
   */
  protected void checkRegistrationCode(Partner partner, SupplierResult result) {
    try {
      RegistrationNumberValidator validator =
          Beans.get(PartnerRegistrationValidatorFactoryService.class)
              .getRegistrationNumberValidator(partner);
      if (validator != null && !validator.isRegistrationCodeValid(partner)) {
        result.warnings.add(
            "Nyxa's registration-number check does not accept '"
                + partner.getRegistrationCode()
                + "' for this country. Verify it against the document.");
      }
    } catch (Exception e) {
      LOG.info("Could not run the registration-number validator: {}", e.getMessage());
    }
  }

  /**
   * Refuses before writing anything when this company cannot initialise a supplier.
   *
   * <p>The checks mirror {@code AccountingSituationInitServiceImpl.createSupplierAccount} exactly,
   * because that is what will run inside {@code PartnerRepository.save} once the company is in
   * {@code companySet}. It throws an {@code AxelorException} there, and the save is already past
   * creating the Partner row by then — inside a single transaction that rolls back, but the caller
   * would get a configuration error dressed as a failed supplier creation. Evaluating the same
   * conditions first turns that into one clear message naming the missing setting.
   *
   * <p>Read-only, and deliberately not a guess: each branch names the field an administrator has to
   * fill in.
   */
  protected void assertCompanyCanInitialiseSupplier(Company company) {
    com.axelor.apps.account.db.AccountConfig accountConfig;
    try {
      accountConfig = Beans.get(AccountConfigService.class).getAccountConfig(company);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Company "
              + company.getName()
              + " has no accounting configuration, so a supplier cannot be given one. Configure"
              + " Company → Accounting first.");
    }
    if (accountConfig == null) {
      throw new IllegalArgumentException(
          "Company " + company.getName() + " has no accounting configuration (AccountConfig).");
    }
    if (accountConfig.getSupplierAccount() == null) {
      throw new IllegalArgumentException(
          "Company "
              + company.getName()
              + " has no default supplier account configured (AccountConfig.supplierAccount), so a"
              + " new supplier would have no payable account. Set it in Nyxa before creating"
              + " suppliers.");
    }
    Integer mode = accountConfig.getPartnerAccountGenerationModeSelect();
    int creationMode =
        mode == null ? AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_NONE : mode;
    if (creationMode == AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_PREFIX
        && StringUtils.isBlank(accountConfig.getSupplierAccountPrefix())) {
      throw new IllegalArgumentException(
          "Company "
              + company.getName()
              + " generates partner accounts by prefix but has no supplier account prefix"
              + " (AccountConfig.supplierAccountPrefix).");
    }
    if (creationMode == AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_SEQUENCE
        && accountConfig.getSupplierAccountSequence() == null) {
      throw new IllegalArgumentException(
          "Company "
              + company.getName()
              + " generates partner accounts by sequence but has no supplier account sequence"
              + " (AccountConfig.supplierAccountSequence).");
    }
    if (creationMode != AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_NONE
        && creationMode != AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_PREFIX
        && creationMode != AccountConfigRepository.AUTOMATIC_ACCOUNT_CREATION_SEQUENCE) {
      throw new IllegalArgumentException(
          "Company "
              + company.getName()
              + " has an unrecognised partner-account generation mode ("
              + creationMode
              + ").");
    }
  }

  /**
   * The partner holding this ЕИК within the ACTIVE company's own scope, or null when nothing does.
   *
   * <h2>Why "within this company's scope" is now load-bearing</h2>
   *
   * {@link #create} refuses a duplicate only against a Partner the active company can already see —
   * {@link #assertNoDuplicateRegistrationCode} — because companies are isolated and suppliers are
   * never shared between them, confirmed by the accountant. So company 4 and company 5 may each
   * legitimately hold their own, separate Partner row for the same real ЕИК. A query with no
   * company filter at all — this method's own shape before that rule existed — would then see BOTH
   * rows, read two as "ambiguous, cannot say" and answer null for either company even immediately
   * after its own create succeeded, which is exactly the false "did this creation land?" this
   * method exists to settle. So the match itself has to be scoped the same way the duplicate check
   * is: {@link SupplierEik#matchForCompany}, excluding any company's own identity Partner, which is
   * never a supplier and never this answer.
   *
   * <p>Matched on the canonical form of the code against both {@code registrationCode} and {@code
   * taxNbr} — the one rule {@link SupplierEik} defines for every AI-facing lookup — so a code
   * written with spaces or a BG prefix resolves to the same partner it would have collided with.
   *
   * @param eik already canonical, as {@link SupplierEik#canonical} produces
   */
  public Partner findScopedByEik(String eik) {
    String canonical = SupplierEik.canonical(eik);
    if (canonical == null) {
      return null;
    }
    List<Partner> candidates = new ArrayList<>();
    for (Partner partner : matchEikForCompany(canonical, companyId())) {
      if (!isCompanyIdentity(partner)) {
        candidates.add(partner);
      }
    }
    // None, or — impossibly, but the answer must not depend on that — more than one within this
    // company's own scope. Either way there is no single record this could be about, and "cannot
    // say" is the honest answer. A partner in a DIFFERENT company's scope is never counted here at
    // all: SupplierEik#matchForCompany never returns it.
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  /**
   * Whether this partner is some company's own legal identity ({@code Company.partner}) rather than
   * an external supplier. Those are configured on the company form and never edited from here.
   */
  public boolean isCompanyIdentity(Partner partner) {
    if (partner == null || partner.getId() == null) {
      return false;
    }
    return Beans.get(CompanyRepository.class)
            .all()
            .filter("self.partner.id = :id")
            .bind("id", partner.getId())
            .count()
        > 0;
  }

  /**
   * Refuses when a Partner the ACTIVE company can already see holds this registration number —
   * never when only another company's own, isolated supplier does.
   *
   * <h2>Why the installation-wide stock check is wrong here</h2>
   *
   * Companies are isolated: suppliers, customers and master data are never shared between them,
   * confirmed by the accountant. So two companies may each hold their own, entirely separate
   * Partner row for the same real-world ЕИК — nothing in the schema forbids it ({@code
   * registration_code} carries no unique constraint, DB-side or domain-side) and nothing here may
   * forbid it either: company 4 already having a supplier for an ЕИК must never stop company 5
   * creating its own. {@code PartnerService.checkIfRegistrationCodeExists} answers a different,
   * broader question — "does ANY partner in the whole installation hold this code" — which is right
   * for the ordinary Partner form (editing one company's own contact database, unscoped there on
   * purpose) and wrong for an AI tool authorised for exactly one company's books.
   *
   * <p>So the comparison set is the same one every other AI-facing lookup already uses — {@link
   * SupplierEik#matchForCompany}, the identical visibility {@code find_supplier} reports suppliers
   * under (shared, or explicitly linked to the active company) — never a row that belongs only to
   * another company. A Partner out of that scope is invisible here exactly as it is to {@code
   * find_supplier}: never read, compared against by identity, linked to, or exposed in the refusal.
   *
   * <p>{@code Company.partner} — any company's own legal identity — is excluded from the comparison
   * entirely, matching every other supplier check in this module.
   */
  protected void assertNoDuplicateRegistrationCode(Partner partner) {
    String eik = SupplierEik.canonical(partner.getRegistrationCode());
    if (eik == null) {
      // Nothing this module's own identity key can compare against — a blank or unreadable
      // registration number is a different refusal, already given by checkRegistrationCode's own
      // caller before this runs.
      return;
    }
    for (Partner existing : matchEikForCompany(eik, companyId())) {
      if (partner.getId() != null && partner.getId().equals(existing.getId())) {
        continue; // the record being updated is not a duplicate of itself
      }
      if (isCompanyIdentity(existing)) {
        continue; // a company's own identity is never a supplier and never blocks one
      }
      throw duplicateOf(eik, existing);
    }
  }

  /**
   * The refusal for a registration number an accessible partner of the active company already
   * holds.
   *
   * <p>Details only for a record this caller may actually read: {@link #matchEikForCompany} has
   * already confined {@code existing} to the active company's scope, and {@link #canRead} adds the
   * row-level read permission. Without it the refusal still stops the duplicate but says nothing
   * about the record — no id, no name.
   *
   * <p>{@code find_supplier} does return a partner that is not yet marked as a supplier (it never
   * filters on {@code isSupplier}), so the answer points at {@code operation=update}, which marks
   * it — unless the partner is shared beyond this company, which {@link #update} refuses.
   */
  protected DuplicateSupplierException duplicateOf(String eik, Partner existing) {
    if (!canRead(existing)) {
      return new DuplicateSupplierException(
          "Registration number "
              + eik
              + " already belongs to a partner in this company that you do not have permission to"
              + " read, so a second one is not created. An administrator can grant access or"
              + " update that partner.",
          null,
          null,
          false);
    }
    boolean isSupplier = Boolean.TRUE.equals(existing.getIsSupplier());
    boolean shared = isSharedBeyondCompany(existing, companyId());
    String who =
        "partner "
            + existing.getFullName()
            + " (supplierId "
            + existing.getId()
            + (isSupplier ? "" : ", not yet marked as a supplier")
            + ")";
    if (shared) {
      return new DuplicateSupplierException(
          "Registration number "
              + eik
              + " already belongs to "
              + who
              + ", which is shared master data, not this company's alone. A second partner is not"
              + " created, and a shared partner is not changed from here; an accountant decides in"
              + " Nyxa whether this company should get its own.",
          existing.getId(),
          isSupplier,
          true);
    }
    return new DuplicateSupplierException(
        "Registration number "
            + eik
            + " already belongs to "
            + who
            + " in this company. Do not create a second one: use manage_supplier with"
            + " operation=update and supplierId="
            + existing.getId()
            + (isSupplier ? "." : " — an update marks it as a supplier, even with no other field."),
        existing.getId(),
        isSupplier,
        false);
  }

  /**
   * Row-level read permission on the partner. A seam so a test needs no injector; anything that
   * cannot be checked answers "not readable", which only withholds details from a refusal.
   */
  protected boolean canRead(Partner partner) {
    try {
      return AiExecutionContext.isSystem()
          || Beans.get(JpaSecurity.class)
              .isPermitted(JpaSecurity.CAN_READ, Partner.class, partner.getId());
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Whether a partner is shared beyond one company — and so not editable from that company's books.
   *
   * <h2>What "shared" means here</h2>
   *
   * {@link AiCompanyScope#partnerFilter} — the visibility rule every AI lookup uses — admits a
   * partner whose {@code companySet} is <b>empty</b> (Axelor's own meaning: usable by every
   * company) or <b>contains</b> the active company. Under strict company isolation only the second
   * kind, with the active company as its <b>only</b> member, is this company's own record. So a
   * partner is shared when its {@code companySet} is empty, or names any company other than {@code
   * companyId}. Such a partner stays readable and usable where Axelor allows it; writing to it
   * would change master data another company sees, so {@link #update} refuses it.
   *
   * <p>An unknown {@code companyId} answers "shared": never a reason to allow a write.
   */
  public static boolean isSharedBeyondCompany(Partner partner, Long companyId) {
    if (partner == null) {
      return false;
    }
    if (companyId == null) {
      return true;
    }
    java.util.Set<Company> companies = partner.getCompanySet();
    if (companies == null || companies.isEmpty()) {
      return true;
    }
    for (Company company : companies) {
      if (company == null || !companyId.equals(company.getId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Refuses an update of a partner that is not the active company's alone — before any field is
   * touched. No auto-link, clone or migration: who should own shared master data is an accountant's
   * decision, not one that follows from a document.
   */
  protected void assertOwnedOnlyByActiveCompany(Partner partner, long supplierId) {
    if (!isSharedBeyondCompany(partner, companyId())) {
      return;
    }
    // One message for both shapes (empty set, or other members): which other companies, if any,
    // use this partner is not this company's business to learn.
    throw new OcrBlocked(
        AiBlockerCode.PERMISSION_DENIED,
        "Supplier "
            + supplierId
            + " is shared master data, not this company's alone, so it is not changed from here."
            + " Nothing was changed. It can still be used as it is; giving this company its own"
            + " supplier, or assigning this one to it, is an accountant's decision in Nyxa.");
  }

  /**
   * The active company, or an exception outside one. A seam so a test can supply a fixed company id
   * instead of needing a running request scope — the same idiom {@code
   * SupplierSearchService#companyId} uses.
   */
  protected Long companyId() {
    return AiCompanyScope.requireCompanyId();
  }

  /**
   * Partners holding this ЕИК within one company's own scope — {@link SupplierEik#matchForCompany},
   * the one rule every AI-facing lookup in this module uses. A seam so a test can supply fixed rows
   * instead of needing a running Guice injector and a database; the two callers, {@link
   * #assertNoDuplicateRegistrationCode} and {@link #findScopedByEik}, differ only in what they do
   * with the result, never in how it is found.
   */
  protected List<Partner> matchEikForCompany(String canonicalEik, Long companyId) {
    return SupplierEik.matchForCompany(partnerRepo, canonicalEik, companyId).fetch(5);
  }

  private void requireCaller() {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No caller identity; suppliers cannot be created or changed anonymously.");
    }
  }

  // ---------------------------------------------------------------------------
  // Result
  // ---------------------------------------------------------------------------

  private SupplierResult finish(Partner partner, SupplierResult result, String reason) {
    fillDraftInvoiceAddresses(partner, result);
    result.supplierId = partner.getId();
    result.supplier = SupplierView.detail(partner);
    if (reason != null && !reason.trim().isEmpty()) {
      result.changes.add("evidence: " + reason.trim());
    }
    // A newly created supplier is now associated with the active company and has its accounting
    // situation, so there is nothing to warn about. An existing shared supplier is left exactly as
    // the accountant set it up — re-homing somebody else's master data is not this service's call —
    // and SupplierView.detail already reports sharedAcrossAllCompanies and
    // accountingForThisCompany, so the fact stays visible without being dressed up as a problem.
    AiWriteEffects.touched(Partner.class.getName(), partner.getId());
    return result;
  }

  /**
   * Puts the supplier's invoicing address onto its OCR drafts that have none.
   *
   * <p>Adding the address to the Partner is not by itself the thing the accountant asked for. The
   * document they are looking at is a draft supplier invoice whose {@code address} is empty, and an
   * empty address is one of the five fields that keep it in review. Axelor fills that field when
   * the partner is selected on the form; a partner edited afterwards leaves the already-created
   * draft exactly as it was, so the assistant would report "адресът е добавен" while the record
   * still said the address was missing — and the only way forward was to tell the user to fix it by
   * hand.
   *
   * <p>Deliberately narrow, and every condition matters: only invoices of <em>this</em> partner, in
   * the caller's active company, that OCR generated, that are still DRAFT, and that have <b>no</b>
   * address at all. Nothing is ever replaced, so an address a person chose is untouched.
   *
   * <p>Uses {@code PartnerService.getInvoicingAddress} — the same lookup the invoice form uses — so
   * the draft ends up with the address Axelor itself would have picked, and {@code addressStr} is
   * rendered by {@code AddressService} rather than assembled here. Review state is then re-derived
   * through the existing {@code missingReviewFields} / {@code syncUploadReview} path.
   */
  protected void fillDraftInvoiceAddresses(Partner partner, SupplierResult result) {
    Address invoicing;
    try {
      invoicing = Beans.get(PartnerService.class).getInvoicingAddress(partner);
    } catch (RuntimeException e) {
      LOG.info("Could not resolve an invoicing address for partner {}: {}", partner.getId(), e);
      return;
    }
    if (invoicing == null || partner.getId() == null) {
      return;
    }
    Company company;
    try {
      company = AiCompanyScope.requireCompany();
    } catch (RuntimeException e) {
      return;
    }
    List<Invoice> drafts =
        Beans.get(InvoiceRepository.class)
            .all()
            .filter(
                "self.partner = :partner AND self.company = :company AND self.address IS NULL"
                    + " AND self.statusSelect = :draft AND self.aiGenerated = TRUE")
            .bind("partner", partner)
            .bind("company", company)
            .bind("draft", InvoiceRepository.STATUS_DRAFT)
            .fetch(20);
    if (drafts.isEmpty()) {
      return;
    }
    BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);
    for (Invoice invoice : drafts) {
      invoice.setAddress(invoicing);
      String rendered = Beans.get(AddressService.class).computeAddressStr(invoicing);
      invoice.setAddressStr(blank(rendered) ? invoicing.getFullName() : rendered);
      List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
      boolean complete = missing.isEmpty();
      invoice.setOcrReviewStatus(
          complete
              ? BgOcrIntakeService.OCR_REVIEW_COMPLETE
              : BgOcrIntakeService.OCR_REVIEW_TO_REVIEW);
      invoice.setOcrMissingFields(BgOcrIntakeService.missingFieldsLabel(missing));
      // JPA.save, never invoiceRepo.save: the Invoice repository chain recomputes line amounts and
      // pack totals, which must not happen as a side effect of setting an address. Same decision,
      // and the same audit, as OcrRecordUpdateService.
      JPA.save(invoice);
      intake.syncUploadReview(invoice.getId(), complete);
      AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
      result.changes.add(
          "supplier invoice " + invoice.getId() + ": address set to " + invoice.getAddressStr());
    }
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  /** Same normalised comparison the intake uses to decide an address is a duplicate. */
  private static boolean sameAddress(
      Address a, City city, String zip, String street, String number) {
    String aCity = a.getCity() == null ? null : a.getCity().getName();
    return BgOcrAddressParser.norm(aCity).equals(BgOcrAddressParser.norm(city.getName()))
        && BgOcrAddressParser.norm(a.getZip()).equals(BgOcrAddressParser.norm(zip))
        && BgOcrAddressParser.norm(a.getStreetName()).equals(BgOcrAddressParser.norm(street))
        && BgOcrAddressParser.norm(a.getBuildingNumber()).equals(BgOcrAddressParser.norm(number));
  }

  /** Axelor's own phone normalisation, so a number looks the way the UI would store it. */
  private static String normalizePhone(String raw) {
    try {
      String normalized = Beans.get(PartnerService.class).normalizePhoneNumber(raw.trim());
      return blank(normalized) ? raw.trim() : normalized;
    } catch (RuntimeException e) {
      return raw.trim();
    }
  }

  private static String text(String value, String field) {
    String v = value == null ? null : value.trim();
    if (v != null && v.length() > MAX_TEXT) {
      throw new IllegalArgumentException(field + " is longer than " + MAX_TEXT + " characters.");
    }
    return v;
  }

  private static boolean blank(String v) {
    return v == null || v.trim().isEmpty();
  }

  private static String nz(String v) {
    return v == null ? "" : v.trim();
  }

  private static String nzOrNull(String v) {
    return blank(v) ? null : v.trim();
  }

  /**
   * Whitespace-free upper case, for a BIC or a bank code. Null when there is nothing to compare.
   */
  private static String normalizeCode(String v) {
    if (blank(v)) {
      return null;
    }
    String code = v.replaceAll("\\s+", "").toUpperCase();
    return code.isEmpty() ? null : code;
  }
}
