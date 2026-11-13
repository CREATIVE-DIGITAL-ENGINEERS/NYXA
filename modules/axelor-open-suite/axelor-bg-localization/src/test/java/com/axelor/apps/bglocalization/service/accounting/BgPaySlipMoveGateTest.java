package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.hr.db.EmploymentContract;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВРАТИТЕ ПРЕД СТАТИЯТА и предпазителят срещу дублиране.
 *
 * <p><b>⚠️ ПОЧТИ НИТО ЕДНА ОТ ТЯХ НЕ ХВЪРЛЯ.</b> Причина в самия фиш остава ОБЯВЕНА ПРИЧИНА върху
 * документа — инак „няма статия“ е състояние, което се открива месеци по-късно.
 *
 * <p><b>⚠️ ДВЕТЕ ИЗКЛЮЧЕНИЯ:</b> празен заплатен журнал и сметка, която я няма в сметкоплана,
 * ОТКАЗВАТ изплащането на глас — те са настройка на дружеството и важат за всеки фиш. Ретро-фишът
 * (статия при потвърждаване) остава с бележка и за тях.
 *
 * <p>⚠️ Услугата се строи с {@code null} зависимости НАРОЧНО: всяка от шестте врати трябва да
 * излезе ПРЕДИ първото докосване на репозиторий. Подадени истински, тестът щеше да мине и когато
 * вратата е след заявката — тоест точно когато {@code pay()} без база пада.
 */
class BgPaySlipMoveGateTest {

  /** Услугата без нито една зависимост — всяко докосване на репозиторий тук е NPE, тоест провал. */
  private static BgPaySlipMoveService service() {
    return new BgPaySlipMoveService(null, null, null, null, null, null, null);
  }

  // ── 1 · идемпотентност ──────────────────────────────────────────────────

  @Test
  @DisplayName("1 · фиш, който ВЕЧЕ има статия, не получава втора")
  void aSlipThatAlreadyHasAMoveIsLeftAlone() throws Exception {
    BgPaySlip slip = payableSlip();
    Move existing = new Move();
    slip.setBgPayrollMove(existing);
    slip.setBgPayrollMoveNote("стара бележка");

    service().generate(slip);

    assertSame(existing, slip.getBgPayrollMove(), "препратката не бива да се подменя");
    assertEquals("стара бележка", slip.getBgPayrollMoveNote(), "нищо не се пипа");
  }

  @Test
  @DisplayName("1а · повторното извикване е без ефект и след успешно генериране")
  void generatingTwiceYieldsOneMove() throws Exception {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();

    service.generate(slip);
    Move first = slip.getBgPayrollMove();
    assertNotNull(first, "първото извикване строи статия");
    assertEquals(1, service.built, "построена е точно една");

    service.generate(slip);
    assertSame(first, slip.getBgPayrollMove(), "второто извикване не строи нищо");
    assertEquals(1, service.built, "и не вика строителя повторно");
  }

  // ── 2 · петте причини да няма статия ────────────────────────────────────

  @Test
  @DisplayName("2 · фиш без трудов договор (СОЛ) не получава статия, а причина")
  void aSlipWithoutAContractGetsAReasonNotAnException() throws Exception {
    BgPaySlip slip = payableSlip();
    slip.setEmploymentContract(null);

    service().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("фирма"), slip.getBgPayrollMoveNote());
  }

  @Test
  @DisplayName("3 · непопълнен заплатен журнал ОТКАЗВА изплащането и назовава полето и екрана")
  void aMissingPayrollJournalRefusesThePayment() {
    BgPaySlip slip = payableSlip();
    slip.getEmploymentContract().getPayCompany().setAccountConfig(new AccountConfig());

    AxelorException e = assertThrows(AxelorException.class, () -> service().generate(slip));

    assertTrue(e.getMessage().contains("bgPayrollJournal"), e.getMessage());
    assertTrue(e.getMessage().contains("„Заплатен журнал (BG)“"), "назовава полето на екрана");
    assertTrue(e.getMessage().contains("„Заплати (BG)“"), "и панела, в който е");
    assertTrue(e.getMessage().contains("„Тест ЕООД“"), "и дружеството");
    // ⚠️ И казва изрично, че expenseJournal НЕ е отговорът — той сочи MOM и обслужва друг поток.
    assertTrue(e.getMessage().contains("expenseJournal"));
    assertNull(slip.getBgPayrollMove());
    assertNull(slip.getBgPayrollMoveNote(), "отказ, а не бележка: тиха бележка е точно дефектът");
  }

  @Test
  @DisplayName("3а · фиш без заплатен журнал — и без AccountConfig изобщо — също отказва")
  void aCompanyWithoutAccountConfigRefusesThePayment() {
    BgPaySlip slip = payableSlip();
    slip.getEmploymentContract().getPayCompany().setAccountConfig(null);

    AxelorException e = assertThrows(AxelorException.class, () -> service().generate(slip));

    assertTrue(e.getMessage().contains("bgPayrollJournal"), e.getMessage());
  }

  @Test
  @DisplayName("3б · КОРИГИРАЩ фиш без заплатен журнал остава с бележка — потвърждаването не спира")
  void aRetroSlipWithoutAJournalKeepsTheNote() throws Exception {
    BgPaySlip slip = payableSlip();
    slip.setBgIsCorrection(true);
    slip.getEmploymentContract().getPayCompany().setAccountConfig(new AccountConfig());

    service().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(
        slip.getBgPayrollMoveNote().contains("bgPayrollJournal"), slip.getBgPayrollMoveNote());
  }

  @Test
  @DisplayName("4 · перо без сметка спира статията и изброява перата поименно")
  void anUnmappedLineStopsTheArticle() throws Exception {
    BgPaySlip slip = payableSlip();
    // ⚠️ 630, А НЕ 230. След счетоводното сверяване 2xx има сметка (609) и вече НЕ отказва.
    // Единственото перо, което днес отказва по НАСРЕЩНАТА си сметка, е предаденото в натура.
    slip.getBgPaySlipLineList().add(line(630, "Предадено в натура", "DEBIT", "40.00"));

    new Recording().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("перо 630"), slip.getBgPayrollMoveNote());
    assertTrue(slip.getBgPayrollMoveNote().contains("ЦЯЛО ИЛИ НИЩО"));
  }

  @Test
  @DisplayName("5 · липсваща сметка в сметкоплана ОТКАЗВА изплащането с кода ѝ и екрана")
  void aMissingAccountRefusesThePaymentWithTheCode() {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();
    service.knownAccounts = false; // сметкопланът на фирмата е празен

    AxelorException e = assertThrows(AxelorException.class, () -> service.generate(slip));

    assertTrue(e.getMessage().contains("6041"), e.getMessage());
    assertTrue(e.getMessage().contains("4211"), "всички липсващи, не само първата");
    assertTrue(e.getMessage().contains("Финансови сметки"), "назовава екрана");
    assertTrue(e.getMessage().contains("„Тест ЕООД“"), "и дружеството");
    assertNull(slip.getBgPayrollMove());
    assertNull(slip.getBgPayrollMoveNote(), "отказ, а не бележка");
    assertEquals(0, service.built, "статия не се строи");
  }

  @Test
  @DisplayName("5а · само ЕДНА липсваща сметка също отказва — и назовава само нея")
  void oneMissingAccountRefusesByItsCode() {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();
    service.missingCode = "4211";

    AxelorException e = assertThrows(AxelorException.class, () -> service.generate(slip));

    assertTrue(e.getMessage().contains("4211"), e.getMessage());
    assertTrue(!e.getMessage().contains("6041"), "6041 я има: " + e.getMessage());
  }

  @Test
  @DisplayName("5б · КОРИГИРАЩ фиш с липсваща сметка остава с бележка, не отказ")
  void aRetroSlipWithAMissingAccountKeepsTheNote() throws Exception {
    BgPaySlip original = payableSlip();
    original.setBgPayrollMove(new Move());
    BgPaySlip slip = payableSlip();
    slip.setBgIsCorrection(true);
    slip.setBgCorrectionOf(original);
    slip.getBgPaySlipLineList().get(0).setBgAmount(new BigDecimal("1600.00"));
    Recording service = new Recording();
    service.knownAccounts = false;

    service.generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("6041"), slip.getBgPayrollMoveNote());
  }

  @Test
  @DisplayName("6 · фиш без нито едно перо, което дава ред, не получава празна статия")
  void aSlipWithNoPostableLinesGetsNoEmptyArticle() throws Exception {
    BgPaySlip slip = payableSlip();
    slip.getBgPaySlipLineList().clear();

    new Recording().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertNotNull(slip.getBgPayrollMoveNote());
  }

  @Test
  @DisplayName("6а · фиш без дата и без период не получава статия, а причина")
  void aSlipWithoutADateGetsAReasonNotAnException() throws Exception {
    BgPaySlip slip = payableSlip();
    slip.setBgPaidDate(null);

    new Recording().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("период"), slip.getBgPayrollMoveNote());
  }

  @Test
  @DisplayName("6б · журнал без позволени типове отказва статията с ИЗРЕЧЕНИЕ, не с изключение")
  void aJournalThatRejectsEveryAccountTypeGetsANamedReason() throws Exception {
    BgPaySlip slip = payableSlip();
    // Журналът, както го строеше старата фикстура: без нито един позволен тип. Точно това
    // MoveManagementRepository#save посреща с изключение вътре в pay().
    Journal bare = new Journal();
    bare.setCode("PAY");
    slip.getEmploymentContract().getPayCompany().getAccountConfig().setBgPayrollJournal(bare);

    new Recording().generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("НИТО ЕДИН"), slip.getBgPayrollMoveNote());
    assertTrue(slip.getBgPayrollMoveNote().contains("6041"), slip.getBgPayrollMoveNote());
  }

  // ── 7 · успешният случай изчиства бележката ─────────────────────────────

  @Test
  @DisplayName("7 · успешно генериране изчиства старата причина")
  void aSuccessfulGenerationClearsTheNote() throws Exception {
    BgPaySlip slip = payableSlip();
    slip.setBgPayrollMoveNote("отпреди журналът да е попълнен");

    new Recording().generate(slip);

    assertNotNull(slip.getBgPayrollMove());
    assertNull(slip.getBgPayrollMoveNote(), "остаряла причина не бива да стои до готова статия");
  }

  // ── 8 · изплащането ОСЧЕТОВОДЯВА статията ─────────────────────────────

  @Test
  @DisplayName("8 · изплатеният фиш предава статията си на осчетоводяването на AOS — точно веднъж")
  void aPaidSlipHandsItsMoveToTheAosAccounting() throws Exception {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();

    service.generate(slip);

    assertNotNull(slip.getBgPayrollMove());
    assertEquals(1, service.accounted.size(), "MoveValidateService#accounting е извикан веднъж");
    assertSame(slip.getBgPayrollMove(), service.accounted.get(0), "и то със статията на фиша");

    service.generate(slip);
    assertEquals(1, service.accounted.size(), "повторното изплащане не осчетоводява втори път");
  }

  @Test
  @DisplayName("8а · затворен фискален период ОТКАЗВА изплащането с изречение — без статия")
  void aClosedFiscalPeriodRefusesThePayment() {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();
    service.fiscal = "Фискалният период „06/2026“ (дата 2026-06-10) е ЗАТВОРЕН";

    AxelorException e = assertThrows(AxelorException.class, () -> service.generate(slip));

    assertTrue(e.getMessage().startsWith("Фишът не се изплаща: "), e.getMessage());
    assertTrue(e.getMessage().contains("ЗАТВОРЕН"), e.getMessage());
    assertEquals(0, service.built, "статия не се строи — няма какво да остане чернова");
    assertTrue(service.accounted.isEmpty());
    assertNull(slip.getBgPayrollMove());
    assertNull(slip.getBgPayrollMoveNote(), "отказ, а не бележка до изплатен фиш");
  }

  @Test
  @DisplayName("8б · КОРИГИРАЩ фиш в затворен период остава с бележка — потвърждаването не спира")
  void aRetroSlipInAClosedPeriodKeepsTheNote() throws Exception {
    // Оригинал с осчетоводена статия и корекция с друго брутно — делтата не е нула, тоест
    // ретро-пътят стига до въпроса за фискалния период.
    BgPaySlip original = payableSlip();
    original.setBgPayrollMove(new Move());
    BgPaySlip slip = payableSlip();
    slip.setBgIsCorrection(true);
    slip.setBgCorrectionOf(original);
    slip.getBgPaySlipLineList().get(0).setBgAmount(new BigDecimal("1600.00"));
    Recording service = new Recording();
    service.fiscal = "Фискалният период „06/2026“ (дата 2026-06-10) е ЗАТВОРЕН";

    service.generate(slip);

    assertNull(slip.getBgPayrollMove());
    assertTrue(slip.getBgPayrollMoveNote().contains("ЗАТВОРЕН"), slip.getBgPayrollMoveNote());
    assertTrue(slip.getBgPayrollMoveNote().contains("122"), "ретро-обяснението за изходите");
    assertTrue(service.accounted.isEmpty(), "ретро-статията не се осчетоводява оттук");
  }

  @Test
  @DisplayName("8в · провал на осчетоводяването спира изплащането с причината на AOS отпред")
  void aFailedAccountingRefusesThePaymentWithTheReason() {
    BgPaySlip slip = payableSlip();
    Recording service = new Recording();
    service.accountingFailure = "Период 06/2026 е затворен.";

    AxelorException e = assertThrows(AxelorException.class, () -> service.generate(slip));

    assertTrue(e.getMessage().startsWith("Фишът не се изплаща: "), e.getMessage());
    assertTrue(e.getMessage().contains("Период 06/2026 е затворен."), "причината на AOS");
    assertTrue(e.getMessage().contains("фишът остава потвърден"), e.getMessage());
    assertNull(slip.getBgPayrollMove(), "препратката не се пише до неосчетоводена статия");
  }

  // ── подпората ───────────────────────────────────────────────────────────

  /**
   * Услуга с подменени САМО двата шева към базата: намирането на сметка и самото построяване.
   *
   * <p>⚠️ Гейтовете НЕ се подменят — те са това, което тестът мери.
   */
  private static final class Recording extends BgPaySlipMoveService {

    boolean knownAccounts = true;
    String missingCode;
    int built;
    String fiscal;
    String accountingFailure;
    final List<Move> accounted;

    Recording() {
      this(new ArrayList<>(), new String[1]);
    }

    /**
     * ⚠️ ОСЧЕТОВОДЯВАНЕТО НЕ СЕ ПОДМЕНЯ КАТО ШЕВ, А КАТО УСЛУГА: {@code post} е кодът под проверка
     * (превеждането на грешката), а {@code MoveValidateService} е интерфейс — прокси, което записва
     * статията или хвърля, без контейнер и без mock библиотека.
     */
    private Recording(List<Move> accounted, String[] failure) {
      super(null, null, null, null, null, null, validating(accounted, failure));
      this.accounted = accounted;
      this.failure = failure;
    }

    private final String[] failure;

    @Override
    public void generate(BgPaySlip slip) throws AxelorException {
      failure[0] = accountingFailure;
      super.generate(slip);
    }

    @Override
    protected String fiscalPeriodRefusal(Company company, LocalDate date) {
      return fiscal;
    }

    private static MoveValidateService validating(List<Move> accounted, String[] failure) {
      return (MoveValidateService)
          Proxy.newProxyInstance(
              MoveValidateService.class.getClassLoader(),
              new Class<?>[] {MoveValidateService.class},
              (proxy, method, args) -> {
                if (!"accounting".equals(method.getName())) {
                  throw new UnsupportedOperationException(method.getName());
                }
                if (failure[0] != null) {
                  throw new AxelorException(
                      TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, "%s", failure[0]);
                }
                accounted.add((Move) args[0]);
                return null;
              });
    }

    /**
     * ⚠️ СМЕТКАТА ИДВА С ТИП, а не само с код, и това не е излишна точност: гейтът за журнала пита
     * точно {@code accountType.technicalTypeSelect}, тоест сметка без тип щеше да бъде отказана и
     * успешните случаи нямаше да минат — при напълно верен модел. Типовете следват BG_COA: 60x и
     * 604 са „charge“, 70x е „income“, останалите са „debt“.
     */
    @Override
    protected Account accountFor(String code, Company company) {
      if (!knownAccounts || code.equals(missingCode)) {
        return null;
      }
      AccountType type = new AccountType();
      type.setTechnicalTypeSelect(
          code.startsWith("60") ? "charge" : code.startsWith("70") ? "income" : "debt");
      Account account = new Account();
      account.setCode(code);
      account.setAccountType(type);
      return account;
    }

    @Override
    protected Move build(
        BgPaySlip slip, Company company, Journal journal, BgPayrollPostingPlan.Plan plan) {
      built++;
      Move move = new Move();
      move.setReference("MOVE-" + built);
      return move;
    }
  }

  /** Фиш, който минава и шестте врати: фирма, валута, журнал, разпознати пера. */
  private static BgPaySlip payableSlip() {
    Company company = new Company();
    company.setName("Тест ЕООД");
    Currency euro = new Currency();
    euro.setCode("EUR");
    company.setCurrency(euro);
    AccountConfig config = new AccountConfig();
    config.setBgPayrollJournal(journal());
    company.setAccountConfig(config);

    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    contract.setBgInsuredTypeCode("01");

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-0001");
    slip.setEmploymentContract(contract);
    // ⚠️ ДАТАТА Е ЧАСТ ОТ МИНИМАЛНИЯ ФИШ. Статия без дата не сяда в никой месец и оттам не може
    // да съществува — виж BgPaySlipMoveService#dateOf.
    slip.setBgPaidDate(LocalDate.of(2026, 6, 10));

    List<BgPaySlipLine> lines = new ArrayList<>();
    BgPaySlipLine wage = line(100, "Основно възнаграждение", "CREDIT", "1500.00");
    wage.setBgFormsGrossWage(true);
    lines.add(wage);
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  /**
   * Журнал PAY с позволените типове сметки, както са в BG_COA.
   *
   * <p><b>⚠️ ПРАЗНИЯТ НАБОР НЕ Е „БЕЗ ОГРАНИЧЕНИЕ“, А ОТКАЗ НА ВСИЧКО.</b> {@code
   * MoveLineControlServiceImpl#controlAccountingAccount} тръгва от {@code isValid = false} и я
   * вдига само при попадение — тоест журнал без нито един позволен тип отказва всяка сметка, при
   * запис, вътре в транзакцията на изплащането. Затова фикстурата носи истинските типове: инак
   * „успешният случай“ щеше да мери отказ.
   */
  private static Journal journal() {
    Journal journal = new Journal();
    journal.setCode("PAY");
    journal.setName("Заплати и осигуровки");
    journal.setValidAccountTypeSet(
        new LinkedHashSet<>(
            List.of(
                accountType("cash"),
                accountType("receivable"),
                accountType("debt"),
                accountType("payable"),
                accountType("tax"),
                accountType("income"),
                accountType("charge"))));
    return journal;
  }

  private static AccountType accountType(String technical) {
    AccountType type = new AccountType();
    type.setTechnicalTypeSelect(technical);
    type.setName(technical);
    return type;
  }

  private static BgPaySlipLine line(int number, String name, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    return line;
  }
}
