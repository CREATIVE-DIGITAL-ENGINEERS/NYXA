package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipCapUsage;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSocialExpenseCap;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * СОЦИАЛНИТЕ РАЗХОДИ ПРЕЗ ИСТИНСКИЯ ВНОС И ИСТИНСКАТА БАЗА (HSQLDB).
 *
 * <p><b>⚠️ ЗАЩО СЪЩЕСТВУВА.</b> {@code BgSocialExpenseDbTest} пита ЖИВА PostgreSQL база и се
 * ПРОПУСКА навсякъде, където такава няма — в гейта и в CI. Тоест твърденията му („капачките и
 * перата са доставени“) там не се изпълняват. Тук същото се пита през ВНОСА: входовете се вадят от
 * самия {@code bg-localization-config.xml} (със същите {@code <bind>}-ове) и се пускат през {@code
 * CSVImporter} — пътя, по който seed-ът стига и до заварените бази при рестарт ({@code
 * DataInitSyncService}).
 *
 * <p>И трите неща, които живеят САМО в базата: колоната {@code bgIsCapExcess} стига ли чрез
 * авто-мапването; JPQL заявката в {@code BgSocialExpenseService#query} назовава ли съществуващо
 * поле (грешка там пада едва при изпълнение); и пазачът при запис в {@code
 * BgPaySlipManagementRepository}.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSocialExpensePersistenceTest extends JpaSupport {

  private static final String CONFIG = "/apps/init-data/bg-localization-config.xml";
  private static final String MARKER = "/apps/init-data/en/bg_social_expense_cap.csv";

  /** Само входовете на 2xx и капачките — в реда на конфига, с неговите bind-ове. */
  private static final Set<String> INPUTS =
      new LinkedHashSet<>(
          Arrays.asList(
              "bg_social_expense_cap.csv",
              "bg_pay_slip_line_type.csv",
              "bg_pay_slip_line_type_social.csv"));

  private static final List<String> CAP_EXCESS =
      Arrays.asList(
          "pl_soc_ddo_previshenie",
          "pl_soc_dzo_previshenie",
          "pl_soc_pomosht_brak_previshenie",
          "pl_soc_pomosht_razhdane_previshenie",
          "pl_soc_pomosht_smart_previshenie",
          "pl_soc_vaucheri_previshenie",
          "pl_soc_zhivot_previshenie");

  // ── вносът ─────────────────────────────────────────────────────────────

  /** Конфигът на модула, орязан до {@link #INPUTS} — bind-овете остават същите байтове. */
  private File trimmedConfig() throws Exception {
    Document doc;
    try (InputStream in = getClass().getResourceAsStream(CONFIG)) {
      assertNotNull(in, "липсва " + CONFIG);
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      doc = f.newDocumentBuilder().parse(in);
    }
    Element root = doc.getDocumentElement();
    List<Node> drop = new ArrayList<>();
    Set<String> kept = new LinkedHashSet<>();
    NodeList children = root.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n instanceof Element && "input".equals(n.getLocalName())) {
        String file = ((Element) n).getAttribute("file");
        if (INPUTS.contains(file)) {
          kept.add(file);
          continue;
        }
      }
      drop.add(n);
    }
    for (Node n : drop) {
      root.removeChild(n);
    }
    assertEquals(INPUTS, kept, "входовете на 2xx трябва да са в " + CONFIG);
    Path tmp = Files.createTempFile("bg-social-expense-config", ".xml");
    TransformerFactory.newInstance()
        .newTransformer()
        .transform(new DOMSource(doc), new StreamResult(tmp.toFile()));
    return tmp.toFile();
  }

  private void runImport() throws Exception {
    URL marker = getClass().getResource(MARKER);
    assertNotNull(marker, "липсва " + MARKER);
    File dataDir = new File(marker.toURI()).getParentFile();
    File cfg = trimmedConfig();
    try {
      new CSVImporter(cfg.getAbsolutePath(), dataDir.getAbsolutePath(), null).run();
    } finally {
      Files.deleteIfExists(cfg.toPath());
    }
    JPA.clear();
  }

  @BeforeEach
  void seed() throws Exception {
    if (JPA.all(BgSocialExpenseCap.class).count() == 0) {
      runImport();
    }
  }

  private static BgPaySlipLineType type(int number) {
    BgPaySlipLineType t =
        JPA.all(BgPaySlipLineType.class).filter("self.number = :n").bind("n", number).fetchOne();
    assertNotNull(t, "перо " + number + " не е внесено");
    return t;
  }

  private static BgSocialExpenseCap cap(String code) {
    BgSocialExpenseCap c =
        JPA.all(BgSocialExpenseCap.class).filter("self.code = :c").bind("c", code).fetchOne();
    assertNotNull(c, "капачка " + code + " не е внесена");
    return c;
  }

  private static final String TWO_XX = "self.number >= 200 AND self.number < 300";

  private static long types(String filter) {
    return JPA.all(BgPaySlipLineType.class).filter(filter).count();
  }

  private static List<String> codes(String filter) {
    List<String> out = new ArrayList<>();
    for (BgPaySlipLineType t : JPA.all(BgPaySlipLineType.class).filter(filter).fetch()) {
      out.add(t.getCode());
    }
    Collections.sort(out);
    return out;
  }

  // ── доставено ли е ─────────────────────────────────────────────────────

  @Test
  @DisplayName("ВНОС · капачките и 25-те пера от 2xx стигат до базата с флаговете си")
  void theCapsAndTheSocialExpenseLinesAreDelivered() {
    assertEquals(3, JPA.all(BgSocialExpenseCap.class).count(), "три капачки");
    BgSocialExpenseCap ddo = cap("DDO_HEALTH_LIFE");
    assertEquals(new BigDecimal("30.68"), ddo.getBgPersonalCapAmount().setScale(2));
    assertEquals(new BigDecimal("30.68"), ddo.getBgCompanyCapAmount().setScale(2));
    assertEquals("MONTHLY", ddo.getBgPeriodicity());
    assertTrue(ddo.getBgCompanyTaxApplies(), "ЗКПО чл. 204 ал. 1 т. 2 б. „а“ — фирмена ос");
    BgSocialExpenseCap vouchers = cap("FOOD_VOUCHERS");
    assertEquals(new BigDecimal("102.26"), vouchers.getBgPersonalCapAmount().setScale(2));
    assertEquals(new BigDecimal("102.26"), vouchers.getBgCompanyCapAmount().setScale(2));
    assertTrue(vouchers.getBgCompanyTaxApplies());
    BgSocialExpenseCap aid = cap("ONE_OFF_AID");
    assertEquals(new BigDecimal("1227.10"), aid.getBgPersonalCapAmount().setScale(2));
    assertEquals("ANNUAL", aid.getBgPeriodicity());
    assertEquals(Boolean.FALSE, aid.getBgCompanyTaxApplies(), "чл. 205 ЗКПО — без фирмена ос");

    assertEquals(25, types(TWO_XX));
    assertEquals(
        14,
        types(TWO_XX + " AND self.bgSocialExpenseCap IS NOT NULL"),
        "⚠️ липсващ FK значи перо, което резолверът НЕ вижда");
    assertEquals(6, types("self.bgSocialExpenseCap.code = 'DDO_HEALTH_LIFE'"));
    assertEquals(2, types("self.bgSocialExpenseCap.code = 'FOOD_VOUCHERS'"));

    assertEquals(
        Arrays.asList(
            "pl_soc_ne_v_natura_dohod",
            "pl_soc_pomosht_brak_previshenie",
            "pl_soc_pomosht_brak_v_ramkite",
            "pl_soc_transport_lek_avtomobil",
            "pl_soc_v_natura_oblozhen",
            "pl_soc_vaucheri_previshenie",
            "pl_soc_zhivot_previshenie",
            "pl_soc_zhivot_v_ramkite"),
        codes(TWO_XX + " AND self.bgIsInsuranceIncome = TRUE"),
        "осигурителният доход в 2xx — НЕВД чл. 2 ал. 1 и изключенията по ал. 2 и ал. 3 т. 1");
    assertEquals(
        Arrays.asList("pl_soc_transport_lek_avtomobil", "pl_soc_v_natura_oblozhen"),
        codes("self.bgIsCompanyExpenseTaxed = TRUE"),
        "целият размер в основата по ЗКПО — ТОЧНО два реда");

    BgPaySlipLineType counter = type(630);
    assertEquals("DEBIT", counter.getBgSign(), "контра-перото на натурата излиза от нетото");
    assertEquals(Boolean.FALSE, counter.getBgIsTaxable());
    assertEquals(Boolean.FALSE, counter.getBgIsInsuranceIncome());
  }

  @Test
  @DisplayName("ВНОС · ⚠️ bgIsCapExcess стига до базата; 201/203/205 са НЕоблагаеми, 211 — още не")
  void theCapExcessFlagAndTheTaxabilityFixReachTheDatabase() {
    assertEquals(
        CAP_EXCESS,
        codes("self.bgIsCapExcess = TRUE"),
        "⚠️ колоната се пълни от АВТО-МАПВАНЕТО, без изричен <bind> — само вносът го доказва");
    assertEquals(
        0,
        JPA.all(BgPaySlipLineType.class)
            .filter("self.bgSocialExpenseCap IS NOT NULL AND self.bgIsCapExcess IS NULL")
            .count(),
        "NULL на ред под капачка би се чел като „в рамките“");
    for (int number : new int[] {201, 203, 205}) {
      assertEquals(
          Boolean.FALSE,
          type(number).getBgIsTaxable(),
          number + " — ЗДДФЛ чл. 24 ал. 2 т. 9: обложено по ЗКПО чл. 213 ал. 2");
    }
    assertEquals(Boolean.TRUE, type(211).getBgIsTaxable(), "211 — остава облагаем, отворен въпрос");
    assertEquals(Boolean.TRUE, type(261).getBgIsTaxable(), "261 — остава облагаем, отворен въпрос");
  }

  @Test
  @DisplayName("ВНОС · ⚠️ заварена база (201 облагаем, без bgIsCapExcess) се поправя при вноса")
  void anExistingDatabaseIsCorrectedByTheStartupResync() throws Exception {
    JPA.runInTransaction(
        () -> {
          for (int number : new int[] {201, 203, 205}) {
            BgPaySlipLineType t = JPA.em().find(BgPaySlipLineType.class, type(number).getId());
            t.setBgIsTaxable(true);
            t.setBgIsCapExcess(null);
          }
        });
    JPA.clear();
    assertEquals(Boolean.TRUE, type(201).getBgIsTaxable(), "заварено състояние");

    // Същият внос, който DataInitSyncService пуска при всяко вдигане: upsert по importId.
    runImport();

    for (int number : new int[] {201, 203, 205}) {
      BgPaySlipLineType t = type(number);
      assertEquals(Boolean.FALSE, t.getBgIsTaxable(), number + " — поправено от seed-а");
      assertEquals(Boolean.TRUE, t.getBgIsCapExcess(), number + " — признакът е попълнен");
    }
    assertEquals(25, types(TWO_XX), "без дубликати");
  }

  // ── сметката през базата ───────────────────────────────────────────────

  private Long contractId;
  private Long companyId;
  private Long marchId;
  private Long januaryId;

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Фирма " + tag);
          company.setCode("S" + tag);
          JPA.em().persist(company);
          // ⚠️ Без Partner: HSQLDB не изпълнява PostgreSQL-формулите му, а JPA.clear() по-долу
          // кара всяко следващо четене на договора да го зареди наново.
          Employee employee = new Employee();
          JPA.em().persist(employee);
          EmploymentContract contract = new EmploymentContract();
          contract.setEmployee(employee);
          contract.setPayCompany(company);
          contract.setBgInsuredTypeCode("01");
          contract.setBgWorkTimeMode("1");
          contract.setBgHoursPerDay(new BigDecimal("8.00"));
          JPA.em().persist(contract);
          Year year = new Year();
          year.setName("Заплатна 2026 " + tag);
          year.setCode("SY-" + tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setTypeSelect(2);
          JPA.em().persist(year);
          Period january = period(year, tag, 1);
          Period march = period(year, tag, 3);
          contractId = contract.getId();
          companyId = company.getId();
          januaryId = january.getId();
          marchId = march.getId();
        });
    JPA.clear();
    // ⚠️ С роля от каталога (all.write) в дружеството на фиша: записът минава през пазача на
    // активното дружество в репозитория — без вписан потребител фиш не се записва.
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, companyId));
  }

  private static Period period(Year year, String tag, int month) {
    Period p = new Period();
    p.setName(month + "/2026 " + tag);
    p.setYear(year);
    p.setFromDate(LocalDate.of(2026, month, 1));
    p.setToDate(LocalDate.of(2026, month, 1).plusMonths(1).minusDays(1));
    JPA.em().persist(p);
    return p;
  }

  private BgPaySlip slip(Long periodId, String status, String tag) {
    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-SOC-" + tag + "-" + System.nanoTime());
    s.setStatusSelect(status);
    s.setEmploymentContract(JPA.em().find(EmploymentContract.class, contractId));
    s.setPeriod(JPA.em().find(Period.class, periodId));
    s.setBgPaySlipLineList(new ArrayList<>());
    // Договорът тук е без заплата: авто-попълването щеше да откаже ПРЕДИ пазача, който се мери.
    s.setBgAutoFillBaseSalary(false);
    return s;
  }

  /** Перо от ВНЕСЕНИЯ речник, през истинския lineFrom — флаговете идват от базата. */
  private static BgPaySlipLine line(BgPaySlip slip, int number, String amount) {
    BgPaySlipService service = Beans.get(BgPaySlipService.class);
    BgPaySlipLine l = service.lineFrom(type(number), new BigDecimal(amount));
    service.addLine(slip, l);
    return l;
  }

  @Test
  @DisplayName("БАЗА · ⚠️ годишната капачка брои ОСВОБОДЕНОТО от замразения ред, не перата — JPQL")
  void theAnnualCapCountsTheFrozenExemptPart() {
    scene();
    JPA.runInTransaction(
        () -> {
          BgPaySlip january = slip(januaryId, BgPaySlipResolver.CONFIRMED, "JAN");
          line(january, 220, "1500.00");
          BgPaySlipCapUsage row = new BgPaySlipCapUsage();
          row.setBgSocialExpenseCap(cap("ONE_OFF_AID"));
          row.setBgUsedBefore(new BigDecimal("0.00"));
          row.setBgExempt(new BigDecimal("1000.00"));
          january.addBgCapUsageListItem(row);
          JPA.em().persist(january);
        });
    JPA.clear();

    BgPaySlip march = slip(marchId, BgPaySlipResolver.DRAFT, "MAR");
    BigDecimal used = new BgSocialExpenseService().usedBefore(march, cap("ONE_OFF_AID"));
    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(used),
        "⚠️ освободеното (1000,00), не въведеното на перото (1500,00): " + used);
  }

  @Test
  @DisplayName("БАЗА · 100 лв. ДДО + 250 лв. ваучери → основа по ЗКПО 46,01 € през внесения речник")
  void theExampleThroughTheImportedCatalogue() {
    scene();
    BgPaySlip march = slip(marchId, BgPaySlipResolver.DRAFT, "EX");
    line(march, 200, "51.13");
    line(march, 210, "127.82");

    assertEquals(
        new BigDecimal("46.01"),
        Beans.get(BgSocialExpenseService.class).monthlyTaxBase(march),
        "(51,13 − 30,68) + (127,82 − 102,26) — ЗКПО чл. 213 ал. 2 и чл. 214 ал. 2;"
            + " условията — от празната таблица с изключения (истинската заявка)");
  }

  // ── ръчното превишение ─────────────────────────────────────────────────

  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  @Test
  @DisplayName("ЗАПИС · ⚠️ нов ръчен 201 не се записва; заварен се записва, но преходите отказват")
  void theRepositoryAndTheTransitionsRefuseTheManualExcess() {
    scene();

    // Нов ред 201 през истинското хранилище — отказ.
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                JPA.runInTransaction(
                    () -> {
                      BgPaySlip draft = slip(marchId, BgPaySlipResolver.DRAFT, "NEW");
                      line(draft, 200, "30.68");
                      line(draft, 201, "20.45");
                      Beans.get(BgPaySlipRepository.class).save(draft);
                    }));
    assertTrue(chain(refused).contains("201"), chain(refused));
    JPA.clear();
    assertEquals(
        0,
        JPA.all(BgPaySlip.class).filter("self.reference LIKE 'FISH-SOC-NEW-%'").count(),
        "нищо не е записано");

    // Стара чернова: редът е влязъл ПРЕДИ пазача (тук — покрай хранилището).
    Long[] oldId = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgPaySlip old = slip(marchId, BgPaySlipResolver.DRAFT, "OLD");
          line(old, 200, "30.68");
          line(old, 201, "20.45");
          JPA.em().persist(old);
          oldId[0] = old.getId();
        });
    JPA.clear();

    // Записът ѝ минава — иначе редът нямаше как да се махне.
    JPA.runInTransaction(
        () -> {
          BgPaySlip old = JPA.em().find(BgPaySlip.class, oldId[0]);
          old.setNote("пипната");
          Beans.get(BgPaySlipRepository.class).save(old);
        });
    JPA.clear();

    BgPaySlip old = JPA.em().find(BgPaySlip.class, oldId[0]);
    BgCalculationRefusedException calc =
        assertThrows(
            BgCalculationRefusedException.class,
            () -> Beans.get(BgPaySlipService.class).calculate(old));
    assertTrue(calc.getMessage().contains("201"), calc.getMessage());

    JPA.clear();
    BgPaySlip again = JPA.em().find(BgPaySlip.class, oldId[0]);
    try {
      Beans.get(BgPaySlipService.class).confirm(again);
      fail("„Потвърди“ трябваше да откаже ръчния 201");
    } catch (AxelorException e) {
      assertTrue(e.getMessage().contains("201"), e.getMessage());
    }
    JPA.clear();
    BgPaySlip after = JPA.em().find(BgPaySlip.class, oldId[0]);
    assertEquals(BgPaySlipResolver.DRAFT, after.getStatusSelect(), "остава чернова");
    assertEquals(2, after.getBgPaySlipLineList().size(), "⚠️ редът НЕ е изтрит тихо");
  }
}
