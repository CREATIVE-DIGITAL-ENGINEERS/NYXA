package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.AccountingSituation;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.AccountManagementRepository;
import com.axelor.apps.account.db.repo.AccountingSituationRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.service.YearAccountService;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationInitService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PrintingSettings;
import com.axelor.apps.base.db.PrintingTemplate;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgSafPaymentMapping;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * САМОЛИЧНОСТТА НА ДРУЖЕСТВОТО, ФИНАНСОВАТА ГОДИНА И БАНКОВИТЕ СМЕТКИ — {@code company.csv} и
 * {@code banks.csv}.
 *
 * <p>Името, ЕИК, ДДС номерът и адресът отиват в партньора на дружеството (там ги четат
 * декларациите); счетоводната база (НСС/МСФО) — в {@code Company.bgAccountingFramework}; FATCA
 * статусът — в профила {@code BgFatcaProfile}. Финансовата година се генерира с месечни периоди
 * през същата услуга като бутона „Генериране на финансова година“, ако дружеството още я няма.
 */
final class BgDemoCompanyStep {

  private BgDemoCompanyStep() {}

  static void run(BgDemoContext ctx) throws AxelorException {
    Map<String, String> v = keyValues(ctx.rows("company"));
    JPA.runInTransaction(() -> identity(ctx, v));
    fiscalYear(ctx);
    JPA.runInTransaction(() -> subAccounts(ctx));
    JPA.runInTransaction(() -> bankTransferModes(ctx));
    JPA.runInTransaction(() -> banks(ctx));
    JPA.runInTransaction(() -> purchaseJournals(ctx));
    ownVatSystem(ctx);
    JPA.runInTransaction(() -> invoiceSequences(ctx));
    JPA.runInTransaction(() -> fixedAssetVatAccounts(ctx));
    ctx.log("Дружество: " + v.get("name") + ", ЕИК " + v.get("eik"));
  }

  static Map<String, String> keyValues(List<BgDemoCsv.Row> rows) {
    Map<String, String> v = new HashMap<>();
    for (BgDemoCsv.Row r : rows) {
      v.put(r.get("key"), r.get("value"));
    }
    return v;
  }

  private static void identity(BgDemoContext ctx, Map<String, String> v) {
    Company company = ctx.company();
    // Името на дружеството е уникално: заето от друго дружество — остава даденото от
    // администратора (партньорът на дружеството пак получава името от набора).
    boolean nameTaken =
        JPA.all(Company.class)
                .filter("self.name = ?1 AND self.id <> ?2", v.get("name"), company.getId())
                .count()
            > 0;
    if (!nameTaken) {
      company.setName(v.get("name"));
    } else {
      ctx.log(
          "Името „"
              + v.get("name")
              + "“ е заето от друго дружество — остава „"
              + company.getName()
              + "“");
    }
    company.setCurrency(currency("EUR"));
    company.setBgAccountingFramework(v.get("framework"));
    if (v.containsKey("seniorityPct")) {
      company.setBgSeniorityRatePct(new BigDecimal(v.get("seniorityPct")));
    }
    if (v.containsKey("nkid")) {
      company.setBgNkid(JPA.all(BgNkid.class).filter("self.code = ?1", v.get("nkid")).fetchOne());
    }
    Address address = address(v.get("street"), v.get("zip"), v.get("city"), "BG");
    company.setAddress(address);
    if (company.getPrintingSettings() == null) {
      // Без настройки за печат Axelor не съставя PDF на продажната фактура при осчетоводяването.
      PrintingSettings printing = new PrintingSettings();
      printing.setName(v.get("name"));
      company.setPrintingSettings(JPA.save(printing));
    }

    Partner partner = company.getPartner();
    partner.setName(v.get("name"));
    partner.setRegistrationCode(v.get("eik"));
    partner.setTaxNbr(v.get("vat"));
    partner.setCurrency(company.getCurrency());
    Beans.get(PartnerService.class).addPartnerAddress(partner, address, true, true, true);
    partner.setMainAddress(address);
    if (v.containsKey("declarantNames")) {
      BgDeclarant declarant = new BgDeclarant();
      declarant.setCompany(company);
      declarant.setDeclarantNames(v.get("declarantNames"));
      declarant.setDeclarantEgn(v.get("declarantEgn"));
      declarant.setDeclarantPosition(v.get("declarantPosition"));
      declarant.setDeclarantCapacity(v.get("declarantCapacity"));
      declarant.setDeclarantCity(v.get("city"));
      declarant.setDeclarantPostCode(v.get("zip"));
      declarant.setDeclarantAddress(v.get("street"));
      declarant.setIsDefault(true);
      company.addBgDeclarantListItem(declarant);
    }
    Beans.get(CompanyRepository.class).save(company);

    BgFatcaProfile fatca =
        JPA.all(BgFatcaProfile.class).filter("self.company = ?1", company).fetchOne();
    if (fatca == null) {
      fatca = new BgFatcaProfile();
      fatca.setCompany(company);
    }
    fatca.setStatusSelect(v.get("fatca"));
    fatca.setNote("Демо данни: " + v.get("fatcaNote"));
    JPA.save(fatca);
  }

  private static void fiscalYear(BgDemoContext ctx) throws AxelorException {
    LocalDate from = ctx.dates().first();
    LocalDate to = from.withMonth(12).withDayOfMonth(31);
    long present =
        JPA.all(Year.class)
            .filter(
                "self.company.id = ?1 AND self.typeSelect = ?2 AND self.fromDate <= ?3"
                    + " AND self.toDate >= ?3",
                ctx.companyId(),
                YearRepository.TYPE_FISCAL,
                from)
            .count();
    if (present == 0) {
      // Месечни периоди, както от бутона в счетоводната конфигурация.
      Beans.get(YearAccountService.class)
          .generateFiscalYear(ctx.company(), from, to, YearRepository.DURATION_MONTH, null);
    }
  }

  /**
   * НОМЕРАЦИЯТА НА ФАКТУРИТЕ В ДРУЖЕСТВОТО. В BG_COA продажните (7), покупните (9) и известията (8,
   * 10) са отделни поредици по 10 цифри без префикс, а Axelor отказва номер, който вече стои на
   * фактура в същото дружество („The generated sequence … already exists“) — тоест първата продажба
   * след първата покупка пада. Настройката, която потребителят прави в поредиците: покупните
   * получават префикс (вътрешен номер; данъчният е номерът на доставчика), а известията към клиент
   * вървят по поредицата на фактурите — продажните фактури и известия са в една поредица от точно
   * 10 арабски цифри (ЗДДС чл. 114 ал. 1 т. 2 вр. чл. 115 ал. 4).
   */
  private static void invoiceSequences(BgDemoContext ctx) {
    AccountConfig config =
        JPA.all(AccountConfig.class).filter("self.company = ?1", ctx.company()).fetchOne();
    prefix(config.getSuppInvSequence(), "P-");
    prefix(config.getSuppRefSequence(), "PR-");
    config.setCustRefSequence(config.getCustInvSequence());
    // Шаблонът за печат на фактурата — Axelor прави PDF при осчетоводяване на продажба, а BG_COA
    // не го задава (празно в account_accountConfig_invoicing.csv).
    if (config.getInvoicePrintTemplate() == null) {
      config.setInvoicePrintTemplate(
          JPA.all(PrintingTemplate.class)
              .filter("self.metaModel.name = 'Invoice' AND self.name = 'Invoice PDF'")
              .fetchOne());
    }
    JPA.save(config);
  }

  /**
   * ДДС сметката при покупка на ДМА — в данъчните редове на дружеството BG_COA попълва само
   * сметката за обикновени покупки (4531); Axelor иска отделна за ред „ДМА“. Попълва се същата
   * сметка, само където е празна (както в картона на данъка › Счетоводство).
   */
  private static void fixedAssetVatAccounts(BgDemoContext ctx) {
    for (AccountManagement am :
        JPA.all(AccountManagement.class)
            .filter(
                "self.company = ?1 AND self.tax IS NOT NULL"
                    + " AND self.purchaseTaxVatSystem1Account IS NOT NULL",
                ctx.company())
            .fetch()) {
      boolean changed = false;
      if (am.getPurchFixedAssetsTaxVatSystem1Account() == null) {
        am.setPurchFixedAssetsTaxVatSystem1Account(am.getPurchaseTaxVatSystem1Account());
        changed = true;
      }
      if (am.getPurchFixedAssetsTaxVatSystem2Account() == null) {
        am.setPurchFixedAssetsTaxVatSystem2Account(
            am.getPurchaseTaxVatSystem2Account() != null
                ? am.getPurchaseTaxVatSystem2Account()
                : am.getPurchaseTaxVatSystem1Account());
        changed = true;
      }
      if (changed) {
        JPA.save(am);
      }
    }
  }

  private static void prefix(Sequence sequence, String prefix) {
    if (sequence != null && (sequence.getPrefixe() == null || sequence.getPrefixe().isEmpty())) {
      sequence.setPrefixe(prefix);
      JPA.save(sequence);
    }
  }

  /**
   * Счетоводната ситуация на партньора на дружеството с режим на ДДС „общ“ (не касова отчетност) —
   * панелът „Счетоводна ситуация“ на партньора; без нея Axelor не изчислява данъка на продажба.
   * Новото дружество я няма: BG хранилището оставя партньора без companySet (виж {@code
   * BgCompanyManagementRepository#createOwnPartner}).
   */
  private static void ownVatSystem(BgDemoContext ctx) throws AxelorException {
    try {
      BgDemoTx.run(
          () -> {
            Company company = ctx.company();
            Partner own = company.getPartner();
            AccountingSituation s =
                Beans.get(AccountingSituationRepository.class)
                    .findByCompanyAndPartner(company, own);
            if (s == null) {
              s =
                  Beans.get(AccountingSituationInitService.class)
                      .createAccountingSituation(own, company);
            }
            s.setVatSystemSelect(AccountingSituationRepository.VAT_COMMON_SYSTEM);
            JPA.save(s);
          });
    } catch (AxelorException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Дневниците за покупки (PCH, RFP) допускат и „текущ актив“ — покупката на стоки по BG_COA е по
   * 304 (семейство GOODS), а шаблонът допуска в PCH само ДМА, задължения, данъци и разходи.
   * Корекцията е тази, която подсказва самото съобщение на Axelor („modify journal settings“), и е
   * само за това дружество.
   */
  private static void purchaseJournals(BgDemoContext ctx) {
    AccountType currentAsset =
        JPA.all(AccountType.class).filter("self.technicalTypeSelect = 'currentAsset'").fetchOne();
    for (Journal j :
        JPA.all(Journal.class)
            .filter("self.company = ?1 AND self.code IN ('PCH', 'RFP')", ctx.company())
            .fetch()) {
      if (j.getValidAccountTypeSet() != null
          && !j.getValidAccountTypeSet().contains(currentAsset)) {
        j.getValidAccountTypeSet().add(currentAsset);
        JPA.save(j);
      }
    }
    // Дневникът „ДДС протоколи“ (VAT) допуска в BG_COA произход 5, 6, 7, а статиите на модула за
    // ДДС са „продажба“ (3: протокол по чл. 117, чл. 79) и „покупка“ (4: чл. 79а, приспадане по
    // чл. 79) — без тях нито една не се осчетоводява.
    Journal vat =
        JPA.all(Journal.class)
            .filter("self.company = ?1 AND self.code = 'VAT'", ctx.company())
            .fetchOne();
    if (vat != null
        && vat.getAuthorizedFunctionalOriginSelect() != null
        && !vat.getAuthorizedFunctionalOriginSelect().isEmpty()) {
      java.util.Set<String> origins =
          new java.util.LinkedHashSet<>(
              java.util.Arrays.asList(vat.getAuthorizedFunctionalOriginSelect().split(",")));
      if (origins.add("3") | origins.add("4")) {
        vat.setAuthorizedFunctionalOriginSelect(String.join(",", origins));
        JPA.save(vat);
      }
    }
  }

  /** {@code banks.csv}: код;IBAN;BIC;банка;валута;по подразбиране. */
  private static void banks(BgDemoContext ctx) {
    Company company = ctx.company();
    for (BgDemoCsv.Row r : ctx.rows("banks")) {
      // Сметка, въведена на ръка при създаването на дружеството (същият IBAN), се преизползва.
      BankDetails existing =
          JPA.all(BankDetails.class)
              .filter("self.company = ?1 AND self.iban = ?2", company, r.get("iban"))
              .fetchOne();
      BankDetails bd = existing != null ? existing : new BankDetails();
      bd.setBank(bank(r.get("bic"), r.get("bank"), "BG"));
      bd.setIban(r.get("iban"));
      bd.setCurrency(currency(r.get("currency")));
      bd.setLabel(r.get("label"));
      bd.setCode(r.get("code"));
      bd.setActive(true);
      bd.setIsDefault(r.bool("default"));
      bd.setOwnerName(company.getName());
      bd.setCompany(company);
      bd.setPartner(company.getPartner());
      // Сметката и дневникът на банковата сметка (картонът › Счетоводство) — по тях равнението
      // намира движенията: 503 за сметката в евро, иначе {@code account} (504).
      bd.setBankAccount(
          BgDemoPartnerStep.account(company, r.has("account") ? r.get("account") : "503"));
      bd.setJournal(
          JPA.all(Journal.class)
              .filter("self.company = ?1 AND self.code = 'BNK'", company)
              .fetchOne());
      if (existing == null) {
        company.addBankDetailsListItem(bd);
      }
      if (r.bool("default")) {
        company.setDefaultBankDetails(bd);
      }
      JPA.save(bd);
      ctx.put("bank", r.get("code"), bd);
      if (r.has("account")) {
        paymentAccounts(company, bd, r.get("account"));
      }
    }
    Beans.get(CompanyRepository.class).save(company);
  }

  /**
   * Режимите „Банков превод“ на набора: код, име, режимът на BG_COA, чиито редове се копират, и
   * сметката (празно — тази на копирания ред, 503). Във валута — отделни режими към 504: без
   * „Управление на няколко банки“ (общ ключ на Base) Axelor взема ПЪРВИЯ ред на дружеството в
   * режима и не гледа банковата сметка на плащането ({@code
   * PaymentModeServiceImpl#getAccountManagement}).
   */
  static final String[][] BANK_TRANSFER_MODES = {
    {"INC_BT", "Банков превод — постъпление", "INC_OTH", ""},
    {"OUT_BT", "Банков превод — плащане", "OUT_OTH", ""},
    {"INC_BTV", "Банков превод във валута — постъпление", "INC_OTH", "504"},
    {"OUT_BTV", "Банков превод във валута — плащане", "OUT_OTH", "504"},
    {"INC_BTC", "Банков превод в CHF — постъпление", "INC_OTH", "5042"},
  };

  /**
   * Подсметките на дружеството: код, име, родител. Сметката в CHF е отделна от 504 (USD):
   * равнението на Axelor търси кандидати по сметка и дневник, не по банкова сметка, и превалутира
   * чуждата валута по днешен курс USD→CHF, какъвто ЕЦБ не дава. SAF-T я мапва през родителя 504,
   * ГОД — по префикса.
   */
  static final String[][] SUB_ACCOUNTS = {
    {"5042", "Разплащателна сметка в CHF", "504"},
  };

  private static void subAccounts(BgDemoContext ctx) {
    Company company = ctx.company();
    for (String[] a : SUB_ACCOUNTS) {
      if (JPA.all(Account.class)
              .filter("self.company = ?1 AND self.code = ?2", company, a[0])
              .count()
          > 0) {
        continue;
      }
      Account parent = BgDemoPartnerStep.account(company, a[2]);
      Account sub = JPA.copy(parent, false);
      sub.setCode(a[0]);
      sub.setName(a[1]);
      sub.setParentAccount(parent);
      JPA.save(sub);
      ctx.log("Подсметка " + a[0] + " „" + a[1] + "“ (родител " + a[2] + ")");
    }
  }

  /**
   * „БАНКОВ ПРЕВОД“ БЕЗ БАНКОВО НАРЕЖДАНЕ. BG_COA има два вида банкови режими: „Постъпил/Изходящ
   * банков превод“ (INC_WT/OUT_WT) — с банково нареждане без формат, всяко плащане пада
   * (NullPointerException в BankOrderCreateService); и „Друго постъпление/плащане“
   * (INC_OTH/OUT_OTH) — те минават, но SAF-T ги отказва (SAFT_PAY_METHOD_EXCLUDED:
   * Nom_PaymentMethod няма „друго“). Затова наборът създава, ако ги няма, INC_BT/OUT_BT (и
   * INC_BTV/OUT_BTV за сметките във валута, 504): вид „превод“, без банково нареждане, с дневника
   * на INC_OTH/OUT_OTH („Банка“) за това дружество и мапинг към НАП 03/42 — същият като на
   * INC_WT/OUT_WT („безкасово плащане по банкова сметка“). Режимите и мапингът са общи за базата
   * (както всеки режим на плащане); на другите дружества не се дават сметки.
   */
  private static void bankTransferModes(BgDemoContext ctx) {
    Company company = ctx.company();
    for (String[] m : BANK_TRANSFER_MODES) {
      PaymentMode source = JPA.all(PaymentMode.class).filter("self.code = ?1", m[2]).fetchOne();
      PaymentMode mode = JPA.all(PaymentMode.class).filter("self.code = ?1", m[0]).fetchOne();
      if (mode == null) {
        mode = new PaymentMode();
        mode.setCode(m[0]);
        mode.setName(m[1]);
        mode.setInOutSelect(source.getInOutSelect());
        mode.setTypeSelect(PaymentModeRepository.TYPE_TRANSFER);
        mode.setAccountingMethodSelect(source.getAccountingMethodSelect());
        mode.setAccountingTriggerSelect(source.getAccountingTriggerSelect());
        mode.setGenerateBankOrder(false);
        mode = JPA.save(mode);
        ctx.log("Режим на плащане „" + m[1] + "“ (" + m[0] + ") — създаден");
      }
      if (JPA.all(AccountManagement.class)
              .filter("self.paymentMode = ?1 AND self.company = ?2", mode, company)
              .count()
          == 0) {
        for (AccountManagement src :
            JPA.all(AccountManagement.class)
                .filter("self.paymentMode = ?1 AND self.company = ?2", source, company)
                .fetch()) {
          AccountManagement am = new AccountManagement();
          am.setTypeSelect(src.getTypeSelect());
          am.setPaymentMode(mode);
          am.setCompany(company);
          am.setBankDetails(src.getBankDetails());
          am.setCashAccount(
              m[3].isEmpty() ? src.getCashAccount() : BgDemoPartnerStep.account(company, m[3]));
          am.setJournal(src.getJournal());
          am.setSequence(src.getSequence());
          mode.addAccountManagementListItem(am);
          JPA.save(am);
        }
      }
      if (JPA.all(BgSafPaymentMapping.class).filter("self.paymentModeCode = ?1", m[0]).count()
          == 0) {
        BgSafPaymentMapping map = new BgSafPaymentMapping();
        map.setImportId("SAFTPM-" + m[0]);
        map.setPaymentModeCode(m[0]);
        map.setMethodCode("03");
        map.setMechanismCode("42");
        map.setIsExcluded(false);
        map.setNote(
            m[1]
                + " (демо данни) — безкасово плащане по банкова сметка, като "
                + m[2].replace("OTH", "WT")
                + ".");
        JPA.save(map);
      }
    }
  }

  /**
   * Сметката в чужда валута получава свое управление на сметки по начините на плащане на набора
   * (както в „Начини на плащане › Сметки“): постъпление и плащане през тази банкова сметка → {@code
   * account} (504), дневник „Банка“. Сметката по подразбиране ползва редовете на шаблона.
   */
  private static void paymentAccounts(Company company, BankDetails bd, String accountCode) {
    Journal bank =
        JPA.all(Journal.class)
            .filter("self.company = ?1 AND self.code = 'BNK'", company)
            .fetchOne();
    for (String code : new String[] {"INC_OTH", "OUT_OTH", "INC_BT", "OUT_BT"}) {
      PaymentMode mode = JPA.all(PaymentMode.class).filter("self.code = ?1", code).fetchOne();
      AccountManagement am = new AccountManagement();
      am.setTypeSelect(AccountManagementRepository.TYPE_PAYMENT);
      am.setPaymentMode(mode);
      am.setCompany(company);
      am.setBankDetails(bd);
      am.setCashAccount(BgDemoPartnerStep.account(company, accountCode));
      am.setJournal(bank);
      mode.addAccountManagementListItem(am);
      JPA.save(am);
    }
  }

  static Currency currency(String iso) {
    Currency c = JPA.all(Currency.class).filter("self.codeISO = ?1", iso).fetchOne();
    if (c == null) {
      throw new IllegalStateException(
          "Валутата " + iso + " липсва — тя идва с App Install на базовите данни.");
    }
    return c;
  }

  static Country country(String alpha2) {
    Country c = JPA.all(Country.class).filter("self.alpha2Code = ?1", alpha2).fetchOne();
    if (c == null) {
      throw new IllegalStateException(
          "Държавата " + alpha2 + " липсва — тя идва с App Install на базовите данни.");
    }
    return c;
  }

  static Address address(String street, String zip, String city, String alpha2) {
    return address(street, zip, city, alpha2, null);
  }

  /**
   * Адрес по шаблона на държавата: улица, пощенски код и град (запис „Град“ — намира се по име и
   * държава, липсващ се създава, както от полето в адреса), за САЩ и щат.
   */
  static Address address(String street, String zip, String city, String alpha2, String region) {
    Country country = country(alpha2);
    City c =
        JPA.all(City.class)
            .filter("self.name = ?1 AND self.country = ?2", city, country)
            .fetchOne();
    if (c == null) {
      c = new City();
      c.setName(city);
      c.setZip(zip);
      c.setCountry(country);
      c = JPA.save(c);
    }
    Address a = new Address();
    a.setStreetName(street);
    a.setAddressL4(street);
    a.setZip(zip);
    a.setCity(c);
    a.setTownName(city);
    a.setAddressL6((zip == null ? "" : zip + " ") + city);
    if (region != null && !region.isEmpty()) {
      a.setCountrySubDivision(region);
    }
    a.setCountry(country);
    // През хранилището — то проверява шаблона на адреса и изчислява пълното име (fullName), което
    // четат ДДС файловете.
    return Beans.get(AddressRepository.class).save(a);
  }

  /** Банката по BIC — измислена ({@code DEMO…}), създава се веднъж. */
  static Bank bank(String bic, String name, String alpha2) {
    Bank b = JPA.all(Bank.class).filter("self.code = ?1", bic).fetchOne();
    if (b == null) {
      b = new Bank();
      b.setCode(bic);
      b.setBankName(name);
      b.setCountry(country(alpha2));
      b.setBankDetailsTypeSelect(1);
      b = JPA.save(b);
    }
    return b;
  }
}
