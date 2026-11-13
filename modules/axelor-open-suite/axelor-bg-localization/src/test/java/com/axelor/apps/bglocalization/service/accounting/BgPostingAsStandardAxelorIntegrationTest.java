package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.web.InvoiceController;
import com.axelor.apps.account.web.InvoicePaymentController;
import com.axelor.apps.account.web.MoveController;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.module.BgStockWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.nyxasecurity.service.DaybookOffMigration;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ОСЧЕТОВОДЯВАНЕТО Е КАТО В СТАНДАРТНИЯ AXELOR: без два етапа и без G1.
 *
 * <ul>
 *   <li>Статия, валидирана от потребител с {@code accounting.write} → ACCOUNTED.
 *   <li>С {@code accounting.read} → отказ от G13 ({@code ManualValidationGuard}): няма право на
 *       запис върху Move, а платформата проверява само четене преди action. И за вече записана
 *       чернова с произход IMPORT, осчетоводена по-късно през {@code /ws/action}.
 *   <li>Статия, която модул създава и осчетоводява в същия поток (AUTOMATIC), минава САМО с WRITE
 *       върху документа-източник: {@code invoicing.read} не вентилира фактура и не регистрира
 *       плащане през {@code /ws/action}, {@code invoicing.write} — да; {@code stock.write}
 *       осчетоводява изписване на складово движение, {@code stock.read} — не.
 *   <li>Служебният {@code nyxa-auto-posting} осчетоводява само изписването на склада в складовия
 *       дневник на дружеството; ръчна статия, фактура или друг дневник — отказ.
 *   <li>Без потребител (Quartz, boot) G13 не проверява нищо.
 *   <li>AI инструмент не може да осчетоводи окончателно, дори за {@code accounting.write}.
 *   <li>Миграцията изключва daybook веднъж; второ пускане не прави нищо.
 * </ul>
 *
 * <p>⚠️ HSQLDB няма {@code array_to_string}, а PostgreSQL формулата {@code Move.ocrUploadIds} го
 * вика при ВСЯКО четене на Move („unexpected token: SELECT“). {@link #hsqldbArrayToString} я
 * дефинира в тестовата база, за да може статията да се зареди във втора заявка, както в {@code
 * /ws/action}. Статусът се чете с native SQL.
 *
 * <p>⚠️ Записаната чернова се осчетоводява в НОВА транзакция след тази, която я е записала — тоест
 * тя е в базата като NEW преди заявката, което е и условието на G13. Хибернейт обаче не я чете
 * наново, а ползва заредения обект: редовете ѝ носят {@code InvoiceTerm.paymentDelay}, формула с
 * {@code ::DATE}, която HSQLDB не разбира. Затова входът е ПРЕДИ черновата — вносът на ролята от
 * каталога изчиства сесията.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgStockWiringTestModule.class)
@Tag("integration")
class BgPostingAsStandardAxelorIntegrationTest extends JpaSupport {

  private static final LocalDate DATE = LocalDate.of(2026, 3, 10);
  private static final AtomicLong TAGS = new AtomicLong();

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
    com.axelor.apps.account.service.AccountingService.setUpdateCustomerAccount(true);
  }

  /**
   * Салдата по клиентската сметка се преизчисляват с native заявка, която HSQLDB не приема
   * („incompatible data types“); тестът не е за тях. Същият превключвател ползват batch-овете.
   */
  @BeforeEach
  void noCustomerAccountBalances() {
    com.axelor.apps.account.service.AccountingService.setUpdateCustomerAccount(false);
  }

  /**
   * Track-съобщението на Invoice при вентилиране чете {@code __config__.date}, а описанието на
   * плащането — {@code application.locale}; тестовете на модула нямат axelor-config.properties.
   * Същите стойности като в src/main/resources/axelor-config.properties. Задава се преди първото
   * {@code __config__} — то се кешира веднъж за JVM.
   */
  @BeforeEach
  void configDate() {
    java.util.Map<String, String> settings =
        com.axelor.app.AppSettings.get().getInternalProperties();
    settings.putIfAbsent("context.date", "com.axelor.apps.base.service.DateService:date");
    settings.putIfAbsent("application.locale", "en"); // формат на датата в описанието на плащане
  }

  /** {@code array_to_string(bigint[], text)} на PostgreSQL — само за тестовата HSQLDB. */
  @BeforeEach
  void hsqldbArrayToString() {
    JPA.runInTransaction(
        () -> {
          Number present =
              (Number)
                  JPA.em()
                      .createNativeQuery(
                          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.ROUTINES"
                              + " WHERE ROUTINE_NAME = 'ARRAY_TO_STRING'")
                      .getSingleResult();
          if (present.intValue() == 0) {
            JPA.em()
                .createNativeQuery(
                    "CREATE FUNCTION array_to_string(a BIGINT ARRAY, d VARCHAR(16))"
                        + " RETURNS VARCHAR(16000) BEGIN ATOMIC"
                        + " DECLARE r VARCHAR(16000) DEFAULT NULL; DECLARE i INT DEFAULT 1;"
                        + " WHILE i <= CARDINALITY(a) DO"
                        + " SET r = CASE WHEN r IS NULL THEN CAST(a[i] AS VARCHAR(32))"
                        + " ELSE r || d || CAST(a[i] AS VARCHAR(32)) END; SET i = i + 1;"
                        + " END WHILE; RETURN r; END")
                .executeUpdate();
          }
        });
  }

  /** Дружество, две сметки, журнал с поредица и отворен период; daybook изключен. */
  private static Company company() {
    String tag = "P" + TAGS.incrementAndGet();
    Company[] out = new Company[1];
    JPA.runInTransaction(
        () -> {
          if (JPA.all(com.axelor.studio.db.AppBase.class).fetchOne() == null) {
            com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
            appBase.setCheckExistingSequenceOnGeneration(false);
            JPA.save(appBase);
          }
          if (JPA.all(com.axelor.studio.db.AppAccount.class).fetchOne() == null) {
            JPA.save(new com.axelor.studio.db.AppAccount());
          }
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("V" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("Осчетоводяване " + tag);
          company.setCode("C-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);

          Account stock = account(company, "304", 1, "currentAsset");
          Account sale = account(company, "702", 2, "income");

          JournalType type = new JournalType();
          type.setName("Други " + tag);
          type.setCode("OTH-" + tag);
          type.setTechnicalTypeSelect(5);
          type = JPA.save(type);
          Journal journal = new Journal();
          journal.setName("Мемориален " + tag);
          journal.setCode("MEM-" + tag);
          journal.setJournalType(type);
          journal.setCompany(company);
          journal.setStatusSelect(1);
          journal.setValidAccountSet(new HashSet<>(Arrays.asList(stock, sale)));
          Sequence sequence = new Sequence();
          sequence.setCompany(company);
          sequence.setName("Мемориален " + tag);
          sequence.setCodeSelect("move");
          sequence.setPrefixe("MEM" + tag);
          sequence.setPadding(5);
          sequence.setToBeAdded(1);
          SequenceVersion version = new SequenceVersion();
          version.setStartDate(LocalDate.of(2000, 1, 1));
          version.setNextNum(1L);
          sequence.addSequenceVersionListItem(version);
          journal.setSequence(JPA.save(sequence));
          JPA.save(journal);

          AccountConfig config = new AccountConfig();
          config.setCompany(company);
          config.setAccountingDaybook(false);
          company.setAccountConfig(JPA.save(config));

          Year year = new Year();
          year.setName("2026 " + tag);
          year.setCode("FY-" + tag);
          year.setCompany(company);
          year.setTypeSelect(1);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setStatusSelect(1);
          year = JPA.save(year);
          Period period = new Period();
          period.setName("03/2026 " + tag);
          period.setYear(year);
          period.setFromDate(LocalDate.of(2026, 3, 1));
          period.setToDate(LocalDate.of(2026, 3, 31));
          period.setStatusSelect(1);
          JPA.save(period);
          out[0] = JPA.save(company);
        });
    return out[0];
  }

  private static Account account(Company company, String code, int position, String technical) {
    AccountType type =
        JPA.all(AccountType.class).filter("self.technicalTypeSelect = ?1", technical).fetchOne();
    if (type == null) {
      type = new AccountType();
      type.setName(technical);
      type.setTechnicalTypeSelect(technical);
      type = JPA.save(type);
    }
    Account a = new Account();
    a.setAccountType(type);
    a.setCode(code);
    a.setName(code);
    a.setCompany(company);
    a.setCommonPosition(position);
    a.setStatusSelect(1);
    return JPA.save(a);
  }

  /**
   * Статия, създадена и валидирана в една транзакция: с произход ENTRY — като масовото въвеждане; с
   * AUTOMATIC — като модул (склад, фактура, плащане).
   */
  private static void enterAndValidate(Company company, int origin) {
    JPA.runInTransaction(
        () -> {
          Move move = draftMove(company, origin);
          try {
            Beans.get(MoveValidateService.class).accounting(move);
          } catch (com.axelor.apps.base.AxelorException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** Чернова (NEW), записана в своя транзакция — като внос от Microinvest/CSV. @return id */
  private static Long savedDraft(Company company, int origin) {
    Long[] id = new Long[1];
    JPA.runInTransaction(() -> id[0] = draftMove(company, origin).getId());
    return id[0];
  }

  /** Осчетоводяване като през {@code /ws/action}: платформата проверява само READ, после това. */
  private static ActionResponse accountViaAction(Long moveId) {
    ActionRequest request = new ActionRequest();
    request.setModel(Move.class.getName());
    Map<String, Object> context = new HashMap<>();
    context.put("_model", Move.class.getName());
    context.put("id", moveId);
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    request.setData(data);
    ActionResponse response = new ActionResponse();
    Beans.get(MoveController.class).accounting(request, response);
    return response;
  }

  private static Move draftMove(Company company, int origin) {
    try {
      Company c = JPA.find(Company.class, company.getId());
      Journal journal = JPA.all(Journal.class).filter("self.company = ?1", c).fetchOne();
      List<Account> accounts =
          JPA.all(Account.class).filter("self.company = ?1", c).order("code").fetch();
      Move move =
          Beans.get(MoveCreateService.class)
              .createMove(
                  journal,
                  c,
                  c.getCurrency(),
                  null,
                  DATE,
                  DATE,
                  null,
                  null,
                  origin,
                  0,
                  "РЪЧНА",
                  "Ръчна статия",
                  null);
      BigDecimal amount = new BigDecimal("100.00");
      MoveLineCreateService lines = Beans.get(MoveLineCreateService.class);
      move.addMoveLineListItem(
          lines.createMoveLine(move, null, accounts.get(0), amount, true, DATE, 1, "РЪЧНА", "Дт"));
      move.addMoveLineListItem(
          lines.createMoveLine(move, null, accounts.get(1), amount, false, DATE, 2, "РЪЧНА", "Кт"));
      return Beans.get(MoveRepository.class).save(move);
    } catch (com.axelor.apps.base.AxelorException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Статусите на статиите на дружеството, през native SQL (HSQLDB не чете Move). */
  @SuppressWarnings("unchecked")
  private static List<Number> statuses(Company company) {
    return JPA.em()
        .createNativeQuery("SELECT status_select FROM account_move WHERE company = ?1")
        .setParameter(1, company.getId())
        .getResultList();
  }

  @Test
  void accountingWriteValidatesToAccounted() {
    Company company = company();
    BgRoleLogin.login(company, "accounting.write");

    enterAndValidate(company, MoveRepository.TECHNICAL_ORIGIN_ENTRY);

    List<Number> statuses = statuses(company);
    assertEquals(1, statuses.size(), "една статия");
    assertEquals(
        MoveRepository.STATUS_ACCOUNTED, statuses.get(0).intValue(), "ACCOUNTED, не DAYBOOK");
  }

  @Test
  void accountingReadIsRefused() {
    Company company = company();
    BgRoleLogin.login(company, "accounting.read");

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () -> enterAndValidate(company, MoveRepository.TECHNICAL_ORIGIN_ENTRY));

    assertTrue(
        String.valueOf(rootMessage(refused)).contains("write access to accounting entries"),
        "отказът е от G13: " + rootMessage(refused));
    assertTrue(statuses(company).isEmpty(), "транзакцията е върната — статия няма");
  }

  @Test
  void accountingReadCannotPostASavedImportedDraftThroughWsAction() {
    Company company = company();
    BgRoleLogin.login(company, "accounting.read");
    Long draft = savedDraft(company, MoveRepository.TECHNICAL_ORIGIN_IMPORT);

    ActionResponse response = accountViaAction(draft);

    assertTrue(
        String.valueOf(response.getData()).contains("write access to accounting entries"),
        "отказът е от G13: " + response.getData());
    assertEquals(List.of(MoveRepository.STATUS_NEW), ints(statuses(company)), "остава чернова");
  }

  @Test
  void accountingWritePostsASavedImportedDraftThroughWsAction() {
    Company company = company();
    BgRoleLogin.login(company, "accounting.write");
    Long draft = savedDraft(company, MoveRepository.TECHNICAL_ORIGIN_IMPORT);

    ActionResponse response = accountViaAction(draft);

    assertEquals(
        List.of(MoveRepository.STATUS_ACCOUNTED),
        ints(statuses(company)),
        "ACCOUNTED: " + response.getData());
  }

  @Test
  void stockWritePostsTheStockIssueOfItsOwnFlow() {
    Company company = company();
    BgRoleLogin.login(company, "stock.write");

    stockIssueOfItsOwnFlow(company);

    assertEquals(List.of(MoveRepository.STATUS_ACCOUNTED), ints(statuses(company)));
  }

  @Test
  void stockWriteCannotPostASavedAutomaticDraftLater() {
    Company company = company();
    BgRoleLogin.login(company, "stock.write");
    Long draft = savedDraft(company, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC);

    ActionResponse response = accountViaAction(draft);

    assertTrue(
        String.valueOf(response.getData()).contains("write access to accounting entries"),
        "вече записаната чернова се проверява и при AUTOMATIC: " + response.getData());
    assertEquals(List.of(MoveRepository.STATUS_NEW), ints(statuses(company)));
  }

  // ─── Документ-източник: фактура, плащане, складово движение ─────────────────────────────

  /**
   * Вносът на роля от каталога изчиства сесията, а фактура в HSQLDB не се чете наново ({@code
   * Invoice.paymentDelay} е с {@code ::DATE}). Затова ролята се внася ПРЕДИ фикстурите.
   */
  private static void warm(Company company, String role) {
    BgRoleLogin.login(company, role);
    BgRoleLogin.logout();
  }

  /**
   * Валидирана (не вентилирана) фактура за продажба на 100, без ДДС, с един срок; дружеството
   * получава журнал за продажби, клиентска сметка 411, поредица на фактурите и начин на плащане със
   * сметка 503 в същия журнал. Записва се БЕЗ влязъл потребител. @return id на фактурата
   */
  private static Long validatedInvoice(Company company) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          // Настройките на приложенията, които вентилирането и плащането четат.
          for (Class<? extends com.axelor.db.Model> app :
              List.<Class<? extends com.axelor.db.Model>>of(
                  com.axelor.studio.db.AppBankPayment.class,
                  com.axelor.studio.db.AppSale.class,
                  com.axelor.studio.db.AppSupplychain.class,
                  com.axelor.studio.db.AppInvoice.class,
                  com.axelor.studio.db.AppBudget.class)) {
            if (JPA.all(app).fetchOne() == null) {
              try {
                JPA.save(app.getDeclaredConstructor().newInstance());
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
              }
            }
          }
          // Без PDF при вентилиране: тестовата база няма печатни форми.
          JPA.all(com.axelor.studio.db.AppInvoice.class)
              .fetchOne()
              .setAutoGenerateInvoicePrintingFileOnSaleInvoice(false);
          Company c = JPA.find(Company.class, company.getId());
          String tag = c.getCode();
          Journal journal = JPA.all(Journal.class).filter("self.company = ?1", c).fetchOne();
          Account sale =
              JPA.all(Account.class)
                  .filter("self.company = ?1 AND self.code = '702'", c)
                  .fetchOne();
          Account customer = account(c, "411", 1, "receivable");
          customer.setReconcileOk(true);
          customer.setUseForPartnerBalance(true);
          Account bank = account(c, "503", 1, "cash");
          journal.getValidAccountSet().add(customer);
          journal.getValidAccountSet().add(bank);

          AccountConfig config = c.getAccountConfig();
          config.setCustomerSalesJournal(journal);
          config.setCustomerAccount(customer);
          Sequence sequence = new Sequence();
          sequence.setCompany(c);
          sequence.setName("Фактури " + tag);
          sequence.setCodeSelect("custInvSeq");
          sequence.setPrefixe("F" + tag);
          sequence.setPadding(5);
          sequence.setToBeAdded(1);
          SequenceVersion version = new SequenceVersion();
          version.setStartDate(LocalDate.of(2000, 1, 1));
          version.setNextNum(1L);
          sequence.addSequenceVersionListItem(version);
          config.setCustInvSequence(JPA.save(sequence));

          // Равняването на плащането с фактурата иска свои поредици.
          for (String code : List.of("reconcile", "reconcileGroupDraft", "reconcileGroupFinal")) {
            Sequence reconcile = new Sequence();
            reconcile.setCompany(c);
            reconcile.setName(code + " " + tag);
            reconcile.setCodeSelect(code);
            reconcile.setPrefixe(code.substring(0, 3).toUpperCase() + tag);
            reconcile.setPadding(5);
            reconcile.setToBeAdded(1);
            SequenceVersion reconcileVersion = new SequenceVersion();
            reconcileVersion.setStartDate(LocalDate.of(2000, 1, 1));
            reconcileVersion.setNextNum(1L);
            reconcile.addSequenceVersionListItem(reconcileVersion);
            JPA.save(reconcile);
          }

          PaymentCondition condition = new PaymentCondition();
          condition.setCode("PC-" + tag);
          condition.setName("На място " + tag);
          condition.setIsFree(true);
          com.axelor.apps.account.db.PaymentConditionLine conditionLine =
              new com.axelor.apps.account.db.PaymentConditionLine();
          conditionLine.setSequence(1);
          conditionLine.setTypeSelect(1);
          conditionLine.setPeriodTypeSelect(1);
          conditionLine.setPaymentTime(0);
          conditionLine.setPaymentPercentage(new BigDecimal("100"));
          condition.addPaymentConditionLineListItem(conditionLine);
          condition = JPA.save(condition);
          PaymentMode mode = new PaymentMode();
          mode.setCode("PM-" + tag);
          mode.setName("Банка " + tag);
          mode.setTypeSelect(com.axelor.apps.account.db.repo.PaymentModeRepository.TYPE_TRANSFER);
          mode.setInOutSelect(1);
          mode = JPA.save(mode);
          AccountManagement management = new AccountManagement();
          management.setCompany(c);
          management.setPaymentMode(mode);
          management.setJournal(journal);
          management.setCashAccount(bank);
          management.setTypeSelect(
              com.axelor.apps.account.db.repo.AccountManagementRepository.TYPE_PAYMENT);
          mode.addAccountManagementListItem(management);
          JPA.save(mode);

          Partner partner = new Partner();
          partner.setName("Клиент " + tag);
          partner.setIsCustomer(true);
          partner.setPartnerTypeSelect(1);
          partner.setCurrency(c.getCurrency());
          partner.setCompany(c);
          partner = JPA.save(partner);

          BigDecimal amount = new BigDecimal("100.00");
          Invoice invoice = new Invoice();
          invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
          invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
          invoice.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
          invoice.setCompany(c);
          invoice.setPartner(partner);
          invoice.setCurrency(c.getCurrency());
          invoice.setPaymentCondition(condition);
          invoice.setPaymentMode(mode);
          invoice.setInvoiceDate(DATE);
          invoice.setDueDate(DATE);
          invoice.setExTaxTotal(amount);
          invoice.setTaxTotal(BigDecimal.ZERO);
          invoice.setInTaxTotal(amount);
          invoice.setCompanyExTaxTotal(amount);
          invoice.setCompanyTaxTotal(BigDecimal.ZERO);
          invoice.setCompanyInTaxTotal(amount);
          invoice.setAmountRemaining(amount);
          invoice.setCompanyInTaxTotalRemaining(amount);
          invoice.setInvoiceLineTaxList(new java.util.ArrayList<>()); // без ДДС
          InvoiceLine line = new InvoiceLine();
          line.setProductName("Услуга");
          line.setAccount(sale);
          line.setQty(BigDecimal.ONE);
          line.setPrice(amount);
          line.setPriceDiscounted(amount);
          line.setExTaxTotal(amount);
          line.setInTaxTotal(amount);
          line.setCompanyExTaxTotal(amount);
          line.setCompanyInTaxTotal(amount);
          invoice.addInvoiceLineListItem(line);
          InvoiceTerm term = new InvoiceTerm();
          term.setSequence(1);
          term.setPercentage(new BigDecimal("100"));
          term.setAmount(amount);
          term.setAmountRemaining(amount);
          term.setCompanyAmount(amount);
          term.setCompanyAmountRemaining(amount);
          term.setDueDate(DATE);
          term.setIsPaid(false);
          term.setIsCustomized(false);
          term.setPaymentMode(mode);
          term.setCompany(c);
          term.setPartner(partner);
          term.setCurrency(c.getCurrency());
          invoice.addInvoiceTermListItem(term);
          id[0] = JPA.save(invoice).getId();
        });
    return id[0];
  }

  /**
   * Вентилирана фактура — подготовка за плащането, не проверката. Вентилира invoicing.write: без
   * влязъл потребител AOS смята периода за затворен. @return id на фактурата
   */
  private static Long ventilatedInvoice(Company company) {
    warm(company, "invoicing.write");
    Long invoice = validatedInvoice(company);
    BgRoleLogin.login(company, "invoicing.write");
    ActionResponse ventilated = ventilateViaAction(invoice);
    BgRoleLogin.logout();
    assertEquals(
        InvoiceRepository.STATUS_VENTILATED,
        invoiceStatus(invoice),
        "подготовка: " + ventilated.getData());
    return invoice;
  }

  /** Вентилиране като през {@code /ws/action}: платформата проверява само READ върху Invoice. */
  private static ActionResponse ventilateViaAction(Long invoiceId) {
    ActionResponse response = new ActionResponse();
    Beans.get(InvoiceController.class)
        .ventilate(request(Invoice.class.getName(), invoiceId, new HashMap<>()), response);
    return response;
  }

  /** Масово плащане като през {@code /ws/action} (READ върху InvoicePayment): цялата фактура. */
  private static ActionResponse payViaAction(Long invoiceId) {
    Invoice invoice = JPA.em().find(Invoice.class, invoiceId);
    Map<String, Object> extra = new HashMap<>();
    extra.put("_selectedInvoices", List.of(invoiceId));
    extra.put("paymentMode", Map.of("id", invoice.getPaymentMode().getId()));
    extra.put("paymentDate", DATE.toString());
    ActionResponse response = new ActionResponse();
    Beans.get(InvoicePaymentController.class)
        .validateMassPayment(
            request("com.axelor.apps.account.db.InvoicePayment", null, extra), response);
    return response;
  }

  private static ActionRequest request(String model, Long id, Map<String, Object> extra) {
    ActionRequest request = new ActionRequest();
    request.setModel(model);
    Map<String, Object> context = new HashMap<>(extra);
    context.put("_model", model);
    if (id != null) {
      context.put("id", id);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    request.setData(data);
    return request;
  }

  private static int invoiceStatus(Long invoiceId) {
    return ((Number)
            JPA.em()
                .createNativeQuery("SELECT status_select FROM account_invoice WHERE id = ?1")
                .setParameter(1, invoiceId)
                .getSingleResult())
        .intValue();
  }

  @Test
  void invoicingReadCannotVentilateAnInvoiceThroughWsAction() {
    Company company = company();
    warm(company, "invoicing.read");
    Long invoice = validatedInvoice(company);
    BgRoleLogin.login(company, "invoicing.read");

    ActionResponse response = ventilateViaAction(invoice);

    assertTrue(
        String.valueOf(response.getData()).contains("write access"),
        "отказ по правото върху фактурата: " + response.getData());
    assertEquals(InvoiceRepository.STATUS_VALIDATED, invoiceStatus(invoice), "не е вентилирана");
    assertTrue(statuses(company).isEmpty(), "статия няма");
  }

  @Test
  void invoicingWriteVentilatesAnInvoiceThroughWsAction() {
    Company company = company();
    warm(company, "invoicing.write");
    Long invoice = validatedInvoice(company);
    BgRoleLogin.login(company, "invoicing.write");

    ActionResponse response = ventilateViaAction(invoice);

    assertEquals(
        InvoiceRepository.STATUS_VENTILATED,
        invoiceStatus(invoice),
        "вентилирана: " + response.getData());
    assertEquals(List.of(MoveRepository.STATUS_ACCOUNTED), ints(statuses(company)));
  }

  @Test
  void invoicingReadCannotRegisterAPaymentThroughWsAction() {
    Company company = company();
    warm(company, "invoicing.read");
    Long invoice = ventilatedInvoice(company);
    BgRoleLogin.login(company, "invoicing.read");

    ActionResponse response = payViaAction(invoice);

    assertTrue(
        String.valueOf(response.getData()).contains("write access"),
        "отказ по правото върху плащането: " + response.getData());
    assertEquals(
        List.of(MoveRepository.STATUS_ACCOUNTED),
        ints(statuses(company)),
        "само статията на фактурата — плащане няма");
  }

  /** Статия на складово движение, създадена и осчетоводена в една транзакция — като модула. */
  private static void stockIssueOfItsOwnFlow(Company company) {
    JPA.runInTransaction(
        () -> {
          StockMove stockMove = new StockMove();
          stockMove.setCompany(JPA.find(Company.class, company.getId()));
          stockMove.setStockMoveSeq("SM-" + company.getCode());
          stockMove.setStatusSelect(3);
          stockMove = JPA.save(stockMove);
          Move move = draftMove(company, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC);
          move.setStockMove(stockMove);
          try {
            Beans.get(MoveValidateService.class).accounting(move);
          } catch (com.axelor.apps.base.AxelorException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void stockReadCannotPostAStockIssue() {
    Company company = company();
    BgRoleLogin.login(company, "stock.read");

    RuntimeException refused =
        assertThrows(RuntimeException.class, () -> stockIssueOfItsOwnFlow(company));

    assertTrue(
        String.valueOf(rootMessage(refused)).contains("write access"),
        "отказ по правото върху складовото движение: " + rootMessage(refused));
    assertTrue(statuses(company).isEmpty());
  }

  // ─── Служебният потребител и „без потребител“ ──────────────────────────────────────────

  /**
   * Служебният потребител и складовият дневник на дружеството (журналът на фикстурата, или нов, ако
   * {@code otherJournal}).
   */
  private static void autoPostingSetup(Company company, boolean otherJournal) {
    JPA.runInTransaction(
        () -> {
          Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
          Company c = JPA.find(Company.class, company.getId());
          Journal journal = JPA.all(Journal.class).filter("self.company = ?1", c).fetchOne();
          if (otherJournal) {
            Journal stk = new Journal();
            stk.setName("Друг дневник " + c.getCode());
            stk.setCode("STK-" + c.getCode());
            stk.setJournalType(journal.getJournalType());
            stk.setCompany(c);
            stk.setStatusSelect(1);
            journal = JPA.save(stk);
          }
          c.getAccountConfig().setBgStockIssueJournal(journal);
        });
  }

  private static void asAutoPosting(Runnable work) {
    try {
      BgAutoPostingIdentity.runAs(
          BgAutoPostingUserService.USER_CODE,
          () -> {
            work.run();
            return null;
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theCodeIsTheSameOnBothSides() {
    assertEquals(
        BgAutoPostingUserService.USER_CODE,
        com.axelor.apps.nyxasecurity.posting.ManualValidationPolicy.AUTO_POSTING_USER);
  }

  @Test
  void autoPostingPostsTheStockIssueInTheStockIssueJournal() {
    Company company = company();
    autoPostingSetup(company, false);

    asAutoPosting(() -> stockIssueOfItsOwnFlow(company));

    assertEquals(List.of(MoveRepository.STATUS_ACCOUNTED), ints(statuses(company)));
  }

  @Test
  void autoPostingCannotPostAStockIssueInAnotherJournal() {
    Company company = company();
    autoPostingSetup(company, true);

    RuntimeException refused =
        assertThrows(
            RuntimeException.class, () -> asAutoPosting(() -> stockIssueOfItsOwnFlow(company)));

    assertTrue(
        String.valueOf(rootMessage(refused)).contains("automatic posting user"),
        "отказ за служебния: " + rootMessage(refused));
    assertTrue(statuses(company).isEmpty());
  }

  @Test
  void autoPostingCannotPostAHandEnteredMove() {
    Company company = company();
    autoPostingSetup(company, false);

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                asAutoPosting(
                    () -> enterAndValidate(company, MoveRepository.TECHNICAL_ORIGIN_ENTRY)));

    assertTrue(
        String.valueOf(rootMessage(refused)).contains("automatic posting user"),
        "отказ за служебния: " + rootMessage(refused));
    assertTrue(statuses(company).isEmpty());
  }

  @Test
  void autoPostingCannotVentilateAnInvoice() {
    Company company = company();
    autoPostingSetup(company, false);
    Long invoice = validatedInvoice(company);
    ActionResponse[] response = new ActionResponse[1];

    asAutoPosting(() -> response[0] = ventilateViaAction(invoice));

    assertTrue(
        String.valueOf(response[0].getData()).contains("automatic posting user"),
        "отказ за служебния: " + response[0].getData());
    assertEquals(InvoiceRepository.STATUS_VALIDATED, invoiceStatus(invoice), "не е вентилирана");
    assertTrue(statuses(company).isEmpty());
  }

  /**
   * Без потребител (Quartz, boot) G13 не проверява: същата промяна на статуса, която отказва на
   * accounting.read, минава. Статусът се пише направо, защото accounting() на AOS без потребител
   * сам отказва („периодът е затворен“).
   */
  @Test
  void withoutAUserG13ChecksNothing() {
    Company company = company();

    JPA.runInTransaction(
        () ->
            draftMove(company, MoveRepository.TECHNICAL_ORIGIN_ENTRY)
                .setStatusSelect(MoveRepository.STATUS_ACCOUNTED));

    assertEquals(List.of(MoveRepository.STATUS_ACCOUNTED), ints(statuses(company)));

    Company other = company();
    BgRoleLogin.login(other, "accounting.read");
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                JPA.runInTransaction(
                    () ->
                        draftMove(other, MoveRepository.TECHNICAL_ORIGIN_ENTRY)
                            .setStatusSelect(MoveRepository.STATUS_ACCOUNTED)));
    assertTrue(
        String.valueOf(rootMessage(refused)).contains("write access"),
        "с потребител — отказ: " + rootMessage(refused));
  }

  private static List<Integer> ints(List<Number> values) {
    return values.stream().map(Number::intValue).collect(java.util.stream.Collectors.toList());
  }

  @Test
  void aiCannotPostDefinitively() {
    Company company = company();
    BgRoleLogin.login(company, "accounting.write");

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                AiExecutionContext.callAsToolWrite(
                    () -> {
                      enterAndValidate(company, MoveRepository.TECHNICAL_ORIGIN_ENTRY);
                      return null;
                    }));

    assertNotNull(AiDraftOnlyRefusal.find(refused), "отказът е „само чернови“: " + refused);
    assertTrue(statuses(company).isEmpty(), "нищо не е осчетоводено");
  }

  @Test
  void daybookOffMigrationRunsOnce() {
    Company on = company();
    Company off = company();
    JPA.runInTransaction(
        () -> JPA.find(Company.class, on.getId()).getAccountConfig().setAccountingDaybook(true));

    List<String> first = Beans.get(DaybookOffMigration.class).apply();

    assertEquals(List.of(on.getName()), first, "само дружеството с включен daybook");
    assertFalse(daybook(on));
    assertFalse(daybook(off));

    // Второ пускане: нищо. И дружество, включено нарочно след миграцията, остава включено.
    JPA.runInTransaction(
        () -> JPA.find(Company.class, off.getId()).getAccountConfig().setAccountingDaybook(true));
    List<String> second = Beans.get(DaybookOffMigration.class).apply();

    assertTrue(second.isEmpty(), "второто пускане не прави нищо: " + second);
    assertTrue(daybook(off), "включеното след миграцията остава включено");
  }

  private static boolean daybook(Company company) {
    JPA.em().clear();
    return Boolean.TRUE.equals(
        JPA.find(Company.class, company.getId()).getAccountConfig().getAccountingDaybook());
  }

  private static String rootMessage(Throwable e) {
    String message = null;
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t.getMessage() != null) {
        message = t.getMessage();
      }
    }
    return message;
  }
}
