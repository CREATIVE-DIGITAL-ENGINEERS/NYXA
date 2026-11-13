package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.d1.BgCompensationParts;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБЕЗЩЕТЕНИЕТО ПО ЧЛ. 222 АЛ. 1 КТ НА ФИША — вноските по вид 27, отделно от заплатата.
 *
 * <p><b>Проблемът (задача „Обезщетения [2/3]“):</b> перото 900 влизаше в осигурителния доход на вид
 * 01 и носеше ЦЕЛИЯ пакет — ОЗМ, безработица, ТЗПБ, здравно, с част за лицето. Вид 27 дължи САМО
 * Пенсии и УПФ (КСО чл. 9 ал. 3 т. 5): образецът 8,22/6,58 и 2,80/2,20.
 *
 * <p><b>Тук:</b> истинската услуга и истинският генератор на пера; подменени са само заредените
 * редове на каталога (от seed-а), речникът на перата и входът.
 */
class BgCompensation27PayslipTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final String CREDIT = BgPaySlipResolver.CREDIT;
  private static final String DEBIT = BgPaySlipResolver.DEBIT;

  // ═══ 01-ОСНОВАТА И ВНОСКИТЕ ПО ВИД 27 ══════════════════════════════════

  @Test
  @DisplayName("⚠️ основата по вид 01 е БЕЗ обезщетението: 1500,00, не 2577,00")
  void theRegularBaseLeavesTheCompensationOut() {
    BgPaySlip slip = mixedSlip(compensation("comp_kt_222_1", "27"));
    assertEquals(0, new BigDecimal("1500.00").compareTo(new Service().insuranceGross(slip)));
  }

  @Test
  @DisplayName(
      "вноските върху 1077,00 — вид 27, comp_kt_222_1, кат. 3, без ТЗПБ: САМО Пенсии и УПФ,"
          + " 88,53/70,87 и 30,16/23,69, белязани с документа")
  void theCompensationIsInsuredAsType27Only() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    BgPaySlip slip = mixedSlip(doc);
    Service service = new Service();

    service.generateContributions(slip);

    BgContributionRequest request = service.requestFor("27");
    assertNotNull(request, "заявка по вид 27 трябва да има: " + service.seen);
    assertEquals("comp_kt_222_1", request.getCompensationTypeCode());
    assertEquals("3", request.getLaborCategory());
    assertNull(request.getTzpbRate(), "⚠️ ТЗПБ не се долепя — работодател по фонда няма");
    assertEquals(0, new BigDecimal("1077.00").compareTo(request.getInsurableIncome()));

    Map<Integer, BgPaySlipLine> tagged = taggedWith(slip, doc);
    assertEquals(4, tagged.size(), "⚠️ само Пенсии и УПФ — не пълният пакет: " + describe(slip));
    amount(tagged.get(501), "88.53", "Пенсии, работодател 8,22 %");
    amount(tagged.get(502), "70.87", "Пенсии, лице 6,58 %");
    amount(tagged.get(508), "30.16", "УПФ, работодател 2,80 %");
    amount(tagged.get(509), "23.69", "УПФ, лице 2,20 %");
    for (BgPaySlipLine line : tagged.values()) {
      assertEquals(0, new BigDecimal("1077.00").compareTo(line.getBgQuantity()), "база 1077,00");
    }
  }

  @Test
  @DisplayName(
      "⚠️ ОБЩ ТАВАН (КСО чл. 6 ал. 11): вид 01 = 2000,00 при таван 2111,64 → вид 27 се"
          + " осигурява върху 111,64 (март) + 796,02 (април) = 907,66, не върху 1077,00")
  void type27IsInsuredOnlyUpToTheCeilingLeftByType01() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    doc.setBgEventDate(LocalDate.of(2026, 3, 23));
    BgPaySlip slip = mixedSlip(doc, "2000.00");
    BigDecimal ceiling = new BigDecimal("2111.64");
    Service service = new Service();
    // Като BgCompensationCalendar#roomFor: вид 01 на лицето е само на носителя, през март. ⚠️ Чете
    // се при начисляването на вид 27 — тоест перата на вид 01 трябва вече да са на фиша.
    service.room =
        month ->
            month.equals(YearMonth.of(2026, 3))
                ? ceiling.subtract(BgCompensationParts.regularBase(slip))
                : ceiling;

    service.generateContributions(slip);

    BgContributionRequest request = service.requestFor("27");
    assertNotNull(request, "заявка по вид 27: " + service.seen);
    assertEquals(
        0,
        new BigDecimal("907.66").compareTo(request.getInsurableIncome()),
        "март 6 р.д. × 46,83 = 280,98 → под тавана 111,64; април — остатъкът 796,02: "
            + request.getInsurableIncome());
    Map<Integer, BgPaySlipLine> tagged = taggedWith(slip, doc);
    amount(tagged.get(501), "74.61", "Пенсии, работодател 8,22 % от 907,66");
    amount(tagged.get(502), "59.72", "Пенсии, лице 6,58 %");
    amount(tagged.get(508), "25.41", "УПФ, работодател 2,80 %");
    amount(tagged.get(509), "19.97", "УПФ, лице 2,20 %");
    amount(untagged(slip, 501), "164.40", "⚠️ вид 01 НЕ се реже: 2000,00 × 8,22 %");
    assertNull(
        service.contributionBaseMismatch(slip, new BigDecimal("2000.00")),
        "пазачът при потвърждаване сверява вид 27 със СЪЩАТА сметка");
  }

  @Test
  @DisplayName("вноските по вид 01 върху заплатата остават — същите типове 501/502, друг ключ")
  void theRegularContributionsAreNotOverwritten() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    BgPaySlip slip = mixedSlip(doc);
    Service service = new Service();

    service.generateContributions(slip);
    service.generateContributions(slip);

    int pension501 = 0;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == 501) {
        pension501++;
      }
    }
    assertEquals(2, pension501, "един 501 за заплатата и един за обезщетението: " + describe(slip));
    BgPaySlipLine regular = untagged(slip, 501);
    amount(regular, "123.30", "Пенсии върху заплатата 1500,00 × 8,22 %");
    assertEquals(
        4, taggedWith(slip, doc).size(), "⚠️ идемпотентно — второто пресмятане не дублира");
  }

  @Test
  @DisplayName("фиш само с обезщетение → вноски по вид 01 НЯМА (дори МОД); вид 27 — да")
  void aCompensationOnlySlipHasNoType01Contributions() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    slip.getBgPaySlipLineList().add(compensationLine(doc, "1800.00"));
    Service service = new Service();
    service.realContributions = true;

    BgContributionResult regular = service.contributions(slip);
    assertTrue(regular.isResolved(), "разрешено — празно");
    assertTrue(regular.getLines().isEmpty(), "⚠️ правоотношението е прекратено: " + regular);
    assertTrue(BgCompensationParts.onlyCompensation(slip), "фишът носи само обезщетението");
  }

  // ═══ СТЕСНЕНИТЕ ЧЕТЦИ НА ПРЕПРАТКАТА — само перото 9xx ═══════════════════

  @Test
  @DisplayName("⚠️ „вече платено с друг фиш“ гледа ПЕРОТО 9xx, не вноската със същата препратка")
  void doubleCompensationLooksAtTheCompensationLineOnly() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    doc.setId(77L);
    BgPaySlip onlyContribution = new BgPaySlip();
    onlyContribution.setBgPaySlipLineList(new ArrayList<>());
    onlyContribution.getBgPaySlipLineList().add(contribution(doc, 501, CREDIT, "8.22", "88.53"));
    Service service = new Service();
    service.otherSlip = "ФИШ-ДРУГ";

    assertNull(
        service.doubleCompensationRefusal(onlyContribution),
        "вноската не е обезщетение — второ плащане няма");

    BgPaySlip withLine = mixedSlip(doc);
    assertNotNull(service.doubleCompensationRefusal(withLine), "перото 9xx — отказ, както досега");
  }

  @Test
  @DisplayName(
      "⚠️ пазачът по чл. 9 ал. 3 т. 1–2 гледа ПЕРОТО 9xx, не вноската със същата препратка")
  void art9Para3LooksAtTheCompensationLineOnly() {
    BgCompensation doc = compensation("comp_kt_225_1", "18");
    doc.getBgCompensationType().setLegalBasis("чл. 225 ал. 1 КТ");
    BgPaySlip onlyContribution = new BgPaySlip();
    onlyContribution.setBgPaySlipLineList(new ArrayList<>());
    onlyContribution.getBgPaySlipLineList().add(contribution(doc, 501, CREDIT, "8.22", "88.53"));

    assertNull(new Service().art9Para3Refusal(onlyContribution), "вноската не е обезщетение");

    BgPaySlip withLine = new BgPaySlip();
    withLine.setBgPaySlipLineList(new ArrayList<>());
    withLine.getBgPaySlipLineList().add(compensationLine(doc, "3000.00"));
    assertNotNull(new Service().art9Para3Refusal(withLine), "перото 9xx — отказ, както досега");
  }

  @Test
  @DisplayName("⚠️ изплащането маркира PAID по ПЕРОТО 9xx — веднъж, не по веднъж на вноска")
  void payMarksTheDocumentFromTheCompensationLineOnce() throws Exception {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    BgPaySlip slip = mixedSlip(doc);
    slip.getBgPaySlipLineList().add(contribution(doc, 501, CREDIT, "8.22", "88.53"));
    slip.getBgPaySlipLineList().add(contribution(doc, 502, DEBIT, "6.58", "70.87"));
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setBgPaidDate(LocalDate.of(2026, 3, 31));
    Service service = new Service();

    service.pay(slip);

    assertEquals(1, service.compensationSaves, "⚠️ по веднъж на документ, през перото 9xx");
    assertEquals(BgCompensationResolver.PAID, doc.getStatusSelect());

    BgCompensation orphan = compensation("comp_kt_222_1", "27");
    BgPaySlip onlyContribution = new BgPaySlip();
    onlyContribution.setBgPaySlipLineList(new ArrayList<>());
    onlyContribution.getBgPaySlipLineList().add(contribution(orphan, 501, CREDIT, "8.22", "88.53"));
    onlyContribution.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    onlyContribution.setBgPaidDate(LocalDate.of(2026, 3, 31));
    new Service().pay(onlyContribution);
    assertNull(orphan.getBgPaidDate(), "вноската не изплаща обезщетение");
  }

  @Test
  @DisplayName("⚠️ генераторът 9xx намира СВОЕТО перо по препратката — не вноската 5xx пред него")
  void theCompensationGeneratorFindsItsOwnLineNotTheContribution() {
    BgCompensation doc = compensation("comp_kt_222_1", "27");
    doc.setBgFinalAmount(new BigDecimal("1077.00"));
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    BgPaySlipLine contribution = contribution(doc, 501, CREDIT, "8.22", "88.53");
    slip.getBgPaySlipLineList().add(contribution);
    BgPaySlipLine compensationLine = compensationLine(doc, "1000.00");
    slip.getBgPaySlipLineList().add(compensationLine);

    new CompensationLines(doc).generate(slip, BgPayrollInput.builder().build());

    amount(compensationLine, "1077.00", "перото 900 получава сумата на документа");
    amount(contribution, "88.53", "⚠️ вноската НЕ е пипната");
    assertEquals(2, slip.getBgPaySlipLineList().size(), "нов ред не е създаден");
  }

  // ═══ УСЛУГИТЕ С ПОДМЕНЕНИ ШЕВОВЕ ═══

  static final class Service extends BgPaySlipService {
    final List<BgContributionRequest> seen = new ArrayList<>();
    boolean realContributions;
    String otherSlip;
    int compensationSaves;

    final BgPayrollInput input =
        BgPayrollInput.builder()
            .insuredTypeCode("01")
            .laborCategory("3")
            .bornAfter1959(true)
            .parameterReferenceDate(FROM)
            .build();

    BgContributionRequest requestFor(String insuredType) {
      for (BgContributionRequest r : seen) {
        if (insuredType.equals(r.getInsuredTypeCode())) {
          return r;
        }
      }
      return null;
    }

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    /** Обикновените вноски — вид 01 върху 1500,00, през истинския резолвер. */
    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      if (realContributions) {
        return super.contributions(slip);
      }
      return calculate(
          new BgContributionRequest(
              insuranceGross(slip), "01", "3", true, null, "ANY", FROM, null, null, null));
    }

    @Override
    protected BgContributionResult calculate(BgContributionRequest request) {
      seen.add(request);
      return BgContributionResolver.resolve(
          seedRows(request.getInsuredTypeCode(), request.getCompensationTypeCode()), request);
    }

    @Override
    protected BgContributionLineService contributionLineService() {
      return new ContributionLines();
    }

    /** Мястото под тавана — по подразбиране таванът не реже. */
    BgCompensationParts.Room room = month -> new BigDecimal("1000000.00");

    @Override
    protected BgCompensationParts.Room compensationRoom(BgPaySlip slip) {
      return room;
    }

    /** Работните дни — делниците, без празници (свидетелите тук не минават през празник). */
    @Override
    protected BgCompensationParts.WorkingDays compensationCalendar() {
      return (from, to) -> {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
          if (d.getDayOfWeek().getValue() <= 5) {
            days++;
          }
        }
        return days;
      };
    }

    @Override
    protected void generateUnpaidLeaveHealth(BgPaySlip slip) {}

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return otherSlip;
    }

    @Override
    protected void saveCompensation(BgCompensation doc) {
      compensationSaves++;
    }

    @Override
    protected void generatePayrollMove(BgPaySlip slip) {}

    @Override
    protected void save(BgPaySlip slip) {}
  }

  /** Генераторът 5xx с речник в паметта — типът по двойката фонд + знак, както в seed-а. */
  static final class ContributionLines extends BgContributionLineService {
    private static final Map<String, Integer> NUMBERS = new LinkedHashMap<>();

    static {
      NUMBERS.put("PENSION/CREDIT", 501);
      NUMBERS.put("PENSION/DEBIT", 502);
      NUMBERS.put("OZM/CREDIT", 503);
      NUMBERS.put("OZM/DEBIT", 504);
      NUMBERS.put("UNEMPLOYMENT/CREDIT", 505);
      NUMBERS.put("UNEMPLOYMENT/DEBIT", 506);
      NUMBERS.put("TZPB/CREDIT", 507);
      NUMBERS.put("UPF/CREDIT", 508);
      NUMBERS.put("UPF/DEBIT", 509);
      NUMBERS.put("HEALTH/CREDIT", 510);
      NUMBERS.put("HEALTH/DEBIT", 511);
      NUMBERS.put("GVRS/CREDIT", 512);
    }

    @Override
    protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
      Integer number = NUMBERS.get(fundCode + "/" + sign);
      if (number == null) {
        return null;
      }
      BgContributionFund fund = new BgContributionFund();
      fund.setCode(fundCode);
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode("pl_" + number);
      type.setNumber(number);
      type.setBgSign(sign);
      type.setBgContributionFund(fund);
      return type;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(false);
      line.setBgIsInsuranceIncome(false);
      line.setBgAmount(amount);
      return line;
    }
  }

  /** Генераторът 9xx с подменени документ, речник и перо. */
  static final class CompensationLines extends BgCompensationLineService {
    private final BgCompensation doc;

    CompensationLines(BgCompensation doc) {
      this.doc = doc;
    }

    @Override
    protected List<BgCompensation> documents(BgPayrollInput input) {
      return Collections.singletonList(doc);
    }

    @Override
    protected BgPaySlipLineType lineTypeFor(BgCompensationType kind) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode("pl_obez_kt_222_1");
      type.setNumber(900);
      type.setBgSign(CREDIT);
      type.setBgIsTaxable(true);
      type.setBgIsInsuranceIncome(true);
      return type;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgLineNumber(type.getNumber());
      line.setBgAmount(amount);
      return line;
    }
  }

  // ═══ seed-ът и строителите ═══

  /** Редовете на каталога за вида и основанието — III категория, с УПФ. */
  static List<BgInsuranceContribution> seedRows(String insuredType, String compensationCode) {
    String basis = compensationCode == null ? "STANDARD" : compensationCode;
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!insuredType.equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !"3".equals(BgSeed.col(h, r, "laborCategory"))
          || !basis.equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      if (!"ANY".equals(upf) && !"WITH_UPF".equals(upf)) {
        continue;
      }
      if (!"ANY".equals(BgSeed.col(h, r, "bgSolScope"))
          || !"ANY".equals(BgSeed.col(h, r, "bgTeacherScope"))) {
        continue;
      }
      String validTo = BgSeed.col(h, r, "validTo");
      LocalDate from = LocalDate.parse(BgSeed.col(h, r, "validFrom"));
      if (from.isAfter(FROM) || (!validTo.isEmpty() && LocalDate.parse(validTo).isBefore(FROM))) {
        continue;
      }
      BgInsuranceContribution c = new BgInsuranceContribution();
      c.setImportId(BgSeed.col(h, r, "importId"));
      c.setFundCode(BgSeed.col(h, r, "fundCode"));
      c.setEmployerRate(new BigDecimal(BgSeed.col(h, r, "employerRate")));
      c.setEmployeeRate(new BigDecimal(BgSeed.col(h, r, "employeeRate")));
      c.setBgEmployerRateKind(BgSeed.col(h, r, "bgEmployerRateKind"));
      c.setBgEmployeeRateKind(BgSeed.col(h, r, "bgEmployeeRateKind"));
      out.add(c);
    }
    return out;
  }

  static BgCompensation compensation(String code, String d1Code) {
    BgCompensationType type = new BgCompensationType();
    type.setCode(code);
    type.setD1Code(d1Code);
    type.setIsTaxable(true);
    type.setSocialContributionsDue(true);
    BgCompensation doc = new BgCompensation();
    doc.setReference("ОБ-0041");
    doc.setBgCompensationType(type);
    doc.setBgIsTaxable(true);
    doc.setBgSocialContributionsDue(true);
    doc.setStatusSelect(BgCompensationResolver.CONFIRMED);
    return doc;
  }

  /** Заплата 1500,00 и обезщетение 1077,00 — мартенски фиш. */
  private static BgPaySlip mixedSlip(BgCompensation doc) {
    return mixedSlip(doc, "1500.00");
  }

  private static BgPaySlip mixedSlip(BgCompensation doc, String salaryAmount) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    BgPaySlipLine salary = new BgPaySlipLine();
    salary.setBgLineNumber(100);
    salary.setBgSign(CREDIT);
    salary.setBgIsTaxable(true);
    salary.setBgIsInsuranceIncome(true);
    salary.setBgAmount(new BigDecimal(salaryAmount));
    slip.getBgPaySlipLineList().add(salary);
    slip.getBgPaySlipLineList().add(compensationLine(doc, "1077.00"));
    return slip;
  }

  private static BgPaySlipLine compensationLine(BgCompensation doc, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(900);
    line.setBgSign(CREDIT);
    line.setBgIsTaxable(true);
    line.setBgIsInsuranceIncome(true);
    line.setBgAmount(new BigDecimal(amount));
    line.setBgCompensation(doc);
    return line;
  }

  private static BgPaySlipLine contribution(
      BgCompensation doc, int number, String sign, String rate, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setBgSign(sign);
    line.setBgRate(new BigDecimal(rate));
    line.setBgAmount(new BigDecimal(amount));
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    line.setBgCompensation(doc);
    return line;
  }

  private static Map<Integer, BgPaySlipLine> taggedWith(BgPaySlip slip, BgCompensation doc) {
    Map<Integer, BgPaySlipLine> out = new LinkedHashMap<>();
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgCompensation() == doc
          && BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        out.put(line.getBgLineNumber(), line);
      }
    }
    return out;
  }

  private static BgPaySlipLine untagged(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number && line.getBgCompensation() == null) {
        return line;
      }
    }
    return null;
  }

  private static void amount(BgPaySlipLine line, String expected, String what) {
    assertNotNull(line, what + " — няма перо");
    assertEquals(0, new BigDecimal(expected).compareTo(line.getBgAmount()), what);
  }

  private static String describe(BgPaySlip slip) {
    StringBuilder sb = new StringBuilder();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      sb.append(l.getBgLineNumber())
          .append(l.getBgCompensation() == null ? "" : "*")
          .append('=')
          .append(l.getBgAmount())
          .append(' ');
    }
    return sb.toString();
  }
}
