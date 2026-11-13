package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.OcrExtractorRouter;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import javax.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof that {@link BgOcrIntakeService#routeExtracted} — the REAL production routing
 * method, not a re-implementation of its decision tree — resolves each document type to the correct
 * record, using fixtures whose TEXT STRUCTURE mirrors real OCR output (values sanitized; no real
 * company/EIK/filename is used or matched on).
 *
 * <p>A purely in-memory test that copies {@code routeExtracted}'s condition tree by hand cannot
 * catch a case like this one: a genuine ОББ Интерлийз-style premium notice (bare "Извлечение"
 * heading, no "фактура") still carries the common Bulgarian "ways to pay" paragraph — "...с
 * платежно нареждане при ... - ЦУ,..." — deep in its footer. A {@code STRONG_NON_INVOICE} pattern
 * that matched that phrase ANYWHERE in the document (no heading restriction) would always win over
 * the structured-evidence Invoice fallback and force a Move. See {@link
 * com.axelor.apps.aiocr.service.ocr.BgOcrDocumentClassifierTest} for the classifier-level coverage
 * of the same rule.
 */
class BgOcrRoutingIntegrationTest extends AiOcrTest {

  /**
   * {@code matchSupplier} runs a plain HQL {@code Partner} query. Under HSQLDB (this module's test
   * harness) that always fails with "incompatible data types in combination", because stock {@code
   * axelor-account}'s {@code Partner.dso} (Days Sales Outstanding, {@code Partner.xml})
   * {@code @Formula} column does {@code CURRENT_DATE - <integer column>}, which HSQLDB's type
   * resolver rejects outright (a compile-time SQL error, independent of any data) — Hibernate
   * includes that formula in the SELECT list for every Partner load, however it's queried. No test
   * anywhere in this whole open-suite tree queries Partner via Hibernate in a DB-backed test, for
   * the same reason. This is a pre-existing platform/test-environment limitation, unrelated to OCR
   * routing and out of scope to fix here (it lives in stock, unrelated domain XML).
   *
   * <p>So this subclass overrides ONLY the DB lookup, substituting the already-persisted Partner
   * instance the test already holds a live reference to (no query, no formula column involved).
   * Every other real production code path — the bank-statement/PDF-split/classification decision in
   * {@code routeExtracted}, {@code checkDirectionOrReview}, {@code buildAndFinishInvoiceDraft} —
   * runs completely unmodified.
   */
  static class TestableIntakeService extends BgOcrIntakeService {
    private Partner supplierOverride;

    @Inject
    TestableIntakeService(
        OcrExtractorRouter extractor,
        AiOcrUploadRepository uploadRepo,
        InvoiceRepository invoiceRepo,
        PartnerRepository partnerRepo,
        TaxRepository taxRepo) {
      super(extractor, uploadRepo, invoiceRepo, partnerRepo, taxRepo);
    }

    void useSupplier(Partner partner) {
      this.supplierOverride = partner;
    }

    @Override
    protected SupplierMatch matchSupplier(OcrExtractResult result) {
      if (supplierOverride != null) {
        return new SupplierMatch(supplierOverride, null);
      }
      return super.matchSupplier(result);
    }

    /**
     * {@code docKey} != null makes {@code buildAndFinishInvoiceDraft} take an advisory-lock branch
     * ({@code lockDocKey}) that issues raw {@code SELECT pg_advisory_xact_lock(?, ?)} — a
     * Postgres-only function HSQLDB does not have. Same environment-limitation category as {@link
     * #matchSupplier}: forcing {@code null} here only skips the lock/semantic-key branch (each test
     * uses a fresh company+supplier, so there is nothing to deduplicate against anyway) and falls
     * through to the stock, unmodified duplicate check instead — still real production code.
     */
    @Override
    protected String docKey(OcrExtractResult result, Long companyId) {
      return null;
    }
  }

  private BgOcrIntakeService svc() {
    return Beans.get(BgOcrIntakeService.class);
  }

  private TestableIntakeService testableSvc() {
    return Beans.get(TestableIntakeService.class);
  }

  /**
   * ЕИК of the company these fixtures file their uploads under. It has to exist — {@code
   * checkDirectionOrReview} refuses to route an invoice for a company that carries no ЕИК/ДДС,
   * since nothing on the document could then confirm the company is its buyer. Deliberately
   * unrelated to every supplier fixture's ЕИК, so the company is always the buyer and never a party
   * on the vendor side.
   */
  private static final String OUR_EIK = "888000888";

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      Partner own = new Partner();
      own.setName(code);
      own.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
      own.setRegistrationCode(OUR_EIK);
      c.setPartner(JPA.save(own));
      c = JPA.save(c);
    }
    return c;
  }

  /** A Company fully configured to build a draft Move (manual misc-ops journal + open period). */
  private Company companyReadyForMoves(String code) {
    Company c = company(code);
    if (c.getAccountConfig() != null) {
      return c;
    }
    // Numeric literals, not the generated extra-code constants: JournalType/Year/Period's
    // <extra-code> constants are not reliably present on a src-gen tree that predates this test
    // (generateCode is only re-run for touched modules), so the symbols may not resolve.
    JournalType journalType = new JournalType();
    journalType.setName("Misc-" + code);
    journalType.setCode("MO-" + code);
    journalType.setTechnicalTypeSelect(5); // JournalType.TECHNICAL_TYPE_SELECT_OTHER
    journalType = JPA.save(journalType);

    Journal journal = new Journal();
    journal.setName("Manual misc ops " + code);
    journal.setCode("MISC-" + code);
    journal.setJournalType(journalType);
    journal = JPA.save(journal);

    Year year = new Year();
    year.setName("FY-" + code);
    year.setCode("FY-" + code);
    year.setCompany(c);
    year.setTypeSelect(1); // Year.TYPE_FISCAL
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    year.setStatusSelect(1); // Year.STATUS_OPENED
    year = JPA.save(year);

    Period period = new Period();
    period.setName("P-" + code);
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 1, 1));
    period.setToDate(LocalDate.of(2026, 12, 31));
    period.setStatusSelect(1); // Period.STATUS_OPENED
    JPA.save(period);

    AccountConfig config = new AccountConfig();
    config.setCompany(c);
    config.setManualMiscOpeJournal(journal);
    config = JPA.save(config);

    c.setAccountConfig(config);
    return JPA.save(c);
  }

  /**
   * Seed the outgoing bank-transfer payment mode OUT_WT, the default the router assigns to a
   * document whose payment method cannot be classified. In production it is seed data; a fresh test
   * DB does not have it, so without this the Move routing raises a configuration error. Idempotent.
   */
  private void ensureOutgoingWireTransferMode() {
    PaymentModeRepository repo = Beans.get(PaymentModeRepository.class);
    if (repo.all().filter("self.code = :code").bind("code", "OUT_WT").fetchOne() != null) {
      return;
    }
    PaymentMode mode = new PaymentMode();
    mode.setCode("OUT_WT");
    mode.setName("Изходящ банков превод");
    mode.setTypeSelect(PaymentModeRepository.TYPE_TRANSFER);
    mode.setInOutSelect(PaymentModeRepository.OUT);
    JPA.save(mode);
  }

  private Partner supplierPartner(String name, String eik) {
    Partner p = new Partner();
    p.setName(name);
    p.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
    p.setIsSupplier(true);
    p.setRegistrationCode(eik);
    return JPA.save(p);
  }

  private AiOcrUpload upload(Company c) {
    AiOcrUpload u = new AiOcrUpload();
    u.setCompany(c);
    u.setFileName("test-upload.pdf");
    return JPA.save(u);
  }

  /**
   * The real Invoice-creation chain ({@code InvoiceSupplychainRepository} → {@code
   * InvoiceManagementRepository} → {@code SequenceService}) unconditionally reads the global {@code
   * AppSale}/{@code AppBase} config singletons (installed because {@code AiOcrTestModule} pulls in
   * {@code SupplychainModule}/{@code BudgetModule}) — with no config row in this fresh test DB
   * those return {@code null} and NPE deep inside stock code unrelated to OCR routing. Neither
   * entity has a required field; one empty row each is all they need, seeded once per test class.
   */
  private void ensureAppConfigs() {
    if (Beans.get(com.axelor.studio.db.repo.AppSaleRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppSale());
    }
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppBase());
    }
  }

  private OcrExtractResult result(
      String rawText, String invoiceNo, String supplierName, String supplierVat, String gross) {
    OcrExtractResult r = new OcrExtractResult();
    r.setRawText(rawText);
    r.setInvoiceNumber(invoiceNo);
    r.setSupplierName(supplierName);
    r.setSupplierVat(supplierVat);
    if (gross != null) {
      BigDecimal amount = new BigDecimal(gross);
      r.setTotalGross(amount);
      r.setTotalBase(amount);
      r.setTotalVat(BigDecimal.ZERO);
      OcrExtractLine line = new OcrExtractLine();
      line.setDescription("Test line item");
      line.setNetAmount(amount);
      r.setLines(Collections.singletonList(line));
    } else {
      r.setLines(Collections.emptyList());
    }
    return r;
  }

  /**
   * Sanitized fixture mirroring the REAL ОББ Интерлийз premium-notice OCR structure: a two-column
   * issuer/recipient header block (~29 non-empty lines, an artifact of Azure reading a side-by-side
   * layout top-to-bottom per column), a bare "ИЗВЛЕЧЕНИЕ" title at line 30 with no "фактура"
   * anywhere, a single-item billing table, and — critically — a "ways to pay" footer paragraph
   * containing "...с платежно нареждане при ... - ЦУ,..." well past any reasonable heading window.
   * All names/numbers are fictitious.
   */
  private static final String PREMIUM_NOTICE_PATTERN =
      "01.01.26 г., 10:00\n"
          + "about:blank\n"
          + "ПРИМЕР\n"
          + "ЛИЗИНГ\n"
          + "Издател\n"
          + "Получател\n"
          + "Име:\n"
          + "ПРИМЕР ЛИЗИНГ ЕАД\n"
          + "Име:\n"
          + "ТЕСТОВ КЛИЕНТ ООД\n"
          + "Адрес:\n"
          + "София, бул. \"Примерна\" №1\n"
          + "Адрес:\n"
          + "София, ж.к. Тест, бл. 1, вх. А,\n"
          + "Идент. №\n"
          + "999000001\n"
          + "ет. 1, ап. 1\n"
          + "Идент. ДДС №\n"
          + "BG999000001\n"
          + "Идент. №\n"
          + "999000002\n"
          + "МОЛ:\n"
          + "Иван Иванов\n"
          + "Идент. ДДС №\n"
          + "BG999000002\n"
          + "МОЛ\n"
          + "Петър Петров\n"
          + "Вашият клиентски номер е: 0000001\n"
          + "Моля, посочете в платежното нареждане!\n"
          + "ИЗВЛЕЧЕНИЕ\n"
          + "№ 0000000001\n"
          + "Дата: 01.08.2026\n"
          + "Място на издаване: София\n"
          + "№\n"
          + "Основание\n"
          + "Валута\n"
          + "Сума\n"
          + "Курс\n"
          + "Стойност\n"
          + "1\n"
          + "Застрахователна премия за възстановяване КАСКО\n"
          + "EUR\n"
          + "100.00\n"
          + "1.00000\n"
          + "100.00\n"
          + "МПС 0000000000000000 - 1/4\n"
          + "приложение - 0000001А1\n"
          + "Падеж - 08.08.2026\n"
          + "Нетна сума:\n"
          + "100.00\n"
          + "Данъчна основа:\n"
          + "0.00\n"
          + "ДДС ставка (20%):\n"
          + "0.00\n"
          + "Брутна сума:\n"
          + "100.00 EUR\n"
          + "(195.58 лв.)\n"
          + "Словом: сто евро\n"
          + "Съставил: Тест Тестов\n"
          + "Шифър: ТТ001\n"
          + "Отг. С.О.\n"
          + "Иван Иванов\n"
          + "Шифър:\n"
          + "ПримерЛизинг\n"
          + "Уважаеми клиенти, можете да изберете една от следните възможности за плащане на\n"
          + "дължимите към „Пример Лизинг“ ЕАД суми:\n"
          + "- По банков път с платежно нареждане при Тест Банк - ЦУ,\n"
          + "BIC TESTBGSF,\n"
          + "IBAN BG00TEST00000000000000 (EUR)\n"
          + "- Чрез системата за електронни плащания еРау;\n"
          + "- На касите на EasyPay в цялата страна.\n"
          + "За повече информация можете да се свържете с нашите оператори на тел. 0800 00 000 или"
          + " да\n"
          + "посетите клиентския портал www.example.com\n"
          + "about:blank\n"
          + "1/1\n";

  @Test
  void premiumNoticePatternRoutesToInvoiceNotMove() throws Exception {
    inTransaction(
        () -> {
          try {
            Company c = company("RT-PREMIUM");
            Partner supplier = supplierPartner("Пример Лизинг ЕАД", "999000001");
            AiOcrUpload up = upload(c);
            OcrExtractResult r =
                result(
                    PREMIUM_NOTICE_PATTERN,
                    "0000000001",
                    "Пример Лизинг ЕАД",
                    "BG999000001",
                    "100.00");

            ensureAppConfigs();
            TestableIntakeService svc = testableSvc();
            svc.useSupplier(supplier);
            svc.routeExtracted(up.getId(), r);

            AiOcrUpload after =
                Beans.get(com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository.class)
                    .find(up.getId());
            assertNotNull(
                after.getInvoice(),
                "a premium-notice titled bare 'Извлечение', with no 'фактура' but with structured"
                    + " invoice evidence, must become an Invoice: "
                    + after.getExtractionErrors());
            assertNull(after.getMove(), "must not also be booked as a Move");
            assertNotNull(supplier, "supplier fixture sanity check");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  /**
   * Sanitized fixture mirroring the REAL Vivacom-style invoice: a genuine "ФАКТУРА" heading near
   * the top, and — many lines later, in a usage-detail appendix — the incidental sub-heading
   * "Извлечение по услуги" (a generic Bulgarian word meaning "extract/statement of services", not a
   * document title here).
   */
  private static String telecomInvoicePattern() {
    StringBuilder sb = new StringBuilder();
    sb.append("ТЕЛЕКОМ ПРИМЕР ЕАД\n");
    sb.append("бул. \"Примерен\" № 1, София\n");
    sb.append("ЕИК 999000003, ДДС № BG999000003\n");
    sb.append("ФАКТУРА №: 0000000002\n");
    sb.append("\"ТЕСТОВ КЛИЕНТ\" ООД\n");
    sb.append("Дата на издаване: 01.08.2026\n");
    for (int i = 0; i < 250; i++) {
      sb.append("Мобилни услуги ред ").append(i).append("\n");
    }
    sb.append("Извлечение по услуги\n");
    sb.append("3a 0000000000 Unlimited 200\n");
    return sb.toString();
  }

  @Test
  void telecomInvoicePatternRoutesToInvoiceNotMove() throws Exception {
    inTransaction(
        () -> {
          try {
            Company c = company("RT-TELECOM");
            Partner supplier = supplierPartner("Телеком Пример ЕАД", "999000003");
            AiOcrUpload up = upload(c);
            OcrExtractResult r =
                result(
                    telecomInvoicePattern(),
                    "0000000002",
                    "Телеком Пример ЕАД",
                    "BG999000003",
                    "63.86");

            ensureAppConfigs();
            TestableIntakeService svc = testableSvc();
            svc.useSupplier(supplier);
            svc.routeExtracted(up.getId(), r);

            AiOcrUpload after =
                Beans.get(com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository.class)
                    .find(up.getId());
            assertNotNull(
                after.getInvoice(),
                "a real invoice heading must win over an incidental deep-body 'Извлечение по"
                    + " услуги': "
                    + after.getExtractionErrors());
            assertNull(after.getMove());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void genuineBankStatementNeverBecomesInvoiceOrMove() throws Exception {
    String text =
        "ОТЧЕТ ПО СМЕТКА / ACCOUNT STATEMENT\n"
            + "IBAN BG93 TEST 0000 00 00000000  Currency EUR\n"
            + "01.01 Прехвърляне към Тестов Доставчик ФАКТУРА 0000000009\n"
            + "01.02 Такса за обслужване\n";
    inTransaction(
        () -> {
          try {
            Company c = company("RT-STATEMENT");
            AiOcrUpload up = upload(c);
            OcrExtractResult r = result(text, null, null, null, null);

            svc().routeExtracted(up.getId(), r);

            AiOcrUpload after =
                Beans.get(com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository.class)
                    .find(up.getId());
            assertNull(after.getInvoice(), "a genuine bank statement must never become an Invoice");
            assertNull(after.getMove(), "a genuine bank statement must never become a Move either");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void genuinePaymentOrderRoutesToMove() throws Exception {
    String text =
        "ПЛАТЕЖНО НАРЕЖДАНЕ\n"
            + "Наредител: Тестов Клиент ООД\n"
            + "Получател: Тестов Доставчик ЕООД\n"
            + "Сума: 100.00 EUR\n"
            + "Основание: доставка\n";
    inTransaction(
        () -> {
          try {
            Company c = companyReadyForMoves("RT-PAYORDER");
            ensureOutgoingWireTransferMode();
            AiOcrUpload up = upload(c);
            OcrExtractResult r = result(text, null, "Тестов Доставчик ЕООД", null, "100.00");

            svc().routeExtracted(up.getId(), r);

            AiOcrUpload after =
                Beans.get(com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository.class)
                    .find(up.getId());
            assertNotNull(
                after.getMove(),
                "a genuine payment order (title heading, no 'фактура') must be booked as a Move: "
                    + after.getExtractionErrors());
            assertNull(after.getInvoice());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }
}
