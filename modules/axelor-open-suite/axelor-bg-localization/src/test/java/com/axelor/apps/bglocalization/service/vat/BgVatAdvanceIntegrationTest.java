package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.workflow.cancel.WorkflowCancelServiceImpl;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentRefundState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateState;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveDueService;
import com.axelor.apps.account.service.move.MoveExcessPaymentService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.service.reconcile.ReconcileService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * АВАНСОВАТА ФАКТУРА СТИГА ДО ДНЕВНИКА — през истинските {@code MoveCreateService} и {@code
 * MoveLineCreateService} и през колектора.
 *
 * <p>Преди: авансовата фактура нямаше статия ({@code VentilateAdvancePaymentState}: „we don't
 * create the move“), а статията на плащането — данъчни редове, тоест ДДС-ът по ЗДДС чл. 25 ал. 7 не
 * стигаше нито до PRODAGBI, нито до DEKLAR.
 *
 * <p>Подменени са само местата, които HSQLDB или липсата на потребител не допускат: търсенето на
 * статията ({@code SELECT} върху {@code Move} — формулата {@code Move.ocrUploadIds}) и
 * осчетоводяването ({@code MoveManagementRepository} проверява периода срещу влезлия потребител).
 * Заявките са само ПРЕВЕДЕНИ — преводът хваща всяко сгрешено свойство.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgVatAdvanceIntegrationTest extends JpaSupport {

  private static final LocalDate JUNE_5 = LocalDate.of(2026, 6, 5);

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static Account account(Company company, String code, String name, int position) {
    Account a = new Account();
    a.setCode(code);
    a.setName(name);
    a.setCompany(company);
    a.setCommonPosition(position);
    return JPA.save(a);
  }

  private static void ensureAppConfigs() {
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
      appBase.setCheckExistingSequenceOnGeneration(false);
      JPA.save(appBase);
    }
    if (Beans.get(com.axelor.studio.db.repo.AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppAccount());
    }
  }

  /**
   * Дружество, готово за статиите на авансите: журнал, 412/4532/702 (продажби), 402/4531/601
   * (покупки), отворен период.
   */
  private static Company company(String tag) {
    ensureAppConfigs();
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("A" + tag);
    currency = JPA.save(currency);

    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode("A-" + tag);
    company.setCurrency(currency);
    company = JPA.save(company);

    JournalType type = new JournalType();
    type.setName("Продажби " + tag);
    type.setCode("SL-" + tag);
    type.setTechnicalTypeSelect(2); // TECHNICAL_TYPE_SELECT_SALE
    type = JPA.save(type);

    Account advance = account(company, "412", "Клиенти по аванси", 1);
    Account charged = account(company, "4532", "ДДС продажби", 2);
    Account income = account(company, "702", "Приходи от продажба на стоки", 2);
    Account supplierAdvance = account(company, "402", "Доставчици по аванси", 1);
    Account deductible = account(company, "4531", "ДДС покупки", 1);
    Account expense = account(company, "601", "Разходи за материали", 1);

    Journal journal = new Journal();
    journal.setName("Продажби " + tag);
    journal.setCode("SAL-" + tag);
    journal.setJournalType(type);
    journal.setCompany(company);
    journal.setValidAccountSet(
        new HashSet<>(
            Arrays.asList(advance, charged, income, supplierAdvance, deductible, expense)));
    journal = JPA.save(journal);

    Year year = new Year();
    year.setName("2026 " + tag);
    year.setCode("FA-" + tag);
    year.setCompany(company);
    year.setTypeSelect(1);
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    year.setStatusSelect(1);
    year = JPA.save(year);

    Period period = new Period();
    period.setName("2026 " + tag);
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 1, 1));
    period.setToDate(LocalDate.of(2026, 12, 31));
    period.setStatusSelect(1);
    JPA.save(period);

    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    config.setBgVatChargedAccount(charged);
    config.setAdvancePaymentAccount(advance);
    config.setBgVatDeductibleAccount(deductible);
    config.setSupplierAdvancePaymentAccount(supplierAdvance);
    config.setBgVatCorrectionJournal(journal);
    config = JPA.save(config);
    company.setAccountConfig(config);
    return JPA.save(company);
  }

  private static TaxLine vat20() {
    Tax existing = JPA.all(Tax.class).filter("self.code = ?", "BG_SRS_20").fetchOne();
    if (existing != null) {
      return existing.getActiveTaxLine();
    }
    Tax tax = new Tax();
    tax.setCode("BG_SRS_20");
    tax.setName("BG_SRS_20");
    tax = JPA.save(tax);
    TaxLine line = new TaxLine();
    line.setTax(tax);
    line.setValue(new BigDecimal("20"));
    line.setStartDate(LocalDate.of(2026, 1, 1));
    line = JPA.save(line);
    tax.setActiveTaxLine(line);
    JPA.save(tax);
    return line;
  }

  /** Авансова фактура 1000 + 200 ДДС от 05.06.2026 (или КИ към такава). */
  private static Invoice advance(Company company, int operationType, String number) {
    Partner client = new Partner();
    client.setName("Клиент " + number);
    client.setCompany(company);
    client = JPA.save(client);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setCurrency(company.getCurrency());
    invoice.setPartner(client);
    invoice.setOperationTypeSelect(operationType);
    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
    invoice.setInvoiceId(number);
    invoice.setInvoiceDate(JUNE_5);
    invoice.setJournal(company.getAccountConfig().getBgVatCorrectionJournal());
    invoice.setPartnerAccount(company.getAccountConfig().getAdvancePaymentAccount());
    invoice.setExTaxTotal(new BigDecimal("1000"));
    invoice.setTaxTotal(new BigDecimal("200"));
    invoice.setInTaxTotal(new BigDecimal("1200"));
    invoice.setCompanyExTaxTotal(new BigDecimal("1000"));
    invoice.setCompanyTaxTotal(new BigDecimal("200"));
    invoice.setCompanyInTaxTotal(new BigDecimal("1200"));
    InvoiceLineTax tax = new InvoiceLineTax();
    tax.setInvoice(invoice);
    tax.setTaxLine(vat20());
    tax.setExTaxBase(new BigDecimal("1000"));
    tax.setTaxTotal(new BigDecimal("200"));
    tax.setInTaxTotal(new BigDecimal("1200"));
    tax.setCompanyExTaxBase(new BigDecimal("1000"));
    tax.setCompanyTaxTotal(new BigDecimal("200"));
    tax.setCompanyInTaxTotal(new BigDecimal("1200"));
    invoice.setInvoiceLineTaxList(new ArrayList<>(Collections.singletonList(tax)));
    return JPA.save(invoice);
  }

  /**
   * Авансова фактура от доставчик 1000 + 200 от 05.06.2026 (или КИ към такава): ставката и един ред
   * с кредитния тип — колекторът чете покупките по редове.
   */
  private static Invoice purchaseAdvance(
      Company company, int operationType, String number, String creditType) {
    Invoice invoice = advance(company, operationType, number);
    invoice.setPartnerAccount(company.getAccountConfig().getSupplierAdvancePaymentAccount());
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductName("Аванс по договор");
    line.setQty(BigDecimal.ONE);
    line.setPrice(new BigDecimal("1000"));
    line.setExTaxTotal(new BigDecimal("1000"));
    line.setInTaxTotal(new BigDecimal("1200"));
    line.setCompanyExTaxTotal(new BigDecimal("1000"));
    line.setCompanyInTaxTotal(new BigDecimal("1200"));
    line.setTaxLineSet(new HashSet<>(Collections.singletonList(vat20())));
    line.setBgVatCreditType(creditType);
    invoice.setInvoiceLineList(new ArrayList<>(Collections.singletonList(line)));
    return JPA.save(invoice);
  }

  /**
   * Услугата с истинските {@code MoveCreateService} и {@code MoveLineCreateService}. Подменени:
   * търсенето на статията (SELECT върху Move не върви на HSQLDB) и осчетоводяването (иска влязъл
   * потребител) — статусът се слага направо.
   */
  private static BgVatAdvanceService service() {
    return new BgVatAdvanceService(
        Beans.get(MoveCreateService.class),
        Beans.get(MoveLineCreateService.class),
        Beans.get(MoveValidateService.class),
        Beans.get(MoveRepository.class),
        Beans.get(InvoiceRepository.class),
        Beans.get(AccountConfigService.class),
        Beans.get(ReconcileService.class),
        Beans.get(InvoiceService.class)) {
      @Override
      public Move vatMove(Invoice advance) {
        return null;
      }

      @Override
      protected void account(Move move) {
        move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
      }
    };
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " срещу " + actual);
  }

  private static BigDecimal side(Move move, String code, boolean debit) {
    return move.getMoveLineList().stream()
        .filter(l -> code.equals(l.getAccount().getCode()))
        .map(l -> debit ? l.getDebit() : l.getCredit())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** {@link BgVatDocumentCollector#collect} над статията — подменен е само обхватът на заявката. */
  private static List<BgVatDocumentDto> collect(Move posted) {
    BgVatDocumentCollector collector =
        new BgVatDocumentCollector(Beans.get(MoveRepository.class)) {
          @Override
          protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
            return Collections.singletonList(posted);
          }
        };
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(posted.getCompany().getId());
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    return tx(() -> collector.collect(request));
  }

  // ---------------------------------------------------------------------------
  // Случаите
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Окабеляването: трите състояния и MoveExcessPaymentService са на BG")
  void окабеляването() {
    assertTrue(Beans.get(VentilateState.class) instanceof BgVentilateState);
    assertTrue(
        Beans.get(VentilateAdvancePaymentState.class) instanceof BgVentilateAdvancePaymentState);
    assertTrue(
        Beans.get(VentilateAdvancePaymentRefundState.class)
            instanceof BgVentilateAdvancePaymentRefundState);
    assertTrue(Beans.get(MoveExcessPaymentService.class) instanceof BgMoveExcessPaymentService);
    assertTrue(Beans.get(MoveDueService.class) instanceof BgMoveDueService);
  }

  @Test
  @DisplayName("Пазачът на анулирането хваща afterCancel(Invoice) на всеки наследник")
  void пазачът_на_анулирането() throws Exception {
    assertTrue(BgVatAdvanceCancelGuard.classes().matches(WorkflowCancelServiceImpl.class));
    Method afterCancel = WorkflowCancelServiceImpl.class.getMethod("afterCancel", Invoice.class);
    assertTrue(BgVatAdvanceCancelGuard.methods().matches(afterCancel));
    Method beforeCancel = WorkflowCancelServiceImpl.class.getMethod("beforeCancel", Invoice.class);
    assertTrue(!BgVatAdvanceCancelGuard.methods().matches(beforeCancel));
  }

  @Test
  @DisplayName(
      "Авансова 1000 + 200 от 05.06: статия Дт 412 / Кт 4532 200, и PRODAGBI юни — 1000 / 200")
  void авансовата_стига_до_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("ADV1");
              Invoice invoice =
                  advance(company, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE, "0000000123");
              try {
                return service().createVatMove(invoice);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    assertNotNull(move, "статия не е съставена");
    assertEquals(JUNE_5, move.getDate());
    assertEquals("0000000123", move.getInvoice().getInvoiceId());
    assertEquals(MoveRepository.FUNCTIONAL_ORIGIN_SALE, move.getFunctionalOriginSelect());
    eq("200", side(move, "412", true));
    eq("200", side(move, "4532", false));
    eq("0", side(move, "412", false));
    assertNotNull(
        move.getMoveLineList().stream()
            .filter(l -> "412".equals(l.getAccount().getCode()))
            .findFirst()
            .get()
            .getPartner(),
        "редът по 412 е без контрагент");

    List<BgVatDocumentDto> documents = collect(move);
    assertEquals(1, documents.size());
    BgVatDocumentDto document = documents.get(0);
    assertEquals("0000000123", document.getDocumentNumber());
    assertEquals(JUNE_5, document.getDocumentDate());
    assertTrue(document.isAdvancePayment());
    BgVatSalesRowDto row = new BgVatSalesAdapter().buildRows(documents, null).get(0);
    eq("1000", row.getAmount(BgVatSalesColumn.K09_TOTAL_BASE));
    eq("200", row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT));
  }

  @Test
  @DisplayName("КИ към авансова: обратната статия — Дт 4532 / Кт 412 200")
  void кредитното_известие() {
    Move move =
        tx(
            () -> {
              Company company = company("ADV2");
              Invoice refund =
                  advance(company, InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND, "0000000124");
              try {
                return service().createVatMove(refund);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    eq("200", side(move, "4532", true));
    eq("200", side(move, "412", false));
  }

  @Test
  @DisplayName("Авансова без ДДС (ВОД, 0 %) — статия няма")
  void без_ддс_няма_статия() {
    Move move =
        tx(
            () -> {
              Company company = company("ADV3");
              Invoice invoice =
                  advance(company, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE, "0000000125");
              invoice.getInvoiceLineTaxList().get(0).setTaxTotal(BigDecimal.ZERO);
              invoice.getInvoiceLineTaxList().get(0).setCompanyTaxTotal(BigDecimal.ZERO);
              try {
                return service().createVatMove(invoice);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertNull(move);
  }

  @Test
  @DisplayName("Окончателна със сума 0: статия Дт 412 / Кт 702 3000, закачена към фактурата")
  void нулевата_окончателна() {
    Map<String, Object> out =
        tx(
            () -> {
              Company company = company("ADV4");
              Invoice adv =
                  advance(company, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE, "0000000126");
              Invoice fin = new Invoice();
              fin.setCompany(company);
              fin.setCurrency(company.getCurrency());
              fin.setPartner(adv.getPartner());
              fin.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
              fin.setInvoiceId("0000000300");
              fin.setInvoiceDate(LocalDate.of(2026, 7, 10));
              fin.setJournal(adv.getJournal());
              fin.setInTaxTotal(BigDecimal.ZERO);
              fin.setAdvancePaymentInvoiceSet(new HashSet<>(Collections.singletonList(adv)));
              Account a702 =
                  JPA.all(Account.class)
                      .filter("self.code = '702' AND self.company = ?", company)
                      .fetchOne();
              Account a412 = company.getAccountConfig().getAdvancePaymentAccount();
              fin.setInvoiceLineList(
                  new ArrayList<>(
                      Arrays.asList(
                          line(fin, "Стока", "3000", a702),
                          deduction(fin, adv, "Advance deduction (edited)", "-3000", a412))));
              fin = JPA.save(fin);
              BgVatAdvanceService service = service();
              try {
                assertTrue(service.needsZeroFinalMove(fin));
                Move move = service.createZeroFinalMove(fin);
                return Map.of("move", move, "invoice", fin);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    Move move = (Move) out.get("move");
    Invoice fin = (Invoice) out.get("invoice");
    eq("3000", side(move, "412", true));
    eq("3000", side(move, "702", false));
    assertEquals(2, move.getMoveLineList().size(), "без редове за ДДС — той е начислен с аванса");
    assertEquals(move, fin.getMove());
    assertEquals(fin, move.getInvoice());
  }

  @Test
  @DisplayName(
      "Авансова от доставчик 1000 + 200 от 05.06: Дт 4531 / Кт 402 200, и POKUPKI юни — к.10 1000 / к.11 200")
  void авансовата_от_доставчик_стига_до_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("ADP1");
              Invoice invoice =
                  purchaseAdvance(
                      company, InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE, "П-0001", "1");
              try {
                return service().createVatMove(invoice);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    assertNotNull(move, "статия не е съставена");
    assertEquals(JUNE_5, move.getDate());
    assertEquals(MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE, move.getFunctionalOriginSelect());
    eq("200", side(move, "4531", true));
    eq("200", side(move, "402", false));
    eq("0", side(move, "402", true));

    List<BgVatDocumentDto> documents = collect(move);
    assertEquals(1, documents.size());
    assertTrue(documents.get(0).isAdvancePayment());
    // Сумите на покупката са в документа (адаптерът ги чете оттам при експорта).
    BgVatDocumentDto document = documents.get(0);
    eq("1000", document.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("200", document.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  @Test
  @DisplayName("КИ към авансова от доставчик: обратната статия — Дт 402 / Кт 4531 200")
  void кредитното_известие_от_доставчик() {
    Move move =
        tx(
            () -> {
              Company company = company("ADP2");
              Invoice invoice =
                  purchaseAdvance(
                      company, InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND, "П-0002", "1");
              try {
                return service().createVatMove(invoice);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    eq("200", side(move, "402", true));
    eq("200", side(move, "4531", false));
  }

  @Test
  @DisplayName("Окончателна от доставчик със сума 0: Дт 601 / Кт 402 3000")
  void нулевата_окончателна_от_доставчик() {
    Move move =
        tx(
            () -> {
              Company company = company("ADP3");
              Invoice adv =
                  purchaseAdvance(
                      company, InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE, "П-0003", "1");
              Invoice fin = new Invoice();
              fin.setCompany(company);
              fin.setCurrency(company.getCurrency());
              fin.setPartner(adv.getPartner());
              fin.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
              fin.setInvoiceId("П-0300");
              fin.setInvoiceDate(LocalDate.of(2026, 7, 10));
              fin.setJournal(adv.getJournal());
              fin.setInTaxTotal(BigDecimal.ZERO);
              Account a601 =
                  JPA.all(Account.class)
                      .filter("self.code = '601' AND self.company = ?", company)
                      .fetchOne();
              Account a402 = company.getAccountConfig().getSupplierAdvancePaymentAccount();
              fin.setInvoiceLineList(
                  new ArrayList<>(
                      Arrays.asList(
                          line(fin, "Материали", "3000", a601),
                          deduction(fin, adv, "Приспадане", "-3000", a402))));
              fin = JPA.save(fin);
              BgVatAdvanceService service = service();
              try {
                assertTrue(service.needsZeroFinalMove(fin));
                return service.createZeroFinalMove(fin);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    eq("3000", side(move, "601", true));
    eq("3000", side(move, "402", false));
    assertEquals(MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE, move.getFunctionalOriginSelect());
  }

  /** Ред за приспадане, какъвто го слага бутонът: по полетата, текстът е какъвто и да е. */
  private static InvoiceLine deduction(
      Invoice invoice, Invoice advance, String name, String base, Account account) {
    InvoiceLine line = line(invoice, name, base, account);
    line.setBgAdvanceDeduction(true);
    line.setBgAdvanceInvoice(advance);
    return line;
  }

  @Test
  @DisplayName(
      "generateMoveForInvoicePayment = false: авансова с ДДС — отказ, преди да се състави статия")
  void без_статия_при_плащане() {
    AxelorException refused =
        tx(
            () -> {
              Company company = company("ADV5");
              company.getAccountConfig().setGenerateMoveForInvoicePayment(false);
              Invoice adv =
                  advance(company, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE, "0000000127");
              BgVatAdvanceService service = service();
              AxelorException e =
                  assertThrows(AxelorException.class, () -> service.requirePaymentMoves(adv));
              assertThrows(AxelorException.class, () -> service.createVatMove(adv));
              company.getAccountConfig().setGenerateMoveForInvoicePayment(true);
              try {
                service.requirePaymentMoves(adv);
              } catch (AxelorException unexpected) {
                throw new IllegalStateException("с включена настройка — без отказ", unexpected);
              }
              return e;
            });
    assertEquals(TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, refused.getCategory());
  }

  private static InvoiceLine line(Invoice invoice, String name, String base, Account account) {
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductName(name);
    line.setQty(BigDecimal.ONE);
    line.setPrice(new BigDecimal(base));
    line.setExTaxTotal(new BigDecimal(base));
    line.setCompanyExTaxTotal(new BigDecimal(base));
    line.setTaxLineSet(new HashSet<>(Collections.singletonList(vat20())));
    line.setAccount(account);
    return line;
  }

  @Test
  @DisplayName("Заявките на услугата и справката — преведени (свойствата съществуват)")
  void заявките() throws Exception {
    String view =
        new String(
            Files.readAllBytes(
                BgDomainColumns.modulesRoot()
                    .resolve(
                        "axelor-open-suite/axelor-bg-localization/src/main/resources/views/Invoice-bg.xml")),
            StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile(
                "name=\"action-bg-vat-advance-without-vat-move\".*?<domain>(.*?)</domain>",
                Pattern.DOTALL)
            .matcher(view);
    assertTrue(m.find(), "справката няма домейн");
    String reportDomain = m.group(1).replace(":_company", ":company");

    tx(
        () -> {
          JPA.em()
              .createQuery(
                  "SELECT self.id FROM Move self WHERE " + BgVatAdvanceService.VAT_MOVE,
                  Long.class);
          for (String filter :
              List.of(
                  BgVatAdvanceService.WITHOUT_VAT_MOVE,
                  BgVatAdvanceService.OPEN_ADVANCES,
                  BgVatAdvanceService.PERIOD_FINALS,
                  BgVatAdvanceService.PERIOD_PURCHASE_ADVANCES,
                  reportDomain)) {
            JPA.em().createQuery("SELECT self.id FROM Invoice self WHERE " + filter, Long.class);
          }
          return null;
        });
    // Справката и услугата питат едно и също.
    assertEquals(
        normalize(BgVatAdvanceService.WITHOUT_VAT_MOVE),
        normalize(reportDomain.replace("!=", "<>")));
  }

  private static String normalize(String hql) {
    return Arrays.stream(hql.split("\\s+"))
        .filter(s -> !s.isEmpty())
        .collect(Collectors.joining(" "));
  }
}
