package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.moveline.MoveLineTaxService;
import com.axelor.apps.aiocr.db.repo.OcrMoveRepository;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrection;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrectionLine;
import com.axelor.apps.bglocalization.db.BgVatProtocolNumberEntry;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgMoveRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.listener.BgLocalizationServerStartListener;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79Direction;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79ProtocolService;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Binding;
import com.google.inject.Injector;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
 * ПРОТОКОЛИТЕ ПО ЧЛ. 79 И ЧЛ. 79а СТИГАТ ДО ДНЕВНИКА — през истинските услуги и истинската база.
 *
 * <p>{@link BgArt79ProtocolService} и {@link BgArt79aProtocolService} съставят статията с
 * производственото окабеляване ({@code MoveCreateService}, {@code MoveLineCreateService}), тоест
 * редовете са точно тези, които вижда и приложението: по 4531/4532 и с ПРАЗЕН {@code taxLineSet}.
 * Статията се осчетоводява и минава през {@link BgVatDocumentCollector#collect}: истинските {@code
 * isVatRelevant} и маршрут (обхватът на заявката е подменен — виж {@link #collect}).
 *
 * <p>Преди поправката протоколът не стигаше до дневника по ДВЕ причини, и двете невидими за
 * тестовете, които викаха {@code enrichFromMove} направо:
 *
 * <ul>
 *   <li>услугите ПОДМЕНЯХА списъка с редове на вече записаната от {@code createMove} статия и
 *       {@code save()} гърмеше („collection with cascade=all-delete-orphan was no longer
 *       referenced“) — протоколът не се съставяше изобщо;
 *   <li>{@code isVatRelevant} питаше само за данък на реда и изхвърляше и двата протокола.
 * </ul>
 *
 * <p>Осчетоводяването минава през {@code BgMoveRepository}, който дава на протокола номер от
 * поредицата на дружеството ({@link BgVatProtocolNumbering}).
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgVatProtocolReachabilityIntegrationTest extends JpaSupport {

  private static final LocalDate DATE = LocalDate.of(2026, 6, 30);

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  // ---------------------------------------------------------------------------
  // Фикстури: дружество, готово за статии (журнал, отворен период) + настройките по чл. 79
  // ---------------------------------------------------------------------------

  private static Account account(Company company, String code, String name, int position) {
    Account a = new Account();
    a.setCode(code);
    a.setName(name);
    a.setCompany(company);
    a.setCommonPosition(position);
    return JPA.save(a);
  }

  /**
   * Веригата на {@code MoveCreateService} чете глобалните настройки ({@code
   * SequenceService.getDraftPrefix} → {@code AppBase}); в празна тестова база те са {@code null}.
   * Нито една няма задължително поле — по един празен ред стига.
   */
  private static void ensureAppConfigs() {
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
      // ⚠️ Проверката за вече даден номер (SequenceService.isSequenceAlreadyExisting) е SELECT
      // върху Move, а той не върви на HSQLDB заради PostgreSQL формулата Move.ocrUploadIds. В
      // приложението (PostgreSQL) тя остава; тук се изключва, за да се стигне до номера.
      appBase.setCheckExistingSequenceOnGeneration(false);
      JPA.save(appBase);
    }
    if (Beans.get(com.axelor.studio.db.repo.AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppAccount());
    }
  }

  private static Company company(String tag) {
    return company(tag, true);
  }

  /** {@code withSequence = false} — заварено дружество без поредицата на протоколите. */
  private static Company company(String tag, boolean withSequence) {
    ensureAppConfigs();
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("E" + tag);
    currency = JPA.save(currency);

    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode("C-" + tag);
    company.setCurrency(currency);
    company = JPA.save(company);

    // Числа, не константите от <extra-code>: те не са сигурни в src-gen, който предхожда теста.
    JournalType type = new JournalType();
    type.setName("Корекции " + tag);
    type.setCode("KR-" + tag);
    type.setTechnicalTypeSelect(5); // TECHNICAL_TYPE_SELECT_OTHER
    type = JPA.save(type);

    Account vatCredit = account(company, "4531", "ДДС покупки", 1);
    Account vatCharged = account(company, "4532", "ДДС продажби", 2);
    Account income = account(company, "709", "Други приходи", 2);
    Account expense = account(company, "609", "Други разходи", 1);

    // Журналът пуска само изброените сметки (MoveLineControlServiceImpl.controlAccountingAccount).
    Journal journal = new Journal();
    journal.setName("ДДС корекции " + tag);
    journal.setCode("VAT-" + tag);
    journal.setJournalType(type);
    journal.setCompany(company);
    journal.setValidAccountSet(
        new HashSet<>(Arrays.asList(vatCredit, vatCharged, income, expense)));
    journal = JPA.save(journal);

    Year year = new Year();
    year.setName("2026 " + tag);
    year.setCode("FY-" + tag);
    year.setCompany(company);
    year.setTypeSelect(1); // TYPE_FISCAL
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    year.setStatusSelect(1); // STATUS_OPENED
    year = JPA.save(year);

    Period period = new Period();
    period.setName("2026 " + tag);
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 1, 1));
    period.setToDate(LocalDate.of(2026, 12, 31));
    period.setStatusSelect(1); // STATUS_OPENED
    JPA.save(period);

    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    config.setBgVatCorrectionJournal(journal);
    config.setBgVatDeductibleAccount(vatCredit);
    config.setBgVatChargedAccount(vatCharged);
    config.setBgVatCorrectionIncomeAccount(income);
    config.setBgVatAnnualCorrectionExpenseAccount(expense);
    config = JPA.save(config);
    company.setAccountConfig(config);

    if (!withSequence) {
      return JPA.save(company);
    }
    // Поредицата на ДДС протоколите на дружеството: 10 цифри, без префикс, без годишно нулиране.
    Sequence sequence = new Sequence();
    sequence.setCompany(company);
    sequence.setName("ДДС протоколи " + tag);
    sequence.setCodeSelect(BgVatProtocolNumbering.SEQUENCE_CODE);
    sequence.setPadding(10);
    sequence.setToBeAdded(1);
    sequence.setYearlyResetOk(false);
    sequence = JPA.save(sequence);
    SequenceVersion version = new SequenceVersion();
    version.setSequence(sequence);
    version.setStartDate(LocalDate.of(2026, 1, 1));
    version.setNextNum(1L);
    JPA.save(version);
    return JPA.save(company);
  }

  private static FixedAsset asset(Company company) {
    Partner supplier = new Partner();
    supplier.setName("Доставчик на актива");
    supplier.setCompany(company);
    supplier = JPA.save(supplier);

    FixedAssetCategory category = new FixedAssetCategory();
    category.setName("Машини");
    category.setJournal(company.getAccountConfig().getBgVatCorrectionJournal());
    category = JPA.save(category);

    FixedAsset asset = new FixedAsset();
    asset.setName("Машина");
    asset.setCompany(company);
    asset.setJournal(category.getJournal());
    asset.setPartner(supplier);
    asset.setFixedAssetCategory(category);
    return JPA.save(asset);
  }

  /**
   * Осчетоводяване: статусът и номерът на протокола от {@link
   * BgMoveRepository#assignProtocolNumber} — същото, което прави {@code save()} му, когато {@code
   * MoveValidateServiceImpl.accounting} запише осчетоводената статия.
   *
   * <p>⚠️ Пълното {@code save()} на веригата тук не върви: за осчетоводена статия {@code
   * MoveManagementRepository} проверява периода срещу ВЛЕЗЛИЯ потребител ({@code
   * checkClosurePeriod} → {@code isAuthorizedToAccountOnPeriod(move, user)}, а без потребител
   * отказва). Това е пазач на Axelor, не на номера.
   */
  private static Move post(Move move) {
    return tx(
        () -> {
          Move managed = JPA.em().find(Move.class, move.getId());
          managed.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
          Beans.get(BgMoveRepository.class).assignProtocolNumber(managed);
          return JPA.save(managed);
        });
  }

  /**
   * {@link BgVatDocumentCollector#collect} над осчетоводената статия.
   *
   * <p>⚠️ <b>Подменен е САМО обхватът на заявката</b> ({@code findPostedMoves}): истинският JPQL
   * върху {@code Move} не може да се изпълни на HSQLDB. {@code Move.ocrUploadIds} е
   * {@code @Formula} с PostgreSQL {@code array_to_string(array_agg(...))} и HSQLDB отказва целия
   * SELECT („unexpected token: SELECT“). Статията е самата записана инстанция от услугата; {@code
   * isVatRelevant}, {@code toDocument} и маршрутът са истинските.
   */
  private static List<BgVatDocumentDto> collect(Move posted) {
    BgVatDocumentCollector collector =
        new BgVatDocumentCollector(Beans.get(MoveRepository.class)) {
          @Override
          protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
            assertEquals(MoveRepository.STATUS_ACCOUNTED, posted.getStatusSelect());
            assertEquals(request.getCompanyId(), posted.getCompany().getId());
            return Collections.singletonList(posted);
          }
        };
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(posted.getCompany().getId());
    request.setPeriodFrom(LocalDate.of(2026, 1, 1));
    request.setPeriodTo(LocalDate.of(2026, 12, 31));
    return tx(() -> collector.collect(request));
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " срещу " + actual);
  }

  /**
   * Самите редове: 4531/4532 + насрещна. Редът по сметка за ДДС носи най-много един данък — своята
   * страна (BgVatAccountTaxLines; в база без данъците на BG_COA — никакъв), насрещният — никакъв;
   * данъчна сума на реда няма.
   */
  private static void assertServiceLines(Move move) {
    assertNotNull(move, "протоколът не е съставен");
    assertEquals(2, move.getMoveLineList().size());
    for (MoveLine line : move.getMoveLineList()) {
      String code = line.getAccount().getCode();
      int taxes = line.getTaxLineSet() == null ? 0 : line.getTaxLineSet().size();
      if ("4531".equals(code) || "4532".equals(code)) {
        assertTrue(taxes <= 1, "редът по " + code + " носи повече от един данък");
      } else {
        assertEquals(0, taxes, "насрещният ред " + code + " носи данък");
      }
      assertEquals(0, line.getTaxAmount().signum());
    }
  }

  // ---------------------------------------------------------------------------
  // Случаите
  // ---------------------------------------------------------------------------

  /**
   * В приложението веригата е Account → BankPayment → Budget → Ocr ({@code AiOcrModule} свързва
   * {@code MoveBudgetManagementRepository} с {@code OcrMoveRepository}); BG я продължава с {@link
   * BgMoveRepository}. Тук се пита самата връзка на BG, а изворът ѝ трябва да е
   * BgLocalizationModule.
   */
  @Test
  @DisplayName("OcrMoveRepository е BgMoveRepository — номерът се дава в края на веригата")
  void окабеляването() {
    assertTrue(Beans.get(OcrMoveRepository.class) instanceof BgMoveRepository);
    Binding<OcrMoveRepository> binding =
        Beans.get(Injector.class).getBinding(OcrMoveRepository.class);
    assertTrue(
        binding.getSource().toString().contains("BgLocalizationModule"),
        () -> "връзката не е от BgLocalizationModule: " + binding.getSource());
  }

  @Test
  @DisplayName("Протоколът по чл. 79а (увеличение 200) се чете от базата и влиза в к.11")
  void годишната_корекция_по_чл79а_стига_до_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("79A");
              BgVatAnnualCorrection correction = new BgVatAnnualCorrection();
              correction.setCompany(company);
              correction.setYear(2026);
              correction = JPA.save(correction);
              BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
              line.setAnnualCorrection(correction);
              line.setFixedAsset(asset(company));
              line.setCorrectionAmount(new BigDecimal("200.00"));
              line = JPA.save(line);
              try {
                return Beans.get(BgArt79aProtocolService.class).generate(line);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertServiceLines(move);
    Move posted = post(move);

    List<BgVatDocumentDto> docs = collect(posted);

    assertEquals(1, docs.size(), "протоколът по чл. 79а изпада от дневниците");
    BgVatDocumentDto doc = docs.get(0);
    assertEquals("09", doc.getNapDocumentType());
    // ЗДДС чл. 117 ал. 2 т. 1 / ППЗДДС чл. 66 ал. 5 т. 1: собственият номер от поредицата.
    assertEquals("0000000001", posted.getBgVatProtocolNumber());
    assertEquals("0000000001", doc.getDocumentNumber());
    // ЗДДС чл. 117а ал. 1: номерът не се мени — повторният запис не взима нов.
    assertEquals("0000000001", post(posted).getBgVatProtocolNumber());
    // ППЗДДС чл. 66 ал. 9: протоколите по ал. 5 (чл. 79а) — в колона 11 на дневника за покупките.
    eq("200", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertTrue(doc.getSalesAmounts().isEmpty());
  }

  /** Протокол по чл. 79а (+200), осчетоводен у дружество БЕЗ поредица — заварен, без номер. */
  private static Move legacyProtocol(String tag) {
    Move move =
        tx(
            () -> {
              Company company = company(tag, false);
              BgVatAnnualCorrection correction = new BgVatAnnualCorrection();
              correction.setCompany(company);
              correction.setYear(2026);
              correction = JPA.save(correction);
              BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
              line.setAnnualCorrection(correction);
              line.setFixedAsset(asset(company));
              line.setCorrectionAmount(new BigDecimal("200.00"));
              line = JPA.save(line);
              try {
                return Beans.get(BgArt79aProtocolService.class).generate(line);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    return post(move);
  }

  /** Номерът, както е в БАЗАТА — native, защото SELECT върху Move не върви на HSQLDB. */
  private static String storedNumber(Move move) {
    Object value =
        JPA.em()
            .createNativeQuery("SELECT bg_vat_protocol_number FROM account_move WHERE id = ?1")
            .setParameter(1, move.getId())
            .getSingleResult();
    return value == null ? null : value.toString();
  }

  private static BgVatProtocolNumberEntry enter(Move move, String number) {
    return tx(
        () -> {
          try {
            return Beans.get(BgVatProtocolNumberEntryService.class)
                .record(
                    move,
                    number,
                    "по хартиения протокол",
                    null,
                    LocalDateTime.of(2026, 10, 6, 9, 30));
          } catch (AxelorException e) {
            throw new IllegalStateException(e.getMessage(), e);
          }
        });
  }

  @Test
  @DisplayName(
      "Заварен протокол без номер е ГРЕШКА; номерът на издадения протокол се въвежда с одит")
  void заварен_протокол_получава_номера_на_издадения_протокол() {
    Move legacy = legacyProtocol("OLD");
    assertEquals(null, legacy.getBgVatProtocolNumber(), "без поредица номер не се дава");

    BgVatDocumentDto before = collect(legacy).get(0);
    assertEquals(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE, before.getErrors().get(0).getCode());

    BgVatProtocolNumberEntry entry = enter(legacy, " 0000000777 ");

    assertEquals("0000000777", entry.getProtocolNumber());
    assertEquals("по хартиения протокол", entry.getNote());
    assertEquals(LocalDateTime.of(2026, 10, 6, 9, 30), entry.getEnteredOn());
    assertNotNull(entry.getId(), "следата е записана");
    assertEquals("0000000777", tx(() -> storedNumber(legacy)));
    BgVatDocumentDto after = collect(legacy).get(0);
    assertEquals("0000000777", after.getDocumentNumber());
    assertTrue(after.getErrors().isEmpty(), after.getErrors().toString());

    // ЗДДС чл. 117а ал. 1: даденият номер не се мени — и второ въвеждане се отказва.
    IllegalStateException again =
        assertThrows(IllegalStateException.class, () -> enter(legacy, "0000000778"));
    assertTrue(again.getMessage().contains("номерът не се мени"), again.getMessage());
    assertEquals("0000000777", tx(() -> storedNumber(legacy)));
  }

  private static SequenceVersion versionOf(Sequence sequence) {
    return JPA.all(SequenceVersion.class).filter("self.sequence = ?1", sequence).fetchOne();
  }

  private static List<Sequence> protocolSequences(Company company) {
    return JPA.all(Sequence.class)
        .filter(
            "self.codeSelect = ?1 AND self.company = ?2",
            BgVatProtocolNumbering.SEQUENCE_CODE,
            company)
        .fetch();
  }

  @Test
  @DisplayName("Стартирането създава липсващата поредица bgVatProtocol и не пипа съществуващата")
  void стартирането_създава_липсващата_поредица() {
    Company bare = tx(() -> company("SEQ", false));
    Company configured = tx(() -> company("KEEP", true));
    // Дружество, което вече е издало протоколи: следващият номер е нагласен на ръка.
    tx(
        () -> {
          SequenceVersion version = versionOf(protocolSequences(configured).get(0));
          version.setNextNum(57L);
          return JPA.save(version);
        });

    BgLocalizationServerStartListener listener = Beans.get(BgLocalizationServerStartListener.class);
    listener.ensureVatProtocolSequences();
    listener.ensureVatProtocolSequences(); // идемпотентно

    List<Sequence> created = tx(() -> protocolSequences(bare));
    assertEquals(1, created.size(), "една поредица за заварено дружество без нея");
    assertEquals(10, created.get(0).getPadding());
    assertEquals(null, created.get(0).getPrefixe());
    assertEquals(false, created.get(0).getYearlyResetOk());
    SequenceVersion version = tx(() -> versionOf(protocolSequences(bare).get(0)));
    assertEquals(LocalDate.of(2000, 1, 1), version.getStartDate());
    assertEquals(1L, version.getNextNum());

    List<Sequence> kept = tx(() -> protocolSequences(configured));
    assertEquals(1, kept.size(), "съществуващата не се дублира");
    assertEquals(
        57L,
        tx(() -> versionOf(protocolSequences(configured).get(0)).getNextNum()),
        "и не се пипа");
  }

  /**
   * Стартиране, после BG_COA: ЕДНА поредица. Дружеството е без сметкоплан при стартирането, а
   * шаблонът се прилага по-късно. Двата {@code <input>} за поредиците са дословно от {@code
   * chart-config.xml}, редът е bgVatProtocol от BG_COA, контекстът е като в {@code
   * AccountingConfigTemplateService}. Две поредици с един код биха повторили номерата.
   */
  @Test
  @DisplayName("Стартиране, после BG_COA — една поредица bgVatProtocol и следващият номер остава")
  void стартиране_после_шаблон_дава_една_поредица() throws Exception {
    Company company = tx(() -> company("TPL", false));
    Beans.get(BgLocalizationServerStartListener.class).ensureVatProtocolSequences();
    // Протоколи вече са издадени по поредицата от стартирането.
    tx(
        () -> {
          SequenceVersion version = versionOf(protocolSequences(company).get(0));
          version.setNextNum(57L);
          return JPA.save(version);
        });

    importBgCoaSequences(company);

    List<Sequence> sequences = tx(() -> protocolSequences(company));
    assertEquals(
        1, sequences.size(), "шаблонът обновява поредицата от стартирането, не прави втора");
    assertEquals("C-TPL-145", sequences.get(0).getImportId());
    List<SequenceVersion> versions =
        tx(
            () ->
                JPA.all(SequenceVersion.class)
                    .filter("self.sequence = ?1", sequences.get(0))
                    .fetch());
    assertEquals(1, versions.size(), "една отворена версия");
    assertEquals(57L, versions.get(0).getNextNum(), "следващият номер не се нулира");
    assertEquals(LocalDate.of(2000, 1, 1), versions.get(0).getStartDate());
  }

  private static String classpathText(String name) throws IOException {
    try (InputStream in =
        BgVatProtocolReachabilityIntegrationTest.class.getResourceAsStream(name)) {
      assertNotNull(in, name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String inputOf(String config, String file) {
    Matcher m =
        Pattern.compile("<input file=\"" + Pattern.quote(file) + "\".*?</input>", Pattern.DOTALL)
            .matcher(config);
    assertTrue(m.find(), file);
    return m.group();
  }

  /** Заглавният ред и редовете, които почват с {@code prefix}. */
  private static String rowsOf(String csv, String prefix) {
    List<String> lines = Arrays.asList(csv.split("\r?\n"));
    return lines.get(0)
        + "\n"
        + lines.stream().filter(l -> l.startsWith(prefix)).collect(Collectors.joining("\n"))
        + "\n";
  }

  private static void importBgCoaSequences(Company company) throws IOException {
    String config = classpathText("/l10n/chart-config.xml");
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(config);
    assertTrue(head.find());
    Path dir = Files.createTempDirectory("bg-coa-sequences");
    Path configFile = dir.resolve("config.xml");
    Files.write(
        configFile,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + head.group()
                + inputOf(config, "base_sequence.csv")
                + inputOf(config, "base_sequenceVersion.csv")
                + "</csv-inputs>\n")
            .getBytes(StandardCharsets.UTF_8));
    String coa = "/l10n/l10n_bgr/BG_COA/";
    Files.write(
        dir.resolve("base_sequence.csv"),
        rowsOf(classpathText(coa + "base_sequence.csv"), "\"145\";")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        dir.resolve("base_sequenceVersion.csv"),
        rowsOf(classpathText(coa + "base_sequenceVersion.csv"), "\"V145\";")
            .getBytes(StandardCharsets.UTF_8));

    CSVImporter importer = new CSVImporter(configFile.toString(), dir.toString());
    Map<String, Object> context = new HashMap<>();
    context.put("_companyId", company.getId());
    context.put("_companyName", company.getName());
    context.put("_companyCode", company.getCode());
    context.put("_defaultBankDetailsId", 0);
    context.put("_dataFileName", "BG_COA.zip");
    importer.setContext(context);
    List<String> errors = new ArrayList<>();
    List<Integer> imported = new ArrayList<>();
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {
            imported.add(success);
          }

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(e.toString());
          }
        });
    importer.run();
    assertEquals(Collections.emptyList(), errors);
    assertEquals(Arrays.asList(1, 1), imported, "по един ред от двата файла");
  }

  @Test
  @DisplayName("Номер, който вече е на друг протокол на дружеството, се отказва")
  void дублиран_номер_се_отказва() {
    Move first = legacyProtocol("DUP");
    enter(first, "0000000900");
    Move second =
        post(
            tx(
                () -> {
                  // Втори ред на СЪЩАТА годишна корекция (дружество + година са уникални), друг
                  // актив.
                  BgVatAnnualCorrection correction =
                      JPA.all(BgVatAnnualCorrection.class)
                          .filter("self.company = ?1 AND self.year = 2026", first.getCompany())
                          .fetchOne();
                  BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
                  line.setAnnualCorrection(correction);
                  line.setFixedAsset(asset(first.getCompany()));
                  line.setCorrectionAmount(new BigDecimal("50.00"));
                  line = JPA.save(line);
                  try {
                    return Beans.get(BgArt79aProtocolService.class).generate(line);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                }));

    IllegalStateException duplicate =
        assertThrows(IllegalStateException.class, () -> enter(second, "0000000900"));
    assertTrue(
        duplicate.getMessage().contains("вече е на друг ДДС протокол"), duplicate.getMessage());
    assertEquals(null, tx(() -> storedNumber(second)));
  }

  @Test
  @DisplayName("Протоколът по чл. 79 ал. 7 (приспадане 480) се чете от базата и влиза в к.11")
  void приспадането_по_чл79_стига_до_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("79");
              BgFixedAssetWriteOff writeOff = new BgFixedAssetWriteOff();
              writeOff.setCompany(company);
              writeOff.setFixedAsset(asset(company));
              writeOff.setWriteOffDate(DATE);
              writeOff.setTaxYear(2026);
              writeOff.setDocumentNumber("П-79-1");
              writeOff.setVatCorrectionDirection(BgArt79Direction.DEDUCTION);
              writeOff.setVatCorrectionAmount(new BigDecimal("480.00"));
              writeOff = JPA.save(writeOff);
              try {
                return Beans.get(BgArt79ProtocolService.class).generate(writeOff);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertServiceLines(move);
    Move posted = post(move);

    List<BgVatDocumentDto> docs = collect(posted);

    assertEquals(1, docs.size(), "протоколът по чл. 79 ал. 7 изпада от дневниците");
    BgVatDocumentDto doc = docs.get(0);
    assertEquals("09", doc.getNapDocumentType());
    // Не „П-79-1“ (документът на отписването, Move.origin), а собственият номер на протокола.
    assertEquals("0000000001", doc.getDocumentNumber());
    // ППЗДДС чл. 66 ал. 9: протоколите по ал. 4 (чл. 79 ал. 5 и 6) — в колона 11.
    eq("480", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  @Test
  @DisplayName(
      "SAF-T (G5b) чете протокола по чл. 79а от реда в дневника — същият колектор, к.11 → 03-31")
  void saft_чете_реда_на_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("SAFT");
              BgVatAnnualCorrection correction = new BgVatAnnualCorrection();
              correction.setCompany(company);
              correction.setYear(2026);
              correction = JPA.save(correction);
              BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
              line.setAnnualCorrection(correction);
              line.setFixedAsset(asset(company));
              line.setCorrectionAmount(new BigDecimal("200.00"));
              line = JPA.save(line);
              try {
                return Beans.get(BgArt79aProtocolService.class).generate(line);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Move posted = post(move);

    // Продукционното окабеляване: връзката държи СЪЩИЯ колектор, който пише дневниците.
    BgVatMoveDiaryRows diaryRows = Beans.get(BgVatMoveDiaryRows.class);
    assertTrue(diaryRows.collector == Beans.get(BgVatDocumentCollector.class), "един колектор");
    assertTrue(
        Beans.get(com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesReader.class) != null);

    List<BgVatMoveDiaryRows.Row> rows = tx(() -> diaryRows.rows(posted));
    assertEquals(1, rows.size());
    BgVatMoveDiaryRows.Row row = rows.get(0);
    assertTrue(row.isPurchases());
    assertTrue(!row.isSales());
    assertEquals("0000000001", row.getDocument().getDocumentNumber());
    eq("200", row.getPurchaseColumns().get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));

    com.axelor.apps.bglocalization.service.saft.BgSaftDiaryCells.Coding coding =
        com.axelor.apps.bglocalization.service.saft.BgSaftDiaryCells.purchases(
            row.getPurchaseColumns(), null, "ANNUAL_79A", null, null, null);
    assertEquals(List.of(), coding.getErrors());
    assertEquals(1, coding.getCells().size());
    assertEquals("03-31", coding.getCells().get(0).getKey());
    eq("0", coding.getCells().get(0).getBase());
    eq("200", coding.getCells().get(0).getTax());
  }

  // ---------------------------------------------------------------------------
  // Протокол по чл. 117 от покупна фактура с обратно начисляване
  // ---------------------------------------------------------------------------

  /**
   * Услугата с производственото окабеляване ({@code MoveCreateService}, {@code
   * MoveLineCreateService}, хранилищата, фискалния период, проверката за подадена декларация).
   * Подменени са само местата, които HSQLDB или липсата на потребител не допускат: търсенето на
   * протоколи (SELECT върху {@code Move}) и осчетоводяването — номерът се дава както в {@link
   * #post}.
   */
  private static BgVatInvoiceProtocolService invoiceProtocols() {
    return new BgVatInvoiceProtocolService(
        Beans.get(com.axelor.apps.account.service.move.MoveCreateService.class),
        Beans.get(com.axelor.apps.account.service.moveline.MoveLineCreateService.class),
        Beans.get(com.axelor.apps.account.service.move.MoveValidateService.class),
        Beans.get(MoveRepository.class),
        Beans.get(com.axelor.apps.account.db.repo.InvoiceRepository.class),
        Beans.get(com.axelor.apps.account.db.repo.AccountRepository.class),
        Beans.get(com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository.class),
        Beans.get(com.axelor.apps.base.db.repo.CurrencyConversionLineRepository.class)) {
      @Override
      protected List<Move> protocolsOf(com.axelor.apps.account.db.Invoice invoice) {
        return Collections.emptyList();
      }

      @Override
      protected Move manualProtocolFor(
          com.axelor.apps.account.db.Invoice invoice, BgVatInvoiceProtocolPlan.Group group) {
        return null;
      }

      @Override
      protected void account(Move move) {
        move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
        Beans.get(BgMoveRepository.class).assignProtocolNumber(move);
      }
    };
  }

  private static com.axelor.apps.account.db.Tax savedTax(String code, String percent) {
    // Tax.code е уникален, а HSQLDB е една база за целия JVM — данъкът се преизползва.
    com.axelor.apps.account.db.Tax existing =
        JPA.all(com.axelor.apps.account.db.Tax.class).filter("self.code = ?", code).fetchOne();
    if (existing != null) {
      return existing;
    }
    com.axelor.apps.account.db.Tax tax = new com.axelor.apps.account.db.Tax();
    tax.setCode(code);
    tax.setName(code);
    tax = JPA.save(tax);
    com.axelor.apps.account.db.TaxLine line = new com.axelor.apps.account.db.TaxLine();
    line.setTax(tax);
    line.setValue(new BigDecimal(percent));
    line.setStartDate(LocalDate.of(2026, 1, 1));
    line = JPA.save(line);
    tax.setActiveTaxLine(line);
    return JPA.save(tax);
  }

  /** Осчетоводена покупна фактура за ВОП 1000 / 20 % от германски доставчик. */
  private static com.axelor.apps.account.db.Invoice vopInvoice(Company company) {
    com.axelor.apps.account.db.Tax own = savedTax("BG_VOP_ACQ", "20");
    com.axelor.apps.account.db.Tax self = savedTax("BG_VOP_SELF", "20");
    com.axelor.apps.account.db.TaxEquiv equiv = new com.axelor.apps.account.db.TaxEquiv();
    equiv.setReverseCharge(true);
    equiv.setReverseChargeTaxSet(new HashSet<>(Collections.singleton(self)));
    equiv = JPA.save(equiv);

    Partner supplier = new Partner();
    supplier.setName("Lieferant GmbH");
    supplier.setFullName("Lieferant GmbH");
    supplier.setTaxNbr("DE123456789");
    supplier.setCompany(company);
    supplier = JPA.save(supplier);

    com.axelor.apps.account.db.Invoice invoice = new com.axelor.apps.account.db.Invoice();
    invoice.setCompany(company);
    invoice.setCurrency(company.getCurrency());
    invoice.setPartner(supplier);
    invoice.setOperationTypeSelect(1); // OPERATION_TYPE_SUPPLIER_PURCHASE
    invoice.setOperationSubTypeSelect(1); // OPERATION_SUB_TYPE_DEFAULT
    invoice.setStatusSelect(3); // STATUS_VENTILATED
    invoice.setNapDocumentTypeSelect("01");
    invoice.setSupplierInvoiceNb("RE-2026-77");
    invoice.setInvoiceDate(LocalDate.of(2026, 6, 20));
    invoice.setOriginDate(LocalDate.of(2026, 6, 12));
    invoice.setBgVatTaxEventDate(LocalDate.of(2026, 6, 10));
    invoice = JPA.save(invoice);

    com.axelor.apps.account.db.InvoiceLine line = new com.axelor.apps.account.db.InvoiceLine();
    line.setInvoice(invoice);
    line.setProductName("Машина");
    line.setQty(BigDecimal.ONE);
    line.setExTaxTotal(new BigDecimal("1000.00"));
    line.setCompanyExTaxTotal(new BigDecimal("1000.00"));
    line.setTaxLineSet(new HashSet<>(Collections.singleton(own.getActiveTaxLine())));
    line.setTaxEquiv(equiv);
    line.setBgVatCreditType("1");
    line = JPA.save(line);
    invoice.setInvoiceLineList(new ArrayList<>(Collections.singletonList(line)));
    return JPA.save(invoice);
  }

  @Test
  @DisplayName(
      "Протокол по чл. 117 от фактура за ВОП — истинските createMove/createMoveLine, номер, връзка, дневник")
  void протоколът_от_фактура_стига_до_дневника() {
    Move move =
        tx(
            () -> {
              Company company = company("117");
              com.axelor.apps.account.db.Invoice invoice = vopInvoice(company);
              try {
                BgVatInvoiceProtocolService.Result result =
                    invoiceProtocols().generate(invoice, null);
                assertEquals(List.of(), result.getErrors());
                assertEquals(1, result.getCreated().size());
                return result.getCreated().get(0);
              } catch (AxelorException e) {
                throw new IllegalStateException(e);
              }
            });
    assertServiceLines(move);
    assertEquals("0000000001", move.getBgVatProtocolNumber(), "номерът при осчетоводяване");
    assertEquals(MoveRepository.STATUS_ACCOUNTED, move.getStatusSelect());
    assertEquals("VOP", move.getBgVatMoveVatOperation());
    assertEquals("RE-2026-77", move.getOrigin());
    assertEquals(LocalDate.of(2026, 6, 20), move.getDate());
    eq("1000", move.getBgVatProtocolBase());

    // Връзката е в базата — колоната на Move, не Move.invoice; обратната посока е mappedBy на нея.
    Object link =
        tx(
            () ->
                JPA.em()
                    .createNativeQuery(
                        "SELECT bg_vat_source_invoice FROM account_move WHERE id = ?1")
                    .setParameter(1, move.getId())
                    .getSingleResult());
    assertEquals(move.getBgVatSourceInvoice().getId(), ((Number) link).longValue());
    assertEquals(null, move.getInvoice());

    List<BgVatDocumentDto> docs = collect(move);
    assertEquals(1, docs.size());
    BgVatDocumentDto doc = docs.get(0);
    assertEquals(List.of(), doc.getErrors());
    assertEquals("0000000001", doc.getDocumentNumber());
    eq(
        "1000",
        doc.getSalesAmounts()
            .get(com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn.K13_BASE_ICA));
    eq(
        "200",
        doc.getSalesAmounts()
            .get(
                com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn
                    .K15_VAT_ICA_RC));
    eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("200", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  /** Истинската услуга — без подмени; за заявките ѝ. */
  private static BgVatInvoiceProtocolService realInvoiceProtocols() {
    return Beans.get(BgVatInvoiceProtocolService.class);
  }

  @Test
  @DisplayName(
      "Заявките на услугата: фактурите без протокол, подадената декларация, протоколите на фактурата")
  void заявките_на_услугата() {
    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 6, 30);
    // ⚠️ Всичко в една транзакция: Invoice и Company носят PostgreSQL формули, тоест HSQLDB не ги
    // ПРЕЗАРЕЖДА. Заявките за id-та и за броене вървят; на записаното се прави flush.
    tx(
        () -> {
          Company company = company("Q117");
          com.axelor.apps.account.db.Invoice invoice = vopInvoice(company);
          JPA.flush();
          BgVatInvoiceProtocolService real = realInvoiceProtocols();
          // Фактурата с обратно начисляване и без протокол — истинската заявка.
          assertEquals(List.of(invoice.getId()), real.reverseChargeInvoiceIds(company, from, to));
          try {
            assertEquals(1, invoiceProtocols().generate(invoice, null).getCreated().size());
          } catch (AxelorException e) {
            throw new IllegalStateException(e);
          }
          JPA.flush();
          assertEquals(
              List.of(),
              real.reverseChargeInvoiceIds(company, from, to),
              "със свързан протокол фактурата не се изрежда отново");

          // Подадена декларация — истинската заявка върху BgVatReturn.
          assertEquals(false, real.isFiled(company, "202606"));
          com.axelor.apps.bglocalization.db.BgVatReturn filed =
              new com.axelor.apps.bglocalization.db.BgVatReturn();
          filed.setCompany(company);
          filed.setPeriod("202606");
          filed.setStatusSelect("submitted");
          JPA.save(filed);
          JPA.flush();
          assertEquals(true, real.isFiled(company, "202606"));
          assertEquals(false, real.isFiled(company, "202607"));
          return null;
        });

    // Заявките върху Move — само ПРЕВЕДЕНИ: изпълнението им на HSQLDB е невъзможно (формулите на
    // Move/Partner), а преводът на HQL хваща всяко сгрешено свойство.
    tx(
        () -> {
          for (String filter :
              List.of(
                  BgVatInvoiceProtocolService.PROTOCOLS_OF,
                  BgVatInvoiceProtocolService.MANUAL_PROTOCOLS)) {
            JPA.em().createQuery("SELECT self FROM Move self WHERE " + filter, Move.class);
          }
          return null;
        });
  }

  /** Декларация за периода — само полетата, които заявката чете. */
  private static BgVatReturn vatReturn(Company company, String status, BgVatReturn corrects) {
    BgVatReturn r = new BgVatReturn();
    r.setCompany(company);
    r.setPeriod("202606");
    r.setStatusSelect(status);
    r.setCorrectsPrevious(corrects);
    return JPA.save(r);
  }

  @Test
  @DisplayName(
      "т. 5 (Р8): отхвърлен оригинал + подадена коригираща → подаден; само отхвърлен или corrected → не")
  void подаденият_период() {
    tx(
        () -> {
          BgVatReturnRepository repo = Beans.get(BgVatReturnRepository.class);
          BgVatInvoiceProtocolService real = realInvoiceProtocols();
          Company company = company("R8");
          BgVatReturn original = vatReturn(company, "rejected", null);
          BgVatReturn correction = vatReturn(company, "corrected", original);
          JPA.flush();
          assertEquals(false, BgVatFiledPeriods.isFiled(repo, company, "202606"), "само rejected");
          correction.setStatusSelect("generated");
          JPA.flush();
          assertEquals(false, BgVatFiledPeriods.isFiled(repo, company, "202606"), "generated");
          correction.setStatusSelect("submitted");
          JPA.flush();
          assertEquals(true, BgVatFiledPeriods.isFiled(repo, company, "202606"));
          assertEquals(true, real.isFiled(company, "202606"), "протоколите — същото правило");
          assertEquals(false, BgVatFiledPeriods.isFiled(repo, company, "202607"));
          assertEquals(false, BgVatFiledPeriods.isFiled(repo, company("R8B"), "202606"));
          return null;
        });
  }

  @Test
  @DisplayName(
      "Закъснял протокол — истинските createMove/номер: статията на 12.06, издаден 05.07, дневникът с 05.07")
  void закъснелият_протокол_стига_до_юни() {
    Move move =
        tx(
            () -> {
              Company company = company("LATE");
              com.axelor.apps.account.db.Invoice invoice = vopInvoice(company);
              invoice.setInvoiceDate(LocalDate.of(2026, 7, 5)); // след срока 27.06
              try {
                BgVatInvoiceProtocolService.Result result =
                    invoiceProtocols().generate(invoice, null);
                assertEquals(List.of(), result.getErrors());
                return result.getCreated().get(0);
              } catch (AxelorException e) {
                throw new IllegalStateException(e);
              }
            });
    assertEquals(LocalDate.of(2026, 6, 12), move.getDate(), "периодът на изискуемостта");
    assertEquals(LocalDate.of(2026, 7, 5), move.getBgVatProtocolDate());
    for (MoveLine line : move.getMoveLineList()) {
      assertEquals(LocalDate.of(2026, 6, 12), line.getDate());
    }
    BgVatDocumentDto doc = collect(move).get(0);
    assertEquals(LocalDate.of(2026, 7, 5), doc.getDocumentDate());
    assertTrue(doc.isIssuedLate());
    eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
  }

  @Test
  @DisplayName(
      "Курсът на ЕЦБ: последният ред с дата ≤ изискуемостта (вкл. същия ден) — истинската заявка")
  void курсът_на_еЦБ() {
    BgVatProtocolRate rate =
        tx(
            () -> {
              Company company = company("FX");
              Currency usd = new Currency();
              usd.setName("Щатски долар FX");
              usd.setCode("$");
              usd.setCodeISO("UFX");
              usd = JPA.save(usd);
              // ЕЦБ публикува EUR → валута; записани в разбъркан ред, по-старият е първи.
              for (Object[] r :
                  new Object[][] {
                    {LocalDate.of(2026, 3, 1), "1.1000"},
                    {LocalDate.of(2026, 3, 13), "1.0500"},
                    {LocalDate.of(2026, 3, 12), "1.0700"},
                    {LocalDate.of(2026, 3, 11), "1.0800"}
                  }) {
                com.axelor.apps.base.db.CurrencyConversionLine line =
                    new com.axelor.apps.base.db.CurrencyConversionLine();
                line.setStartCurrency(company.getCurrency());
                line.setEndCurrency(usd);
                line.setFromDate((LocalDate) r[0]);
                line.setExchangeRate(new BigDecimal((String) r[1]));
                JPA.save(line);
              }
              JPA.flush();
              com.axelor.apps.account.db.Invoice invoice = new com.axelor.apps.account.db.Invoice();
              invoice.setCompany(company);
              invoice.setCurrency(usd);
              return Beans.get(BgVatInvoiceProtocolService.class)
                  .exchangeRate(invoice, LocalDate.of(2026, 3, 12));
            });
    // Курсът от самия ден на изискуемостта важи (да се потвърди); 13.03 е след нея.
    assertEquals(LocalDate.of(2026, 3, 12), rate.getDate());
    eq("934.58", rate.toCompanyCurrency(new BigDecimal("1000"))); // 1000 / 1.07
  }

  // ---------------------------------------------------------------------------
  // BG_COA: 4531/4532 са от тип „данък“ — статиите носят данъчен ред
  // ---------------------------------------------------------------------------

  /**
   * Дружеството като BG_COA: 4531 и 4532 от тип „данък“ (account_account.csv, тип 11) и данъците
   * BG_SRP_20 / BG_SRS_20 (account_tax.csv) с активен ред 20 %.
   */
  private static Company bgCoaCompany(String tag) {
    Company company = company(tag);
    AccountType taxType = new AccountType();
    taxType.setName("Tax " + tag);
    taxType.setTechnicalTypeSelect("tax");
    taxType = JPA.save(taxType);
    for (Account a :
        JPA.all(Account.class)
            .filter("self.company = ?1 AND self.code IN ('4531', '4532')", company)
            .fetch()) {
      a.setAccountType(taxType);
      JPA.save(a);
    }
    for (String code : new String[] {"BG_SRP_20", "BG_SRS_20"}) {
      if (JPA.all(Tax.class).filter("self.code = ?1", code).count() == 0) {
        Tax tax = new Tax();
        tax.setCode(code);
        tax.setName(code);
        tax = JPA.save(tax);
        TaxLine line = new TaxLine();
        line.setTax(tax);
        line.setValue(new BigDecimal("20"));
        line.setStartDate(LocalDate.of(2020, 1, 1));
        line = JPA.save(line);
        tax.setActiveTaxLine(line);
        JPA.save(tax);
      }
    }
    return company;
  }

  /** Проверката на AOS, която отказваше статията при осчетоводяване („Tax line must be filled“). */
  private static void checkTaxLines(Move move) {
    try {
      Beans.get(MoveLineTaxService.class).checkEmptyTaxLines(move.getMoveLineList());
    } catch (Exception e) {
      throw new AssertionError("статията не се осчетоводява с BG_COA: " + e.getMessage(), e);
    }
  }

  private static String taxOf(Move move, String accountCode) {
    return move.getMoveLineList().stream()
        .filter(l -> accountCode.equals(l.getAccount().getCode()))
        .map(
            l ->
                l.getTaxLineSet().isEmpty()
                    ? null
                    : l.getTaxLineSet().iterator().next().getTax().getCode())
        .findFirst()
        .orElse("няма ред");
  }

  @Test
  @DisplayName(
      "BG_COA: протоколът по чл. 79а се осчетоводява — 4531 носи данъка на кредита, к.11 = 200 както"
          + " преди")
  void чл79а_с_bg_coa_сметки_се_осчетоводява() {
    Move move =
        tx(
            () -> {
              Company company = bgCoaCompany("79A-COA");
              BgVatAnnualCorrection correction = new BgVatAnnualCorrection();
              correction.setCompany(company);
              correction.setYear(2026);
              correction = JPA.save(correction);
              BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
              line.setAnnualCorrection(correction);
              line.setFixedAsset(asset(company));
              line.setCorrectionAmount(new BigDecimal("200.00"));
              line = JPA.save(line);
              try {
                Move generated = Beans.get(BgArt79aProtocolService.class).generate(line);
                checkTaxLines(generated);
                return generated;
              } catch (AssertionError e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertEquals("BG_SRP_20", taxOf(move, "4531"));
    Move posted = post(move);
    List<BgVatDocumentDto> docs = collect(posted);
    assertEquals(1, docs.size(), "един документ в дневниците");
    eq("200", docs.get(0).getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertTrue(docs.get(0).getSalesAmounts().isEmpty(), "нищо в дневника за продажби");
  }

  @Test
  @DisplayName(
      "BG_COA: приспадането по чл. 79 се осчетоводява — 4531 носи данъка на кредита, к.11 = 480"
          + " както преди")
  void чл79_с_bg_coa_сметки_се_осчетоводява() {
    Move move =
        tx(
            () -> {
              Company company = bgCoaCompany("79-COA");
              BgFixedAssetWriteOff writeOff = new BgFixedAssetWriteOff();
              writeOff.setCompany(company);
              writeOff.setFixedAsset(asset(company));
              writeOff.setWriteOffDate(DATE);
              writeOff.setTaxYear(2026);
              writeOff.setDocumentNumber("П-79-COA");
              writeOff.setVatCorrectionDirection(BgArt79Direction.DEDUCTION);
              writeOff.setVatCorrectionAmount(new BigDecimal("480.00"));
              writeOff = JPA.save(writeOff);
              try {
                Move generated = Beans.get(BgArt79ProtocolService.class).generate(writeOff);
                checkTaxLines(generated);
                return generated;
              } catch (AssertionError e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertEquals("BG_SRP_20", taxOf(move, "4531"));
    Move posted = post(move);
    List<BgVatDocumentDto> docs = collect(posted);
    assertEquals(1, docs.size(), "един документ в дневниците");
    eq("480", docs.get(0).getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertTrue(docs.get(0).getSalesAmounts().isEmpty(), "нищо в дневника за продажби");
  }
}
